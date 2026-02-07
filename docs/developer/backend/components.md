# Backend Components and Responsibilities

This document explains each major backend component and what it owns.

## Entry Point

### `BuildAegisApplication`
**Responsibility:**
- Bootstraps Spring Boot.
- Initializes component scanning and configuration.

## Controllers (HTTP / REST)

### `ProjectAnalysisController` (`/api/project`)
**Responsibility:** project-level analysis used by the UI.

- `GET /api/project/scan`
  - Returns detected dependency coordinates.
  - Delegates to `DependencyScannerService.scanProject(...)`.

- `POST /api/project/analyze`
  - Runs the full pipeline.
  - Delegates to `ProjectAnalysisService.analyze(...)`.

### `DashboardController` (`/api/dashboard`)
**Responsibility:** read-only cached results.

- `GET /api/dashboard/cached-results`
  - Reads cached results scoped to provider/model.
  - Converts cache entities into `DependencyRiskDto`.

### `AiSettingsController` (`/api/ai`)
**Responsibility:** AI configuration lifecycle.

- `GET /api/ai/settings`
  - Returns current saved settings.
- `PUT /api/ai/settings`
  - Saves provider/model/apiKey (encrypted when configured).
- `POST /api/ai/test-connection`
  - Runs a small request through `AIAnalysisService` to validate configuration.

### `ExportController` (`/api/export`)
**Responsibility:** exports (JSON/PDF) using the same analysis pipeline.

- `POST /api/export/json`
- `POST /api/export/pdf`

Both endpoints:
- Accept `ProjectAnalysisRequest`
- Invoke `ProjectAnalysisService.analyze(request)`
- Return a downloadable attachment

### `VulnerabilityAnalysisController` (`/api/vulnerabilities`)
**Responsibility:** “single dependency” and “batch” vulnerability analysis APIs, plus suppression.

This controller is not necessarily used by the current UI flow, but is part of the backend feature set.

Key endpoints:
- `GET /api/vulnerabilities/analyze`
- `POST /api/vulnerabilities/analyze-batch`
- `POST /api/vulnerabilities/suppress`
- `POST /api/vulnerabilities/unsuppress`

## Services

### `DependencyScannerService`
**Responsibility:** dependency detection from a project path.

- Detects build tool (Maven/Gradle) by scanning for build files.
- Returns `DependencyCoordinate` objects used downstream.
- Supports multi-project Gradle builds with intelligent project filtering.

### `GradleExecutionDependencyResolver`
**Responsibility:** Gradle dependency resolution with multi-project support.

**Key Features:**
- **Multi-project detection**: Automatically discovers subprojects via `gradle projects`
- **Intelligent filtering**: Skips Node.js, documentation, test, and build/utility projects
- **Subproject analysis**: Can analyze specific subprojects from root context
- **Performance optimization**: Configurable subproject skipping via system properties

**Project Types Detected & Skipped:**
- Node.js projects (`package.json` + `node-gradle` plugin)
- Documentation projects (Asciidoctor, "doc" in name)
- Test projects ("test" in name + test-only dependencies)
- Build/utility projects (no runtime dependencies)
- Test-only libraries (`java-library` + only test deps)

**Usage Modes:**
- **Root analysis**: Analyzes all relevant subprojects
- **Subproject analysis**: Analyzes specific subproject with parent context
- **Fast mode**: Skip subprojects with `-Dgradle.skip.subprojects=true`

### `ProjectAnalysisService`
**Responsibility:** orchestrates end-to-end analysis.

Owns:
- Scanning
- Enrichment
- AI analysis (if configured)
- Cache lookups and persistence

### `MetadataEnrichmentService`
**Responsibility:** best-effort enrichment of dependency coordinates.

Typical outputs:
- Vulnerability count
- Vulnerability IDs
- Ecosystem / SCM metadata

### `AIAnalysisService`
**Responsibility:** AI calls, structured parsing, and connectivity tests.

- Implements `testConnection()` used by `/api/ai/test-connection`.
- Implements dependency-level analysis used by the pipeline.

### `AiSettingsService`
**Responsibility:** store/retrieve AI provider settings.

Owns:
- `getSettings()`
- `saveSettings(request)`
- Provider/model getters used by caching and dashboard.

### `CryptoService`
**Responsibility:** encrypt/decrypt secrets (API keys).

Behavior:
- Encryption is enabled only when `buildaegis.encryption.secret` is present.

### Vulnerability pipeline services (under `service/vulnerability/*`)

Key components:
- `VulnerabilityMatchingService`: matches dependencies to known vulnerabilities.
- `VulnerabilityExplanationService`: optional AI explanations for vulnerability findings.
- `FalsePositiveAnalyzer`: context heuristics for possible false positives.
- `VulnerabilitySuppressionService`: suppression + unsuppression operations.

## Persistence Layer

### Repositories

- `AiSettingsRepository`
- `DependencyRiskCacheRepository`
- (plus vulnerability-related repositories, if enabled)

### Entities

- AI settings entity (single-row)
- Dependency risk cache entity (multiple rows)

## DTOs (API contracts)

Common DTOs for the main UI pipeline:
- `ProjectAnalysisRequest`
- `ProjectAnalysisResponse`
- `DependencyRiskDto`
- `DependencyEnrichmentDto`

## What the Current Web UI Uses

The current static UI primarily uses:
- `POST /api/project/analyze`
- `GET /api/dashboard/cached-results`

It also relies on the DTO shapes returned by those endpoints.

---

Related docs:
- `docs/developer/backend/architecture.md`
- `docs/api/endpoints.md`
