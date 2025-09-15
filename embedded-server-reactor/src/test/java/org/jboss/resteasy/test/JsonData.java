/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.resteasy.test;

public class JsonData {
    protected String name;

    public JsonData(final String data) {
        this.name = data;
    }

    public JsonData() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

}
