# BuildAegis

BuildAegis is a Spring Boot application that analyzes Java projects (Maven/Gradle) for dependency vulnerabilities and presents results in a lightweight web UI.

## What It Does

- **Project analysis**: Scan a project path and analyze dependencies for known vulnerabilities.
- **Vulnerability enrichment**: Adds vulnerability counts and IDs (best-effort).
- **Optional AI explanations**: When enabled/configured, AI can generate explanations and recommendations.
- **Local caching**: Results are cached locally (H2) and can be reloaded via the UI.
- **Filtering + export**: Filter findings in the UI and export the current view to CSV.

## Supported Build Tools

### Maven
- **Method**: Uses Maven Resolver (Aether) for reliable dependency resolution
- **Confidence**: HIGH - Direct access to Maven's dependency resolution engine
- **Features**: Full dependency graph, transitive dependencies, scope information
- **Safe Mode**: Native support for controlled execution

### Gradle
- **Method**: Best-effort Gradle analysis (confidence depends on project/build file patterns)
- **Confidence**: MEDIUM - Gradle builds are dynamic; analysis may be incomplete
- **Note**: Some false positives/negatives are possible; review Gradle findings carefully

## Confidence Levels

Confidence levels indicate how reliable the vulnerability detection is:

### HIGH (80-100)
- Typically Maven projects (dependency resolution is more reliable)

### MEDIUM (50-79)
- Typically Gradle projects (best-effort resolution)

### LOW (0-49)
- Possible for uncertain matches (project/build specific)

## Documentation (Authoritative)

Start here:

- [docs/README.md](docs/README.md)

Key docs:

- [docs/user-guide/getting-started.md](docs/user-guide/getting-started.md)
- [docs/user-guide/features.md](docs/user-guide/features.md)
- [docs/user-guide/ui-guide.md](docs/user-guide/ui-guide.md)
- [docs/architecture/risk-scoring.md](docs/architecture/risk-scoring.md)
- [docs/developer/backend/architecture.md](docs/developer/backend/architecture.md)
- [docs/developer/frontend/architecture.md](docs/developer/frontend/architecture.md)
- [docs/api/endpoints.md](docs/api/endpoints.md)

## Key Features

- **Dependency Scanning**
  - Maven: `pom.xml` (via Maven Resolver)
  - Gradle: `build.gradle`, `build.gradle.kts` (best-effort)
- **Multi-Source Vulnerability Detection**
  - OSV (Open Source Vulnerability Database)
  - NVD (National Vulnerability Database)
  - GitHub Advisory Database
  - Maven Central metadata
- **AI-Powered Explanations**
  - Human-friendly vulnerability descriptions
  - Real-world impact analysis
  - Remediation recommendations
  - Exploitation scenarios
- **Suppression Management**
  - Auditable vulnerability suppression
  - Detailed reasoning and justification
  - Compliance tracking
- **Local Caching** (H2 + Spring Data JPA)
- **Export Options**
  - JSON export with full analysis details
  - PDF export for reports
- **Desktop Mode**
  - JavaFX wrapper for standalone application

## Prerequisites

- **Java 21** (JDK)
- No global Maven required (uses the Maven Wrapper: `mvnw` / `mvnw.cmd`)

## Documentation

- **Docs index**: [docs/README.md](docs/README.md)

## Quick Start (Web)

1. Run the backend:

```powershell
.\mvnw spring-boot:run
```

2. Open the UI:
- `http://localhost:8080/buildaegis`

3. (Optional) Configure AI:

- Toggle on **Enable AI-assisted analysis**
- Select a provider and enter your API key

If `buildaegis.encryption.secret` is configured, API keys are stored encrypted at rest.

## Quick Start (Desktop)

Desktop mode is enabled via the Maven profile `desktop`:

```powershell
.\mvnw -Pdesktop javafx:run
```

This starts the same Spring Boot backend locally and loads the UI inside a JavaFX WebView.

## Testing

```powershell
.\mvnw clean test
```

## Building a JAR

```powershell
.\mvnw clean package
```

This runs the tests and creates a self-contained, runnable jar: `target\BuildAegis.v<version>.jar` (for example `BuildAegis.v3.0.0.jar`, about 75 MB, all dependencies included; the version comes from `<version>` in `pom.xml`, so raise it there for a new release). Add `-DskipTests` to skip the tests.

Run the built jar (UI at `http://localhost:8080/buildaegis/`):

```powershell
java -jar target\BuildAegis.v3.0.0.jar
```

The same jar can scan a project without the UI for CI: add `--buildaegis.cli.project=<path>` (see [Command Line and CI](docs/user-guide/cli-and-ci.md)).

