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

#### Current Algorithm (Critical-Dominance Model)

The risk score (0-100) is calculated using a weighted approach with critical dominance:

**Base Weights:**
- Critical: 40 points each
- High: 30 points each
- Medium: 15 points each
- Low: 5 points each

**Critical Boosts (non-linear):**
- 1 Critical: Minimum 40 (even if weighted sum is lower)
- 2 Critical: Minimum 60
- 3+ Critical: Minimum 90

**Normalization:**
- Maximum cap: 100
- Applied to current filtered view (not total project)

#### Risk Score Interpretation

| Score Range | Risk Level | Action Required |
|-------------|------------|-----------------|
| 0 | None | No action needed |
| 1-30 | Low | Monitor |
| 31-40 | Medium | Plan remediation |
| 41-60 | High | Address soon |
| 61-90 | Critical | Immediate attention |
| 91-100 | Severe | Urgent action required |

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

**Test Connection Behavior:**
- "Test Connection" validates that the provider accepts the credentials + model + endpoint and returns a valid response envelope.
- It does not require a specific response string (some models/providers may return empty `message.content` or reasoning-only output).

**Security Notes:**
- API keys are encrypted at rest when `buildaegis.encryption.secret` is configured
- Settings persist across browser sessions
- Each provider+model combination is stored separately
- Keys are never displayed in the UI after saving

#### AI Features

**Risk Explanations**
- Plain English descriptions of vulnerabilities
- Why the vulnerability matters for your specific dependency
- Real-world impact scenarios

**Impact Assessment**
- How the vulnerability affects your project
- Likelihood of exploitation
- Potential damage evaluation

**Remediation Advice**
- Specific steps to fix the issue
- Version upgrade recommendations
- Alternative dependency suggestions
- Breaking change warnings

**Security Note**
- Only dependency names and CVE data sent to AI
- No source code or proprietary information shared
- API keys encrypted at rest (with configured secret)

### 6. Filtering and Search

#### Search by Dependency Name
- Real-time filtering as you type
- Partial name matching (e.g., "spring" matches "spring-core")
- Case-insensitive
- Works across all columns

#### Severity Filter
- All, Critical, High, Medium, Low
- Filter by risk level
- Updates KPI cards and risk score for current view

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
