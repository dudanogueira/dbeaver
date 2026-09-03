#!/usr/bin/env python3
"""Seed collections with reranker and generative modules attached, to have something to change.

The two module settings are the pair the mutability probe found genuinely changeable on an
existing collection, and the only ones needing a picker rather than a text field: this server
offers five rerankers and fourteen generative providers. So the fixture is about shapes to change
*from*, not about running a query:

    DBeaverModuleFixture            no reranker, no generative      adding one from nothing
    DBeaverModuleRerankFixture      reranker-cohere                 changing a model, or swapping
                                                                    the reranker for another
    DBeaverModuleGenerativeFixture  generative-openai, gpt-4o       same, on the generative side
    DBeaverModuleBothFixture        reranker-jinaai + generative-   both at once, and the case that
                                    anthropic                       exposes the client's append bug

That last one matters. The bundled client's rerankerModules(...) adds to the existing list rather
than replacing it, so swapping jinaai for cohere through config.update leaves *both* attached.
A collection that already has one is the only way to see that happen.

No API keys are needed. Naming a module in the schema does not call it -- only a rerank or a
generative query would, and those will fail without credentials, which is expected here.

    uv run --with weaviate-client seed_module_fixture.py
    uv run --with weaviate-client seed_module_fixture.py --cleanup

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

HTTP_HOST = os.environ.get("WEAVIATE_HTTP_HOST", "localhost")
HTTP_PORT = int(os.environ.get("WEAVIATE_HTTP_PORT", "8080"))
API_KEY = os.environ.get("WEAVIATE_API_KEY", "root-user-key")
BASE = f"http://{HTTP_HOST}:{HTTP_PORT}"

# name -> (reranker module, generative module), both as raw moduleConfig entries so the script
# does not depend on which typed helpers a given python-client version happens to expose.
COLLECTIONS = {
    "DBeaverModuleFixture": (None, None),
    "DBeaverModuleRerankFixture": (
        ("reranker-cohere", {"model": "rerank-english-v3.0"}), None),
    "DBeaverModuleGenerativeFixture": (
        None, ("generative-openai", {"model": "gpt-4o"})),
    "DBeaverModuleBothFixture": (
        ("reranker-jinaai", {"model": "jina-reranker-v2-base-multilingual"}),
        ("generative-anthropic", {"model": "claude-sonnet-4-5"})),
}

ROWS = [
    ("Mountain trail guide", "outdoor"),
    ("Coastal path notes", "outdoor"),
    ("Compiler design primer", "software"),
    ("Distributed systems reader", "software"),
    ("Alpine weather patterns", "science"),
]


def connect() -> weaviate.WeaviateClient:
    return weaviate.connect_to_custom(
        http_host=HTTP_HOST, http_port=HTTP_PORT, http_secure=False,
        grpc_host=os.environ.get("WEAVIATE_GRPC_HOST", "localhost"),
        grpc_port=int(os.environ.get("WEAVIATE_GRPC_PORT", "50051")), grpc_secure=False,
        auth_credentials=Auth.api_key(API_KEY) if API_KEY else None,
    )


def rest(method: str, path: str, body: dict | None = None) -> tuple[int, str]:
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
        return e.code, e.read().decode()[:300]


def available() -> set[str]:
    status, body = rest("GET", "/v1/meta")
    return set((json.loads(body).get("modules") or {}).keys()) if status == 200 else set()


def cleanup() -> None:
    with connect() as client:
        for name in COLLECTIONS:
            if client.collections.exists(name):
                client.collections.delete(name)
                print(f"deleted {name}")


def main() -> int:
    if "--cleanup" in sys.argv:
        cleanup()
        return 0

    enabled = available()
    print(f"{len(enabled)} modules enabled on this server")
    rerankers = sorted(m for m in enabled if m.startswith("reranker-"))
    generatives = sorted(m for m in enabled if m.startswith("generative-"))
    print(f"  {len(rerankers)} rerankers:  {', '.join(r.split('reranker-')[1] for r in rerankers)}")
    print(f"  {len(generatives)} generative: "
          f"{', '.join(g.split('generative-')[1] for g in generatives)}\n")

    cleanup()
    with connect() as client:
        for name in COLLECTIONS:
            client.collections.create(
                name,
                # Self-provided vectors: this fixture is about module configuration, and an
                # embedding call would make it need credentials it deliberately avoids.
                vector_config=wc.Configure.Vectors.self_provided(),
                properties=[
                    wc.Property(name="title", data_type=wc.DataType.TEXT),
                    wc.Property(name="topic", data_type=wc.DataType.TEXT,
                                tokenization=wc.Tokenization.FIELD),
                ],
            )
            collection = client.collections.use(name)
            for index, (title, topic) in enumerate(ROWS):
                collection.data.insert(
                    properties={"title": title, "topic": topic},
                    vector=[float(index), 1.0 - index / 10.0],
                )

    # The modules go on afterwards, over REST. Attaching them is exactly the change the
    # Configuration editor will make, so the fixture makes it the same way the plugin will.
    print("attaching modules")
    for name, (reranker, generative) in COLLECTIONS.items():
        status, body = rest("GET", f"/v1/schema/{name}")
        if status != 200:
            print(f"  {name}: cannot read back ({status})")
            continue
        document = json.loads(body)
        modules = document.get("moduleConfig") or {}
        wanted = []
        for entry in (reranker, generative):
            if entry is not None:
                modules[entry[0]] = entry[1]
                wanted.append(entry[0])
        if not wanted:
            print(f"  {name}: left bare, for adding one from nothing")
            continue
        document["moduleConfig"] = modules
        status, body = rest("PUT", f"/v1/schema/{name}", document)
        if status >= 300:
            print(f"  {name}: refused, HTTP {status} {body[:150]}")
            continue
        after = json.loads(rest("GET", f"/v1/schema/{name}")[1]).get("moduleConfig") or {}
        for module in wanted:
            mark = "ok" if module in after else "NOT STORED"
            missing = "" if module in enabled else "   (module not enabled on this server)"
            print(f"  {name}: {module} {mark}{missing}")

    print("\nwhat the tree should show, per collection")
    for name in COLLECTIONS:
        modules = json.loads(rest("GET", f"/v1/schema/{name}")[1]).get("moduleConfig") or {}
        rr = [m for m in modules if m.startswith("reranker-")] or ["(none)"]
        gg = [m for m in modules if m.startswith("generative-")] or ["(none)"]
        print(f"  {name:32s} Rerankers: {', '.join(rr):24s} Generative: {', '.join(gg)}")

    print("\nNo API keys are configured, so a rerank or a generative query will fail. Naming a")
    print("module in the schema does not call it -- this fixture is about the definition.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
