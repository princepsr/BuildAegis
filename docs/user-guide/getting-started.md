# Getting Started with BuildAegis

## Prerequisites

Before you begin, ensure you have:

- **Java 21** (JDK) installed
- **Windows, macOS, or Linux** operating system
- **Project path** - Local directory containing a Maven (`pom.xml`) or Gradle (`build.gradle`/`build.gradle.kts`) project
- (Optional) **AI API key** for enhanced analysis (OpenAI, Claude, Gemini, Ollama, or Azure)

## Quick Start

### 1. Start the Application

```powershell
# Using Maven wrapper (Windows)
.\mvnw spring-boot:run

# Using Maven wrapper (macOS/Linux)
./mvnw spring-boot:run
```

The application will start on `http://localhost:8080/buildaegis`

### 2. Open the Web Interface

Navigate to `http://localhost:8080/buildaegis` in your browser.

### 3. Run Your First Analysis

1. **Select Build Tool**: Choose Maven or Gradle from the radio buttons
2. **Enter Project Path**: Type or paste the full path to your project directory
   - Example: `C:\Users\YourName\Projects\my-java-app`
   - The tool will automatically find `pom.xml` or `build.gradle`
3. **(Optional) Enable AI**: Check "Enable AI-assisted analysis" and enter your API key
4. **Click "Analyze Dependencies"**: The scan will begin

### 4. View Results

Once complete, you'll see:
- **KPI Cards**: Total dependencies, vulnerabilities by severity, overall risk score
- **Findings Table**: Detailed vulnerability list with filters
- **Analysis Section**: Severity distribution and recommendations

## Understanding the Results

### KPI Cards

| Card | Description |
|------|-------------|
| **Total dependencies** | All dependencies found (direct + transitive) |
| **Vulnerabilities found** | Dependencies with at least one known vulnerability |
| **Critical** | A vulnerability is exploited in the wild (CISA KEV) or the highest advisory severity is Critical |
| **High** | Highest advisory severity is High |
| **Medium** | Highest advisory severity is Medium |
| **Low** | Highest advisory severity is Low, or no known vulnerabilities |
| **Overall risk score** | 0-100 score for the whole project (not changed by filters) |

### Risk Score Interpretation

The overall risk score (0-100) is the **highest per-dependency risk score** in the project, plus 1 point for each additional dependency with known vulnerabilities (up to +5):

- **70-100**: High - at least one dependency is high risk (for example several known vulnerabilities)
- **40-69**: Medium - vulnerable dependencies, none with a high score
- **20-39**: Low - no known vulnerabilities, only maintenance or adoption concerns
- **0-19**: Minimal or no results

**Important**: The overall score and the status chip describe the **whole project** and do **not** change when you filter or search. While a filter or search is active, a **"Filtered view: N"** line under the score shows the score of just the visible findings. The count tiles do follow the filters.

### Severity Classification

| Severity | How it is determined | Risk Level |
|----------|----------------------|------------|
| Critical | Exploited in the wild (CISA KEV), or the highest advisory severity (CVSS 9.0+) is Critical | Immediate attention required |
| High | Highest advisory severity is High (CVSS 7.0-8.9) | Address soon |
| Medium | Highest advisory severity is Medium (CVSS 4.0-6.9) | Plan to fix |
| Low | Highest advisory severity is Low, or no known vulnerabilities | Monitor |

If the advisories carry no severity information, 10 or more vulnerabilities count as Critical and the backend risk level is used otherwise.

## Using Filters

The findings table supports multiple filters:

### Search Box
- Filter by dependency name (e.g., "spring", "log4j")
- Real-time filtering as you type
- Case-insensitive matching

### Severity Filter
- All, Critical, High, Medium, Low
- Shows only matching severities

### Directness Filter
- **All**: Direct and transitive dependencies
- **Direct**: Only dependencies declared in your build file
- **Transitive**: Only dependencies pulled in by others

### Confidence Filter
- **All**: All confidence levels
- **High**: Maven projects (reliable resolution)
- **Medium**: Gradle projects (best-effort)

