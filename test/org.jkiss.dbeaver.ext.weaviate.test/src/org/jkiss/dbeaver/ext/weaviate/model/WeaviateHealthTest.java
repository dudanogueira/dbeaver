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

import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The health verdict. The point of the class is that "down" and "starting up" stop reading as the
 * same failure, so that is what these assert.
 */
public class WeaviateHealthTest extends DBeaverUnitTest {

    @Test
    public void healthyServerSaysSo() {
        WeaviateHealth health = new WeaviateHealth(true, true, 3, null);
        Assertions.assertTrue(health.isHealthy());
        Assertions.assertEquals("Live and ready.", health.getSummary());
    }

    @Test
    public void unreachableServerBlamesTheAddress() {
        WeaviateHealth health = new WeaviateHealth(false, false, 12, null);
        Assertions.assertFalse(health.isHealthy());
        Assertions.assertTrue(health.getSummary().contains("Not reachable"),
            () -> "unexpected summary: " + health.getSummary());
        Assertions.assertTrue(health.getSummary().contains("listening"));
    }

    /**
     * The row this whole feature exists for. A server that is up but not ready must not be
     * described in a way that sends the user off to change connection settings that were never
     * wrong.
     */
    @Test
    public void startingServerSaysToWaitNotToReconfigure() {
        WeaviateHealth health = new WeaviateHealth(true, false, 4, null);
        Assertions.assertFalse(health.isHealthy());

        String summary = health.getSummary();
        Assertions.assertTrue(summary.contains("not ready"), () -> "unexpected: " + summary);
        Assertions.assertTrue(summary.contains("starting") || summary.contains("recovering"),
            () -> "the summary should say why it is not ready: " + summary);
        Assertions.assertFalse(summary.toLowerCase().contains("not reachable"),
            () -> "a live server must not be reported as unreachable: " + summary);
    }

    @Test
    public void aProbeErrorIsCarriedIntoTheSummary() {
        WeaviateHealth down = new WeaviateHealth(false, false, 9, "Connection refused");
        Assertions.assertTrue(down.getSummary().contains("Connection refused"));

        WeaviateHealth notReady = new WeaviateHealth(true, false, 9, "shard recovery in progress");
        Assertions.assertTrue(notReady.getSummary().contains("shard recovery in progress"));
    }

    @Test
    public void accessorsRoundTrip() {
        WeaviateHealth health = new WeaviateHealth(true, false, 42, "why");
        Assertions.assertTrue(health.isLive());
        Assertions.assertFalse(health.isReady());
        Assertions.assertEquals(42, health.getProbeMillis());
        Assertions.assertEquals("why", health.getDetail());
        Assertions.assertNull(new WeaviateHealth(true, true, 1, null).getDetail());
    }

    /** A server cannot be ready without being live, so that combination has no summary of its own. */
    @Test
    public void readyImpliesLiveForHealthiness() {
        Assertions.assertFalse(new WeaviateHealth(false, true, 1, null).isHealthy());
    }
}
