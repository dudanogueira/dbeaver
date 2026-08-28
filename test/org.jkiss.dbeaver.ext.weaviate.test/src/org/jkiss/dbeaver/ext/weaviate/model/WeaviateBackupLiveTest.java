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

import io.weaviate.client6.v1.api.Authentication;
import io.weaviate.client6.v1.api.WeaviateClient;
import io.weaviate.client6.v1.api.backup.Backup;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Reads backups from a real server.
 * <p>
 * The unit tests cover the status enum, which is ours. What they cannot cover is the seam: the
 * plugin resolves a status from the name of the client's enum constant, and the whole reason it
 * does so is a mismatch between the client and the server. Only a server settles whether that
 * mapping still holds.
 * <p>
 * Read-only. Nothing here creates, restores or cancels anything -- the lab backend holds real
 * backups and a test is not the thing that should be adding to them.
 * <p>
 * Skipped unless {@code WEAVIATE_BACKUP_FIXTURE_URL} is set:
 * <pre>
 * WEAVIATE_BACKUP_FIXTURE_URL=http://localhost:8080 mvn clean verify ...
 * </pre>
 */
public class WeaviateBackupLiveTest extends DBeaverUnitTest {

    private static final String BACKEND = "filesystem";

    private static void requireFixture() {
        String url = System.getenv("WEAVIATE_BACKUP_FIXTURE_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
            "set WEAVIATE_BACKUP_FIXTURE_URL to run the live backup tests");
    }

    private static WeaviateClient connect() {
        String url = System.getenv("WEAVIATE_BACKUP_FIXTURE_URL");
        String host = url.replaceFirst("^https?://", "").replaceFirst(":.*$", "");
        String apiKey = System.getenv().getOrDefault("WEAVIATE_API_KEY", "root-user-key");
        return WeaviateClient.connectToCustom(c -> {
            c.scheme("http");
            c.httpHost(host);
            c.httpPort(8080);
            c.grpcHost(host);
            c.grpcPort(50051);
            c.authentication(Authentication.apiKey(apiKey));
            return c;
        });
    }

    /**
     * The seam. Every status the server reports has to map to something this plugin can name, or
     * the tree shows a column of "Unknown".
     */
    @Test
    public void everyReportedStatusIsRecognised() throws Exception {
        requireFixture();
        try (WeaviateClient client = connect()) {
            List<Backup> backups = client.backup.list(BACKEND);
            Assumptions.assumeFalse(backups.isEmpty(),
                "no backups on this server; seed with testdata/seed_backup_fixture.py");
            for (Backup backup : backups) {
                WeaviateBackup mapped = WeaviateDataSource.toModel(backup, BACKEND);
                Assertions.assertNotEquals(WeaviateBackupStatus.UNKNOWN, mapped.status(),
                    () -> "server reported a status this plugin cannot name for " + backup.id());
            }
        }
    }

    /**
     * The list endpoint reports which collections a backup holds. This is the only place that
     * information is available -- the per-backup status endpoint omits it -- so a tree built from
     * status polls alone would never show it.
     */
    @Test
    public void listReportsCollectionsAndStatusEndpointDoesNot() throws Exception {
        requireFixture();
        try (WeaviateClient client = connect()) {
            List<Backup> backups = client.backup.list(BACKEND);
            Assumptions.assumeFalse(backups.isEmpty(), "no backups on this server");

            Backup withCollections = backups.stream()
                .filter(b -> b.includesCollections() != null && !b.includesCollections().isEmpty())
                .findFirst()
                .orElse(null);
            Assumptions.assumeTrue(withCollections != null,
                "no backup on this server reports its collections");

            Backup fromStatus = client.backup.getCreateStatus(withCollections.id(), BACKEND)
                .orElseThrow(() -> new AssertionError(
                    "status endpoint has no record of " + withCollections.id()));

            Assertions.assertTrue(
                fromStatus.includesCollections() == null || fromStatus.includesCollections().isEmpty(),
                "the status endpoint started reporting collections; the model can stop "
                    + "treating an empty collection list as normal for a polled backup");
            // And the path is the other way round: status has it, list does not.
            Assertions.assertNotNull(fromStatus.path(), "status should report the backup's path");
        }
    }

    /**
     * A backup that has never been restored has no restore status. That is a 404 on the wire, and
     * it is an ordinary answer rather than a failure -- the client models it as an empty Optional
     * and the UI must not treat it as an error.
     */
    @Test
    public void restoreStatusIsEmptyForABackupNeverRestored() throws Exception {
        requireFixture();
        try (WeaviateClient client = connect()) {
            List<Backup> backups = client.backup.list(BACKEND);
            Assumptions.assumeFalse(backups.isEmpty(), "no backups on this server");
            String id = backups.get(0).id();

            Assertions.assertTrue(client.backup.getRestoreStatus(id, BACKEND).isEmpty(),
                () -> id + " reports a restore status but has never been restored");
        }
    }

    /**
     * The short backend name is what the plugin sends. The module name works too, and the test
     * pins both so that choosing one is a decision rather than an accident.
     */
    @Test
    public void bothTheShortAndModuleBackendNamesResolve() throws Exception {
        requireFixture();
        try (WeaviateClient client = connect()) {
            int viaShortName = client.backup.list(BACKEND).size();
            int viaModuleName = client.backup.list(
                WeaviateBackupBackend.moduleNameFor(BACKEND)).size();
            Assertions.assertEquals(viaShortName, viaModuleName,
                "filesystem and backup-filesystem should name the same backend");
        }
    }

    /**
     * A backend whose module is off is refused by the server, which is why the plugin does not
     * ask about one it already knows is unavailable.
     */
    @Test
    public void anUnavailableBackendIsRefused() throws Exception {
        requireFixture();
        try (WeaviateClient client = connect()) {
            Assumptions.assumeTrue(
                client.meta().modules().get("backup-s3") == null,
                "this server has backup-s3 enabled, so it is not the unavailable case");

            Exception refused = Assertions.assertThrows(Exception.class,
                () -> client.backup.list("s3"),
                "an unavailable backend should be refused, not answered");
            Assertions.assertTrue(
                refused.getMessage() != null && refused.getMessage().contains("s3"),
                () -> "the refusal should name the backend: " + refused.getMessage());
        }
    }
}
