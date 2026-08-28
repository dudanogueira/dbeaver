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

import java.util.List;

/**
 * The status enum is resolved from a name rather than from the client's own enum, and these are
 * the reasons why.
 */
public class WeaviateBackupStatusTest extends DBeaverUnitTest {

    /**
     * Every status the server can emit, from {@code entities/backup/status.go}. The list is the
     * point of the test: the bundled client's {@code BackupStatus} has no {@code TRANSFERRED}
     * constant, so reading its enum gives null for a restore that has finished staging.
     */
    private static final List<String> SERVER_STATUSES = List.of(
        "STARTED", "TRANSFERRING", "TRANSFERRED", "FINALIZING", "SUCCESS", "FAILED",
        "CANCELLING", "CANCELED");

    @Test
    public void everyStatusTheServerEmitsIsRecognised() {
        for (String name : SERVER_STATUSES) {
            Assertions.assertNotEquals(WeaviateBackupStatus.UNKNOWN,
                WeaviateBackupStatus.fromName(name),
                () -> name + " is a status the server emits and this plugin cannot name it");
        }
    }

    @Test
    public void transferredIsNotLost() {
        // The specific gap. The client enum stops at FINALIZING, so a restore that has staged its
        // data reports a status Gson maps to null; resolving from the string cannot.
        Assertions.assertEquals(
            WeaviateBackupStatus.TRANSFERRED, WeaviateBackupStatus.fromName("TRANSFERRED"));
    }

    @Test
    public void anUnknownNameIsUnknownRatherThanNull() {
        for (String odd : new String[]{null, "", "   ", "SOMETHING_NEW"}) {
            Assertions.assertEquals(WeaviateBackupStatus.UNKNOWN,
                WeaviateBackupStatus.fromName(odd),
                () -> "expected UNKNOWN for [" + odd + "]");
        }
    }

    @Test
    public void bothSpellingsOfCancelledResolve() {
        // The server writes CANCELED; the docs and some clients write CANCELLED.
        Assertions.assertEquals(
            WeaviateBackupStatus.CANCELED, WeaviateBackupStatus.fromName("CANCELED"));
        Assertions.assertEquals(
            WeaviateBackupStatus.CANCELED, WeaviateBackupStatus.fromName("CANCELLED"));
    }

    @Test
    public void namesAreMatchedRegardlessOfCase() {
        Assertions.assertEquals(
            WeaviateBackupStatus.SUCCESS, WeaviateBackupStatus.fromName(" success "));
    }

    /**
     * Only the three endings are terminal. A poll loop stops on this, so anything wrong here
     * either spins forever or reports a running backup as finished.
     */
    @Test
    public void onlyTheEndingsAreTerminal() {
        Assertions.assertTrue(WeaviateBackupStatus.SUCCESS.isTerminal());
        Assertions.assertTrue(WeaviateBackupStatus.FAILED.isTerminal());
        Assertions.assertTrue(WeaviateBackupStatus.CANCELED.isTerminal());

        for (WeaviateBackupStatus status : new WeaviateBackupStatus[]{
            WeaviateBackupStatus.STARTED, WeaviateBackupStatus.TRANSFERRING,
            WeaviateBackupStatus.TRANSFERRED, WeaviateBackupStatus.FINALIZING,
            WeaviateBackupStatus.CANCELLING}
        ) {
            Assertions.assertFalse(status.isTerminal(), () -> status + " is still in flight");
        }
    }

    /**
     * An unrecognised state is far likelier to be a new intermediate phase than a new ending, and
     * calling it terminal would report a running backup as complete.
     */
    @Test
    public void unknownIsNotTerminal() {
        Assertions.assertFalse(WeaviateBackupStatus.UNKNOWN.isTerminal());
    }

    /**
     * FINALIZING is excluded on purpose: the server answers 422 "is applying schema changes and
     * cannot be cancelled", so offering Cancel there would be offering a button that fails.
     */
    @Test
    public void finalizingCannotBeCancelled() {
        Assertions.assertFalse(WeaviateBackupStatus.FINALIZING.isCancellable());
        Assertions.assertTrue(WeaviateBackupStatus.TRANSFERRING.isCancellable());
        Assertions.assertTrue(WeaviateBackupStatus.TRANSFERRED.isCancellable());
        for (WeaviateBackupStatus status : WeaviateBackupStatus.values()) {
            if (status.isTerminal()) {
                Assertions.assertFalse(status.isCancellable(),
                    () -> status + " has already finished");
            }
        }
    }

    @Test
    public void onlyASucceededBackupCanBeRestoredFrom() {
        for (WeaviateBackupStatus status : WeaviateBackupStatus.values()) {
            Assertions.assertEquals(status == WeaviateBackupStatus.SUCCESS, status.isRestorable(),
                () -> status + " restorable?");
        }
    }

    @Test
    public void everyStatusHasALabel() {
        for (WeaviateBackupStatus status : WeaviateBackupStatus.values()) {
            Assertions.assertFalse(status.getLabel().isBlank(), () -> status + " has no label");
        }
    }
}
