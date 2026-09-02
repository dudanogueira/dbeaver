#!/usr/bin/env python3
"""Ask the server which collection settings can actually be changed after creation.

The docs, the bundled Java client and the server do not agree about this, and only one of the
three is authoritative. So this sends one change at a time to a throwaway collection, records what
the server said, and then reads the definition back to see whether it did what it said.

Three outcomes per setting:

    applied    2xx, and the definition afterwards holds the new value
    rejected   non-2xx, with the server's own explanation
    IGNORED    2xx, and the definition afterwards is unchanged -- accepted and quietly dropped

The third is the reason this exists. A 200 that changes nothing is indistinguishable from success
at the call site, and it is the failure a config editor would otherwise ship.

The collection is populated before probing. An empty one accepts a quantizer configuration and
never trains it, which reads as "mutable" and is not.

    uv run --with weaviate-client probe_collection_mutability.py
    uv run --with weaviate-client probe_collection_mutability.py --cleanup

Environment:
    WEAVIATE_HTTP_HOST / _PORT, WEAVIATE_GRPC_HOST / _PORT, WEAVIATE_API_KEY

Not probed: raising replicationConfig.factor. It is the only change here with effects outside the
throwaway collection -- it starts real shard replication across the cluster -- and the docs are
unambiguous that the schema API refuses it. Recorded as documented-immutable, unverified.
"""

from __future__ import annotations

import copy
import json
import os
import random
import sys
import urllib.error
import urllib.request

import weaviate
import weaviate.classes.config as wc
from weaviate.classes.init import Auth

PROBE = "DBeaverMutabilityProbe"
# One fresh collection per quantizer: the docs say quantization cannot be turned off once set, so
# probing a second one on the same collection would be measuring the first one's leftovers.
QUANTIZERS = ["rq", "bq", "sq", "pq"]
QUANT_PROBES = {q: f"{PROBE}{q.upper()}" for q in QUANTIZERS}
# The auto-tenant flags are the one setting this plugin already writes, and they are refused on a
# single-tenant collection -- so measuring them needs a collection that has tenants.
MT_PROBE = f"{PROBE}MT"

OBJECTS = 1000
DIMS = 8

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
        return e.code, e.read().decode()[:400]


def definition(name: str) -> dict:
    status, body = rest("GET", f"/v1/schema/{name}")
    if status != 200:
        raise SystemExit(f"cannot read {name}: HTTP {status} {body}")
    return json.loads(body)


def dig(document: dict, path: str):
    """Read a dotted path, or None when any step is missing."""
    node = document
    for step in path.split("."):
        if not isinstance(node, dict) or step not in node:
            return None
        node = node[step]
    return node


def plant(document: dict, path: str, value) -> None:
    """Write a dotted path, creating the objects on the way."""
    node = document
    steps = path.split(".")
    for step in steps[:-1]:
        node = node.setdefault(step, {})
    node[steps[-1]] = value


def message(body: str) -> str:
    """The server's own explanation, unwrapped from its error envelope."""
    try:
        parsed = json.loads(body)
    except ValueError:
        return body.strip()[:150]
    errors = parsed.get("error")
    if isinstance(errors, list) and errors:
        return str(errors[0].get("message", errors[0]))[:150]
    return body.strip()[:150]


def probe(name: str, path: str, value, note: str = "") -> tuple[str, str, str]:
    """Send exactly one change and report (verdict, detail, note)."""
    before = definition(name)
    was = dig(before, path)
    document = copy.deepcopy(before)
    plant(document, path, value)
    status, body = rest("PUT", f"/v1/schema/{name}", document)
    if status < 200 or status >= 300:
        return "rejected", f"HTTP {status}: {message(body)}", note
    now = dig(definition(name), path)
    if now == value:
        return "applied", f"{was!r} -> {now!r}", note
    if now == was:
        return "IGNORED", f"HTTP {status}, still {was!r}", note
    return "adjusted", f"sent {value!r}, got {now!r} (was {was!r})", note


