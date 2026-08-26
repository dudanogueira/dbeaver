#!/usr/bin/env python3
"""Seed a collection that shows what each tokenizer does to the same text.

One TEXT property per tokenization, all holding the identical string, so the difference between
them is the tokenizer and nothing else. Right-click any of them in DBeaver ->
"Preview Tokenization..." and compare.

Also seeds two properties that are *not* tokenizable -- an int and a date -- because the absence
of the menu entry on those is part of the behaviour worth seeing.

Skips gse and the two kagome tokenizers on purpose: those are server build flags
(ENABLE_TOKENIZER_*), not release features, so a stock Weaviate refuses them with
"unsupported tokenization strategy" no matter how new it is.

    uv run --with weaviate-client seed_tokenize_fixture.py

Environment:
    WEAVIATE_HTTP_HOST / _PORT, WEAVIATE_GRPC_HOST / _PORT, WEAVIATE_API_KEY
"""

from __future__ import annotations

import json
import os
import sys
import urllib.request

import weaviate
import weaviate.classes.config as wc
from weaviate.classes.init import Auth

COLLECTION = "DBeaverTokenizeFixture"

HTTP_HOST = os.environ.get("WEAVIATE_HTTP_HOST", "localhost")
HTTP_PORT = int(os.environ.get("WEAVIATE_HTTP_PORT", "8080"))
API_KEY = os.environ.get("WEAVIATE_API_KEY", "root-user-key")

# Chosen to make every tokenizer visibly different from the others:
#   a hyphen        -> word splits it, lowercase and whitespace keep it
#   a comma         -> word drops it, lowercase and whitespace keep it attached
#   mixed case      -> only whitespace and field preserve it
#   an accent       -> shows the tokenizers are not ASCII-folding
#   a bare number   -> survives everywhere
SAMPLE = "Red-Maple Leaf, 2021 São Paulo"

# name -> (tokenization, what to notice)
TEXT_PROPERTIES = {
    "by_word": (
        wc.Tokenization.WORD,
        "the default. Lowercases and splits on punctuation, so the hyphen and comma disappear.",
    ),
    "by_lowercase": (
        wc.Tokenization.LOWERCASE,
        "splits on whitespace only, then lowercases. Punctuation stays stuck to its word.",
    ),
    "by_whitespace": (
        wc.Tokenization.WHITESPACE,
        "splits on whitespace and changes nothing else. The only one that keeps capitals.",
    ),
    "by_field": (
        wc.Tokenization.FIELD,
        "no splitting at all: the whole value is one token. Exact-match only, and what you "
        "want for a group-by key or an id.",
    ),
    "by_trigram": (
        wc.Tokenization.TRIGRAM,
        "every run of three characters. Built for languages without word boundaries; also why "
        "a trigram property produces far more tokens than the text has words.",
    ),
}


def connect() -> weaviate.WeaviateClient:
    return weaviate.connect_to_custom(
        http_host=HTTP_HOST, http_port=HTTP_PORT, http_secure=False,
        grpc_host=os.environ.get("WEAVIATE_GRPC_HOST", "localhost"),
        grpc_port=int(os.environ.get("WEAVIATE_GRPC_PORT", "50051")), grpc_secure=False,
        auth_credentials=Auth.api_key(API_KEY) if API_KEY else None,
    )


def tokenize(property_name: str, text: str) -> dict:
    """Ask the server directly, so the printout below is observed rather than predicted."""
    req = urllib.request.Request(
        f"http://{HTTP_HOST}:{HTTP_PORT}/v1/schema/{COLLECTION}/properties/{property_name}/tokenize",
        data=json.dumps({"text": text}).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {API_KEY}"},
    )
    try:
        return json.loads(urllib.request.urlopen(req).read())
    except urllib.error.HTTPError as e:
        return {"error": f"HTTP {e.code}: {e.read().decode()[:120]}"}


def main() -> int:
    with connect() as client:
        # Idempotent: the fixture is defined by this file, so recreate rather than reconcile.
        if client.collections.exists(COLLECTION):
            client.collections.delete(COLLECTION)
            print(f"deleted existing {COLLECTION}")

        properties = [
            wc.Property(name=name, data_type=wc.DataType.TEXT, tokenization=tok)
            for name, (tok, _) in TEXT_PROPERTIES.items()
        ]
        properties += [
            # An array, to show tokenization applies per element.
            wc.Property(name="tags", data_type=wc.DataType.TEXT_ARRAY,
                        tokenization=wc.Tokenization.WORD),
            # Not tokenizable. The menu entry should not appear on these at all.
            wc.Property(name="count", data_type=wc.DataType.INT),
            wc.Property(name="seen_at", data_type=wc.DataType.DATE),
        ]

        client.collections.create(
            COLLECTION,
            # No vectorizer: this fixture is about the inverted index, and an embedding call would
            # make it depend on a model provider being reachable.
            vector_config=wc.Configure.Vectors.self_provided(),
            properties=properties,
        )
        print(f"created {COLLECTION}")

        collection = client.collections.use(COLLECTION)
        row = {name: SAMPLE for name in TEXT_PROPERTIES}
        row["tags"] = ["Red-Maple", "São Paulo"]
        row["count"] = 2021
        row["seen_at"] = "2021-05-04T00:00:00Z"
        collection.data.insert(properties=row, vector=[1.0, 0.0])
        # A second row so BM25 has something to rank against.
        second = {name: "Blue Morpho Wing, 2025 Belo Horizonte" for name in TEXT_PROPERTIES}
        second["tags"] = ["Blue-Morpho"]
        second["count"] = 2025
        second["seen_at"] = "2025-01-15T00:00:00Z"
        collection.data.insert(properties=second, vector=[2.0, 0.0])
        print(f"inserted {len(collection)} objects\n")

        print(f'the same text through each tokenizer: "{SAMPLE}"\n')
        for name, (tok, note) in TEXT_PROPERTIES.items():
            result = tokenize(name, SAMPLE)
            tokens = result.get("indexed")
            label = f"{name} ({tok.value})"
            if tokens is None:
                print(f"  {label:26s} {result.get('error', result)}")
                continue
            shown = tokens if len(tokens) <= 12 else tokens[:12] + [f"... +{len(tokens) - 12} more"]
            print(f"  {label:26s} {len(tokens):3d} tokens  {shown}")
            print(f"  {'':26s} {note}")
            print()

        print("not tokenizable -- Preview Tokenization should not appear on these:")
        for name in ("count", "seen_at"):
            result = tokenize(name, "2021")
            print(f"  {name:26s} {result.get('error', result)}")

        print(f"\nOpen {COLLECTION} in DBeaver, expand Properties, and right-click each one.")
        return 0


if __name__ == "__main__":
    sys.exit(main())
