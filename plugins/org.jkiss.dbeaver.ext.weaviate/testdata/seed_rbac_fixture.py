#!/usr/bin/env python3
"""Seed roles and users for exercising RBAC in DBeaver.

The shapes matter more than the names. Between them these roles cover every scope kind the
editor has to lay out, plus the two cases that trip a client which models permissions as closed
enums:

    dbeaver-data-reader     one collection, one tenant   narrow scope, not the "*" everything else uses
    dbeaver-ops             backups + cluster + nodes    a scopeless action next to scoped ones
    dbeaver-role-admin      roles with scope=all         a fixed-value field the editor must not free-text
    dbeaver-multi-rule      two data rules, two scopes   the case a single-rule-per-section UI gets wrong

Users:

    dbeaver-active-user     active, holds dbeaver-data-reader
    dbeaver-idle-user       deactivated, holds nothing

Nothing here touches the built-in roles, which is where the interesting failure already lives:
admin and root carry manage_namespaces on any 1.38+ server, and the Java client throws on it.

    uv run --with weaviate-client seed_rbac_fixture.py
    uv run --with weaviate-client seed_rbac_fixture.py --cleanup

Requires the server started with AUTHORIZATION_RBAC_ENABLED=true and
AUTHENTICATION_DB_USERS_ENABLED=true.

Environment:
    WEAVIATE_HTTP_HOST / _PORT, WEAVIATE_API_KEY
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

HTTP_HOST = os.environ.get("WEAVIATE_HTTP_HOST", "localhost")
HTTP_PORT = int(os.environ.get("WEAVIATE_HTTP_PORT", "8080"))
API_KEY = os.environ.get("WEAVIATE_API_KEY", "root-user-key")
BASE = f"http://{HTTP_HOST}:{HTTP_PORT}"

ROLES = {
    "dbeaver-data-reader": [
        {"action": "read_data",
         "data": {"collection": "DBeaverFilterFixture", "tenant": "*", "object": "*"}},
        {"action": "read_collections", "collections": {"collection": "DBeaverFilterFixture"}},
    ],
    "dbeaver-ops": [
        {"action": "manage_backups", "backups": {"collection": "*"}},
        {"action": "read_cluster"},
        {"action": "read_nodes", "nodes": {"collection": "*", "verbosity": "verbose"}},
    ],
    "dbeaver-role-admin": [
        {"action": "read_roles", "roles": {"role": "*", "scope": "all"}},
        {"action": "update_roles", "roles": {"role": "dbeaver-*", "scope": "match"}},
    ],
    "dbeaver-multi-rule": [
        {"action": "read_data",
         "data": {"collection": "DBeaverGroupFixture", "tenant": "*", "object": "*"}},
        {"action": "create_data",
         "data": {"collection": "DBeaverTenantFixture", "tenant": "acme-eu-west", "object": "*"}},
        {"action": "read_tenants",
         "tenants": {"collection": "DBeaverTenantFixture", "tenant": "acme-*"}},
    ],
}

USERS = {
    "dbeaver-active-user": {"roles": ["dbeaver-data-reader"], "active": True},
    "dbeaver-idle-user": {"roles": [], "active": False},
}


def call(method: str, path: str, body: dict | None = None, quiet_404: bool = False):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Authorization", f"Bearer {API_KEY}")
    if data:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read().decode()
            return json.loads(raw) if raw.strip() else None
    except urllib.error.HTTPError as e:
        if quiet_404 and e.code in (404, 409):
            return None
        raise SystemExit(f"{method} {path} -> HTTP {e.code}: {e.read().decode()[:300]}")


def cleanup() -> None:
    for user in USERS:
        call("DELETE", f"/v1/users/db/{user}", quiet_404=True)
        print(f"  deleted user {user}")
    for role in ROLES:
        call("DELETE", f"/v1/authz/roles/{role}", quiet_404=True)
        print(f"  deleted role {role}")


def main() -> int:
    own = call("GET", "/v1/users/own-info")
    if own is None or own.get("roles") is None:
        print("RBAC does not look enabled on this server: /v1/users/own-info reports no roles.")
        print("Start Weaviate with AUTHORIZATION_RBAC_ENABLED=true and at least one root user.")
        return 1
    print(f"connected to {BASE} as {own.get('username')}")

    if "--cleanup" in sys.argv:
        cleanup()
        return 0

    # Idempotent: drop anything left from a previous run rather than colliding with a 409.
    cleanup()

    print("\nroles")
    for name, permissions in ROLES.items():
        call("POST", "/v1/authz/roles", {"name": name, "permissions": permissions})
        print(f"  {name}: {len(permissions)} permission(s)")

    print("\nusers")
    for user, spec in USERS.items():
        created = call("POST", f"/v1/users/db/{user}", {})
        key = (created or {}).get("apikey", "")
        if spec["roles"]:
            call("POST", f"/v1/authz/users/{user}/assign",
                 {"roles": spec["roles"], "userType": "db"})
        if not spec["active"]:
            call("POST", f"/v1/users/db/{user}/deactivate", {"revoke_key": False})
        print(f"  {user}: {'active' if spec['active'] else 'deactivated'}, "
              f"roles={spec['roles'] or 'none'}, key={key[:4]}...")

    print("\nverifying what the tree will read")
    roles = call("GET", "/v1/authz/roles")
    builtin = [r["name"] for r in roles if r["name"] in ("admin", "root", "viewer", "read-only")]
    seeded = [r["name"] for r in roles if r["name"].startswith("dbeaver-")]
    print(f"  {len(roles)} roles: {len(builtin)} built-in {builtin}, {len(seeded)} seeded")
    kinds = set()
    for r in roles:
        for p in r.get("permissions") or []:
            kinds.update(k for k in p if k != "action")
    print(f"  permission kinds present: {sorted(kinds)}")
    users = call("GET", "/v1/users/db?includeLastUsedTime=true")
    print(f"  {len(users)} db users: "
          + ", ".join(f"{u['userId']}({'active' if u.get('active') else 'inactive'})"
                      for u in users))
    return 0


if __name__ == "__main__":
    sys.exit(main())
