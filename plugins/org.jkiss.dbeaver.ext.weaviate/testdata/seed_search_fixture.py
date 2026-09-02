#!/usr/bin/env python3
"""Seed the collection the search options are exercised against.

Covers filters, BM25 with each search operator, near_vector with and without MMR, and the query
profile. Every shape here exists because one of those needs it:

    near-duplicate rows   five almost-identical trail-shoe docs, so a plain vector search
                          returns the same thing five times and MMR visibly does not
    split-token rows      "alpine" in the title and "trail" in the body, so AND and AND_CROSS
                          disagree -- AND wants both words in one property, AND_CROSS does not
    three shards          so the query profile reports more than one shard and the per-shard
                          numbers mean something
    typed properties      int, number, bool and text[] alongside the text, so the filter rows
                          have something of each kind to bite on

Vectors are self-provided and deterministic: each document's vector is the normalised count of
its words over a fixed twelve-word vocabulary. That is not an embedding model, but it makes
"closer" mean "about the same thing", which is what near_vector and MMR need in order to be
worth looking at. The script prints paste-ready query vectors for the Near Vector box.

    uv run --with weaviate-client seed_search_fixture.py
    uv run --with weaviate-client seed_search_fixture.py --cleanup

Hybrid and Near Text are NOT exercisable against a self-provided collection: both need the server
to embed the query text, and this cluster's vectorizer modules are all credentialed API services.
Pass --vectorizer text2vec-openai (with OPENAI_APIKEY set on the server) to seed a second
collection that can do those two.

Environment:
    WEAVIATE_HTTP_HOST / _PORT, WEAVIATE_GRPC_HOST / _PORT, WEAVIATE_API_KEY
"""

from __future__ import annotations

import argparse
import math
import os
import sys

import weaviate
import weaviate.classes.config as wc
from weaviate.classes.init import Auth

COLLECTION = "DBeaverSearchFixture"

# One dimension per word. Small enough that a query vector can be pasted into the panel by hand.
VOCABULARY = [
    "trail", "running", "shoe", "alpine", "waterproof", "jacket",
    "database", "vector", "search", "index", "coffee", "roast",
]

# uuid tail -> row. Fixed so a surprising result names a document rather than a position.
ROWS = [
    # -- five near-duplicates. A plain near_vector returns all five; MMR should not. ------------
    ("01", "Trail running shoe review",        "A trail running shoe built for long trail runs.",
     "footwear", 2024, 4.5, True,  ["shoes", "trail"]),
    ("02", "Trail running shoe, second look",  "Another trail running shoe, tested on trail runs.",
     "footwear", 2024, 4.4, True,  ["shoes", "trail"]),
    ("03", "Trail running shoe field notes",   "Running a trail shoe over trail and road.",
     "footwear", 2023, 4.2, True,  ["shoes"]),
    ("04", "The trail shoe, revisited",        "Trail running shoes for trail running people.",
     "footwear", 2023, 3.9, False, ["shoes", "trail"]),
    ("05", "Trail shoe buying guide",          "Choosing a running shoe for the trail.",
     "footwear", 2022, 3.5, True,  ["shoes", "guide"]),

    # -- the split-token pair. This is what makes AND and AND_CROSS differ. ---------------------
    # Both words in one property: AND matches this one.
    ("06", "Gear notes",                       "An alpine trail in bad weather.",
     "outdoors", 2024, 4.0, True,  ["alpine", "trail"]),
    # One word in each property: AND_CROSS matches this one, AND does not.
    ("07", "Alpine crossing",                  "A long trail above the treeline.",
     "outdoors", 2023, 4.1, True,  ["alpine"]),

    # -- waterproof jackets: related to the alpine rows, far from the shoes. --------------------
    ("08", "Waterproof alpine jacket",         "A waterproof jacket for alpine weather.",
     "outdoors", 2024, 4.6, True,  ["jacket", "alpine"]),
    ("09", "Jacket care",                      "Re-proofing a waterproof jacket.",
     "outdoors", 2022, 3.8, False, ["jacket"]),

    # -- a second cluster, nothing to do with the first. ----------------------------------------
    ("10", "Vector search index",              "Building a vector index for search.",
     "software", 2025, 4.8, True,  ["vector", "search"]),
    ("11", "Database index tuning",            "Tuning a database index for search.",
     "software", 2025, 4.3, True,  ["database", "index"]),
    ("12", "Vector database notes",            "A database that stores vector data.",
     "software", 2024, 4.7, False, ["vector", "database"]),

    # -- an outlier, so "everything is somewhat similar" is not the answer. ---------------------
    ("13", "Coffee roast levels",              "A dark roast coffee tastes of roast, not coffee.",
     "food",   2021, 4.9, True,  ["coffee"]),
]


def vector_for(text: str) -> list[float]:
    """A document's vector: normalised word counts over VOCABULARY.

    Not an embedding -- but two documents about the same thing land near each other and two about
    different things do not, which is the only property near_vector and MMR need here.
    """
    counts = [0.0] * len(VOCABULARY)
    words = text.lower().replace(".", " ").replace(",", " ").split()
    for word in words:
        stem = word.rstrip("s")
        for i, term in enumerate(VOCABULARY):
            if stem == term.rstrip("s"):
                counts[i] += 1.0
    length = math.sqrt(sum(c * c for c in counts))
    if length == 0:
        # Nothing in the vocabulary. Give it its own corner rather than the origin, where it would
        # sit at distance 0 from every other empty document.
        return [1.0 / math.sqrt(len(VOCABULARY))] * len(VOCABULARY)
    return [c / length for c in counts]


