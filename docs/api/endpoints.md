# API Endpoints Reference

This document describes the backend REST APIs consumed by the UI.

Base URL (local dev): `http://localhost:8080/buildaegis`

## Conventions

- Requests/Responses use JSON unless otherwise specified.
- Errors are returned as non-2xx HTTP status codes with a JSON body (shape may vary).
- Some endpoints depend on configured AI settings (provider/model).

## Project Analysis

### POST `/api/project/analyze`

Runs a full analysis pipeline for a project path.

**Request body (typical):**
```json
{
  "projectPath": "C:\\path\\to\\project",
  "forceRefresh": false,
  "ai": {
    "enabled": false,
    "provider": "openai",
    "apiKey": "...",
    "model": "gpt-4o-mini"
  }
}
```

Notes:
- Actual request shape can differ depending on current backend DTOs.
- The UI primarily sends project path and scan settings.

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
        "ecosystem": "Maven"
      }
    }
  ],
  "buildTool": "maven",
  "timestamp": "2026-02-05T12:00:00"
}
```

### GET `/api/project/scan?projectPath=...`

Performs dependency detection only (no AI, no export). Useful for debugging.

**Query params:**
- `projectPath` (required)

**Response:** list of dependencies.

## Cached Results

### GET `/api/dashboard/cached-results`

Returns cached `DependencyRiskDto` results for the currently configured provider/model.

Notes:
- This is used by the UI’s **Load Cached** button.
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

### POST `/api/ai/test-connection`

Attempts a minimal request to validate provider/model/apiKey.

Notes:
- For `provider=custom`, the backend uses the saved `customEndpoint`.
- Success indicates the provider accepted the credentials and returned a valid response envelope; it does not require a specific response string.

## Export

### POST `/api/export/json`

Runs analysis (or uses cache) and downloads a JSON report.

### POST `/api/export/pdf`

Runs analysis (or uses cache) and downloads a PDF report.

---

Related docs:
- `docs/developer/backend/architecture.md`
