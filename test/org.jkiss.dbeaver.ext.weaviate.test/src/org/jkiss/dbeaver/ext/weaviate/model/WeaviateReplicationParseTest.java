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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateReplicationRest.OperationInfo;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Reading a replication operation, from payloads a 1.39.0 cluster actually sent.
 */
public class WeaviateReplicationParseTest extends DBeaverUnitTest {

    /**
     * A completed MOVE, captured verbatim from a three-node 1.39.0 cluster.
     * <p>
     * Note what is <em>absent</em>: {@code scheduledForCancel}, {@code scheduledForDelete} and the
     * top-level {@code whenStartedUnixMs} are all omitted rather than sent as false or zero, and
     * the first history entry has no timestamp either. Anything that treats them as required
     * fields breaks on every real operation.
     */
    private static final String REAL_MOVE = """
        {
          "collection": "DBeaverReplicaFixture",
          "id": "239b7fe3-4191-4315-8ad8-63bd4c386db9",
          "shard": "rsIL0TFhmEEG",
          "sourceNode": "weaviate-2",
          "status": { "errors": [], "state": "READY" },
          "statusHistory": [
            { "errors": [], "state": "REGISTERED" },
            { "errors": [], "state": "HYDRATING",   "whenStartedUnixMs": 1788223348031 },
            { "errors": [], "state": "FINALIZING",  "whenStartedUnixMs": 1788223348049 },
            { "errors": [], "state": "INTEGRATING", "whenStartedUnixMs": 1788223348059 },
            { "errors": [], "state": "DEHYDRATING", "whenStartedUnixMs": 1788223348163 }
          ],
          "targetNode": "weaviate-0",
          "type": "MOVE",
          "uncancelable": true
        }""";

    /**
     * An operation carrying errors.
     * <p>
     * Built from the server's model rather than captured -- provoking one needs a node to fail
     * mid-copy, and on this cluster a copy finishes in under a second. The shape is authoritative
     * even so: {@code entities/models/replication_replicate_details_replica_status_error.go}
     * declares exactly {@code message} and {@code whenErroredUnixMs}, both {@code omitempty}, and
     * {@code handlers_replicate.go:110} fills both in.
     * <p>
     * This is the payload the bundled client cannot read at all: it declares {@code errors} as
     * {@code List&lt;String&gt;}, so Gson throws {@code Expected a string but was BEGIN_OBJECT} --
     * on exactly the operation somebody opened the screen to investigate.
     */
    private static final String WITH_ERRORS = """
        {
          "collection": "Orders",
          "id": "a1b2c3d4-0000-0000-0000-000000000001",
          "shard": "abc123",
          "sourceNode": "weaviate-1",
          "targetNode": "weaviate-2",
          "type": "COPY",
          "status": {
            "state": "HYDRATING",
            "errors": [
              { "message": "dial tcp 10.42.0.5:7001: connect: connection refused",
                "whenErroredUnixMs": 1788223348999 },
              { "message": "context deadline exceeded", "whenErroredUnixMs": 1788223349999 }
            ]
          },
          "statusHistory": [
            { "errors": [], "state": "REGISTERED" }
          ]
        }""";

