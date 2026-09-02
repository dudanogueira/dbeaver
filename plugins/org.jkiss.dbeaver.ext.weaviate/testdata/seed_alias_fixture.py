#!/usr/bin/env python3
"""Seed the collections and aliases the alias tests and the navigator work against.

Three collections and three aliases, arranged so every case the UI has to handle is on screen
at once:

    products      -> ProductsV3        the ordinary case: one name, one collection
    catalog       -> ProductsV3        two aliases on one collection, so the per-collection
                                       folder has more than one row to show
    archive       -> ProductsV2        a second target, so repointing has somewhere to go
    (none)           OrdersLegacy      a collection with no alias at all

Deliberately not seeded: a dangling alias, one whose collection has been dropped. Weaviate
deletes an alias with its target, so producing one means racing the server; the tree marks the
case if it ever arises, and the marking is checked by unit test rather than here.

    uv run --with weaviate-client seed_alias_fixture.py
    uv run --with weaviate-client seed_alias_fixture.py --cleanup

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

COLLECTIONS = ["ProductsV2", "ProductsV3", "OrdersLegacy"]

# alias -> collection it resolves to
ALIASES = {
    "dbeaver_products": "ProductsV3",
    "dbeaver_catalog": "ProductsV3",
    "dbeaver_archive": "ProductsV2",
}

HTTP_HOST = os.environ.get("WEAVIATE_HTTP_HOST", "localhost")
HTTP_PORT = int(os.environ.get("WEAVIATE_HTTP_PORT", "8080"))
API_KEY = os.environ.get("WEAVIATE_API_KEY", "root-user-key")

BASE = f"http://{HTTP_HOST}:{HTTP_PORT}"


def connect() -> weaviate.WeaviateClient:
    return weaviate.connect_to_custom(
        http_host=HTTP_HOST, http_port=HTTP_PORT, http_secure=False,
        grpc_host=os.environ.get("WEAVIATE_GRPC_HOST", "localhost"),
        grpc_port=int(os.environ.get("WEAVIATE_GRPC_PORT", "50051")), grpc_secure=False,
        auth_credentials=Auth.api_key(API_KEY) if API_KEY else None,
    )


def rest(method: str, path: str, body: dict | None = None) -> tuple[int, str]:
    """Aliases go over REST here rather than through the Python client, so this script keeps
    working against a client version that has not grown them yet."""
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {API_KEY}"},
        method=method,
    )
    try:
        with urllib.request.urlopen(req) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]


def list_aliases(collection: str | None = None) -> list[dict]:
    # The filter parameter is "class", not "collection" -- the same name the schema API uses for
    # what everything else now calls a collection.
    path = "/v1/aliases" + (f"?class={collection}" if collection else "")
    status, body = rest("GET", path)
    if status != 200:
        print(f"  list failed: HTTP {status} {body}")
        return []
    return json.loads(body).get("aliases") or []


def cleanup() -> None:
    for alias in ALIASES:
        status, _ = rest("DELETE", f"/v1/aliases/{alias}")
        print(f"deleted alias {alias}" if status < 300 else f"no alias {alias}")
    with connect() as client:
        for name in COLLECTIONS:
            if client.collections.exists(name):
                client.collections.delete(name)
                print(f"deleted {name}")


def main() -> int:
    if "--cleanup" in sys.argv:
        cleanup()
        return 0

    # Idempotent: the fixture is defined by this file, so recreate rather than reconcile.
    cleanup()

    with connect() as client:
        for name in COLLECTIONS:
            client.collections.create(
                name,
                # No vectorizer: this fixture is about names, and an embedding call would make it
                # depend on a model provider being reachable.
                vector_config=wc.Configure.Vectors.self_provided(),
                properties=[
                    wc.Property(name="title", data_type=wc.DataType.TEXT),
                    wc.Property(name="sku", data_type=wc.DataType.TEXT,
                                tokenization=wc.Tokenization.FIELD),
                ],
            )
            collection = client.collections.use(name)
            # One row each, so an alias resolves to something a query can return. Which collection
            # answered is readable from the title, which is the point when reading through a name.
            for index in (1, 2):
                collection.data.insert(
                    properties={"title": f"{name} row {index}", "sku": f"{name}-{index}"},
                    vector=[float(index), 0.0],
                )
            print(f"created {name} with 2 objects")

    print()
    for alias, target in ALIASES.items():
        status, body = rest("POST", "/v1/aliases", {"alias": alias, "class": target})
        print(f"created alias {alias} -> {target}"
              if status < 300 else f"alias {alias} failed: HTTP {status} {body}")

    print("\nwhat the server now reports")
    for entry in list_aliases():
        print(f"  {entry['alias']:20s} -> {entry['class']}")

    print("\nfiltered to ProductsV3 (the ?class= parameter the Java client calls collection())")
    for entry in list_aliases("ProductsV3"):
        print(f"  {entry['alias']:20s} -> {entry['class']}")

    print("\nOpen the connection in DBeaver: Aliases under it lists all three, and Aliases under")
    print("ProductsV3 lists the two pointing at it. OrdersLegacy has an empty one, which is the")
    print("case that needs no advisory row -- empty means empty.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
