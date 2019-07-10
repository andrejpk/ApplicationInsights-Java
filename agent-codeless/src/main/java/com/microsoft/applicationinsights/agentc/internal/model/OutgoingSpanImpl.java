/*
 * ApplicationInsights-Java
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the ""Software""), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.microsoft.applicationinsights.agentc.internal.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.Map;

import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.telemetry.Duration;
import com.microsoft.applicationinsights.telemetry.ExceptionTelemetry;
import com.microsoft.applicationinsights.telemetry.RemoteDependencyTelemetry;
import com.microsoft.applicationinsights.web.internal.correlation.TelemetryCorrelationUtilsCore;
import com.microsoft.applicationinsights.web.internal.correlation.TraceContextCorrelationCore;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glowroot.instrumentation.api.Getter;
import org.glowroot.instrumentation.api.MessageSupplier;
import org.glowroot.instrumentation.api.Setter;
import org.glowroot.instrumentation.api.Span;
import org.glowroot.instrumentation.api.Timer;
import org.glowroot.instrumentation.api.internal.ReadableMessage;
import org.glowroot.instrumentation.engine.impl.NopTransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OutgoingSpanImpl implements Span {

    private static final Logger logger = LoggerFactory.getLogger(OutgoingSpanImpl.class);

    private final String type;
    private final String text;
    private final long startTimeMillis;
    private final String outgoingSpanId;
    private final MessageSupplier messageSupplier;

    private volatile @MonotonicNonNull String requestContext; // only used for HTTP

    private volatile @MonotonicNonNull Throwable exception;

    public OutgoingSpanImpl(String type, String text, long startTimeMillis, String outgoingSpanId,
                            MessageSupplier messageSupplier) {
        this.type = type;
        this.text = text;
        this.startTimeMillis = startTimeMillis;
        this.outgoingSpanId = outgoingSpanId;
        this.messageSupplier = messageSupplier;
    }

    @Override
    public void end() {
        endInternal();
    }

    @Override
    public void endWithLocationStackTrace(long thresholdNanos) {
        endInternal();
    }

    @Override
    public void endWithError(Throwable t) {
        exception = t;
        endInternal();
    }

    @Override
    public Timer extend() {
        // timers are not used by ApplicationInsights
        return NopTransactionService.TIMER;
    }

    @Override
    public Object getMessageSupplier() {
        return messageSupplier;
    }

    @Override
    @Deprecated
    public <R> void propagateToResponse(R response, Setter<R> setter) {
    }

    @Override
    @Deprecated
    public <R> void extractFromResponse(R response, Getter<R> getter) {
        requestContext = getter.get(response, "Request-Context");
    }

    private void endInternal() {
        RemoteDependencyTelemetry telemetry = null;
        if (type.equals("HTTP")) {
            telemetry = toHttpTelemetry();
        } else if (type.equals("Redis")) {
            telemetry = new RemoteDependencyTelemetry();
            telemetry.setTimestamp(new Date(startTimeMillis));
            telemetry.setDuration(new Duration(System.currentTimeMillis() - startTimeMillis));
            telemetry.setName(text);
            telemetry.setSuccess(exception == null);
            telemetry.setType(type);
        }
        if (telemetry != null) {
            TelemetryClient telemetryClient = Global.getTelemetryClient();
            telemetryClient.track(telemetry);
            if (exception != null) {
                telemetryClient.track(new ExceptionTelemetry(exception));
            }
        }
    }

    private @Nullable RemoteDependencyTelemetry toHttpTelemetry() {

        ReadableMessage message = (ReadableMessage) messageSupplier.get();
        Map<String, ?> detail = message.getDetail();

        String uri = (String) detail.get("URI");
        if (uri != null && (uri.startsWith("https://dc.services.visualstudio.com")
                || uri.startsWith("https://rt.services.visualstudio.com"))) {
            return null;
        }

        RemoteDependencyTelemetry telemetry = new RemoteDependencyTelemetry();
        telemetry.setTimestamp(new Date(startTimeMillis));
        telemetry.setDuration(new Duration(System.currentTimeMillis() - startTimeMillis));
        telemetry.setId(outgoingSpanId);
        telemetry.setType("Http (tracked component)");

        // FIXME change instrumentation to not add prefixes, then can use message.getText() directly

        String method = (String) detail.get("Method");
        Integer result = (Integer) detail.get("Result");

        // from HttpClientMethodVisitor and CoreAgentNotificationsHandler:

        if (method != null) {
            // for backward compatibility (same comment from CoreAgentNotificationsHandler)
            telemetry.getProperties().put("Method", method);
        }
        if (uri != null) {
            try {
                URI uriObject = new URI(uri);
                String target;
                if (requestContext == null) {
                    target = uriObject.getHost();
                } else if (Global.isOutboundW3CEnabled()) {
                    target = TraceContextCorrelationCore.generateChildDependencyTarget(requestContext);
                } else {
                    target = TelemetryCorrelationUtilsCore.generateChildDependencyTarget(requestContext);
                }
                telemetry.setName(method + " " + uriObject.getPath());
                if (target != null && !target.isEmpty()) {
                    // AI correlation expects target to be of this format.
                    telemetry.setTarget(createTarget(uriObject, target));
                }
            } catch (URISyntaxException e) {
                logger.error(e.getMessage());
                logger.debug(e.getMessage(), e);
            }
            telemetry.setCommandName(uri);
            // for backward compatibility (same comment from CoreAgentNotificationsHandler)
            telemetry.getProperties().put("URI", uri);
        }
        if (result != null) {
            telemetry.setSuccess(result < 400);
            telemetry.setResultCode(Integer.toString(result));
        }

        return telemetry;
    }

    // from CoreAgentNotificationsHandler:
    private static String createTarget(URI uriObject, String incomingTarget) {
        String target = uriObject.getHost();
        if (uriObject.getPort() != 80 && uriObject.getPort() != 443) {
            target += ":" + uriObject.getPort();
        }
        target += " | " + incomingTarget;
        return target;
    }
}