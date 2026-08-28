#!/usr/bin/env python3
"""Seed multi-tenant collections for exercising tenant management in DBeaver.

Three collections, because the two auto-tenant flags are independent and the interesting bugs
live in telling them apart:

    DBeaverTenantFixture       auto-creation off, auto-activation off   9 tenants, mixed status
    DBeaverTenantAutoFixture   auto-creation on,  auto-activation on    grown by inserting
    DBeaverTenantMixedFixture  auto-creation on,  auto-activation off   2 tenants

Tenant names are deliberately two-part -- <company>-<region> -- so wildcard selection has
something to bite on: "acme-*" and "*-ap-south" pick out different, overlapping sets. Statuses
are mixed on purpose; a fixture where every tenant is ACTIVE cannot show a status column working.

OFFLOADED is not seeded. It needs an offload module (offload-s3) on the server, and a stock
Weaviate has none, so the script reports that rather than pretending the status is unreachable.

Pass a count to also seed a large collection, for seeing how the UI holds up:

    uv run --with weaviate-client seed_tenant_fixture.py            # the three above
    uv run --with weaviate-client seed_tenant_fixture.py --scale 4000

Environment:
    WEAVIATE_HTTP_HOST / _PORT, WEAVIATE_GRPC_HOST / _PORT, WEAVIATE_API_KEY
"""

from __future__ import annotations

import os
import sys
import time

import weaviate
import weaviate.classes.config as wc
from weaviate.classes.init import Auth
from weaviate.classes.tenants import Tenant, TenantActivityStatus

MAIN = "DBeaverTenantFixture"
AUTO = "DBeaverTenantAutoFixture"
MIXED = "DBeaverTenantMixedFixture"
SCALE = "DBeaverTenantScaleFixture"

HTTP_HOST = os.environ.get("WEAVIATE_HTTP_HOST", "localhost")
HTTP_PORT = int(os.environ.get("WEAVIATE_HTTP_PORT", "8080"))
API_KEY = os.environ.get("WEAVIATE_API_KEY", "root-user-key")

# <company>-<region>. Three companies x three regions, minus a few, so neither axis is uniform
# and a wildcard on either side selects a different set.
TENANTS = [
    "acme-eu-west",
    "acme-us-east",
    "acme-ap-south",
    "globex-eu-west",
    "globex-us-east",
    "initech-eu-west",
    "initech-us-east",
    "initech-ap-south",
    "soylent-us-east",
]

# Deactivated after their data goes in, so INACTIVE tenants still have something to show once
# they are woken up again. "*-ap-south" is fully inactive; soylent breaks the pattern so that
# selecting by region and selecting by status are visibly different operations.
INACTIVE = {"acme-ap-south", "initech-ap-south", "soylent-us-east"}

PROPERTIES = [
    wc.Property(name="title", data_type=wc.DataType.TEXT),
    wc.Property(name="owner", data_type=wc.DataType.TEXT, tokenization=wc.Tokenization.FIELD),
    wc.Property(name="amount", data_type=wc.DataType.INT),
]


def connect() -> weaviate.WeaviateClient:
    return weaviate.connect_to_custom(
        http_host=HTTP_HOST, http_port=HTTP_PORT, http_secure=False,
        grpc_host=os.environ.get("WEAVIATE_GRPC_HOST", "localhost"),
        grpc_port=int(os.environ.get("WEAVIATE_GRPC_PORT", "50051")), grpc_secure=False,
        auth_credentials=Auth.api_key(API_KEY) if API_KEY else None,
    )


def recreate(client: weaviate.WeaviateClient, name: str, mt: object):
    if client.collections.exists(name):
        client.collections.delete(name)
        print(f"  deleted existing {name}")
    client.collections.create(
        name,
        # No vectorizer: tenancy is the subject, and an embedding call would make the fixture
        # depend on a model provider being reachable.
        vector_config=wc.Configure.Vectors.self_provided(),
        multi_tenancy_config=mt,
        properties=PROPERTIES,
    )
    return client.collections.use(name)


def fill(collection, tenant: str, rows: int) -> None:
    """Rows carry their own tenant name, so a grid row makes its tenant obvious."""
    scoped = collection.with_tenant(tenant)
    company, region = tenant.split("-", 1)
    for i in range(rows):
        scoped.data.insert(
            properties={
                "title": f"{company} order {i + 1} ({region})",
                "owner": tenant,
                "amount": 100 * (i + 1),
            },
            vector=[float(i), 1.0],
        )


def count(collection, tenant: str) -> str:
    """INACTIVE tenants refuse reads; that refusal is the answer, not an error."""
    try:
        return str(len(collection.with_tenant(tenant)))
    except Exception:
        return "-"


def seed_main(client: weaviate.WeaviateClient) -> None:
    print(f"{MAIN}: auto-creation off, auto-activation off")
    collection = recreate(client, MAIN, wc.Configure.multi_tenancy(
        enabled=True, auto_tenant_creation=False, auto_tenant_activation=False))

    collection.tenants.create([Tenant(name=name) for name in TENANTS])
    for i, name in enumerate(TENANTS):
        fill(collection, name, rows=2 + (i % 3))
    print(f"  created {len(TENANTS)} tenants with data")

    # Deactivate last: a tenant has to be ACTIVE to be written to.
    collection.tenants.update([
        Tenant(name=name, activity_status=TenantActivityStatus.INACTIVE) for name in sorted(INACTIVE)
    ])
    print(f"  deactivated {len(INACTIVE)}: {', '.join(sorted(INACTIVE))}")


