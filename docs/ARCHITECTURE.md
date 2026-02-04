# Architecture Documentation

## Overview

Risk Scanner is a Spring Boot application that analyzes Java projects for security vulnerabilities. The architecture follows a layered design with clear separation of concerns between dependency resolution, vulnerability detection, risk assessment, and AI-powered explanations.

## System Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Web UI        │    │  REST API       │    │  Desktop UI     │
│   (Static)      │◄──►│  Controllers    │◄──►│  (JavaFX)       │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                │
                                ▼
                       ┌─────────────────┐
                       │   Service Layer │
                       │                 │
                       │ ┌─────────────┐ │
                       │ │ Dependency  │ │
                       │ │ Resolution  │ │
                       │ └─────────────┘ │
                       │ ┌─────────────┐ │
                       │ │ Vulnerability│ │
                       │ │ Detection   │ │
                       │ └─────────────┘ │
                       │ ┌─────────────┐ │
                       │ │ Risk Scoring│ │
                       │ │ & Analysis  │ │
                       │ └─────────────┘ │
                       │ ┌─────────────┐ │
                       │ │ AI           │ │
                       │ │ Explanations│ │
                       │ └─────────────┘ │
                       └─────────────────┘
                                │
                                ▼
                       ┌─────────────────┐
                       │  Data Layer     │
                       │                 │
                       │ ┌─────────────┐ │
                       │ │ H2 Database  │ │
                       │ │ (Local)      │ │
                       │ └─────────────┘ │
                       │ ┌─────────────┐ │
                       │ │ File Cache   │ │
                       │ │ (Vuln Data)  │ │
                       │ └─────────────┘ │
                       └─────────────────┘
```

## Core Components

### 1. Dependency Resolution Layer

#### MavenDependencyResolver
- **Technology**: Maven Resolver (Aether)
- **Approach**: Direct API access to Maven's dependency resolution engine
- **Confidence**: HIGH
- **Features**:
  - Full dependency graph construction
  - Transitive dependency resolution
  - Scope-aware analysis
  - No code execution required

#### GradleDependencyResolver
- **Technology**: Controlled command execution
- **Approach**: Execute Gradle commands in isolated environments
- **Confidence**: MEDIUM
- **Features**:
  - Multiple configuration support
  - Dependency depth tracking
  - Output parsing for dependency extraction
  - Security sandboxing

#### DependencyResolverFactory
- **Purpose**: Selects appropriate resolver based on project type
- **Strategy**: Priority-based selection (Maven Resolver > Gradle CLI)
- **Fallback**: Graceful degradation when resolvers fail

### 2. Vulnerability Detection Pipeline

#### Vulnerability Sources
1. **OSV (Open Source Vulnerability Database)**
   - Primary source for open source vulnerabilities
   - Structured version ranges
   - Ecosystem-specific data

2. **NVD (National Vulnerability Database)**
   - US government vulnerability database
   - CVSS scores and detailed analysis
   - High-quality, curated data

3. **GitHub Advisory Database**
   - GitHub-curated vulnerability database
   - Focus on open source packages
   - Integration with GitHub ecosystem

4. **Maven Central Metadata**
   - Basic vulnerability indicators
   - Package metadata analysis
   - Limited but always available

#### VulnerabilityMatchingService
- **Purpose**: Orchestrates vulnerability detection across all sources
- **Process**:
  1. Query all vulnerability sources
  2. Match dependencies against vulnerability databases
  3. Deduplicate and prioritize findings
  4. Apply confidence scoring
  5. Generate structured findings

### 3. Risk Assessment Layer

#### RiskScoreCalculator
- **Purpose**: Calculate contextual risk scores (0-100)
- **Factors**:
  - **Severity** (40%): CRITICAL=100, HIGH=80, MEDIUM=60, LOW=40, INFO=20
  - **Dependency Depth** (20%): Direct=100, One level=80, Two levels=60, Deep=40
  - **Scope** (20%): Compile=100, Runtime=80, Provided=60, Test=40, System=20
  - **Exploit Maturity** (20%): PoC=100, Weaponized=80, Theoretical=60, None=40

#### FalsePositiveAnalyzer
- **Purpose**: Identify potential false positives
- **Analysis Factors**:
  - Dependency scope (test vs runtime)
  - Optional dependency status
  - Shaded/repackaged packages
  - Reachability analysis
  - Version range specificity

#### ConfidenceLevel Calculation
- **HIGH (80-100)**: Exact version matches, multiple sources, high-quality data
- **MEDIUM (50-79)**: Version range matches, single source, medium-quality data
- **LOW (0-49)**: Fuzzy matches, limited sources, potential false positives

### 4. AI Integration Layer

#### VulnerabilityExplanationService
- **Purpose**: Generate human-friendly vulnerability explanations
- **AI Providers**: OpenAI, Claude, Gemini, Ollama, Azure OpenAI
- **Process**:
  1. Build comprehensive prompt with vulnerability context
  2. Send to AI provider for analysis
  3. Parse structured response
  4. Generate explanation with impact and remediation

#### AI Client Architecture
- **Interface**: `AiClient` with `generateCompletion(String prompt)` method
- **Factory Pattern**: `AiClientFactory` creates provider-specific clients
- **Graceful Degradation**: System works without AI configuration

### 5. Data Layer

#### H2 Database
- **Purpose**: Persistent storage for analysis results
- **Tables**:
  - `ai_settings`: Encrypted AI configuration
  - `dependency_risk_cache`: Cached analysis results
  - `vulnerability_suppressions`: Suppression records
  - `suppression_audit_log`: Audit trail

#### File Cache
- **Purpose**: Cache vulnerability data from external sources
- **Location**: `~/.riskscanner/vulnerability-cache`
- **Strategy**: Time-based expiration with offline support

## Data Flow

### 1. Project Analysis Flow

```
User Request → Controller → Service Layer → Dependency Resolution
                                                    │
                                                    ▼
                                            Vulnerability Detection
                                                    │
                                                    ▼
                                            Risk Assessment
                                                    │
                                                    ▼
                                            AI Explanations (Optional)
                                                    │
                                                    ▼
                                            Response with Findings