    private static OperationInfo parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return WeaviateReplicationRest.parseOperation(o);
    }

    @Test
    public void aRealMoveParses() {
        OperationInfo op = parse(REAL_MOVE);
        Assertions.assertNotNull(op);
        Assertions.assertEquals("DBeaverReplicaFixture", op.collection());
        Assertions.assertEquals("rsIL0TFhmEEG", op.shard());
        Assertions.assertEquals("weaviate-2", op.sourceNode());
        Assertions.assertEquals("weaviate-0", op.targetNode());
        Assertions.assertEquals("MOVE", op.type());
        Assertions.assertEquals("READY", op.state());
    }

    @Test
    public void theAbsentBooleansDefaultToFalse() {
        // The server omits them when false; a parser that required them would fail on every
        // operation that has not been asked to cancel or delete.
        OperationInfo op = parse(REAL_MOVE);
        Assertions.assertTrue(op.uncancelable());
        Assertions.assertFalse(op.scheduledForCancel());
        Assertions.assertFalse(op.scheduledForDelete());
        Assertions.assertEquals(0L, op.whenStartedUnixMs());
    }

    @Test
    public void theWholeMoveLifecycleIsPreserved() {
        OperationInfo op = parse(REAL_MOVE);
        Assertions.assertEquals(
            java.util.List.of("REGISTERED", "HYDRATING", "FINALIZING", "INTEGRATING", "DEHYDRATING"),
            op.statusHistory().stream().map(WeaviateReplicationRest.StatusInfo::state).toList());
        // A move passes through DEHYDRATING, which a copy does not; both go through INTEGRATING,
        // the state the bundled client has no constant for.
        Assertions.assertTrue(op.statusHistory().stream()
            .anyMatch(s -> "INTEGRATING".equals(s.state())));
    }

    @Test
    public void theStartTimeComesFromTheHistory() {
        // The operation's own whenStartedUnixMs is documented by the API and never sent: on a
        // 1.39.0 cluster every operation arrives without it, and the current status carries only
        // state and errors. The earliest stamped history entry is the only answer available, and
        // it is HYDRATING -- REGISTERED has no stamp either, because the initial state is recorded
        // without the transition that would set one.
        OperationInfo op = parse(REAL_MOVE);
        Assertions.assertEquals(0L, op.whenStartedUnixMs(), "the field the API documents");
        Assertions.assertEquals(1788223348031L, op.startedAtMs(), "the one that is actually there");
    }

    @Test
    public void theLastRecordedTimeIsTheLatestState() {
        // DEHYDRATING, not READY: the current state's start time is exactly the one not sent.
        Assertions.assertEquals(1788223348163L, parse(REAL_MOVE).lastRecordedMs());
    }

    @Test
    public void anOperationWithNoTimestampsAnywhereReportsNone() {
        OperationInfo op = parse("""
            {"id":"x","collection":"C","shard":"s","sourceNode":"a","targetNode":"b",
             "type":"COPY","status":{"state":"REGISTERED","errors":[]}}""");
        Assertions.assertEquals(0L, op.startedAtMs());
        Assertions.assertEquals(0L, op.lastRecordedMs());
    }

    @Test
    public void errorsReadAsObjectsNotStrings() {
        // The regression that justifies not using the client. It throws on this payload.
        OperationInfo op = parse(WITH_ERRORS);
        Assertions.assertNotNull(op);
        Assertions.assertEquals(2, op.status().errors().size());
        WeaviateReplicationRest.ErrorInfo first = op.status().errors().get(0);
        Assertions.assertTrue(first.message().contains("connection refused"), first.message());
        Assertions.assertEquals(1788223348999L, first.whenErroredUnixMs());
        Assertions.assertTrue(op.status().hasErrors());
    }

    @Test
    public void errorsAreGatheredAcrossTheWholeHistory() {
        // What went wrong three states ago still matters when explaining where an operation is.
        Assertions.assertEquals(2, parse(WITH_ERRORS).allErrors().size());
        Assertions.assertTrue(parse(REAL_MOVE).allErrors().isEmpty());
    }

    @Test
    public void aStateFromALaterReleaseStillDisplays() {
        OperationInfo op = parse("""
            {"id":"x","collection":"C","shard":"s","sourceNode":"a","targetNode":"b",
             "type":"MOVE","status":{"state":"TRANSCENDING","errors":[]}}""");
        Assertions.assertEquals("TRANSCENDING", op.state());
        Assertions.assertEquals(WeaviateReplicationState.UNKNOWN,
            WeaviateReplicationState.fromName(op.state()));
    }

    @Test
    public void anOperationWithNoIdIsDropped() {
        Assertions.assertNull(parse("{\"collection\":\"C\",\"shard\":\"s\"}"));
    }

    @Test
    public void statusMayBeAbsentEntirely() {
        OperationInfo op = parse("""
            {"id":"x","collection":"C","shard":"s","sourceNode":"a","targetNode":"b","type":"COPY"}""");
        Assertions.assertNotNull(op);
        Assertions.assertEquals("", op.state());
        Assertions.assertTrue(op.allErrors().isEmpty());
    }
}
