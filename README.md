# Risk Scanner

Risk Scanner is a comprehensive vulnerability analysis tool that provides transparent, explainable risk assessments for Java projects. It combines deterministic vulnerability detection with AI-powered explanations to help development teams understand and prioritize security risks.

## What It Does

- **Vulnerability Detection**: Scans Maven and Gradle projects for known vulnerabilities using multiple sources (OSV, NVD, GitHub Advisory, Maven Central)
- **Risk Scoring**: Calculates contextual risk scores (0-100) based on severity, dependency depth, scope, and exploit maturity
- **Confidence Assessment**: Provides confidence levels (HIGH/MEDIUM/LOW) based on source reliability and match quality
- **False Positive Analysis**: Identifies potential false positives based on dependency scope, optional status, and reachability
- **AI Explanations**: Generates human-friendly explanations of vulnerabilities, their real-world impact, and remediation options
- **Suppression Management**: Allows auditable vulnerability suppression with detailed reasoning
- **Local Processing**: All analysis happens locally - no cloud dependencies except for optional AI services

## Supported Build Tools

### Maven
- **Method**: Uses Maven Resolver (Aether) for reliable dependency resolution
- **Confidence**: HIGH - Direct access to Maven's dependency resolution engine
- **Features**: Full dependency graph, transitive dependencies, scope information
- **Safe Mode**: Native support for controlled execution

### Gradle
- **Method**: Controlled execution of Gradle commands in isolated environments
- **Confidence**: MEDIUM - Parses command output, may miss complex configurations
- **Features**: Multiple configuration support, dependency depth tracking
- **Safe Mode**: Requires controlled execution to prevent arbitrary code execution

## Confidence Levels

Confidence levels indicate how reliable the vulnerability detection is:

### HIGH (80-100)
- Exact version matches in vulnerability databases
- Multiple independent sources confirming the vulnerability
- High-quality vulnerability data (NVD, GitHub Advisory)

### MEDIUM (50-79)
- Version range matches
- Single source confirmation
- Medium-quality vulnerability data (OSV, Maven Central)

### LOW (0-49)
- Fuzzy matches or inferred vulnerabilities
- Limited source information
- Potential false positives

## Safe Mode vs Full Mode

### Safe Mode (Default)
- **Maven**: Uses Maven Resolver (no code execution)
- **Gradle**: Parses existing Gradle files without running commands
- **Protection**: No arbitrary code execution, no network access from build tools
- **Tradeoff**: May miss vulnerabilities that require build-time resolution

### Full Mode
- **Maven**: Same as Safe Mode (already safe)
- **Gradle**: Runs Gradle commands in controlled environments
- **Capability**: Complete dependency resolution including dynamic dependencies
- **Risk**: Controlled execution environments mitigate but don't eliminate all risks

## Key Features

- **Dependency Scanning**
  - Maven: `pom.xml` (via Maven Resolver)
  - Gradle: `build.gradle`, `build.gradle.kts` (via controlled execution)
- **Multi-Source Vulnerability Detection**
  - OSV (Open Source Vulnerability Database)
  - NVD (National Vulnerability Database)
  - GitHub Advisory Database
  - Maven Central metadata
- **Risk Assessment**
  - Contextual risk scoring (0-100 scale)
  - Confidence level calculation
  - False positive analysis
  - Dependency impact assessment
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

- **Architecture**: `docs/ARCHITECTURE.md` - System design and data flow
- **Security**: `docs/SECURITY.md` - Security considerations and safe usage
- **Development**: `docs/CONTRIBUTING.md` - How to contribute and extend
- **Developer Guide**: `docs/DEV_GUIDE.md` - Local setup and development workflow

## Quick Start (Web)

1. Run the backend:

```powershell
.\mvnw spring-boot:run
```

2. Open the UI:
- `http://localhost:8080/`

3. Configure AI (optional but recommended):
- In the UI, set:
  - Provider: `openai`, `claude`, `gemini`, or `ollama`
  - Model: e.g., `gpt-4o-mini`, `claude-3-5-sonnet`, etc.
  - API key: your AI provider key

The API key is stored **encrypted** in the local database.

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

Run the built jar:

```powershell
java -jar target\dependency-risk-analyzer-0.0.1-SNAPSHOT.jar
```

## Configuration

Main configuration file:
- `src/main/resources/application.properties`

Key settings:
- **H2 database**: persisted to `./data/risk-scanner`
- **H2 console**: `http://localhost:8080/h2-console`
- **Encryption secret**: `riskscanner.encryption.secret`
  - Set this for stable encryption across restarts
  - If you change it later, previously stored encrypted API keys cannot be decrypted
- **Vulnerability cache**: `~/.riskscanner/vulnerability-cache`

## API Endpoints

### Vulnerability Analysis
- `GET /api/vulnerabilities/analyze` - Analyze single dependency with optional AI explanations
- `POST /api/vulnerabilities/analyze-batch` - Analyze multiple dependencies
- `POST /api/vulnerabilities/suppress` - Suppress a vulnerability with reasoning
- `POST /api/vulnerabilities/unsuppress` - Unsuppress a vulnerability
- `GET /api/vulnerabilities/suppressions` - Get active suppressions
- `GET /api/vulnerabilities/audit-log` - Get suppression audit trail

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
- **Controlled Execution**: Gradle commands run in isolated environments
- **Audit Trail**: All suppression actions are logged for compliance

## When False Positives May Occur

1. **Test Dependencies**: Vulnerabilities in test-scoped dependencies may not be exploitable
2. **Optional Dependencies**: Optional dependencies may not be loaded at runtime
3. **Shaded Packages**: Dependencies may be shaded/repackaged, changing vulnerability context
4. **Version Ranges**: Broad version ranges may include unaffected versions
5. **Transitive Conflicts**: Dependency mediation may resolve to different versions

## How Confidence Level Works

Confidence is calculated based on:
- **Match Quality**: Exact version (1.0) vs range match (0.8)
- **Source Reliability**: NVD/GitHub (1.0) vs OSV (0.8) vs Maven Central (0.6)
- **Cross-Source Confirmation**: Multiple sources increase confidence
- **ID Format**: Standard formats (CVE, GHSA) increase confidence

## How Scoring Works

Risk Score (0-100) = Weighted sum of:
- **Severity** (40%): CRITICAL=100, HIGH=80, MEDIUM=60, LOW=40, INFO=20
- **Dependency Depth** (20%): Direct=100, One level=80, Two levels=60, Deep=40
- **Scope** (20%): Compile=100, Runtime=80, Provided=60, Test=40, System=20
- **Exploit Maturity** (20%): PoC=100, Weaponized=80, Theoretical=60, None=40

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
- `src/main/java/.../service/dependency` – Dependency resolution logic
- `src/main/java/.../service/vulnerability` – Vulnerability detection and analysis
- `src/main/java/.../service/ai` – AI integration layer
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

See `docs/CONTRIBUTING.md` for guidelines on:
- Adding new dependency resolvers
- Adding vulnerability sources
- Extending AI providers
- Testing strategies
- Code style and conventions
