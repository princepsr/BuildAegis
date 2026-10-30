# Security and Configuration

BuildAegis is designed to run on your own machine. This page lists the security defaults and the settings that change them.

## Defaults

| Area | Default | How to change it |
|---|---|---|
| Network | The server listens on **`127.0.0.1` only** (not reachable from other machines) | Set the environment variable `BUILDAEGIS_BIND_ADDRESS` (for example `0.0.0.0` inside a container) or `server.address` |
| H2 database console | **Off** | Set `BUILDAEGIS_H2_CONSOLE=true` (a development tool; do not enable it on a shared machine) |
| API key storage | **Encrypted** with AES-GCM | See below |
| API key in the API | **Never returned**: shown masked (last four characters) | n/a |
| Error responses | Short, sanitized messages; **no stack traces or exception names** | n/a |
| Local file browsing (`/api/system/*`) | Only answers requests from **the same machine** (loopback), even if the server is reachable from the network | n/a |

## Encryption of stored API keys

The AI provider API key is stored in the local database, encrypted with AES-GCM.

- If `buildaegis.encryption.secret` is set, the key is derived from it.
- If it is empty (the default), BuildAegis generates a random key **once** and stores it in the file `buildaegis.encryption.key-file` (default `./data/buildaegis.key`), readable only by the current user where the operating system allows it.
- Only if that file cannot be created is the API key stored in plain text, and a warning is logged.

**Keep the key file together with the database.** If the database is kept but the key file (or the secret) is lost or changed, the stored API key cannot be decrypted; enter the key again in the AI settings.

**Upgrading from an older version:** keys saved earlier in plain text stay readable and are encrypted the next time you save the AI settings.

## Settings reference

| Property / environment variable | Default | Meaning |
|---|---|---|
| `server.address` / `BUILDAEGIS_BIND_ADDRESS` | `127.0.0.1` | Address the server listens on |
| `spring.h2.console.enabled` / `BUILDAEGIS_H2_CONSOLE` | `false` | H2 web console |
| `buildaegis.encryption.secret` | (empty) | Secret for encrypting stored API keys; empty = use the key file |
| `buildaegis.encryption.key-file` | `./data/buildaegis.key` | Generated key file used when no secret is set |
| `spring.datasource.url` | `jdbc:h2:file:./data/buildaegis` | Database location |
| `server.error.include-stacktrace`, `include-message`, `include-exception` | `never` / `never` / `false` | Keep error responses free of internals |

## What leaves your machine

- **Vulnerability and metadata lookups** send package coordinates (`groupId:artifactId:version`) to OSV, Maven Central, GitHub (repository statistics and release notes), CISA (the KEV catalog) and FIRST (EPSS). No source code is sent.
- **AI features** (optional) send the dependency's coordinates, vulnerability details and, for the upgrade summary, the library's public release notes to the AI provider you configured. **Ask AI** sends only the finding's details and your question. With a local provider (Ollama, LM Studio) nothing leaves your machine.
- Nothing is sent when AI is disabled, apart from the lookups above.

## Reporting a vulnerability

See [SECURITY.md](../SECURITY.md).
