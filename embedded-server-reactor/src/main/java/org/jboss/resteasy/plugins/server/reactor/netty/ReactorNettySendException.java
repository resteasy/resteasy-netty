/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.resteasy.plugins.server.reactor.netty;

public class ReactorNettySendException extends RuntimeException {

    public ReactorNettySendException(final Throwable cause) {
        super(cause);
    }
}
