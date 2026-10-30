# BuildAegis Documentation

Welcome to the BuildAegis documentation. This organized documentation structure provides comprehensive information for users, developers, and system administrators.

## Documentation Structure

```
docs/
├── README.md                    # This file - Documentation overview
├── user-guide/                  # End-user documentation
│   ├── getting-started.md       # Quick start guide
│   ├── features.md              # Feature overview and usage
│   ├── ui-guide.md              # User interface explanation
│   ├── cli-and-ci.md            # Command-line mode and CI pipelines
│   ├── security-and-configuration.md  # Security defaults and settings
│   └── troubleshooting.md       # Common issues and solutions
├── developer/                   # Developer documentation
│   ├── backend/
│   │   ├── architecture.md      # Backend system architecture
│   │   ├── components.md      # Backend components explained
│   │   └── api-reference.md   # API endpoints and usage
│   └── frontend/
│       ├── architecture.md    # Frontend architecture (accurate)
│       ├── modules.md         # JavaScript modules explained
│       └── components.md      # UI components explained
├── architecture/                # System architecture
│   ├── overview.md            # High-level system design
│   ├── data-flow.md           # Data flow diagrams
│   └── risk-scoring.md        # Risk scoring algorithm
└── api/                         # API documentation
    └── endpoints.md           # Complete API reference
```

## Quick Navigation

### For Users
- **[Getting Started](user-guide/getting-started.md)** - First time setup and basic usage
- **[Features Guide](user-guide/features.md)** - Understanding all features
- **[UI Guide](user-guide/ui-guide.md)** - Interface walkthrough
- **[Command Line and CI](user-guide/cli-and-ci.md)** - Run scans in a pipeline and fail the build on findings
- **[Security and Configuration](user-guide/security-and-configuration.md)** - Defaults, key storage and settings

### For Developers
- **[Backend Architecture](developer/backend/architecture.md)** - Understanding the backend
- **[Frontend Architecture](developer/frontend/architecture.md)** - Understanding the frontend (accurate)
- **[API Reference](api/endpoints.md)** - Complete API documentation
- **[Risk Scoring Algorithm](architecture/risk-scoring.md)** - How risk scores are calculated

### Project Policies
- **[Contributing](CONTRIBUTING.md)** - How to contribute changes
- **[Code of Conduct](CODE_OF_CONDUCT.md)** - Community standards
- **[Security Policy](SECURITY.md)** - Responsible disclosure

## Key Information

### Current Risk Scoring Algorithm
The overall risk score is built from the **per-dependency risk scores** calculated by the backend:

- **Overall score** = highest dependency score in the project + 1 point per additional vulnerable dependency (max +5), capped at 100
- **Per-dependency score** (0-100): base 20, plus points by the real severity of each vulnerability (Critical +55, High +40, Medium +20, Low +8; max +75), +25 and a floor of 90 for vulnerabilities exploited in the wild (CISA KEV), and small maintenance/adoption factors; 70+ = High, 40-69 = Medium, 20-39 = Low
- **Whole project, not the current view**: filters and search do **not** change the overall score or status chip. While filtering, a "Filtered view: N" line shows the score of the visible findings
- **Scale**: 0-100, where higher = more risk

See [Risk Scoring](architecture/risk-scoring.md).

### Frontend Structure (Accurate)
The frontend is **NOT** component-based as previously documented. It's a simple, flat structure:

```
src/main/resources/static/
├── index.html          # Single-page application
├── app.js              # All JavaScript logic (~30KB)
├── styles.css          # All styles (~23KB)
└── css/                # Additional CSS files
    └── ...
```

**Key modules in app.js:**
- **DOM Utilities** - Element selection and manipulation
- **State Management** - Application state with filters
- **UI Module** - All rendering and UI updates
- **Scanner Module** - API calls and analysis orchestration

### Supported Features
- Maven and Gradle project analysis
- OSV vulnerability database integration with real severity (CVSS / GitHub), CISA KEV and EPSS exploit data
- Command-line mode with `--fail-on` for CI pipelines
- AI-powered risk explanations (OpenAI, Claude, Gemini, Ollama, Azure)
- Local caching of analysis results
- Export to CSV
- Filterable findings table
- Severity-based risk categorization

## Important Notes

### Outdated Documentation
Previous documentation (especially `UI_DEVELOPMENT.md`) described a modular component architecture that **does not exist**. The actual implementation uses a simpler, monolithic approach that works well for this application scale.

### Security
- All analysis happens locally
- API keys are always encrypted at rest (a local key file is generated when no secret is configured) and never returned by the API
- The server listens on `127.0.0.1` only by default; the H2 console is off by default; errors never include stack traces
- No code execution in safe mode (Maven)
- Controlled execution for Gradle

## Contributing to Documentation

When updating documentation:
1. Verify against actual code
2. Update relevant sections in the organized structure
3. Keep README.md in sync
4. Remove outdated files after migrating content

---

*Last updated: February 2026*
