/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.weaviate.model;

import io.weaviate.client6.v1.api.WeaviateClient;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * Whether the server is up, and whether it is ready to answer.
 * <p>
 * These are two different questions and Weaviate answers them separately:
 * {@code /v1/.well-known/live} says a process is listening, {@code /ready} says it has finished
 * starting and recovering its shards. Reporting only readiness -- which is all the plugin did --
 * collapses "nothing is there" and "it is still coming up" into one message, and those call for
 * opposite responses: reconfigure, versus wait and retry.
 * <p>
 * Both probes need <b>no authentication</b> and answer in about 2ms, which is what makes them
 * worth asking separately: they still work when the credentials are wrong, so a failure can be
 * attributed rather than guessed at.
 * <p>
 * Plain JDK types only -- this crosses into the UI bundle.
 */
public final class WeaviateHealth {

    private final boolean live;
    private final boolean ready;
    private final long probeMillis;
    private final String detail;

    public WeaviateHealth(boolean live, boolean ready, long probeMillis, @Nullable String detail) {
        this.live = live;
        this.ready = ready;
        this.probeMillis = probeMillis;
        this.detail = detail;
    }

    /**
     * Probe a client, never throwing.
     * <p>
     * A probe that fails <em>is</em> the answer -- an unreachable server is not live -- so the
     * exception is recorded as the detail rather than propagated. Readiness is only asked once
     * liveness has been established: a server that is not listening cannot say anything about its
     * own readiness, and asking costs a second timeout.
     */
    @NotNull
    public static WeaviateHealth probe(@NotNull WeaviateClient client) {
        long started = System.currentTimeMillis();
        boolean live;
        try {
            live = client.isLive();
        } catch (Exception e) {
            return new WeaviateHealth(false, false, System.currentTimeMillis() - started,
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        if (!live) {
            return new WeaviateHealth(false, false, System.currentTimeMillis() - started, null);
        }
        boolean ready;
        String detail = null;
        try {
            ready = client.isReady();
        } catch (Exception e) {
            ready = false;
            detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        return new WeaviateHealth(live, ready, System.currentTimeMillis() - started, detail);
    }

    public boolean isLive() {
        return live;
    }

    public boolean isReady() {
        return ready;
    }

    /** Round-trip time of the probe, for the status dialog. */
    public long getProbeMillis() {
        return probeMillis;
    }

    /** The error behind a failed probe, or null when nothing went wrong. */
    @Nullable
    public String getDetail() {
        return detail;
    }

    /** Whether the server can actually serve queries right now. */
    public boolean isHealthy() {
        return live && ready;
    }

    /**
     * One line saying what is wrong, and -- for the case worth distinguishing -- what to do about
     * it. The "not ready" wording deliberately says to wait rather than to check settings, because
     * the settings are not the problem and changing them is the wrong instinct to encourage.
     */
    @NotNull
    public String getSummary() {
        if (!live) {
            return detail == null
                ? "Not reachable. Nothing is listening on this address."
                : "Not reachable: " + detail;
        }
        if (!ready) {
            return detail == null
                ? "Reachable but not ready. The server is still starting or recovering its shards; "
                    + "this usually clears on its own."
                : "Reachable but not ready: " + detail;
        }
        return "Live and ready.";
    }

    @Override
    public String toString() {
        return "live=" + live + " ready=" + ready + " in " + probeMillis + "ms";
    }
}
