# Backend Architecture

## Overview

The BuildAegis backend is a Spring Boot application that provides REST APIs for vulnerability analysis. This document explains the backend architecture, components, and data flow.

## Technology Stack

| Layer | Technology |
|-------|-----------|
| **Framework** | Spring Boot 3.x |
| **Language** | Java 21 |
| **Build Tool** | Maven |
| **Database** | H2 (embedded, file-based) |
| **ORM** | Spring Data JPA |
| **HTTP Client** | JDK HttpClient |
| **JSON** | Jackson |
| **Encryption** | AES-GCM (custom implementation) |

## Project Structure

```
src/main/java/com/riskscanner/dependencyriskanalyzer/
├── config/                    # Configuration classes
│   ├── AiClientConfig.java   # AI client configuration
│   └── EncryptionConfig.java # Encryption settings
├── controller/                # REST API controllers
│   ├── AiSettingsController.java      # AI configuration APIs
│   ├── DashboardController.java       # Cached results retrieval
│   ├── ProjectAnalysisController.java # Main analysis APIs
│   └── ExportController.java          # Export functionality
├── dto/                       # Data Transfer Objects
│   ├── DependencyRiskDto.java         # Main risk result DTO
│   ├── DependencyEnrichmentDto.java   # Enrichment data
│   ├── ProjectAnalysisRequest.java    # Analysis request
│   └── ProjectAnalysisResponse.java   # Analysis response
├── model/                     # Database entities
│   ├── AiSettingsEntity.java          # AI configuration storage
│   └── DependencyRiskCacheEntity.java # Cached results
├── repository/                # Spring Data repositories
│   ├── AiSettingsRepository.java
│   └── DependencyRiskCacheRepository.java
├── service/                   # Business logic
│   ├── AIAnalysisService.java         # AI provider integration
│   ├── AiSettingsService.java         # AI settings management
│   ├── CryptoService.java             # Encryption/decryption
│   ├── DependencyScannerService.java  # Build file parsing
│   ├── MetadataEnrichmentService.java # OSV/Maven enrichment
│   ├── PdfExportService.java          # PDF generation
│   └── ProjectAnalysisService.java    # Main orchestration
└── RiskScannerApplication.java # Spring Boot entry point
```

## Core Services Explained

### 1. ProjectAnalysisService

**Purpose**: Orchestrates the entire analysis pipeline.

**Location**: `service/ProjectAnalysisService.java`

**Workflow**:
```
1. Receive ProjectAnalysisRequest
2. Determine provider/model from AiSettingsService
3. Scan dependencies via DependencyScannerService
4. For each dependency:
   a. Check cache (groupId+artifactId+version+provider+model)
   b. If cache hit: return cached result
   c. If cache miss:
      - Enrich via MetadataEnrichmentService
      - Analyze via AIAnalysisService (if enabled)
      - Persist to cache
      - Return result
5. Aggregate all results into ProjectAnalysisResponse
```

**Key Methods**:
```java
public ProjectAnalysisResponse analyze(ProjectAnalysisRequest request)
public ProjectAnalysisResponse analyze(String projectPath, boolean forceRefresh)
```

**Caching Logic**:
- Cache key: `groupId + ":" + artifactId + ":" + version + ":" + provider + ":" + model`
- Cache is bypassed if `forceRefresh=true`
- Cache entries scoped by AI provider/model

### 2. DependencyScannerService

**Purpose**: Extracts dependencies from Maven and Gradle projects.

**Location**: `service/DependencyScannerService.java`

**Maven Resolution**:
```java
public List<DependencyInfo> scanMavenDependencies(Path projectPath)
```
- Uses Maven Resolver (Aether) library
- Parses `pom.xml` directly
- Resolves transitive dependencies
- HIGH confidence

**Gradle Resolution**:
```java
public List<DependencyInfo> scanGradleDependencies(Path projectPath)
```
- Parses `build.gradle` and `build.gradle.kts`
- Regex-based dependency extraction
- MEDIUM confidence (best-effort)
- Does NOT execute Gradle commands (safe mode)

