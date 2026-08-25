#!/usr/bin/env python3
"""Seed the collection the group-by tests assert against.

Group cardinality is deliberately uneven -- 3 red, 2 green, 1 blue -- because that is what
separates "how many groups" from "how many objects per group": capping objects per group
truncates red and leaves blue untouched, and either cap alone would look the same on evenly
sized groups. The labels array exists so multi-group membership can be asserted rather than
assumed: one object carries two labels and must appear in both groups.

    uv run --with weaviate-client seed_group_fixture.py

Or with an existing environment:

    ~/dev/weaviate/lab/.venv/bin/python seed_group_fixture.py

Environment:
    WEAVIATE_HTTP_HOST / _PORT, WEAVIATE_GRPC_HOST / _PORT, WEAVIATE_API_KEY
"""

from __future__ import annotations

import os
import sys

import weaviate
import weaviate.classes.config as wc
from weaviate.classes.init import Auth

COLLECTION = "DBeaverGroupFixture"

# Fixed UUIDs so a failing assertion names a row instead of a position.
#
# Vectors sit on one axis at x = rank, and the collection uses squared L2 rather than the default
# cosine: cosine would score every one of these as identical, since they all point the same way.
# With L2 a near-vector probe at the origin ranks them by rank, so "groups come back ordered by
# their best member" has a knowable expected answer.
ROWS = [
    {"uuid": "00000000-0000-4000-9000-000000000001",
     "title": "red-near",   "category": "red",   "labels": ["alpha", "beta"], "rank": 1},
    {"uuid": "00000000-0000-4000-9000-000000000002",
     "title": "red-mid",    "category": "red",   "labels": ["alpha"],         "rank": 2},
    {"uuid": "00000000-0000-4000-9000-000000000003",
     "title": "red-far",    "category": "red",   "labels": ["beta"],          "rank": 3},
    {"uuid": "00000000-0000-4000-9000-000000000004",
     "title": "green-near", "category": "green", "labels": ["alpha"],         "rank": 4},
    {"uuid": "00000000-0000-4000-9000-000000000005",
     "title": "green-far",  "category": "green", "labels": [],                "rank": 5},
    {"uuid": "00000000-0000-4000-9000-000000000006",
     "title": "blue-only",  "category": "blue",  "labels": ["gamma"],         "rank": 6},
    # No category at all. What the server does with an unset group key is a real question and
    # this row is here so the test can state the answer instead of avoiding it.
    {"uuid": "00000000-0000-4000-9000-000000000007",
     "title": "no-category", "category": None,   "labels": [],                "rank": 7},
]

# Verified against Weaviate 1.39 -- these are observed, not predicted. Two of them are not what
# you would guess: an unset group key becomes the empty-string group rather than being dropped,
# and the per-group count reports what came back after the cap, not the group's true size.
EXPECTATIONS = """
  group by category, no caps        -> red(3):   red-near, red-mid, red-far
                                       green(2): green-near, green-far
                                       blue(1):  blue-only
                                       "" (1):   no-category
                                       -- an unset property groups under the EMPTY STRING; the
                                          row is not dropped and gets no group of its own name
  group by category, maxGroups=2    -> red, green only: groups are ordered by their best-ranked
                                       member, so the cap keeps the nearest groups
  group by category, perGroup=1     -> red(1): red-near   green(1): green-near
                                       blue(1): blue-only   ""(1): no-category
                                       -- numberOfObjects reports the CAPPED size, not the real
                                          one: red says 1 here and 3 above. It counts what was
                                          returned, so it cannot be used as a group total
  group by labels                   -> alpha(3): red-near, red-mid, green-near
                                       beta(2):  red-near, red-far
                                       ""(2):    green-far, no-category
                                       gamma(1): blue-only
                                       -- red-near is in two groups: one object, two grid rows.
                                          An empty array groups under "" just like an unset value
  near_vector [0,0] (squared L2)    -> rank order: red-near .. no-category
  group by on a plain fetch         -> REFUSED by the server, "group is not present". The Java
                                       client declares the overload and the Python one does not
                                       expose it at all; grouping needs a ranking to order the
                                       groups by, and a bare fetch has none
  metadata inside a group           -> distance, id and vector. Nothing else -- that is the whole
                                       group-hits type, in GraphQL as well as gRPC:
                                         ungrouped _additional  13 fields, incl. score,
                                                                explainScore, certainty, timestamps
                                         group hits _additional  3 fields: distance, id, vector
                                       So a grouped BM25 or hybrid has no score anywhere in the
                                       API, and a grouped object has no creation time. Both the
                                       Java and Python clients reflect this; Python's grouped
                                       metadata type has no score field at all
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
            # No vectorizer: these tests are about grouping, and an embedding call would make the
            # fixture depend on a model provider being reachable.
            vector_config=wc.Configure.Vectors.self_provided(
                vector_index_config=wc.Configure.VectorIndex.hnsw(
                    distance_metric=wc.VectorDistances.L2_SQUARED,
                ),
            ),
            # Grouping on an unset property only means something if the null state is indexed.
            inverted_index_config=wc.Configure.inverted_index(index_null_state=True),
            properties=[
                wc.Property(name="title", data_type=wc.DataType.TEXT),
                # Not tokenized: grouping is by exact value, and the default word tokenization
                # would make "green" and "green-ish" the same group key.
                wc.Property(name="category", data_type=wc.DataType.TEXT,
                            tokenization=wc.Tokenization.FIELD),
                wc.Property(name="labels", data_type=wc.DataType.TEXT_ARRAY,
                            tokenization=wc.Tokenization.FIELD),
                wc.Property(name="rank", data_type=wc.DataType.INT),
            ],
        )
        print(f"created {COLLECTION}")

        collection = client.collections.use(COLLECTION)
        for row in ROWS:
            properties = {
                k: v for k, v in row.items() if k != "uuid" and v is not None
            }
            # An empty labels array is kept: an absent array and an empty one are not the same
            # thing to a group-by that has to decide whether the object belongs anywhere.
            properties.setdefault("labels", [])
            collection.data.insert(
                properties=properties,
                uuid=row["uuid"],
                vector=[float(row["rank"]), 0.0],
            )

        count = len(collection)
        print(f"inserted {count} objects\n")
        print("uuid -> title / category")
        for row in ROWS:
            print(f"  {row['uuid']}  {row['title']:12s} {row['category'] or '(null)'}")
        print("\nexpected results:")
        print(EXPECTATIONS)
        return 0 if count == len(ROWS) else 1


if __name__ == "__main__":
    sys.exit(main())