def query_vector(*words: str) -> list[float]:
    return vector_for(" ".join(words))


def fmt(vector: list[float]) -> str:
    return "[" + ", ".join(f"{v:.4f}" for v in vector) + "]"


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


def create(client: weaviate.WeaviateClient, name: str, vectorizer: str | None, shards: int) -> None:
    if client.collections.exists(name):
        client.collections.delete(name)
        print(f"deleted existing {name}")
    if vectorizer:
        # Named after the module so the collection says which provider it needs to work.
        vector_config = wc.Configure.Vectors.text2vec_openai() \
            if vectorizer == "text2vec-openai" else wc.Configure.Vectors.self_provided()
    else:
        vector_config = wc.Configure.Vectors.self_provided()
    client.collections.create(
        name,
        vector_config=vector_config,
        # More than one shard on purpose: a query profile of a single shard cannot show that
        # different shards did different amounts of work, which is most of the point of reading one.
        sharding_config=wc.Configure.sharding(desired_count=shards),
        properties=[
            wc.Property(name="title", data_type=wc.DataType.TEXT),
            wc.Property(name="body", data_type=wc.DataType.TEXT),
            wc.Property(name="topic", data_type=wc.DataType.TEXT),
            wc.Property(name="year", data_type=wc.DataType.INT),
            wc.Property(name="rating", data_type=wc.DataType.NUMBER),
            wc.Property(name="published", data_type=wc.DataType.BOOL),
            wc.Property(name="tags", data_type=wc.DataType.TEXT_ARRAY),
        ],
    )
    print(f"created {name} ({shards} shards, "
          f"{vectorizer or 'self-provided vectors'})")


def fill(client: weaviate.WeaviateClient, name: str, self_provided: bool) -> int:
    collection = client.collections.use(name)
    for tail, title, body, topic, year, rating, published, tags in ROWS:
        properties = {
            "title": title, "body": body, "topic": topic,
            "year": year, "rating": rating, "published": published, "tags": tags,
        }
        uuid = f"00000000-0000-4000-8000-0000000000{tail}"
        if self_provided:
            collection.data.insert(
                properties=properties, uuid=uuid,
                vector=vector_for(f"{title} {body}"))
        else:
            collection.data.insert(properties=properties, uuid=uuid)
    return len(collection)


EXPECTATIONS = f"""
Near Vector -- paste one of these into the panel's vector box:

  "trail running shoe"   {fmt(query_vector("trail", "running", "shoe"))}
     without MMR, fetching 13:            01 02 03 04 05 06 07 08 09 10 11 12 13
     without MMR, fetching 5:             01 02 03 04 05  -- five near-copies
     MMR limit 5, balance 0, fetching 13: 01 08 10 13 12  -- one from each cluster
     MMR limit 5, balance 1, fetching 13: 01 02 03 04 05  -- balance 1 is no MMR at all
     MMR limit 5, balance 0, fetching 5:  01 05 02 03 04  -- reordered, not diversified

     The row limit is the candidate pool. MMR picks its limit out of what the read fetched, so
     with both at 5 there is nothing to choose from. The server refuses the inverse outright:
     "MMR limit (13) cannot be larger than the query limit (5)".

  "alpine waterproof"    {fmt(query_vector("alpine", "waterproof"))}
     the jacket and alpine rows, 06-09

  "vector database"      {fmt(query_vector("vector", "database"))}
     the software rows, 10-12

BM25 "alpine trail" -- the operator is the whole point:

  (no operator set)          01 02 03 04 05 06 07 08   -- the server's own default, which
                                                          matches "at least one word"
  All words                  06                        -- both words in one property
  All words, across props    06 07                     -- 07 has alpine in the title only
  At least N words, N=1      01 02 03 04 05 06 07 08
  At least N words, N=2      06

  All four measured against this fixture on 1.39.0, not inferred from the names.

Filters worth trying:
  year >= 2024                  01, 02, 06, 08, 10, 11, 12
  published = false             04, 09, 12
  rating > 4.5                  08, 10, 12, 13
  tags CONTAINS ANY [alpine]    06, 07, 08
  topic = software              10, 11, 12

Query profile: switch it on with any of the above. Three shards, so expect more than one entry;
the row counts per shard are what say whether the work was spread evenly.
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cleanup", action="store_true", help="delete the fixture and exit")
    parser.add_argument("--shards", type=int, default=3,
                        help="shard count; more than one so the query profile has something to say")
    parser.add_argument("--vectorizer", default=None,
                        help="module name to seed a second, server-vectorised collection with "
                             "(text2vec-openai); needed for Near Text and Hybrid")
    args = parser.parse_args()

    with connect() as client:
        if args.cleanup:
            for name in (COLLECTION, COLLECTION + "Vectorized"):
                if client.collections.exists(name):
                    client.collections.delete(name)
                    print(f"deleted {name}")
            return 0

        create(client, COLLECTION, None, args.shards)
        count = fill(client, COLLECTION, self_provided=True)
        print(f"inserted {count} objects")

        if args.vectorizer:
            name = COLLECTION + "Vectorized"
            create(client, name, args.vectorizer, args.shards)
            vectorized = fill(client, name, self_provided=False)
            print(f"inserted {vectorized} objects into {name}")
            print("Near Text and Hybrid work against that one; the server embeds the query.")
        else:
            print("\nNear Text and Hybrid need a server-side vectorizer -- see --vectorizer.")

        print(EXPECTATIONS)
        return 0 if count == len(ROWS) else 1


if __name__ == "__main__":
    sys.exit(main())
