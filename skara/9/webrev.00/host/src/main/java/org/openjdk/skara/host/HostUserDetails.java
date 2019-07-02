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
package org.openjdk.skara.host;

import java.util.Objects;
import java.util.function.Supplier;

public class HostUserDetails {
    private final int id;
    private final String username;
    private String name;
    private final Supplier<String> nameSupplier;

    public HostUserDetails(int id, String username, String name) {
        this.id = id;
        this.username = username;
        this.name = name;
        this.nameSupplier = null;
    }

    public HostUserDetails(int id, String username, Supplier<String> nameSupplier) {
        this.id = id;
        this.username = username;
        this.name = null;
        this.nameSupplier = nameSupplier;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HostUserDetails that = (HostUserDetails) o;
        return id == that.id &&
                Objects.equals(username, that.username);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, username);
    }

    public String id() {
        return String.valueOf(id);
    }

    public String userName() {
        return username;
    }

    public String fullName() {
        if (name != null) {
            return name;
        }
        assert nameSupplier != null;
        name = nameSupplier.get();
        return name;
    }

    @Override
    public String toString() {
        return "HostUserDetails{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", name='" + name + '\'' +
                '}';
    }
}