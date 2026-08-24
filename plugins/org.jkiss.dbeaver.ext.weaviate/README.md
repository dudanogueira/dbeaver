# DBeaver Weaviate plugin

Support for [Weaviate](https://weaviate.io), a vector database. Non-JDBC: the plugin talks to
Weaviate over the official Java client (HTTP + gRPC) and, for collection creation and schema
reading, directly over the REST API.

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

## Layout

| Bundle | Contents |
|---|---|
| `org.jkiss.dbeaver.ext.weaviate` | Model, navigator tree, data reading, collection create/delete |
| `org.jkiss.dbeaver.ext.weaviate.ui` | Connection page, query panel, collection definition dialog |
| `org.jkiss.dbeaver.ext.weaviate.test` | Unit tests (fragment of the model bundle) |

The UI bundle must not reference shaded `io.weaviate.*` types: its classloader cannot see them,
since they live on the model bundle's `Bundle-ClassPath`. Anything crossing that boundary is
exchanged as plain strings - see `WeaviateSchemaJson`.