```

### 2. Dependency Resolution Flow

#### Maven Flow
```
pom.xml → MavenDependencyResolver → Maven Resolver API → Dependency Graph
```

#### Gradle Flow
```
build.gradle → GradleDependencyResolver → Controlled Execution → Output Parsing → Dependency Graph
```

### 3. Vulnerability Detection Flow

```
Dependency → VulnerabilityMatchingService → Parallel Source Queries
                                                    │
                              ┌─────────────────┼─────────────────┐
                              ▼                 ▼                 ▼
                            OSV               NVD           GitHub Advisory
                              │                 │                 │
                              └─────────────────┼─────────────────┘
                                                    ▼
                                            Deduplication
                                                    │
                                                    ▼
                                            Confidence Scoring
                                                    │
                                                    ▼
                                            VulnerabilityFindings
```

### 4. Risk Assessment Flow

```
VulnerabilityFinding → RiskScoreCalculator → Contextual Risk Score
                           │
                           ▼
                    FalsePositiveAnalyzer → False Positive Analysis
                           │
                           ▼
                    ConfidenceLevel → Confidence Assessment
                           │
                           ▼
                    Final VulnerabilityFinding
```

## Security Architecture

### 1. Controlled Execution
- **Gradle Commands**: Isolated execution environments
- **Network Restrictions**: Limited network access during build tool execution
- **Timeout Protection**: Command execution timeouts to prevent hanging
- **Output Sanitization**: Parse only expected output formats

### 2. Data Protection
- **Local Processing**: All analysis happens locally
- **Encrypted Storage**: API keys stored encrypted in H2 database
- **No Data Sharing**: Vulnerability data not sent to external services (except AI)
- **Audit Trail**: All suppression actions logged

### 3. AI Security
- **Optional Integration**: AI services are optional, system works without them
- **Provider Choice**: Multiple AI providers supported
- **Prompt Engineering**: Carefully crafted prompts to prevent injection
- **Response Validation**: Structured parsing of AI responses

## Performance Considerations

### 1. Caching Strategy
- **Vulnerability Data**: File-based caching with expiration
- **Analysis Results**: Database caching with provider/model keys
- **Dependency Graph**: In-memory caching during analysis

### 2. Parallel Processing
- **Vulnerability Sources**: Parallel queries to multiple sources
- **AI Explanations**: Parallel generation for multiple findings
- **Batch Analysis**: Concurrent processing of multiple dependencies

### 3. Resource Management
- **Memory Management**: Streaming processing for large dependency graphs
- **Connection Pooling**: Efficient HTTP client usage
- **Background Processing**: Async operations for long-running tasks

## Error Handling

### 1. Graceful Degradation
- **Resolver Failures**: Fallback to alternative resolvers
- **Source Failures**: Continue with available vulnerability sources
- **AI Failures**: Provide fallback explanations without AI

### 2. Error Recovery
- **Retry Logic**: Configurable retry for external service calls
- **Circuit Breaker**: Prevent cascade failures
- **Fallback Data**: Use cached data when sources are unavailable

### 3. User Feedback
- **Clear Error Messages**: Specific, actionable error descriptions
- **Progress Indicators**: Real-time progress for long operations
- **Correlation IDs**: Track requests across system components

## Extension Points

### 1. New Dependency Resolvers
- **Interface**: `DependencyResolver`
- **Registration**: Add to `DependencyResolverFactory`
- **Configuration**: Provider-specific settings

### 2. New Vulnerability Sources
- **Interface**: `VulnerabilityProvider`
- **Integration**: Add to `VulnerabilityMatchingService`
- **Normalization**: Convert to standard `Vulnerability` model

### 3. New AI Providers
- **Interface**: `AiClient`
- **Factory**: Add to `AiClientFactory`
- **Configuration**: Provider-specific API integration

## Monitoring and Observability

### 1. Structured Logging
- **Correlation IDs**: Track requests across components
- **Performance Metrics**: Operation timing and success rates
- **Error Tracking**: Detailed error information with context

### 2. Metrics Collection
- **Cache Hit Rates**: Effectiveness of caching strategies
- **Source Performance**: Response times and success rates
- **AI Usage**: Token usage and response quality

### 3. Health Checks
- **Database Connectivity**: H2 database health
- **External Services**: Vulnerability source availability
- **AI Services**: AI provider connectivity

## Deployment Architecture

### 1. Local Deployment
- **Default Mode**: Single-node local deployment
- **Data Storage**: Local file system and H2 database
- **No External Dependencies**: Works offline except for AI services

### 2. Desktop Application
- **JavaFX Wrapper**: Standalone desktop application
- **Embedded Server**: Spring Boot server embedded in application
- **Local UI**: WebView-based user interface

### 3. Web Application
- **Standalone Server**: Spring Boot web server
- **REST API**: Full API access for integration
- **Web UI**: Browser-based interface

This architecture provides a robust, secure, and extensible foundation for vulnerability analysis while maintaining transparency and explainability of all results.