def seed(name: str, populate: bool = True, **overrides) -> None:
    with connect() as client:
        if client.collections.exists(name):
            client.collections.delete(name)
        client.collections.create(
            name,
            # Self-provided vectors: this is about the definition, and an embedding call would make
            # the probe depend on a model provider being reachable.
            vector_config=wc.Configure.Vectors.self_provided(),
            properties=[
                wc.Property(name="title", data_type=wc.DataType.TEXT,
                            tokenization=wc.Tokenization.WORD),
                wc.Property(name="rank", data_type=wc.DataType.INT),
            ],
            **overrides,
        )
        if not populate:
            # A multi-tenant collection refuses a write that names no tenant, and the flags this
            # one exists to measure do not care how many objects are underneath them.
            print(f"  created {name}, empty")
            return
        collection = client.collections.use(name)
        random.seed(7)
        with collection.batch.fixed_size(batch_size=200) as batch:
            for i in range(OBJECTS):
                batch.add_object(
                    properties={"title": f"row {i}", "rank": i},
                    vector=[random.random() for _ in range(DIMS)],
                )
        print(f"  seeded {name} with {OBJECTS} objects")


def drop(name: str) -> None:
    with connect() as client:
        if client.collections.exists(name):
            client.collections.delete(name)
            print(f"deleted {name}")


def cleanup() -> None:
    drop(PROBE)
    drop(MT_PROBE)
    for name in QUANT_PROBES.values():
        drop(name)


def row(label: str, verdict: str, detail: str, note: str) -> None:
    mark = {"applied": "yes", "rejected": "NO", "IGNORED": "IGNORED", "adjusted": "adjusted"}[verdict]
    print(f"  {label:46s} {mark:9s} {detail}")
    if note:
        print(f"  {'':46s} {'':9s} {note}")


