/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.email.*;
import org.openjdk.skara.forge.HostedRepository;
import org.openjdk.skara.mailinglist.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.OpenJDKTag;

import java.io.*;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MailingListUpdater implements RepositoryUpdateConsumer {
    private final MailingList list;
    private final EmailAddress recipient;
    private final EmailAddress sender;
    private final EmailAddress author;
    private final boolean includeBranch;
    private final Mode mode;
    private final Map<String, String> headers;
    private final Pattern allowedAuthorDomains;
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    enum Mode {
        ALL,
        PR,
        PR_ONLY
    }

    MailingListUpdater(MailingList list, EmailAddress recipient, EmailAddress sender, EmailAddress author,
                       boolean includeBranch, Mode mode, Map<String, String> headers, Pattern allowedAuthorDomains) {
        this.list = list;
        this.recipient = recipient;
        this.sender = sender;
        this.author = author;
        this.includeBranch = includeBranch;
        this.mode = mode;
        this.headers = headers;
        this.allowedAuthorDomains = allowedAuthorDomains;
    }

    private String tagAnnotationToText(HostedRepository repository, Tag.Annotated annotation) {
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        printer.println("Tagged by: " + annotation.author().name() + " <" + annotation.author().email() + ">");
        printer.println("Date:      " + annotation.date().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss +0000")));
        printer.println();
        printer.print(String.join("\n", annotation.message()));

        return writer.toString();
    }

    private EmailAddress filteredAuthor(EmailAddress commitAddress) {
        if (author != null) {
            return author;
        }
        var allowedAuthorMatcher = allowedAuthorDomains.matcher(commitAddress.domain());
        if (!allowedAuthorMatcher.matches()) {
            return sender;
        } else {
            return commitAddress;
        }
    }

    private EmailAddress commitToAuthor(Commit commit) {
        return filteredAuthor(EmailAddress.from(commit.committer().name(), commit.committer().email()));
    }

    private EmailAddress annotationToAuthor(Tag.Annotated annotation) {
        return filteredAuthor(EmailAddress.from(annotation.author().name(), annotation.author().email()));
    }

    private String commitsToSubject(HostedRepository repository, List<Commit> commits, Branch branch) {
        var subject = new StringBuilder();
        subject.append(repository.repositoryType().shortName());
        subject.append(": ");
        subject.append(repository.name());
        subject.append(": ");
        if (includeBranch) {
            subject.append(branch.name());
            subject.append(": ");
        }
        if (commits.size() > 1) {
            subject.append(commits.size());
            subject.append(" new changesets");
        } else {
            subject.append(commits.get(0).message().get(0));
        }
        return subject.toString();
    }

    private String tagToSubject(HostedRepository repository, Hash hash, Tag tag) {
        return repository.repositoryType().shortName() +
                ": " +
                repository.name() +
                ": Added tag " +
                tag +
                " for changeset " +
                hash.abbreviate();
    }

    private List<Commit> filterAndSendPrCommits(HostedRepository repository, List<Commit> commits) {
        var ret = new ArrayList<Commit>();

        var rfrs = list.conversations(Duration.ofDays(365)).stream()
                       .map(Conversation::first)
                       .filter(email -> email.subject().startsWith("RFR: "))
                       .collect(Collectors.toList());

        for (var commit : commits) {
            var candidates = repository.findPullRequestsWithComment(null, "Pushed as commit " + commit.hash() + ".");
            if (candidates.size() != 1) {
                log.warning("Commit " + commit.hash() + " matches " + candidates.size() + " pull requests - expected 1");
                ret.add(commit);
                continue;
            }

            var candidate = candidates.get(0);
            var prLink = candidate.webUrl();
            var prLinkPattern = Pattern.compile("^(?:PR: )?" + Pattern.quote(prLink.toString()), Pattern.MULTILINE);

            var rfrCandidates = rfrs.stream()
                                    .filter(email -> prLinkPattern.matcher(email.body()).find())
                                    .collect(Collectors.toList());
            if (rfrCandidates.size() != 1) {
                log.warning("Pull request " + prLink + " found in " + rfrCandidates.size() + " RFR threads - expected 1");
                ret.add(commit);
                continue;
            }
            var rfr = rfrCandidates.get(0);

            var body = CommitFormatters.toText(repository, commit);
            var email = Email.reply(rfr, "Re: [Integrated] " + rfr.subject(), body)
                             .sender(sender)
                             .author(commitToAuthor(commit))
                             .recipient(recipient)
                             .headers(headers)
                             .build();
            list.post(email);
        }

        return ret;
    }

    private void sendCombinedCommits(HostedRepository repository, List<Commit> commits, Branch branch) {
        if (commits.size() == 0) {
            return;
        }

        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        for (var commit : commits) {
            printer.println(CommitFormatters.toText(repository, commit));
        }

        var subject = commitsToSubject(repository, commits, branch);
        var lastCommit = commits.get(commits.size() - 1);
        var commitAddress = filteredAuthor(EmailAddress.from(lastCommit.committer().name(), lastCommit.committer().email()));
        var email = Email.create(subject, writer.toString())
                         .sender(sender)
                         .author(commitAddress)
                         .recipient(recipient)
                         .headers(headers)
                         .build();

        list.post(email);
    }

    @Override
    public void handleCommits(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch branch) {
        switch (mode) {
            case PR_ONLY:
                filterAndSendPrCommits(repository, commits);
                break;
            case PR:
                commits = filterAndSendPrCommits(repository, commits);
                // fall-through
            case ALL:
                sendCombinedCommits(repository, commits, branch);
                break;
        }
    }

    @Override
    public void handleOpenJDKTagCommits(HostedRepository repository, Repository localRepository, List<Commit> commits, OpenJDKTag tag, Tag.Annotated annotation) {
        if (mode == Mode.PR_ONLY) {
            return;
        }
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        var taggedCommit = commits.get(commits.size() - 1);
        if (annotation != null) {
            printer.println(tagAnnotationToText(repository, annotation));
        }
        printer.println(CommitFormatters.toTextBrief(repository, taggedCommit));

        printer.println("The following commits are included in " + tag.tag());
        printer.println("========================================================");
        for (var commit : commits) {
            printer.print(commit.hash().abbreviate());
            if (commit.message().size() > 0) {
                printer.print(": " + commit.message().get(0));
            }
            printer.println();
        }

        var subject = tagToSubject(repository, taggedCommit.hash(), tag.tag());
        var email = Email.create(subject, writer.toString())
                         .sender(sender)
                         .recipient(recipient)
                         .headers(headers);

        if (annotation != null) {
            email.author(annotationToAuthor(annotation));
        } else {
            email.author(commitToAuthor(taggedCommit));
        }

        list.post(email.build());
    }

    @Override
    public void handleTagCommit(HostedRepository repository, Repository localRepository, Commit commit, Tag tag, Tag.Annotated annotation) {
        if (mode == Mode.PR_ONLY) {
            return;
        }
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        if (annotation != null) {
            printer.println(tagAnnotationToText(repository, annotation));
        }
        printer.println(CommitFormatters.toTextBrief(repository, commit));

        var subject = tagToSubject(repository, commit.hash(), tag);
        var email = Email.create(subject, writer.toString())
                         .sender(sender)
                         .recipient(recipient)
                         .headers(headers);

        if (annotation != null) {
            email.author(annotationToAuthor(annotation));
        } else {
            email.author(commitToAuthor(commit));
        }

        list.post(email.build());
    }

    private String newBranchSubject(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch parent, Branch branch) {
        var subject = new StringBuilder();
        subject.append(repository.repositoryType().shortName());
        subject.append(": ");
        subject.append(repository.name());
        subject.append(": created branch ");
        subject.append(branch);
        subject.append(" based on the branch ");
        subject.append(parent);
        subject.append(" containing ");
        subject.append(commits.size());
        subject.append(" unique commit");
        if (commits.size() != 1) {
            subject.append("s");
        }

        return subject.toString();
    }

    @Override
    public void handleNewBranch(HostedRepository repository, Repository localRepository, List<Commit> commits, Branch parent, Branch branch) {
        var writer = new StringWriter();
        var printer = new PrintWriter(writer);

        if (commits.size() > 0) {
            printer.println("The following commits are unique to the " + branch.name() + " branch:");
            printer.println("========================================================");
            for (var commit : commits) {
                printer.print(commit.hash().abbreviate());
                if (commit.message().size() > 0) {
                    printer.print(": " + commit.message().get(0));
                }
                printer.println();
            }
        } else {
            printer.println("The new branch " + branch.name() + " is currently identical to the " + parent.name() + " branch.");
        }

        var subject = newBranchSubject(repository, localRepository, commits, parent, branch);
        var finalAuthor = commits.size() > 0 ? commitToAuthor(commits.get(commits.size() - 1)) : sender;

        var email = Email.create(subject, writer.toString())
                         .sender(sender)
                         .author(finalAuthor)
                         .recipient(recipient)
                         .headers(headers)
                         .build();
        list.post(email);
    }

    @Override
    public boolean idempotent() {
        return false;
    }
}