**DependencyInfo Structure**:
```java
public class DependencyInfo {
    private String groupId;
    private String artifactId;
    private String version;
    private String buildTool;        // "maven" or "gradle"
    private boolean direct;          // true if declared in build file
    private List<String> path;       // Dependency path from root
}
```

### 3. MetadataEnrichmentService

**Purpose**: Enriches dependency info with vulnerability data from external sources.

**Location**: `service/MetadataEnrichmentService.java`

**Data Sources**:
1. **OSV (Open Source Vulnerabilities)**
   - Primary source
   - API: `https://api.osv.dev/v1/query`
   - Returns: CVE IDs, severity, description

2. **Maven Central**
   - POM file analysis
   - SCM (GitHub) URL extraction
   - Dependency metadata

**Enrichment Flow**:
```
DependencyInfo
    ↓
Query OSV API → Vulnerability IDs + severity
    ↓
Fetch Maven Central POM → SCM URL
    ↓
Construct DependencyEnrichmentDto
```

**DependencyEnrichmentDto**:
```java
public class DependencyEnrichmentDto {
    private Integer vulnerabilityCount;
    private List<String> vulnerabilityIds;
    private String ecosystem;
    private String scmUrl;
    private String description;
    private List<String> affectedVersions;
}
```

### 4. AIAnalysisService

**Purpose**: Calls AI providers for risk analysis and explanations.

**Location**: `service/AIAnalysisService.java`

**Supported Providers**:
| Provider | API Endpoint | Authentication |
|----------|--------------|----------------|
| OpenAI | `api.openai.com/v1/chat/completions` | Bearer token |
| Claude | `api.anthropic.com/v1/messages` | x-api-key header |
| Gemini | `generativelanguage.googleapis.com` | API key param |
| Ollama | `localhost:11434/api/generate` | None (local) |
| Azure | `{endpoint}/openai/deployments/{model}/chat/completions` | Bearer token |
| Custom (OpenAI-compatible) | User-provided Chat Completions URL (e.g. `https://openrouter.ai/api/v1/chat/completions`) | Bearer token |

Notes:
- The Custom provider sends requests to the configured endpoint as-is using an OpenAI-compatible JSON payload.
- Some OpenAI-compatible providers/models may return assistant text in non-standard fields (e.g. `message.reasoning` with empty `message.content`). The implementation handles this best-effort.

**Analysis Prompt Template**:
```
Analyze the security risk for:
- Dependency: {groupId}:{artifactId}:{version}
- Vulnerabilities: {vulnerabilityCount} CVEs including {cveList}
- Severity: {severity}

Provide:
1. Risk level (CRITICAL, HIGH, MEDIUM, LOW)
2. Risk score (0-100)
3. Explanation of why this is risky
4. Specific recommendations to fix
5. Exploitation likelihood

Return as JSON with fields: riskLevel, riskScore, explanation, recommendations
```

**Response Format**:
```java
public class AiAnalysisResult {
    private String riskLevel;        // CRITICAL, HIGH, MEDIUM, LOW
    private Integer riskScore;       // 0-100
    private String explanation;      // Human-readable explanation
    private List<String> recommendations;
    private String exploitationLikelihood;
}
```

**Configuration**:
```properties
buildaegis.ai.provider=openai
buildaegis.ai.model=gpt-4o-mini
buildaegis.ai.api-key=sk-...
```

### 5. AiSettingsService

**Purpose**: Manages AI provider configuration with optional encryption.

**Location**: `service/AiSettingsService.java`

**Encryption**:
- Uses AES-GCM via CryptoService
- Key material derived from `buildaegis.encryption.secret` using SHA-256
- API keys stored in `AiSettingsEntity`

**Entity Structure**:
```java
@Entity
public class AiSettingsEntity {
    @Id
    private Long id = 1L;  // Single-row table
    
    private String provider;
    private String model;
    private String apiKeyCiphertext;  // Encrypted or plaintext
    private boolean encrypted;
    private LocalDateTime updatedAt;
}
```

### 6. CryptoService

**Purpose**: Encrypts and decrypts sensitive data (API keys).

**Location**: `service/CryptoService.java`

**Algorithm**: AES-GCM with 256-bit keys