def main() -> int:
    if "--cleanup" in sys.argv:
        cleanup()
        return 0

    print(f"probing {BASE}\n")
    cleanup()
    seed(PROBE)

    current = definition(PROBE)
    index_path = "vectorIndexConfig"
    if not current.get("vectorIndexConfig") and current.get("vectorConfig"):
        # Named vectors: the index config sits under the vector's own entry instead.
        vector = next(iter(current["vectorConfig"]))
        index_path = f"vectorConfig.{vector}.vectorIndexConfig"
        print(f"  (named vector {vector!r}: reading the index at {index_path})")
    print()

    print("collection")
    row("description", *probe(PROBE, "description", "probed"))

    print("\ninverted index")
    for path, value in [
        ("invertedIndexConfig.bm25.b", 0.5),
        ("invertedIndexConfig.bm25.k1", 1.5),
        ("invertedIndexConfig.cleanupIntervalSeconds", 120),
        ("invertedIndexConfig.stopwords.preset", "none"),
        ("invertedIndexConfig.stopwords.additions", ["probe"]),
        ("invertedIndexConfig.stopwords.removals", ["the"]),
        ("invertedIndexConfig.indexTimestamps", True),
        ("invertedIndexConfig.indexNullState", True),
        ("invertedIndexConfig.indexPropertyLength", True),
        ("invertedIndexConfig.usingBlockMaxWAND", False),
    ]:
        row(path.split("invertedIndexConfig.")[1], *probe(PROBE, path, value))

    print("\nreplication")
    for path, value, note in [
        ("replicationConfig.deletionStrategy", "DeleteOnConflict", ""),
        ("replicationConfig.asyncEnabled", True,
         "docs say the flag was removed in 1.38; the server still reports it"),
    ]:
        row(path.split("replicationConfig.")[1], *probe(PROBE, path, value, note))
    print(f"  {'factor':46s} {'not probed':9s} "
          "documented immutable; raising it starts real replication across the cluster")

    print("\nobject TTL")
    row("objectTtlConfig.enabled", *probe(
        PROBE, "objectTtlConfig",
        {"enabled": True, "defaultTtl": 86400, "deleteOn": "_creationTimeUnix"},
        "absent from the definition entirely until it is set"))

    print("\nmulti-tenancy (single-tenant collection)")
    for path, value in [
        ("multiTenancyConfig.autoTenantCreation", True),
        ("multiTenancyConfig.autoTenantActivation", True),
        ("multiTenancyConfig.enabled", True),
    ]:
        row(path.split("multiTenancyConfig.")[1], *probe(PROBE, path, value))

    print("\nmulti-tenancy (multi-tenant collection)")
    seed(MT_PROBE, populate=False,
         multi_tenancy_config=wc.Configure.multi_tenancy(enabled=True))
    for path, value in [
        ("multiTenancyConfig.autoTenantCreation", True),
        ("multiTenancyConfig.autoTenantActivation", True),
        ("multiTenancyConfig.enabled", False),
    ]:
        row(path.split("multiTenancyConfig.")[1], *probe(MT_PROBE, path, value))

    print("\nsharding")
    for path, value in [
        ("shardingConfig.desiredCount", 2),
        ("shardingConfig.virtualPerPhysical", 256),
    ]:
        row(path.split("shardingConfig.")[1], *probe(PROBE, path, value))

    print("\nvector index (hnsw)")
    for leaf, value in [
        ("ef", 96),
        ("dynamicEfMin", 50),
        ("dynamicEfMax", 400),
        ("dynamicEfFactor", 10),
        ("flatSearchCutoff", 60000),
        ("cleanupIntervalSeconds", 600),
        ("vectorCacheMaxObjects", 500000),
        ("filterStrategy", "sweeping"),
        ("efConstruction", 256),
        ("maxConnections", 64),
        ("distance", "dot"),
        ("skip", True),
    ]:
        row(leaf, *probe(PROBE, f"{index_path}.{leaf}", value))

    print("\nimmutable by documentation, checked anyway")
    row("vectorizer", *probe(PROBE, "vectorizer", "text2vec-contextionary"))
    row("vectorIndexType", *probe(PROBE, "vectorIndexType", "flat"))
    row("class", *probe(PROBE, "class", "DBeaverMutabilityProbeRenamed"))

    print("\nmodules")
    row("moduleConfig.reranker-transformers", *probe(
        PROBE, "moduleConfig.reranker-transformers", {}))
    row("moduleConfig.generative-openai", *probe(
        PROBE, "moduleConfig.generative-openai", {"model": "gpt-4o"}))

    print("\nproperties")
    before = definition(PROBE)
    properties = copy.deepcopy(before["properties"])
    for prop in properties:
        if prop["name"] == "title":
            prop["description"] = "probed"
    document = copy.deepcopy(before)
    document["properties"] = properties
    status, body = rest("PUT", f"/v1/schema/{PROBE}", document)
    after = next(p for p in definition(PROBE)["properties"] if p["name"] == "title")
    print(f"  {'title.description':46s} "
          f"{('applied' if after.get('description') == 'probed' else 'IGNORED'):9s} "
          f"HTTP {status} {'' if status < 300 else message(body)}")

    properties = copy.deepcopy(before["properties"])
    for prop in properties:
        if prop["name"] == "title":
            prop["tokenization"] = "field"
    document = copy.deepcopy(before)
    document["properties"] = properties
    status, body = rest("PUT", f"/v1/schema/{PROBE}", document)
    after = next(p for p in definition(PROBE)["properties"] if p["name"] == "title")
    verdict = ("rejected" if status >= 300
               else "applied" if after.get("tokenization") == "field" else "IGNORED")
    print(f"  {'title.tokenization':46s} {verdict:9s} "
          f"HTTP {status} {message(body) if status >= 300 else 'now ' + str(after.get('tokenization'))}")

    print("\nquantization -- one fresh, populated collection each")
    for quantizer, name in QUANT_PROBES.items():
        seed(name)
        path = "vectorIndexConfig"
        fresh = definition(name)
        if not fresh.get("vectorIndexConfig") and fresh.get("vectorConfig"):
            path = f"vectorConfig.{next(iter(fresh['vectorConfig']))}.vectorIndexConfig"
        verdict, detail, _ = probe(name, f"{path}.{quantizer}.enabled", True)
        row(quantizer, verdict, detail, "")

    print("\nprobe collections left in place; re-run with --cleanup to remove them")
    return 0


if __name__ == "__main__":
    sys.exit(main())
