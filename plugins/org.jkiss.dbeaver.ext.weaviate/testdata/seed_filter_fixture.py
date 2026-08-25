#!/usr/bin/env python3
"""Seed the collection the filter tests assert against.

Every row is chosen so that each filter operator has one unambiguous expected result, across
each type the plugin's value coercion handles: text, int, number, bool, date, text[] and int[].
Row 4 is the empty case -- nulls and empty arrays -- which is what IS NULL and CONTAINS NONE
need in order to mean anything.

    uv run --with weaviate-client seed_filter_fixture.py

Or with an existing environment:

    ~/dev/weaviate/lab/.venv/bin/python seed_filter_fixture.py

Environment:
    WEAVIATE_HTTP_HOST / _PORT, WEAVIATE_GRPC_HOST / _PORT, WEAVIATE_API_KEY
"""

from __future__ import annotations

import datetime
import os
import sys

import weaviate
import weaviate.classes.config as wc
from weaviate.classes.init import Auth

COLLECTION = "DBeaverFilterFixture"

# Fixed UUIDs so a failing assertion names a row instead of a position.
ROWS = [
    {
        "uuid": "00000000-0000-4000-8000-000000000001",
        "name": "alpha",
        "rank": 1,
        "score": 1.5,
        "flag": True,
        "when": datetime.datetime(2024, 1, 1, tzinfo=datetime.timezone.utc),
        "tags": ["x", "y"],
        "nums": [1, 2],
    },
    {
        "uuid": "00000000-0000-4000-8000-000000000002",
        "name": "beta",
        "rank": 2,
        "score": 2.5,
        "flag": False,
        "when": datetime.datetime(2024, 6, 1, tzinfo=datetime.timezone.utc),
        "tags": ["y", "z"],
        "nums": [2, 3],
    },
    {
        "uuid": "00000000-0000-4000-8000-000000000003",
        "name": "gamma",
        "rank": 3,
        "score": 3.5,
        "flag": True,
        "when": datetime.datetime(2025, 1, 1, tzinfo=datetime.timezone.utc),
        "tags": ["z"],
        "nums": [3],
    },
    {
        # The empty row. Without it, IS NULL and CONTAINS NONE have nothing to distinguish.
        "uuid": "00000000-0000-4000-8000-000000000004",
        "name": None,
        "rank": None,
        "score": None,
        "flag": None,
        "when": None,
        "tags": [],
        "nums": [],
    },
]

# What each operator should return, given the rows above. The Java harness asserts these; keeping
# them here means the data and the expectation are read together and stay in step.
EXPECTATIONS = """
  name  = "alpha"                 -> alpha
  name != "alpha"                 -> beta, gamma
  name LIKE "*a"                  -> alpha, beta, gamma
  name LIKE "?eta"                -> beta
  name IS NULL                    -> (row 4)
  name IS NOT NULL                -> alpha, beta, gamma
  rank > 1                        -> beta, gamma
  rank >= 2                       -> beta, gamma
  rank BETWEEN 1,2                -> alpha, beta
  score < 2.5                     -> alpha
  flag = true                     -> alpha, gamma
  when > 2024-03-01T00:00:00Z     -> beta, gamma
  tags CONTAINS ANY  x            -> alpha
  tags CONTAINS ANY  y,z          -> alpha, beta, gamma
  tags CONTAINS ALL  y,z          -> beta
  tags CONTAINS NONE x            -> beta, gamma, (row 4)
  nums CONTAINS ANY  1            -> alpha
  nums CONTAINS ALL  2,3          -> beta
  nums CONTAINS NONE 1,2          -> gamma, (row 4)
"""


def connect() -> weaviate.WeaviateClient:
    api_key = os.environ.get("WEAVIATE_API_KEY", "root-user-key")
    return weaviate.connect_to_custom(
        http_host=os.environ.get("WEAVIATE_HTTP_HOST", "localhost"),
        http_port=int(os.environ.get("WEAVIATE_HTTP_PORT", "8080")),
        http_secure=False,
        grpc_host=os.environ.get("WEAVIATE_GRPC_HOST", "localhost"),
        grpc_port=int(os.environ.get("WEAVIATE_GRPC_PORT", "50051")),
        grpc_secure=False,
        auth_credentials=Auth.api_key(api_key) if api_key else None,
    )


def main() -> int:
    with connect() as client:
        # Idempotent: the fixture is defined by this file, so recreate rather than reconcile.
        if client.collections.exists(COLLECTION):
            client.collections.delete(COLLECTION)
            print(f"deleted existing {COLLECTION}")

        client.collections.create(
            COLLECTION,
            # No vectorizer: these tests are about filters, and an embedding call would make the
            # fixture depend on a model provider being reachable.
            vector_config=wc.Configure.Vectors.self_provided(),
            # Both are off by default and both are required by operators the tests exercise:
            # IS NULL needs the null state indexed, and the _created/_updated filters need the
            # timestamps indexed. Weaviate reports each as an error rather than returning nothing.
            inverted_index_config=wc.Configure.inverted_index(
                index_null_state=True,
                index_timestamps=True,
            ),
            properties=[
                wc.Property(name="name", data_type=wc.DataType.TEXT),
                wc.Property(name="rank", data_type=wc.DataType.INT),
                wc.Property(name="score", data_type=wc.DataType.NUMBER),
                wc.Property(name="flag", data_type=wc.DataType.BOOL),
                wc.Property(name="when", data_type=wc.DataType.DATE),
                wc.Property(name="tags", data_type=wc.DataType.TEXT_ARRAY),
                wc.Property(name="nums", data_type=wc.DataType.INT_ARRAY),
            ],
        )
        print(f"created {COLLECTION}")

        collection = client.collections.use(COLLECTION)
        for row in ROWS:
            uuid = row["uuid"]
            properties = {k: v for k, v in row.items() if k != "uuid" and v is not None}
            # Arrays stay even when empty -- an absent array and an empty one are not the same
            # thing to CONTAINS NONE.
            for key in ("tags", "nums"):
                properties.setdefault(key, [])
            collection.data.insert(
                properties=properties,
                uuid=uuid,
                vector=[float(row["rank"] or 0), 0.5],
            )

        count = len(collection)
        print(f"inserted {count} objects\n")
        print("uuid -> name")
        for row in ROWS:
            print(f"  {row['uuid']}  {row['name'] or '(null)'}")
        print("\nexpected results:")
        print(EXPECTATIONS)
        return 0 if count == len(ROWS) else 1


if __name__ == "__main__":
    sys.exit(main())