**Key Derivation**:
```
encryptionSecret (user-provided string)
    ↓
PBKDF2 with SHA-256
    ↓
256-bit AES key
```

**Usage**:
```java
String encrypted = cryptoService.encrypt(plaintextApiKey);
String decrypted = cryptoService.decrypt(ciphertext);
```

### 7. PdfExportService

**Purpose**: Generates PDF reports from analysis results.

**Location**: `service/PdfExportService.java`

**Library**: iText or Apache PDFBox (implementation detail)

**Report Sections**:
1. Executive Summary
2. Dependency List with Risk Scores
3. Vulnerability Details
4. Recommendations
5. Methodology

## Controllers (REST API Layer)

### ProjectAnalysisController

**Base Path**: `/api/project`

**Endpoints**:

#### GET /api/project/scan
```java
@GetMapping("/scan")
public ResponseEntity<List<DependencyInfo>> scan(@RequestParam String projectPath)
```
- Quick dependency scan without enrichment
- Returns list of dependencies found

#### POST /api/project/analyze
```java
@PostMapping("/analyze")
public ResponseEntity<ProjectAnalysisResponse> analyze(
    @RequestBody ProjectAnalysisRequest request)
```
- Full analysis pipeline
- Scans, enriches, analyzes with AI (if enabled), caches
- Main endpoint used by UI

**Request**:
```json
{
  "projectPath": "C:\\Users\\Name\\Projects\\my-app",
  "forceRefresh": false
}
```

**Response**:
```json
{
  "dependencies": [
    {
      "groupId": "org.apache.logging.log4j",
      "artifactId": "log4j-core",
      "version": "2.14.1",
      "riskLevel": "CRITICAL",
      "riskScore": 95,
      "enrichment": {
        "vulnerabilityCount": 12,
        "vulnerabilityIds": ["CVE-2021-44228", ...],
        "ecosystem": "Maven"
      }
    }
  ],
  "analysisTimestamp": "2026-02-05T12:00:00",
  "buildTool": "maven"
}
```

### DashboardController

**Base Path**: `/api/dashboard`

#### GET /api/dashboard/cached-results
```java
@GetMapping("/cached-results")
public ResponseEntity<List<DependencyRiskDto>> getCachedResults()
```
- Returns all cached results for current provider/model
- Used by "Load Cached" button in UI

### AiSettingsController

**Base Path**: `/api/ai`

#### GET /api/ai/settings
```java
@GetMapping("/settings")
public ResponseEntity<AiSettingsResponse> getSettings()
```
- Returns current AI configuration (API key masked)

#### PUT /api/ai/settings
```java
@PutMapping("/settings")
public ResponseEntity<Void> saveSettings(@RequestBody AiSettingsRequest request)
```
- Saves AI provider, model, and API key
- Encrypts API key if encryption is configured

#### POST /api/ai/test-connection
```java
@PostMapping("/test-connection")
public ResponseEntity<Map<String, Object>> testConnection()
```
- Tests connectivity to configured AI provider
- Returns success/failure with error details

### ExportController

**Base Path**: `/api/export`

#### POST /api/export/json
```java
@PostMapping("/json")
public ResponseEntity<byte[]> exportJson(@RequestBody ProjectAnalysisRequest request)
```
- Returns analysis results as JSON file

#### POST /api/export/pdf
```java
@PostMapping("/pdf")
public ResponseEntity<byte[]> exportPdf(@RequestBody ProjectAnalysisRequest request)
```
- Returns analysis results as PDF report

## Data Layer

### Database Schema (H2)

#### ai_settings Table
```sql
CREATE TABLE ai_settings (
    id BIGINT PRIMARY KEY,
    provider VARCHAR(50),
    model VARCHAR(100),
    api_key_ciphertext VARCHAR(500),
    encrypted BOOLEAN,
    updated_at TIMESTAMP
);
```

#### dependency_risk_cache Table
```sql
CREATE TABLE dependency_risk_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id VARCHAR(255),
    artifact_id VARCHAR(255),
    version VARCHAR(100),
    provider VARCHAR(50),
    model VARCHAR(100),
    result_data TEXT,  -- JSON serialized DependencyRiskDto
    created_at TIMESTAMP,
    expires_at TIMESTAMP
);

CREATE INDEX idx_cache_lookup ON dependency_risk_cache (
    group_id, artifact_id, version, provider, model
);
```

