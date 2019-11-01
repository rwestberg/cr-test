/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge.github;

import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.*;
import org.openjdk.skara.json.*;
import org.openjdk.skara.network.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

public class GitHubHost implements Forge {
    private final URI uri;
    private final Pattern webUriPattern;
    private final String webUriReplacement;
    private final GitHubApplication application;
    private final Credential pat;
    private final RestRequest request;
    private HostUser currentUser;

    public GitHubHost(URI uri, GitHubApplication application, Pattern webUriPattern, String webUriReplacement) {
        this.uri = uri;
        this.webUriPattern = webUriPattern;
        this.webUriReplacement = webUriReplacement;
        this.application = application;
        this.pat = null;

        var baseApi = URIBuilder.base(uri)
                .appendSubDomain("api")
                .setPath("/")
                .build();

        request = new RestRequest(baseApi, () -> Arrays.asList(
                "Authorization", "token " + getInstallationToken(),
                "Accept", "application/vnd.github.machine-man-preview+json",
                "Accept", "application/vnd.github.antiope-preview+json"));
    }

    public GitHubHost(URI uri, Credential pat, Pattern webUriPattern, String webUriReplacement) {
        this.uri = uri;
        this.webUriPattern = webUriPattern;
        this.webUriReplacement = webUriReplacement;
        this.pat = pat;
        this.application = null;

        var baseApi = URIBuilder.base(uri)
                                .appendSubDomain("api")
                                .setPath("/")
                                .build();

        request = new RestRequest(baseApi, () -> Arrays.asList(
                "Authorization", "token " + pat.password()));
    }

    GitHubHost(URI uri, Pattern webUriPattern, String webUriReplacement) {
        this.uri = uri;
        this.webUriPattern = webUriPattern;
        this.webUriReplacement = webUriReplacement;
        this.pat = null;
        this.application = null;

        var baseApi = URIBuilder.base(uri)
                                .appendSubDomain("api")
                                .setPath("/")
                                .build();

        request = new RestRequest(baseApi);
    }

    public URI getURI() {
        return uri;
    }

    URI getWebURI(String endpoint) {
        var baseWebUri = URIBuilder.base(uri)
                                   .setPath(endpoint)
                                   .build();

        if (webUriPattern == null) {
            return baseWebUri;
        }

        var matcher = webUriPattern.matcher(baseWebUri.toString());
        if (!matcher.matches()) {
            return baseWebUri;

        }
        return URIBuilder.base(matcher.replaceAll(webUriReplacement)).build();
    }

    String getInstallationToken() {
        if (application != null) {
            return application.getInstallationToken();
        } else {
            return pat.password();
        }
    }

    private String getFullName(String userName) {
        var details = user(userName);
        return details.fullName();
    }

    // Most GitHub API's return user information in this format
    HostUser parseUserField(JSONValue json) {
        return parseUserObject(json.get("user"));
    }

    HostUser parseUserObject(JSONValue json) {
        return new HostUser(json.get("id").asInt(), json.get("login").asString(),
                            () -> getFullName(json.get("login").asString()));
    }

    @Override
    public boolean isValid() {
        var endpoints = request.get("")
                               .onError(response -> JSON.of())
                               .execute();
        return !endpoints.isNull();
    }

    JSONObject getProjectInfo(String name) {
        var project = request.get("repos/" + name)
                             .execute();
        return project.asObject();
    }

    JSONObject runSearch(String query) {
        var result = request.get("search/issues")
                            .param("q", query)
                            .execute();
        return result.asObject();
    }

    @Override
    public HostedRepository repository(String name) {
        return new GitHubRepository(this, name);
    }

    @Override
    public HostUser user(String username) {
        var details = request.get("users/" + URLEncoder.encode(username, StandardCharsets.UTF_8)).execute().asObject();

        // Always present
        var login = details.get("login").asString();
        var id = details.get("id").asInt();

        var name = details.get("name").asString();
        if (name == null) {
            name = login;
        }
        return new HostUser(id, login, name);
    }

    @Override
    public HostUser currentUser() {
        if (currentUser == null) {
            if (application != null) {
                var appDetails = application.getAppDetails();
                var appName = appDetails.get("name").asString() + "[bot]";
                currentUser = user(appName);
            } else if (pat != null) {
                currentUser = user(pat.username());
            } else {
                throw new IllegalStateException("No credentials present");
            }
        }
        return currentUser;
    }

    @Override
    public boolean supportsReviewBody() {
        return true;
    }

    @Override
    public boolean isMemberOf(String groupId, HostUser user) {
        long gid = 0L;
        try {
            gid = Long.parseLong(groupId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Group id is not a number: " + groupId);
        }
        var username = URLEncoder.encode(user.userName(), StandardCharsets.UTF_8);
        var orgs = request.get("users/" + username + "/orgs").execute().asArray();
        for (var org : orgs) {
            if (org.get("id").asLong() == gid) {
                return true;
            }
        }

        return false;
    }
}