def seed_auto(client: weaviate.WeaviateClient) -> None:
    print(f"\n{AUTO}: auto-creation on, auto-activation on")
    collection = recreate(client, AUTO, wc.Configure.multi_tenancy(
        enabled=True, auto_tenant_creation=True, auto_tenant_activation=True))

    # Never created explicitly -- writing to them is what brings them into being.
    grown = ["wonka-eu-west", "wonka-us-east"]
    for name in grown:
        fill(collection, name, rows=2)
    print(f"  {len(collection.tenants.get())} tenants exist after inserting into: {', '.join(grown)}")

    # And auto-activation is the other half: a deactivated tenant answers a read anyway.
    sleeper = grown[0]
    collection.tenants.update([Tenant(name=sleeper, activity_status=TenantActivityStatus.INACTIVE)])
    before = collection.tenants.get_by_name(sleeper).activity_status
    rows = len(collection.with_tenant(sleeper))
    after = collection.tenants.get_by_name(sleeper).activity_status
    print(f"  {sleeper}: set {before.value}, read {rows} objects, now {after.value}")


def seed_mixed(client: weaviate.WeaviateClient) -> None:
    print(f"\n{MIXED}: auto-creation on, auto-activation off")
    collection = recreate(client, MIXED, wc.Configure.multi_tenancy(
        enabled=True, auto_tenant_creation=True, auto_tenant_activation=False))
    for name in ("umbrella-eu-west", "umbrella-us-east"):
        fill(collection, name, rows=1)
    sleeper = "umbrella-us-east"
    collection.tenants.update([
        Tenant(name=sleeper, activity_status=TenantActivityStatus.INACTIVE)])

    # The contrast with AUTO above, observed rather than asserted: same read, same INACTIVE
    # starting point, and here it stays asleep because only auto-creation is on.
    try:
        outcome = f"read {len(collection.with_tenant(sleeper))} objects"
    except Exception as e:
        outcome = f"refused the read ({type(e).__name__})"
    status = collection.tenants.get_by_name(sleeper).activity_status
    print(f"  {sleeper}: set INACTIVE, {outcome}, now {status.value}")


def seed_scale(client: weaviate.WeaviateClient, total: int) -> None:
    """A collection big enough that a UI which renders every row will show it.

    Names are <company>-<type>-<n>, three axes, so a wildcard on any one of them selects a
    different slice: company01-* is 200 tenants, company01-type1-* is 50, *-type1-* is 1000.
    """
    print(f"\n{SCALE}: {total} tenants")
    collection = recreate(client, SCALE, wc.Configure.multi_tenancy(enabled=True))

    per_type = 50
    names = [f"company{c:02d}-type{t}-{r:03d}"
             for c in range(1, 21) for t in range(1, 5) for r in range(1, per_type + 1)][:total]

    # Chunked because the create is a single request otherwise, and a few thousand tenants in
    # one body is a slow way to find out something is wrong.
    chunk = 500
    t0 = time.time()
    for i in range(0, len(names), chunk):
        collection.tenants.create([Tenant(name=n) for n in names[i:i + chunk]])
    print(f"  created in {time.time() - t0:.1f}s")

    # A slice deactivated, so the status column has something to show at this scale too.
    sleepers = [n for n in names if n.startswith("company01-type1-")]
    t0 = time.time()
    collection.tenants.update([
        Tenant(name=n, activity_status=TenantActivityStatus.INACTIVE) for n in sleepers])
    print(f"  deactivated {len(sleepers)} matching company01-type1-* in {time.time() - t0:.1f}s")

    t0 = time.time()
    listed = collection.tenants.get()
    print(f"  listing all {len(listed)} back: {(time.time() - t0) * 1000:.0f} ms")


def report(client: weaviate.WeaviateClient) -> None:
    print("\ntenants as the server sees them:\n")
    print(f"  {'collection':28s} {'tenant':20s} {'status':10s} objects")
    for name in (MAIN, AUTO, MIXED):
        collection = client.collections.use(name)
        for tenant in sorted(collection.tenants.get().values(), key=lambda t: t.name):
            print(f"  {name:28s} {tenant.name:20s} "
                  f"{tenant.activity_status.value:10s} {count(collection, tenant.name)}")

    modules = client.get_meta().get("modules", {})
    offload = [m for m in modules if m.startswith("offload-")]
    print("\nOFFLOADED tenants: " + (
        f"available via {', '.join(offload)}" if offload
        else "not seeded -- this server has no offload module, so the status is unreachable"))


def main(argv: list[str]) -> int:
    scale = 0
    if "--scale" in argv:
        scale = int(argv[argv.index("--scale") + 1])

    with connect() as client:
        seed_main(client)
        seed_auto(client)
        seed_mixed(client)
        if scale:
            seed_scale(client, scale)
        report(client)
        print(f"\nOpen {MAIN} in DBeaver and right-click it -> Manage Tenants.")
        if scale:
            print(f"For the scale test, open {SCALE} and filter on company01-type1-*")
        return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
