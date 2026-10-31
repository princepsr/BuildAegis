# API Endpoints Reference

This document describes the backend REST APIs consumed by the UI.

Base URL (local dev): `http://localhost:8080/buildaegis`

## Conventions

- Requests/Responses use JSON unless otherwise specified.
- Errors are returned as non-2xx HTTP status codes with a JSON body `{"error": "short message"}`. Bad input or missing configuration gives `400`; other failures give `500`. Messages are single-line, length-limited and have anything that looks like an API key removed. Stack traces and exception class names are never returned.
- Some endpoints depend on configured AI settings (provider/model).
- Timestamps (for example `analyzedAt`) are ISO-8601 strings, e.g. `2026-02-05T12:00:00Z`.
- The UI sends `Accept: application/json`; endpoints that return a file (PDF export) must be called with a matching `Accept` header (`application/pdf` or `*/*`), otherwise the server answers `406 Not Acceptable`.

## Project Analysis

### POST `/api/project/analyze`

Runs a full analysis pipeline for a project path.

**Request body (typical):**
```json
{
  "projectPath": "C:\\path\\to\\project",
  "forceRefresh": false,
  "aiEnabled": false
}
```

**Request Fields:**
- `projectPath` (string, required): Path to Maven or Gradle project
- `forceRefresh` (boolean, optional): Bypass cache and re-analyze
- `aiEnabled` (boolean, optional): Enable AI analysis (requires configured AI settings)

Notes:
- For Gradle projects, the app automatically uses the best available Gradle installation (project wrapper → bundled wrapper → system Gradle)
- The UI primarily sends project path and scan settings.
- When `aiEnabled` is `false` (or omitted), the backend will not call any AI provider APIs.

**Response (typical):**
```json
{
  "dependencies": [
    {
      "groupId": "org.example",
      "artifactId": "lib",
      "version": "1.2.3",
      "buildTool": "maven",
      "riskLevel": "HIGH",
      "riskScore": 72,
      "explanation": "...",
      "recommendations": ["..."],
      "enrichment": {
        "vulnerabilityCount": 5,
        "vulnerabilityIds": ["CVE-..."],
        "ecosystem": "Maven",
        "suggestedFixVersion": "1.2.9",
        "maxSeverity": "HIGH",
        "severityCounts": { "HIGH": 2, "MEDIUM": 3 },
        "knownExploited": false,
        "maxEpssScore": 0.04,
        "fixNote": null
      }
    }
  ],
  "buildTool": "maven",
  "timestamp": "2026-02-05T12:00:00"
}
```