## Loading Cached Results

If you've analyzed a project before:

1. Enter the same project path
2. Click **"Load Cached"** button
3. Results will load instantly from local database

**Note**: Cached results are scoped by AI provider/model. Different providers = separate caches.

## Exporting Results

Click **"Export CSV"** button to download findings as a CSV file with columns:
- Dependency name and version
- CVE count and IDs
- Severity level
- Risk score per vulnerability
- Confidence and directness
- Description and recommendations

## AI Configuration (Optional)

### Enabling AI Analysis

1. Check **"Enable AI-assisted analysis"**
2. Select your **Provider**: OpenAI, Claude, Gemini, Ollama, or Azure
3. Enter your **API Key**
4. (Optional) Select specific **Model**

### Supported Providers

| Provider | Recommended Models |
|----------|-------------------|
| **OpenAI** | gpt-4o-mini, gpt-4o |
| **Claude** | claude-3-5-sonnet, claude-3-opus |
| **Gemini** | gemini-pro, gemini-1.5-pro |
| **Ollama** | llama3, mistral (local) |
| **Azure** | Your deployed model name |

### AI Features

When enabled, AI provides:
- **Risk explanations** in plain English
- **Impact assessment** - How the vulnerability affects your project
- **Remediation advice** - Specific steps to fix
- **Contextual analysis** - Why this matters for your specific dependency

### Security Note

- Only dependency names and vulnerability data are sent to AI providers
- **No source code** or proprietary information is shared
- API keys are **encrypted at rest** (when encryption secret is configured)

## Build Tool Specifics

### Maven Projects

- **Confidence**: HIGH - Uses Maven Resolver for accurate dependency trees
- **Features**: Full transitive resolution, scope information, reliable results
- **Multi-module support**: Automatically detects and analyzes all modules
- **BOM support**: Handles Bill of Materials for version management
- **Parent POM inheritance**: Resolves dependency management from parent chain
- **Requirements**: `pom.xml` in project directory

### Gradle Projects

- **Confidence**: MEDIUM - Parses build file output (best-effort)
- **Multi-project support**: Automatically detects and analyzes all subprojects
- **Intelligent filtering**: Skips Node.js, documentation, test, and build/utility projects
- **Subproject analysis**: Can analyze specific subprojects with parent dependency context
- **Performance**: Optimized for large multi-project builds
- **Requirements**: `build.gradle` or `build.gradle.kts` in project directory

### Multi-Module Projects

**For Maven:**
- Point to **parent project directory**. The tool will:
- Detect `<modules>` in parent POM and discover all modules
- Analyze parent project and each module separately
- Aggregate findings across all modules
- Handle BOM imports and dependency management automatically

**For Gradle:**
- **Root analysis**: Point to parent directory to analyze all relevant subprojects
- **Subproject analysis**: Point to specific subproject directory for focused analysis
- **Performance optimization**: Use `-Dgradle.skip.subprojects=true` to skip subproject analysis
- **Automatic filtering**: Skips Node.js, docs, tests, and build utilities automatically

**Maven Module Types Analyzed:**
- ✅ All Java modules (applications, libraries, services)
- ✅ Parent POM dependency management
- ✅ BOM (Bill of Materials) imports
- ✅ Transitive dependencies with proper scopes

**Gradle Project Types Analyzed:**
- ✅ Java applications and libraries
- ✅ Web applications (Spring Boot, etc.)
- ✅ Feature modules and shared libraries
- ❌ Node.js/React projects (skipped automatically)
- ❌ Documentation projects (skipped automatically)
- ❌ Test-only projects (skipped automatically)
- ❌ Build/utility projects (skipped automatically)

## Next Steps

- **[Features Guide](features.md)** - Learn about all capabilities
- **[UI Guide](ui-guide.md)** - Detailed interface walkthrough
- **[Troubleshooting](troubleshooting.md)** - Common issues and solutions

---

*For developer documentation, see [Developer Guide](../developer/) or [Architecture Overview](../architecture/).*
