# BuildAegis Features

## Overview

BuildAegis provides comprehensive vulnerability analysis for Java projects. This guide explains all features and how to use them effectively.

## Core Features

### 1. Dependency Scanning

#### Maven Support
- **High Confidence**: Uses Maven Resolver (Aether) for reliable dependency resolution
- **Full Transitive Analysis**: Complete dependency graph construction
- **Scope Awareness**: Distinguishes compile, test, provided, runtime scopes
- **Automatic Detection**: Finds and parses `pom.xml` automatically

#### Gradle Support
- **Medium Confidence**: Best-effort parsing via controlled execution
- **Build File Parsing**: Analyzes `build.gradle` and `build.gradle.kts`
- **Configuration Support**: Handles multiple Gradle configurations
- **Note**: May have some false positives due to dynamic resolution

### 2. Vulnerability Detection

#### OSV Database Integration
- Primary source: Open Source Vulnerabilities (OSV)
- Structured version ranges for accurate matching
- Ecosystem-specific vulnerability data
- Real-time API queries with local caching

#### Data Sources
| Source | Type | Coverage |
|--------|------|----------|
| OSV | Primary | Open source vulnerabilities |
| NVD | Reference | National Vulnerability Database |
| GitHub Advisory | Reference | GitHub-curated advisories |
| Maven Central | Metadata | Package metadata |

### 3. Risk Scoring System

#### Per-Dependency Score (0-100)

Each dependency gets a deterministic risk score from the backend (AI is only used to write the explanation):

- Starts at 20
- Known vulnerabilities add points by their **real severity**: the most severe one counts in full (Critical +55, High +40, Medium +20, Low +8), each additional one adds +3 (max +15), capped at +65 in total
- A vulnerability **exploited in the wild** (CISA KEV) adds 25 and keeps the score at 90 or above
- A high exploit probability (EPSS) adds a few points
- Small additions for long inactivity, a large open-issue backlog and low community adoption

Score to risk level: **70+ = High**, **40-69 = Medium**, **20-39 = Low**.

#### Severity Shown in the UI

The Critical / High / Medium / Low tiles, the severity column and the severity filter use:

