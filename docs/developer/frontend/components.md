# Frontend Components (UI)

This document describes the major UI components as they exist in `index.html`, and which `app.js` functions are responsible for updating them.

## 1) Input & Configuration Panel (Left)

### Build Tool Picker
**Markup:** radio buttons (`#buildToolMaven`, `#buildToolGradle`)

**Responsibility:**
- Lets you select Maven vs Gradle scanning mode.
- Impacts:
  - Confidence badge display
  - Gradle warning banner behavior

### Project Path Input
**Markup:** `textarea#buildFileContent`

**Responsibility:**
- User inputs a filesystem path to a project directory.
- The backend resolves the build file from that path.

### Primary Actions
**Buttons:**
- `#analyzeDependencies`
- `#loadCachedResults`
- `#clearBuildFile`

**Responsibility:**
- Trigger analysis and cached reload.
- Reset UI/state.

### AI Configuration
**Toggle:** `#enableAI`

**Fieldset:** `#aiFieldset`

**Responsibility:**
- When enabled, the fieldset becomes interactive.
- UI reads provider + apiKey from the inputs and submits them as part of requests (or saves settings depending on backend implementation).

## 2) Results Overview (Right)

### Empty State
**Markup:** `#overviewEmpty`

**Responsibility:**
- Shown before any results are loaded.

### KPI Grid
**Markup:** elements like:
- `#kpiTotalDependencies`
- `#kpiVulnsFound`
- `#kpiCritical`
- `#kpiHigh`
- `#kpiMedium`
- `#kpiLow`
- `#kpiOverallRisk`

**Responsibility:**
- Display summary metrics.
- Hover tooltips are provided using the `data-tooltip` attribute.

**Important behavior:**
- KPI **counts** reflect the **current view** (i.e., filtered findings).
- `#kpiOverallRisk` (overall risk score) and the status chip reflect the **whole project** and do not change with filters.
- `#kpiOverallNote` is hidden unless a filter/search is active; then it shows "Filtered view: N" (the score of the visible findings).

### Confidence Badge
**Markup:** `#confidenceBadge`

**Responsibility:**
- Communicates how reliable dependency resolution is.
- Typically:
  - Maven → HIGH
  - Gradle → MEDIUM

### Scan Metadata
**Markup:**
- `#metaBuildTool`
- `#metaTimestamp`
- `#metaSources`

**Responsibility:**
- Shows build tool used, timestamp, and vulnerability sources.

## 3) Findings (Table)

### Filters
**Markup:**
- `input#searchDependencies`
- `select#filterSeverity`
- `select#filterDirectness`
- `select#filterConfidence`
- `button#exportResults`

**Responsibility:**
- Updates `State.filters`.
- Triggers `State.applyFilters()` and `UI.updateResults()`.

### Findings Count
**Markup:** `#findingsCount`

**Responsibility:**
- Shows how many findings are visible vs total.

### Results Table
**Markup:**
- `tbody#resultsBody`

**Columns:**
- Dependency
- CVEs
- Severity
- ID
- Confidence
- Directness
- Source

**Responsibility:**
- Rows are generated dynamically based on `State.filteredFindings`.
- Each row includes a “View” action that opens the details modal.

## 4) Analysis Panel

### Severity Distribution Bars
**Markup:**
- `#barCritical`, `#barHigh`, `#barMedium`, `#barLow`
- `#countCritical`, `#countHigh`, `#countMedium`, `#countLow`

**Responsibility:**
- Visual distribution based on the current view.

### Top Recommendations
**Markup:** `#topRecommendations`

**Responsibility:**
- Aggregates recommendations from visible findings.
- Falls back to a default message when none exist.

## 5) Details Modal

**Modal container:** `#detailsModal`

**Primary fields:**
- `#detailsDependency`
- `#detailsSeverity`
- `#detailsId`
- `#detailsRiskScore`
- `#detailsConfidence`
- `#detailsDirectness`
- `#detailsSource`
- `#detailsDescription`
- `#detailsAffected`
- `#detailsPath`
- `#detailsExplanation`

**Responsibility:**
- Shows the selected finding in detail.
- Includes the per-finding risk score.

## 6) Global UI Elements

### Loading Overlay
**Markup:** `#loadingOverlay`, `#loadingText`

**Responsibility:**
- Prevents interaction during long operations.

### Error Banner
**Markup:** created/inserted dynamically by `app.js`

**Responsibility:**
- Displays friendly errors for API failures and unexpected responses.

---

Next docs:
- `docs/api/endpoints.md`
- `docs/architecture/risk-scoring.md`
