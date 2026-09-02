# DBeaver Weaviate plugin

Support for [Weaviate](https://weaviate.io), a vector database. Non-JDBC: the plugin talks to
Weaviate over the official Java client (HTTP + gRPC), and over the REST API directly wherever the
client cannot be trusted with the answer - see [Why some of this is raw REST](#why-some-of-this-is-raw-rest).

## Building — one manual step

The Weaviate Java client is **not** committed to this repository. Download it into `lib/` before
building:

```bash
curl -L -o plugins/org.jkiss.dbeaver.ext.weaviate/lib/client6-6.3.1-all.jar \
  https://repo1.maven.org/maven2/io/weaviate/client6/6.3.1/client6-6.3.1-all.jar
```

The version must match `Bundle-ClassPath` in `META-INF/MANIFEST.MF` and
`WeaviateConstants.CLIENT_VERSION`. Then build normally from the repository root:

```bash
mvn clean verify -P product-dbeaver-ce
```

Tycho cannot resolve OSGi `Require-Bundle` from a partial reactor, so `-pl`, `-am` and `-rf` all
fail here - build the whole reactor. Tests run under `verify`, not `package`.

## Open question: how should the client be distributed?

This manual step exists because none of DBeaver's usual mechanisms fit, and picking one needs a
maintainer decision:

- **`maven:/` driver files** (how the 100+ JDBC drivers do it) download at runtime and keep the jar
  out of git. That works because those plugins compile against `java.sql` only. This plugin
  compiles against `io.weaviate.*` directly, so it needs the library at build time as well.
- **A `org.jkiss.bundle.*` wrapper on `repo.dbeaver.net/p2/ce/`** is how non-JDBC libraries
  (`sshj`, `gis`, `gson`) are handled, and is the natural fit here - but only DBeaver can publish
  to that repository.
- **A Maven `<dependency>`** resolved through Tycho does not work as configured: the root POM sets
  `<pomDependencies>consider</pomDependencies>`, which silently ignores artifacts that are not OSGi
  bundles, and the client's manifest declares no `Bundle-SymbolicName`. Tycho reports:
  `... is not a bundle and will be ignored, automatic wrapping of such artifacts can be enabled
  with <pomDependencies>wrapAsBundle</pomDependencies>`. Switching that would change dependency
  resolution for every module in the build.
- **Committing the jar** is what the plugin did previously. At ~30 MB it is far larger than the
  only comparable precedent (`org.jkiss.dbeaver.core/lib/awt.injector-1.0.0.jar`, 8 KB), and once
  committed it cannot be removed from history without a rewrite.

The `-all` classifier is required either way: it relocates gRPC and protobuf under
`io.weaviate.shaded.*`, which avoids clashing with other bundles in the OSGi runtime.

## What it does

The navigator tree, top level down, and where each part lives.

| Area | What you can do | Model | UI |
|---|---|---|---|
| **Collections** | browse the full definition, create and delete, edit as raw JSON, preview tokenization | `WeaviateCollection`, `WeaviateJsonNode` | `WeaviateCollectionEditDialog`, `WeaviateTokenizePreviewDialog` |
| **Data** | read rows, filter, group, pick a tenant to read as | `WeaviateResultSet*`, `WeaviateFilter*` | query panel, `WeaviateTenantSelectDialog` |
| **Multi-Tenancy** | list tenants, activate and deactivate in bulk, toggle auto-create and auto-activate | `WeaviateTenantNode`, `WeaviateTenantFilter` | `WeaviateTenantManageDialog` |
| **Cluster Nodes** | nodes, their shards grouped by collection, per-shard status with colour | `WeaviateNode`, `WeaviateShardGroup`, `WeaviateShard` | `WeaviateShardChangeDialog`, `WeaviateInactiveTenantShardsDialog` |
| **Backups** | list by backend, create and restore as DBeaver tasks with a poll loop | `WeaviateBackup*`, `model/tasks/` | `ui/tasks/` |
| **Security** | roles and their permissions, database users, OIDC groups; create, edit and delete roles; manage users, assign roles, rotate keys | `WeaviateRole`, `WeaviateDbUser`, `WeaviateRbacAction`, `WeaviateRbacKind` | `WeaviateRoleDialog`, `WeaviateRoleRuleDialog`, `WeaviateRoleAssignmentDialog`, `WeaviateApiKeyDialog` |
| **Replication** | movements with their state history, where each shard's replicas are, start a move or copy, follow, cancel, delete, force-delete | `WeaviateReplicationOp`, `WeaviateReplicationState`, `WeaviatePlacementTracker` | `WeaviateReplicateShardDialog`, `WeaviateReplicationWatch` |
| **Aliases** | list every alias and the collection it resolves to, from the connection or from a collection's own folder; create, repoint, delete | `WeaviateAlias`, `WeaviateAliases` | `WeaviateAliasDialog` |
| **Modules, Server Metadata** | what the server has enabled, with links to the docs for each | `WeaviateModule`, `WeaviateDocTopics` | - |

Version-gated features are declared once in `WeaviateServerFeature` rather than as version
literals at call sites. The policy is to hide what can never apply and disable-and-explain what
merely does not apply yet.

## Why some of this is raw REST

Four areas go over `java.net.http` with plain-JDK records instead of the bundled client, and not
for taste. The client models server vocabularies as closed enums, and the server keeps adding to
them:

| Helper | What the client gets wrong |
|---|---|
| `WeaviateSchemaRest` | `collections.create` round-trips through an object model that drops fields it does not know; `updateShards` reads the shard list back afterwards, and that read 500s on any multi-tenant collection with an inactive tenant - reporting failure for writes that succeeded |
| `WeaviateNodesRest` | `VectorIndexingStatus` has three constants against the server's six, so Gson nulls `LAZY_LOADING`; `vectorQueueLenght` is misspelled, so the queue length is always 0 |
| `WeaviateRbacRest` | `Permission.Kind` has no `NAMESPACES`, and `JsonEnum.valueOfJson` **throws** - so `roles.list()` fails outright on every 1.38+ server, because the built-in `admin` and `root` roles always carry `manage_namespaces` |
| `WeaviateReplicationRest` | `errors` is typed `List<String>` where the server sends objects, so reading an operation that recorded an error throws; `ReplicationState` has no `INTEGRATING`; `ShardReplica.shardName` never binds |

So actions, states and scopes are carried as `String` and `Map`, never as enums that can throw or
null. A server newer than this build degrades to "shown but not editable" instead of failing.

Aliases are the counter-example, and the reason the check is worth doing each time rather than
assuming: `Alias` is a two-field record carrying exactly what the wire carries, and the namespace
has no enum at all, so that branch stays on the client. It is also the only area that therefore
works on OIDC connections, where a REST helper cannot authenticate. The five rough edges it does
have -- an argument order, a null list, a filter parameter named twice over, and 404 handled two
different ways -- are pinned by `WeaviateAliasLiveTest` rather than routed around.

## Layout

| Bundle | Contents |
|---|---|
| `org.jkiss.dbeaver.ext.weaviate` | Model, navigator tree, REST helpers, data reading, backup tasks |
| `org.jkiss.dbeaver.ext.weaviate.ui` | Connection page, query panel, dialogs, navigator commands |
| `org.jkiss.dbeaver.ext.weaviate.test` | Unit and live tests (fragment of the model bundle) |

The UI bundle must not reference shaded `io.weaviate.*` types: its classloader cannot see them,
since they live on the model bundle's `Bundle-ClassPath`. Anything crossing that boundary is
exchanged as plain-JDK records - see `WeaviateSchemaJson` and the REST helpers above.

Two traps worth knowing before editing either `plugin.xml`:

- **Enablement is computed in `setEnabled()`**, not `<enabledWhen>`. The conditions here depend on
  a navigator folder's meta id, which no built-in property tester exposes, and a tester shipped
  from a lazily-activated bundle evaluates as `NOT_LOADED` before that bundle starts - so the menu
  entry would never appear and the bundle would never activate to make it appear. `setEnabled` also
  cannot see a command's parameter, which is why folder-scoped and row-scoped actions are separate
  handler classes.
- **A node's identity is its unique name, not its label.** `DBNDatabaseNode` reuses a tree node only
  when class and unique name both match, so any label carrying changing state must implement
  `DBPUniqueObject` or the row is replaced on every change and everything expanded under it
  collapses.

## Tests

Unit tests run in any build. Live tests are skipped unless their fixture URL is set, so the
reactor stays hermetic:

```bash
mvn clean verify -T 1C                                    # unit only
for v in FILTER GROUP TENANT TOKENIZE BACKUP RBAC REPLICATION ALIAS; do
  export WEAVIATE_${v}_FIXTURE_URL=http://localhost:8080
done
mvn clean verify -T 1C                                    # plus the live ones
```

Nine fixture variables, one per live test class. `WEAVIATE_OLD_FIXTURE_URL` is the odd one out:
it wants a *deliberately old* server, because `WeaviateVersionGateLiveTest` exists to check that a
feature gate hides what that server cannot do. Pointing it at the same server as the others proves
nothing.

Each area has a seed script under `testdata/`, run with
`uv run --with weaviate-client seed_<area>_fixture.py` and `--cleanup` to undo. They exist to make
the *shapes* reproducible, not the data: the RBAC fixture covers every permission scope shape, and
the replication fixture deliberately includes one shard replicated onto every node, so there is no
legal target for a movement, and the alias fixture puts two aliases on one collection and none on
another.

The live tests are there for one job - catching drift between the server's vocabularies and this
plugin's. A new state or action shows up as a failure naming it, rather than as a blank row in
somebody's tree. `WEAVIATE_REPLICATION_FIXTURE_URL` needs a cluster of at least two nodes started
with `REPLICA_MOVEMENT_ENABLED=true`.