**Enrichment fields** (all optional, `null` when unknown):
- `maxSeverity`: highest advisory severity (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`), from CVSS or the advisory's own label
- `severityCounts`: number of vulnerabilities per severity
- `knownExploited`: true if a vulnerability is in the CISA Known Exploited Vulnerabilities catalog
- `maxEpssScore`: highest EPSS exploit probability (0-1) among the vulnerabilities
- `suggestedFixVersion`: smallest version fixing the known vulnerabilities, preferring the same major version as the one in use
- `fixNote`: set when that version leaves some vulnerabilities unfixed because they are only fixed in another major version (for example "2 of 14 vulnerabilities are not fixed in this minor version (6.2.x); fixing them needs a major upgrade to 7.0.9.")

### GET `/api/project/scan?projectPath=...`

Performs dependency detection only (no AI, no export). Useful for debugging.

**Query params:**
- `projectPath` (required)

**Response:** list of dependencies.

## Cached Results

### GET `/api/dashboard/cached-results?projectPath=...`

Returns cached `DependencyRiskDto` results for the currently configured provider/model.

**Query params:**
- `projectPath` (optional): project folder or build file. When given, only the dependencies found in that project at its last analysis are returned, and `isDirect` is the value for that project. When omitted, all cached results are returned.

Notes:
- This is used by the UI’s **Load Cached** button, which sends the path currently in the project path box.
- `projectPath` is normalized, so `C:/proj`, `C:/proj/` and `C:/proj/pom.xml` refer to the same project (case-insensitive on Windows).
- A project returns results only after it has been analyzed once; before that the list is empty.
- Without `projectPath`, `isDirect` is true if the dependency is direct in any analyzed project, and true for dependencies analyzed before projects were recorded.
- If provider/model changes, the cached result set changes.

## AI Settings

### GET `/api/ai/settings`

Returns stored AI settings.

Response fields include:
- `provider`
- `model`
- `configured`
- `updatedAt`
- `customEndpoint` (only relevant when `provider` is `custom`)

### PUT `/api/ai/settings`

Saves AI settings.

Request fields:
- `provider`
- `model`
- `apiKey`
- `customEndpoint` (required when `provider` is `custom`)

**API key:** responses never contain the stored key. `apiKey` is returned masked (for example `********wxyz`, or `null` when none is saved). When saving, sending the masked value or an empty `apiKey` keeps the stored key; sending a new key replaces it. `apiKey` is required the first time.

### POST `/api/ai/ask`

Answers a question about one finding using only the details in the request (no source code is sent to the AI).

**Body:**

```json
{
  "dependency": "org.apache.tomcat.embed:tomcat-embed-core:10.1.34",
  "severity": "CRITICAL",
  "vulnerabilityCount": 11,
  "vulnerabilityIds": ["CVE-2025-24813"],
  "knownExploited": true,
  "fixVersion": "10.1.36",
  "explanation": "...",
  "recommendations": ["..."],
  "question": "Am I affected if I only serve static files?"
}
```

**Response:** `{"answer": "..."}`. `question` is required and at most 500 characters. Returns `400` if AI is not configured or the question is invalid. Rate limits are retried.

### POST `/api/ai/test-connection`

Attempts a minimal request to validate provider/model/apiKey.

**Response:** `{"status": "OK"}` (JSON). Errors use a non-2xx status.

Notes:
- For `provider=custom`, the backend uses the saved `customEndpoint`.
- Success indicates the provider accepted the credentials and returned a valid response envelope; it does not require a specific response string.

## Export

### POST `/api/export/json`

Runs analysis (or uses cache) and downloads a JSON report.

**Body:** same as `/api/project/analyze`. If `projectPath` is empty, the report contains all cached results for the current provider/model (this is what the UI exports after **Load Cached** with an empty path).

### POST `/api/export/pdf`

Runs analysis (or uses cache) and downloads a PDF report. Same body and empty-path behavior as the JSON export.

Call it with `Accept: application/pdf` (or `*/*`).

## System (Project Path Picker)

Used by the UI's **Browse...** button. These endpoints act on the machine that runs the backend and are meant for local use.

### POST `/api/system/browse?initial=...`

Opens the operating system's file dialog and returns the selection.

**Query params:**
- `initial` (optional): path to start in.

**Responses:**
- `200` `{"path": "C:/proj/pom.xml"}`: a build file, or a project folder.
- `204`: the dialog was cancelled.
- `501`: no dialog can be shown (headless server); the UI falls back to the in-page browser below.

On Windows the dialog runs in its own PowerShell process so that it appears in front of the browser. Opening can take about a second.

### GET `/api/system/fs?path=...`

Lists sub-folders and build files (`pom.xml`, `build.gradle`, `build.gradle.kts`) of a folder, for the in-page folder browser.

**Query params:**
- `path` (optional): folder to list. When omitted, returns the home folder and the filesystem roots/drives.

**Response:**

```json
{
  "path": "C:\\Users\\Name\\proj",
  "parent": "C:\\Users\\Name",
  "entries": [
    { "name": "src", "path": "C:\\Users\\Name\\proj\\src", "directory": true },
    { "name": "pom.xml", "path": "C:\\Users\\Name\\proj\\pom.xml", "directory": false }
  ]
}
```

At the top level `path` is an empty string and `parent` is `null`. A path that is not a folder returns `400` with `{"error": "..."}`.

---

Related docs:
- `docs/developer/backend/architecture.md`
