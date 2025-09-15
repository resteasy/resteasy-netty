/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.resteasy.netty.reactor.engine;

import org.jboss.resteasy.client.jaxrs.internal.ClientConfiguration;
import org.jboss.resteasy.client.jaxrs.internal.ClientRequestHeaders;
import org.jboss.resteasy.util.CaseInsensitiveMap;

/**
 * An extension of ClientRequestHeaders that helps decorate the headers with a TrackingMap.
 */
class TrackingClientRequestHeaders extends ClientRequestHeaders {

    TrackingClientRequestHeaders(final ClientConfiguration configuration, final CaseInsensitiveMap<Object> headers) {
        super(configuration);
        this.headers = new TrackingMap<>(headers);
    }

    @Override
    public TrackingMap<Object> getHeaders() {
        return (TrackingMap<Object>) super.getHeaders();
    }
}
