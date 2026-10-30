# Frontend Architecture

## Overview

BuildAegis’s frontend is intentionally simple: a **single-page application** built with **vanilla HTML/CSS/JavaScript** served by Spring Boot from `src/main/resources/static/`.

There is **no build step**, **no bundler**, and **no framework**. The entire UI behavior is implemented in a single JavaScript file (`app.js`) with a small set of internal modules/objects.

## Source of Truth

**Frontend files:**

```
src/main/resources/static/
├── index.html      # UI markup
├── styles.css      # UI styling
└── app.js          # UI logic (state, rendering, API calls)
```

Notes:
- The repo also contains `static/js/` and `static/css/` folders, but the current app uses `index.html` + `styles.css` + `app.js` as the primary runtime assets.
- `index.html` loads `app.js` via:

```html
<script src="/app.js?v=1" defer></script>
```

## Architectural Principles

- **Single source of UI state** via a `State` object.
- **Pure-ish rendering functions** inside a `UI` module: render table, KPI cards, analysis section, modal.
- **Network boundary** centralized in an API helper (fetch wrapper) and a `Scanner` module.
- **Defensive rendering**: handle empty/missing fields gracefully.
- **Filters are first-class**: current view is derived from state, not from DOM.

## Key Runtime Modules (in `app.js`)

### 1) DOM Utilities

**Responsibility:**
- Centralize DOM lookups and safe UI updates.

Typical helpers:
- `DOM.get(id)`
- `DOM.setText(el, text)`
- `DOM.setHTML(el, html)`
- `DOM.show(el)` / `DOM.hide(el)`

Why it exists:
- Avoid repeated `document.getElementById` and null checks.
- Prevent accidental `innerHTML` misuse.

### 2) State

**Responsibility:**
- Maintain the canonical state of the application.

Current state includes (representative):
- `isScanning`
- `error`
- `allFindings` (raw mapped findings)
- `filteredFindings` (derived based on filters)
- `settings` (AI settings toggle/provider/key)
- `filters`:
  - `severity`
  - `directness`
  - `confidence`
  - `search`

State responsibilities:
- Store findings returned from backend.
- Apply filters consistently.
- Trigger UI updates when state changes.

Important behavioral detail:
- The **current view** is `State.filteredFindings`.
- The count tiles (Total, Vulnerabilities found, Critical, High, Medium, Low) are rendered from the current view.
- The **overall risk score and the status chip are rendered from the whole project** (`State.allFindings`), so filters never change them. While a filter/search is active, `#kpiOverallNote` shows "Filtered view: N", the same calculation applied to `State.filteredFindings` (see `UI.updateSummary` and `UI.riskScoreFor`).

### 3) UI

**Responsibility:**
- Render the state into the DOM.

Main responsibilities:
- Results overview (KPIs, metadata)
- Findings table (rows, empty states, count label)
- Modal rendering for a selected finding
- Analysis panel (severity distribution + recommendations)
- Loading overlay and error banner

Key UI functions (representative):
- `UI.clearResults()`
- `UI.updateResults(findings, totalCount)`
- `UI.updateSummary(summary)`
- `UI.updateAnalysis(summary, findings)`
- `UI.renderRows(findings)`
- `UI.openDetailsModal(finding)`

Rendering rules:
- Empty state message: “Enter a project path …” (overview) and “Run an analysis …” (table).
- Tooltips use `[data-tooltip]` and CSS pseudo-elements.

### 4) Scanner

**Responsibility:**
- Coordinate user-triggered operations.

Operations:
- Analyze dependencies via POST `/api/project/analyze`
- Load cached results via GET `/api/dashboard/cached-results`

Scanner controls:
- Loading state (overlay)
- Error handling (banner)
- Mapping backend DTOs into UI “findings” objects

## Data Model: Finding (Frontend)

A frontend “finding” represents one dependency risk row.

Representative shape:

```js
{
  dependency: "groupId:artifactId:version",
  groupId,
  artifactId,
  version,
  cveCount: number,
  vulnerabilityIds: string[],
  primaryVulnId: string,
  severity: "CRITICAL"|"HIGH"|"MEDIUM"|"LOW",
  riskScore: number,
  confidence: "HIGH"|"MEDIUM"|"LOW",
  directness: "direct"|"transitive",
  source: "OSV"|"Cache"|"Maven"|...,
  description,
  affectedVersions,
  path,
  explanation,
  recommendations: string[]
}
```

The mapping from backend DTOs happens in `mapProjectResultsToFindings` (and related helpers).

## Filtering Pipeline

1. Backend results are mapped into `State.allFindings`.
2. Filters are applied in `State.applyFilters()` to produce `State.filteredFindings`.
3. UI renders the filtered list:
   - Table rows
   - KPI counts
   - Analysis distribution
   - Overall risk score and status chip (whole project, not filtered) plus the "Filtered view" line

Filters:
- **Severity**: exact match (unless “all”)
- **Directness**: direct/transitive
- **Confidence**: HIGH/MEDIUM/LOW
- **Search**: substring match on dependency name

## Risk Score on the Frontend

The frontend displays:
- **Overall risk score** in KPI card: computed from **all** findings (`State.allFindings`); filters do not change it.
- **Filtered view** line under it: the same calculation for the visible findings, shown only while a filter/search is active.
- **Per-finding risk score** inside the details modal.

The algorithm is documented in `docs/architecture/risk-scoring.md`.

## Error Handling

- API wrapper converts non-2xx responses into user-friendly messages.
- Unexpected response shapes are guarded to avoid UI crashes.
- An error banner appears in the UI and auto-dismisses.

## Extending the Frontend

Common changes:
- **Add a new filter**: extend `State.filters`, wire UI input events, update `applyFilters`.
- **Add a new column**: update `index.html` table header + `UI.renderRows`.
- **Add more modal fields**: update modal markup in `index.html` + `UI.openDetailsModal`.

---

Related docs:
- `docs/developer/frontend/modules.md`
- `docs/developer/frontend/components.md`
- `docs/api/endpoints.md`
