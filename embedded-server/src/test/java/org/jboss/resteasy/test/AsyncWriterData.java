/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.resteasy.test;

public class AsyncWriterData {

    public final boolean expectOnIoThread;
    public final boolean simulateSlowIo;

    public AsyncWriterData(final boolean expectOnIoThread, final boolean simulateSlowIo) {
        this.expectOnIoThread = expectOnIoThread;
        this.simulateSlowIo = simulateSlowIo;
    }

}