- **Critical**: a vulnerability of the dependency is exploited in the wild (CISA KEV)
- Otherwise the **highest advisory severity** of its vulnerabilities (from CVSS or GitHub's severity label)
- Without severity data: 10 or more vulnerabilities is Critical, then the backend risk level

#### Exploit Data (CISA KEV and EPSS)

BuildAegis looks up two free data sources to prioritize vulnerabilities that are actually being attacked:

- **CISA Known Exploited Vulnerabilities**: confirmed exploitation in the wild. Shown as "Exploited in the wild (CISA KEV)" in the finding details.
- **EPSS**: the probability that a CVE is exploited in the next 30 days. Shown as "EPSS: 97%".

Both are cached for 24 hours and are best-effort: if they cannot be reached, the analysis simply continues without them.

#### Overall Risk Score (Whole Project)

The **Overall risk score** card is the highest per-dependency score in the project, plus 1 point for each additional dependency with known vulnerabilities (up to +5), capped at 100.

**Filters and search do not change the overall score or the status chip.** They only change the findings list and the count tiles. While a filter or search is active, a **"Filtered view: N"** line under the overall score shows the same calculation for just the visible findings. See [Risk Scoring](../architecture/risk-scoring.md) for details and an example.

#### Risk Score Interpretation

| Score Range | Meaning | Action |
|-------------|---------|--------|
| 70-100 | High | Address soon / immediately |
| 40-69 | Medium | Plan remediation |
| 20-39 | Low | Monitor |
| 0-19 | Minimal / none | No action needed |

#### Upgrade Guidance

For dependencies with known vulnerabilities, the recommendations include the **smallest known version that fixes all reported vulnerabilities** (taken from OSV fix data), together with the expected size of the behavior change:

- **Patch** upgrade: behavior changes are unlikely
- **Minor** upgrade: usually backward compatible; check release notes for deprecations and changed defaults
- **Major** upgrade: expect breaking changes; review release notes and run your tests

When AI is enabled, the same AI request that writes the explanation also summarizes the probable behavior changes of the upgrade. If the library's GitHub **release notes** for the fixed version can be found, they are given to the AI, so the summary is based on real changelog text; without release notes it is labeled as AI-generated without release notes. Always verify against the release notes before upgrading.

### 4. Confidence Levels

Confidence indicates reliability of vulnerability detection:

#### HIGH Confidence
- **Maven projects** only
- Exact version matches
- Multiple source confirmation
- Standard CVE/GHSA identifiers

#### MEDIUM Confidence
- **Gradle projects**
- Version range matches
- Single source confirmation
- Best-effort parsing

#### LOW Confidence
- Fuzzy matches
- Inferred vulnerabilities
- Limited source data
- Potential false positives

### 5. AI-Powered Analysis (Optional)

#### Supported Providers
- **OpenAI**: GPT-4o, GPT-4o-mini
- **Claude**: Claude 3.5 Sonnet, Claude 3 Opus
- **Gemini**: Gemini Pro, Gemini 1.5 Pro
- **Ollama**: Local models (Llama3, Mistral, etc.)
- **Azure OpenAI**: Enterprise deployments
- **Custom (OpenAI-compatible)**: Any provider that exposes an OpenAI-compatible Chat Completions API

#### AI Configuration Persistence

The UI now supports saving AI settings to the backend:

1. **Enable AI Toggle** - Turn on "Enable AI-assisted analysis"
2. **Select Provider** - Choose from dropdown (OpenAI, Claude, etc.)
3. **Enter Model** - Specify model name (e.g., "gpt-4o-mini")
4. **Enter API Key** - Your provider API key
5. **Enter Custom Endpoint (Custom provider only)** - Full Chat Completions URL for your provider
5. **Save Settings** - Click "Save Settings" to persist (encrypted if configured)
6. **Test Connection** - Click "Test Connection" to verify connectivity

**Custom Provider Notes:**
- The custom endpoint is sent as-is. For many providers this is the full URL, for example:
  - `https://openrouter.ai/api/v1/chat/completions`
- The model field must match the provider’s model identifier (e.g., `openai/gpt-oss-120b:free`).
- Some OpenAI-compatible providers may return the assistant text in a non-standard field (e.g., `message.reasoning`). BuildAegis handles this best-effort.

**Quick setup (Custom provider):** choose **Custom**, then pick a service under **Quick setup** (OpenRouter, Groq, Together AI, Mistral, DeepSeek, or LM Studio for a local server). It fills in the endpoint and a suggested model; then paste your API key. Model names change over time, so edit the model if the suggestion is out of date. For local servers that need no key, enter any text as the key.

**Test Connection Behavior:**
- "Test Connection" validates that the provider accepts the credentials + model + endpoint and returns a valid response envelope.
- It does not require a specific response string (some models/providers may return empty `message.content` or reasoning-only output).

**Security Notes:**
- API keys are always encrypted at rest: with `buildaegis.encryption.secret` when it is set, otherwise with a key generated once and kept in `./data/buildaegis.key` (see [Security and Configuration](security-and-configuration.md))
- Settings persist across browser sessions
- Each provider+model combination is stored separately
- The API never returns the stored key: the UI shows it masked (only the last four characters). Leave the masked value as it is to keep the stored key, or type a new key to replace it

#### How AI Is Used (Requests, Retries, Cost)

- **One request per dependency** that needs an explanation: the explanation, recommendations and upgrade behavior notes come from a single AI call.
- **No AI request for clean dependencies**: dependencies without known vulnerabilities and with low risk get a template explanation, which saves requests and cost. This is cached like any other result.
- **Rate limits are retried**: HTTP 429, overload (5xx) and timeouts are retried up to three times with increasing waits (a `Retry-After` hint is honored). If the provider still refuses, the explanation starts with a notice that AI was unavailable and the template explanation is shown.
- **Stand-in explanations are not cached** as AI results, so a later analysis asks the AI again.
- The risk level and score are never changed by the AI.

#### Ask AI About a Finding

Open a finding's details and use **Ask AI about this finding** (shown when AI is enabled) to ask a question such as "Is this exploitable if I only use the library for JSON parsing?".

- The AI sees only that finding's details (dependency, severity, advisory ids, fix version, explanation and recommendations), **not your source code**. If the answer depends on how you use the library, it should say what to check.
- Questions are limited to 500 characters. Answers can be wrong; verify before acting on them.

#### AI Features

**Risk Explanations**
- Plain English descriptions of vulnerabilities

### 6. Zero-Installation Gradle Support

The application includes a **bundled Gradle wrapper** to ensure dependency analysis works without requiring Gradle installation.

#### Gradle Resolution Priority

The app tries Gradle installations in this order:

1. **Project Wrapper** (Preferred)
   - Uses `gradlew` or `gradlew.bat` if present in the project
   - Respects project's specific Gradle version

2. **Bundled Wrapper** (Fallback)
   - Extracts Gradle 8.5 wrapper from the application JAR
   - Extracted to `.buildaegis-gradle-wrapper/` in project directory
   - Works on Windows and Unix systems

3. **System Gradle** (Last Resort)
   - Uses `gradle` command if available in PATH
   - Falls back if no wrapper is available

4. **Error Guidance**
   - Clear error message with installation instructions
   - Links to Gradle installation guide

#### Benefits

- ✅ **Zero Installation** - Analyze any Gradle project immediately
- ✅ **Self-Contained** - No external dependencies required
- ✅ **Cross-Platform** - Windows, macOS, Linux support
- ✅ **Version-Aware** - Uses project's Gradle when available
- ✅ **Graceful Degradation** - Always tries the next best option

#### Usage

No configuration required. The app automatically selects the best available Gradle installation when analyzing Gradle projects.

### 7. Filtering and Search

#### Search by Dependency Name
- Real-time filtering as you type
- Partial name matching (e.g., "spring" matches "spring-core")
- Case-insensitive
- Works across all columns

#### Severity Filter
- All, Critical, High, Medium, Low
- Filter by risk level
- Updates the findings list and the count tiles (Total, Vulnerabilities found, Critical, High, Medium, Low)
- Does **not** change the overall risk score or status chip; a "Filtered view: N" line shows the score of the visible findings while a filter is active

#### Directness Filter
- **All**: Show both direct and transitive
- **Direct**: Only dependencies declared in build file
- **Transitive**: Only dependencies pulled in by others

#### Confidence Filter
- **All**: All confidence levels
- **High**: Maven projects (reliable)
- **Medium**: Gradle projects (best-effort)

### 7. Findings Table

#### Columns

| Column | Description |
|--------|-------------|
| **Dependency** | groupId:artifactId:version |
| **CVEs** | Number of known vulnerabilities |
| **Severity** | Critical/High/Medium/Low based on CVE count |
| **ID** | Primary CVE or vulnerability identifier |
| **Confidence** | HIGH/MEDIUM based on build tool |
| **Directness** | Direct (declared) or Transitive (pulled in) |
| **Source** | Data source (OSV, Maven Central, Cache) |

#### Row Actions
- **Click "View"**: Opens detailed modal with full information

### 8. Detail Modal

Clicking "View" on any finding opens a modal with:

**Header Chips**
- Severity badge (color-coded)
- Vulnerability ID
- Risk score for this specific vulnerability
- Confidence level
- Directness (Direct/Transitive)
- Data source

**Sections**
- **Description**: Detailed vulnerability explanation
- **Affected versions**: Which versions are vulnerable
- **Dependency path**: How this dependency was included
- **Risk explanation**: AI-generated analysis (if enabled)

### 9. Analysis Panel

#### Severity Distribution
Visual bars showing:
- Percentage of each severity level
- Raw counts per category
- Relative distribution

Updates based on current filter selection.

#### Top Recommendations
Aggregated remediation advice from all findings:
- Most critical fixes first
- Specific version upgrades
- Alternative dependencies
- Grouped by priority

### 10. Caching System

#### How Caching Works
- Results stored in local H2 database
- Cache key: `groupId + artifactId + version + AI provider + AI model`
- Same dependency + different provider = separate cache entries

#### Load Cached Results
- The cache is keyed by dependency, so it is shared between projects
- Each analysis also records which dependencies belong to which project (and whether each is direct or transitive in that project)
- **Load Cached with a project path in the box** shows only that project's cached results (folder or its build file, e.g. `C:/proj` and `C:/proj/pom.xml` are the same project)
- **Load Cached with an empty path** shows every cached result
- A project shows cached results only after it has been analyzed once with this version; until then you see "No cached results found for this project"
- Direct/transitive labels in cached results come from the project's last analysis

#### Cache Benefits
- Instant results for previously analyzed dependencies
- No redundant AI API calls
- Offline capability for cached results

#### Cache Management
- Cache expires based on configured TTL
- Force refresh option to bypass cache
- Clear cache via database console if needed

### 11. Export Functionality

#### CSV Export
Click "Export CSV" to download findings as CSV with columns:
- Dependency (groupId:artifactId:version)
- CVE count
- Severity
- Vulnerability ID
- Risk score
- Confidence
- Directness
- Description
- Recommendations

#### JSON Export
Click "Export JSON" to download a complete analysis report:
- Full dependency details
- All vulnerability data
- Risk scores and explanations
- AI analysis results
- Metadata and timestamps

#### PDF Export
Click "Export PDF" to generate a formatted PDF report:
- Professional layout for sharing
- Executive summary
- Detailed findings
- Charts and visualizations
- Suitable for compliance documentation

### 12. Vulnerability Suppression Management

Manage suppressed vulnerabilities directly from the UI:

**Access Suppression Manager:**
1. Click "🚫 Suppressions" button in the filters area
2. View active suppressions and audit log

**Features:**
- **Active Suppressions**: List of currently suppressed findings
- **Audit Log**: History of suppress/unsuppress actions
- **Unsuppress**: Re-enable previously suppressed vulnerabilities

**In the Detail Modal:**
- Click to suppress a specific finding with reason
- Provide justification for compliance

### 13. Single Dependency Analysis

Analyze a specific dependency without scanning a full project:

1. **Enter Coordinates:**
   - Group ID (e.g., "org.springframework")
   - Artifact ID (e.g., "spring-core")
   - Version (e.g., "5.3.21")

2. **Click "Analyze Dependency"**

3. **Results:**
   - Vulnerabilities specific to that version
   - CVE details
   - Risk assessment
   - AI explanations (if enabled)

Useful for:
- Checking dependencies before adding to project
- Evaluating specific version upgrades
- Quick security checks

### 14. Load Cached Results

Quickly reload previous analysis:
1. Enter project path
2. Click "Load Cached" button
3. Results load instantly from database

Useful for:
- Reviewing previous scans
- Comparing results over time
- Working offline

## Advanced Features

### Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl/Cmd + Enter` | Start analysis (when path entered) |
| `Escape` | Close modal / clear search |
| `Ctrl/Cmd + F` | Focus search box |
| `[` | Collapse / expand the input panel (when not typing in a field) |

### Error Handling

The UI provides clear error messages for:
- Invalid project paths
- Missing build files
- Network connectivity issues
- AI API failures
- Parsing errors

### Progress Indicators

- Loading overlay during analysis
- Status chip updates (Idle → Analyzing → Complete)
- Character count for project path input

## Configuration Options

### AI Settings
Configure in the UI or via `application.properties`:

```properties
# AI Provider Configuration
buildaegis.ai.provider=openai
buildaegis.ai.model=gpt-4o-mini
buildaegis.ai.api-key=your-api-key

# Encryption (recommended)
buildaegis.encryption.secret=your-secret-key
```

### Database Configuration

Default H2 configuration:
```properties
spring.datasource.url=jdbc:h2:file:./data/buildaegis
spring.datasource.username=sa
spring.datasource.password=
spring.h2.console.enabled=true
```

Access console at: `http://localhost:8080/buildaegis/h2-console`

### Cache Configuration

```properties
# Cache TTL in seconds
riskscanner.cache.ttl=3600s

# Maximum cache entries
riskscanner.cache.max-size=10000
```

## Feature Comparison: Maven vs Gradle

| Feature | Maven | Gradle |
|---------|-------|--------|
| Confidence | HIGH | MEDIUM |
| Transitive deps | Full | Partial |
| Scope info | Yes | Limited |
| False positives | Low | Possible |
| Build file detection | pom.xml | build.gradle, build.gradle.kts |

## Tips for Best Results

### For Maven Projects
- Ensure `pom.xml` is valid and parseable
- Run `mvn dependency:tree` first to verify project health
- Check for unresolved dependencies

### For Gradle Projects
- Be aware of potential false positives
- Review findings manually for accuracy
- Consider using Maven for critical security audits

### For AI Analysis
- Use GPT-4o-mini for cost-effective analysis
- Claude 3.5 Sonnet for detailed explanations
- Ollama for offline/local processing

### General Tips
- Start with small projects to learn the tool
- Use filters to focus on critical issues first
- Export results for team sharing
- Check cached results before re-analyzing

---

*See [UI Guide](ui-guide.md) for interface details or [Getting Started](getting-started.md) for setup instructions.*
