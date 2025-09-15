/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.resteasy.plugins.server.reactor.netty;

import io.netty.util.concurrent.FastThreadLocalThread;

public class NettyUtil {
    public static boolean isIoThread() {
        return Thread.currentThread() instanceof FastThreadLocalThread;
    }
}