### Repositories

#### AiSettingsRepository
```java
public interface AiSettingsRepository extends JpaRepository<AiSettingsEntity, Long> {
    // Single row operations
}
```

#### DependencyRiskCacheRepository
```java
public interface DependencyRiskCacheRepository 
    extends JpaRepository<DependencyRiskCacheEntity, Long> {
    
    Optional<DependencyRiskCacheEntity> findByGroupIdAndArtifactIdAndVersionAndProviderAndModel(
        String groupId, String artifactId, String version, 
        String provider, String model);
    
    List<DependencyRiskCacheEntity> findByProviderAndModel(
        String provider, String model);
    
    @Modifying
    @Query("DELETE FROM DependencyRiskCacheEntity c WHERE c.expiresAt < CURRENT_TIMESTAMP")
    void deleteExpired();
}
```

## Request Flow Example

### Full Analysis Flow

```
User clicks "Analyze Dependencies"
    ↓
Frontend sends POST /api/project/analyze
    ↓
ProjectAnalysisController.analyze(request)
    ↓
ProjectAnalysisService.analyze(projectPath, forceRefresh)
    ↓
1. Get AI settings (provider/model)
    ↓
2. Scan dependencies
    DependencyScannerService.scanProject(projectPath)
        ↓
        If Maven: parse pom.xml
        If Gradle: parse build.gradle
    ↓
3. For each dependency:
    a. Check cache
       DependencyRiskCacheRepository.findBy...
    ↓
    b. If cache miss:
       MetadataEnrichmentService.enrich(dep)
           ↓
           Query OSV API
           Parse Maven Central POM
       AIAnalysisService.analyzeDependencyRisk(dep, enrichment)
           ↓
           Build prompt
           Call AI provider API
           Parse JSON response
       Save to cache
           ↓
           DependencyRiskCacheRepository.save(entity)
    ↓
4. Aggregate results
    ↓
Return ProjectAnalysisResponse
    ↓
Frontend displays results
```

## Configuration

### application.properties

```properties
# Server
server.port=8080

# Database
spring.datasource.url=jdbc:h2:file:./data/buildaegis
spring.datasource.username=sa
spring.datasource.password=
spring.h2.console.enabled=true
spring.jpa.hibernate.ddl-auto=update

# AI (optional)
buildaegis.ai.provider=openai
buildaegis.ai.model=gpt-4o-mini
# buildaegis.ai.api-key=sk-...

# Encryption (recommended)
# buildaegis.encryption.secret=your-secret-key-min-16-chars

# Logging
logging.level.com.riskscanner.dependencyriskanalyzer=INFO
```

## Extension Points

### Adding a New AI Provider

1. Update `AIAnalysisService` with new provider logic:
```java
private AiAnalysisResult callNewProvider(String prompt, String apiKey) {
    // Implement API call
}
```

2. Add provider to `AiSettingsRequest.Provider` enum

3. Update test connection logic

### Adding New Vulnerability Sources

1. Create new service:
```java
@Service
public class NewVulnerabilityProvider {
    public List<Vulnerability> query(DependencyInfo dep) {
        // Implementation
    }
}
```

2. Inject into `MetadataEnrichmentService`

3. Call from enrichment flow

## Monitoring and Debugging

### Logging

Enable debug logging:
```properties
logging.level.com.riskscanner.dependencyriskanalyzer=DEBUG
logging.level.org.springframework.web=DEBUG
```

### Health Checks

Spring Boot Actuator endpoints:
- `/actuator/health` - Application health
- `/actuator/info` - Application info
- `/actuator/metrics` - JVM metrics

### H2 Console

Access at: `http://localhost:8080/buildaegis/h2-console`

Connection details:
- JDBC URL: `jdbc:h2:file:./data/buildaegis`
- User: `sa`
- Password: (empty)

---

*For API details, see [API Reference](../api/endpoints.md). For frontend architecture, see [Frontend Architecture](../frontend/architecture.md).*
