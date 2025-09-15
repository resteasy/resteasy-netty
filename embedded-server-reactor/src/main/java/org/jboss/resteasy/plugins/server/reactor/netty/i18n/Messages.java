/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.resteasy.plugins.server.reactor.netty.i18n;

import java.lang.invoke.MethodHandles;

import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageBundle;

@MessageBundle(projectCode = "RESTEASY")
public interface Messages {
    Messages MESSAGES = org.jboss.logging.Messages.getBundle(MethodHandles.lookup(), Messages.class);
    int BASE = 22500;

    @Message(id = BASE, value = "Already committed")
    String alreadyCommitted();

    @Message(id = BASE + 5, value = "Already suspended")
    String alreadySuspended();

    @Message(id = BASE + 10, value = "Response write aborted abruptly")
    String responseWriteAborted();
}