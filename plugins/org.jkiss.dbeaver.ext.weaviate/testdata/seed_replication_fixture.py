#!/usr/bin/env python3
"""Seed collections for exercising replica movement in DBeaver.

Replica movement needs somewhere to move to, so the shapes here are about where the replicas
are not:

    DBeaverReplicaFixture      replicationFactor 1, several shards   every shard has one replica
                               and two legal targets on a 3-node cluster
    DBeaverReplicaFullFixture  replicationFactor 3                   every node already holds a
                               replica, so there is no legal target at all

The second one is the interesting case for the UI. The server refuses a move whose target
already holds a replica (ErrAlreadyExists), so the target picker has to exclude those nodes --
and on this collection that leaves nothing, which the dialog has to say rather than offer.

Objects are inserted so a move has something to copy. A move of an empty shard finishes between
two polls and never shows a state in flight.

    uv run --with weaviate-client seed_replication_fixture.py
    uv run --with weaviate-client seed_replication_fixture.py --objects 5000
    uv run --with weaviate-client seed_replication_fixture.py --cleanup

Requires a cluster of at least 2 nodes started with REPLICA_MOVEMENT_ENABLED=true.

Environment:
    WEAVIATE_HTTP_HOST / _PORT, WEAVIATE_GRPC_HOST / _PORT, WEAVIATE_API_KEY
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

import weaviate
import weaviate.classes.config as wc
from weaviate.classes.init import Auth

SPREAD = "DBeaverReplicaFixture"
FULL = "DBeaverReplicaFullFixture"

HTTP_HOST = os.environ.get("WEAVIATE_HTTP_HOST", "localhost")
HTTP_PORT = int(os.environ.get("WEAVIATE_HTTP_PORT", "8080"))
API_KEY = os.environ.get("WEAVIATE_API_KEY", "root-user-key")
BASE = f"http://{HTTP_HOST}:{HTTP_PORT}"

DEFAULT_OBJECTS = 2000


def rest(path: str):
    request = urllib.request.Request(BASE + path)
    request.add_header("Authorization", f"Bearer {API_KEY}")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read().decode()
            return json.loads(raw) if raw.strip() else None
    except urllib.error.HTTPError as e:
        return {"_error": e.code, "_body": e.read().decode()[:300]}


def connect():
    return weaviate.connect_to_custom(
        http_host=HTTP_HOST,
        http_port=HTTP_PORT,
        http_secure=False,
        grpc_host=os.environ.get("WEAVIATE_GRPC_HOST", HTTP_HOST),
        grpc_port=int(os.environ.get("WEAVIATE_GRPC_PORT", "50051")),
        grpc_secure=False,
        auth_credentials=Auth.api_key(API_KEY),
    )


def create(client, name: str, factor: int, shards: int, objects: int) -> None:
    if client.collections.exists(name):
        client.collections.delete(name)
    collection = client.collections.create(
        name=name,
        properties=[
            wc.Property(name="title", data_type=wc.DataType.TEXT),
            wc.Property(name="body", data_type=wc.DataType.TEXT),
        ],
        replication_config=wc.Configure.replication(factor=factor),
        sharding_config=wc.Configure.sharding(desired_count=shards),
        vector_config=wc.Configure.Vectors.self_provided(),
    )
    # Bodies are padded so the shards carry enough bytes that a copy is observable rather than
    # instantaneous -- the point is to watch the state machine, not to store anything.
    padding = "x" * 400
    with collection.batch.fixed_size(batch_size=200) as batch:
        for i in range(objects):
            batch.add_object(
                properties={"title": f"{name}-{i:06d}", "body": padding},
                vector=[float(i % 97) / 97.0, float(i % 31) / 31.0],
            )
    failed = collection.batch.failed_objects
    if failed:
        print(f"  WARNING: {len(failed)} objects failed, first: {failed[0].message[:120]}")
    print(f"  {name}: rf={factor}, {shards} shard(s) requested, {objects} objects")


def report_sharding(name: str) -> None:
    state = rest(f"/v1/replication/sharding-state?collection={name}")
    if state is None or "_error" in state:
        print(f"  {name}: sharding state unavailable: {state}")
        return
    shards = state.get("shardingState", {}).get("shards") or []
    print(f"  {name}: {len(shards)} shard(s)")
    for shard in shards:
        print(f"    {shard.get('shard')} -> {', '.join(shard.get('replicas') or [])}")


def main() -> int:
    nodes = rest("/v1/nodes")
    if nodes is None or "_error" in nodes:
        print(f"cannot read /v1/nodes: {nodes}")
        return 1
    names = [n["name"] for n in nodes.get("nodes", [])]
    print(f"connected to {BASE}, {len(names)} node(s): {', '.join(names)}")
    if len(names) < 2:
        print("Replica movement needs at least two nodes: a move must have a target that does")
        print("not already hold the shard. Bring up a multi-node cluster first.")
        return 1

    ops = rest("/v1/replication/replicate/list")
    if isinstance(ops, dict) and ops.get("_error") == 501:
        print("Replica movement is disabled on this server.")
        print("Start it with REPLICA_MOVEMENT_ENABLED=true on every node.")
        return 1

    client = connect()
    try:
        if "--cleanup" in sys.argv:
            for name in (SPREAD, FULL):
                if client.collections.exists(name):
                    client.collections.delete(name)
                    print(f"  deleted {name}")
            return 0

        objects = DEFAULT_OBJECTS
        if "--objects" in sys.argv:
            objects = int(sys.argv[sys.argv.index("--objects") + 1])

        print("\ncollections")
        # One shard per node, so the spread collection has a replica on each and each shard
        # still has two nodes it could legally move to.
        create(client, SPREAD, factor=1, shards=len(names), objects=objects)
        # Replicated onto every node: no legal target remains.
        create(client, FULL, factor=len(names), shards=1, objects=objects // 4)

        print("\nsharding state, which is what the tree will show")
        report_sharding(SPREAD)
        report_sharding(FULL)

        print("\nreplication operations")
        current = rest("/v1/replication/replicate/list")
        print(f"  {len(current) if isinstance(current, list) else current} operation(s)")
        return 0
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