Notes:
- If `clean` fails with "Failed to delete ... target\classes", an instance of the app is still running from `target`. Stop it, or run `.\mvnw package` without `clean`.
- The jar is a build artifact and is not committed (`target/` is gitignored).
- **Publishing a release**: set the new version in `pom.xml`, build, and attach `target\BuildAegis.v<version>.jar` to the GitHub release: `gh release upload <tag> target\BuildAegis.v<version>.jar`, or drag it onto the release page.
- The JavaFX desktop version is built separately with the `desktop` Maven profile (`-Pdesktop`) and needs platform-specific JavaFX libraries.

## Configuration

Main configuration file:
- `src/main/resources/application.properties`

Key settings:
- **H2 database**: persisted to `./data/buildaegis`
- **H2 console**: `http://localhost:8080/buildaegis/h2-console` (off by default; start the app with `BUILDAEGIS_H2_CONSOLE=true` to enable it)
- **Encryption secret**: `buildaegis.encryption.secret`
  - Set this for stable encryption across restarts
  - If you change it later, previously stored encrypted API keys cannot be decrypted
- **Vulnerability cache**: `~/.buildaegis/vulnerability-cache`

## API Endpoints

Authoritative API documentation is in [docs/api/endpoints.md](docs/api/endpoints.md).

### AI Settings
- `GET /api/ai/settings` – Get current AI provider/model configuration
- `PUT /api/ai/settings` – Save AI settings (provider/model/apiKey)
- `POST /api/ai/test-connection` – Verify AI connection

### Project Analysis
- `GET /api/project/scan?projectPath=...` – Detect dependencies
- `POST /api/project/analyze` – Full analysis with caching

### Export
- `POST /api/export/json` – Download JSON report
- `POST /api/export/pdf` – Download PDF report

## Security Notes

- **Local Processing**: All vulnerability analysis happens locally
- **No Data Sharing**: Vulnerability data is not sent to external services (except optional AI)
- **Encrypted Storage**: API keys and sensitive data are encrypted at rest
- **Gradle note**: Gradle projects are analyzed best-effort; results may be incomplete
- **Audit Trail**: All suppression actions are logged for compliance

## When False Positives May Occur

1. **Test Dependencies**: Vulnerabilities in test-scoped dependencies may not be exploitable
2. **Optional Dependencies**: Optional dependencies may not be loaded at runtime
3. **Shaded Packages**: Dependencies may be shaded/repackaged, changing vulnerability context
4. **Version Ranges**: Broad version ranges may include unaffected versions
5. **Transitive Conflicts**: Dependency mediation may resolve to different versions

## How Confidence Level Works

See [docs/user-guide/features.md](docs/user-guide/features.md).

## How Scoring Works

See [docs/architecture/risk-scoring.md](docs/architecture/risk-scoring.md).

## Architecture Tradeoffs

### Maven vs Gradle Resolution
**Why Maven uses Aether but Gradle can't:**
- Maven has stable, public APIs for dependency resolution (Maven Resolver/Aether)
- Gradle's internal APIs are unstable and version-specific
- Gradle requires executing the build to get accurate dependency information
- Solution: Controlled execution of Gradle commands with output parsing

### Controlled Execution Necessity
**Why Gradle requires controlled execution:**
- Gradle builds can execute arbitrary code
- Build scripts may download and execute unknown dependencies
- Plugin system introduces security risks
- Solution: Isolated execution environments with restricted permissions

### Safe Mode Limitations
**How SAFE mode protects users:**
- Prevents arbitrary code execution from build tools
- Limits network access during dependency resolution
- May miss dynamic dependencies or build-time resolved artifacts
- Tradeoff: Security vs completeness of analysis

## Project Structure

- `src/main/java/.../controller` – REST controllers
- `src/main/java/.../service` – Core business logic and orchestration
- `src/main/java/.../model` – Domain models and entities
- `src/main/java/.../dto` – Request/response DTOs
- `src/main/resources/static` – Web UI
- `src/desktop/java` – JavaFX desktop wrapper
- `docs/` – Documentation

## Troubleshooting

- **Build fails with JavaFX classes**: Ensure you're running desktop mode with `-Pdesktop`
- **AI test-connection fails**: Verify provider, model name, and API key
- **Gradle resolution fails**: Check Gradle installation and project compatibility
- **Old cached results**: Use `forceRefresh=true` to bypass cache
- **Memory issues**: Increase JVM heap size with `-Xmx2g` or higher

## Contributing

See [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) for guidelines on:
- Adding new dependency resolvers
- Adding vulnerability sources
- Extending AI providers
- Testing strategies
- Code style and conventions

Additional project policies:
- [docs/CODE_OF_CONDUCT.md](docs/CODE_OF_CONDUCT.md)
- [docs/SECURITY.md](docs/SECURITY.md)
