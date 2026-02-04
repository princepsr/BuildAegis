# Developer Guide

This guide provides comprehensive information for developers working on Risk Scanner, including local setup, development workflow, testing strategies, and debugging techniques.

## Prerequisites

### Required Software

- **Java 21** (JDK) - Minimum required version
- **Maven 3.8+** - Build tool (Maven Wrapper included)
- **Git** - Version control
- **IDE** - IntelliJ IDEA recommended (VS Code also works)

### Optional Tools

- **Docker** - For containerized testing
- **Postman** - For API testing
- **H2 Console** - Built-in database console
- **JavaFX SDK** - For desktop development

## Local Development Setup

### 1. Clone and Build

```bash
# Clone the repository
git clone https://github.com/your-org/risk-scanner.git
cd risk-scanner

# Build the project
./mvnw clean compile

# Run tests to verify setup
./mvnw test
```

### 2. Development Configuration

Create `application-dev.properties` in `src/main/resources/`:

```properties
# Development database
spring.datasource.url=jdbc:h2:file:./data/risk-scanner-dev;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.username=sa
spring.datasource.password=
spring.h2.console.enabled=true

# Development logging
logging.level.com.riskscanner.dependencyriskanalyzer=DEBUG
logging.level.org.springframework.web=DEBUG
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n

# Development cache settings
riskscanner.cache.ttl=300s
riskscanner.cache.max-size=1000

# AI settings (development)
riskscanner.ai.provider=openai
riskscanner.ai.model=gpt-4o-mini
# riskscanner.ai.api-key=your-dev-key-here

# Gradle execution settings
riskscanner.gradle.execution-timeout=60s
riskscanner.gradle.network-restricted=true
```

### 3. IDE Configuration

#### IntelliJ IDEA Setup

1. **Import Project**: Open as Maven project
2. **SDK Setup**: Set Project SDK to Java 21
3. **Code Style**: Import Google Java Style
4. **Annotations**: Enable annotation processing
5. **Run Configuration**: Create Spring Boot run configuration

#### VS Code Setup

1. **Extensions**: Install Java Extension Pack
2. **Settings**: Configure Java 21 as default
3. **Tasks**: Use Maven tasks for build/test
4. **Debugging**: Configure Java debugger

### 4. Database Setup

#### H2 Database (Default)

The application uses H2 database by default:

- **Location**: `./data/risk-scanner`
- **Console**: `http://localhost:8080/h2-console`
- **JDBC URL**: `jdbc:h2:file:./data/risk-scanner`
- **Username**: `sa`
- **Password**: (empty)

#### Database Schema

```sql
-- AI Settings
CREATE TABLE ai_settings (
    id BIGINT PRIMARY KEY,
    provider VARCHAR(50),
    model VARCHAR(100),
    api_key_ciphertext TEXT,
    encrypted BOOLEAN,
    updated_at TIMESTAMP
);

-- Dependency Risk Cache
CREATE TABLE dependency_risk_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id VARCHAR(255),
    artifact_id VARCHAR(255),
    version VARCHAR(100),
    provider VARCHAR(50),
    model VARCHAR(100),
    result_data TEXT,
    created_at TIMESTAMP,
    expires_at TIMESTAMP
);

-- Vulnerability Suppressions
CREATE TABLE vulnerability_suppressions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    finding_id VARCHAR(255),
    dependency_group_id VARCHAR(255),
    dependency_artifact_id VARCHAR(255),
    dependency_version VARCHAR(100),
    reason TEXT,
    justification TEXT,
    suppressed_by VARCHAR(255),
    suppressed_at TIMESTAMP,
    unsuppressed_at TIMESTAMP,
    unsuppressed_by VARCHAR(255)
);

-- Suppression Audit Log
CREATE TABLE suppression_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    finding_id VARCHAR(255),
    action VARCHAR(20),
    reason TEXT,
    justification TEXT,
    user_id VARCHAR(255),
    timestamp TIMESTAMP,
    details TEXT
);
```

## Development Workflow

### 1. Feature Development

```bash
# Create feature branch
git checkout -b feature/new-feature

# Make changes
# ... implement feature ...

# Run tests
./mvnw test

# Run integration tests
./mvnw verify

# Commit changes
git add .
git commit -m "feat: add new feature"

# Push branch
git push origin feature/new-feature
```

### 2. Running the Application

#### Web Application

```bash
# Development mode with profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Or with JVM options
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx2g -Dspring.profiles.active=dev"
```

#### Desktop Application

```bash
# Run desktop mode
./mvnw -Pdesktop javafx:run -Dspring-boot.run.profiles=dev
```

### 3. Testing Strategy

#### Unit Tests

```bash
# Run all unit tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=VulnerabilityMatchingServiceTest

# Run with coverage
./mvnw test jacoco:report
```

#### Integration Tests

```bash
# Run integration tests
./mvnw verify

# Run specific integration test
./mvnw test -Dtest=*IntegrationTest
```

#### Performance Tests

```bash
# Run performance tests
./mvnw test -Dtest=*PerformanceTest

# With JVM monitoring
./mvnw test -Dspring-boot.run.jvmArguments="-XX:+PrintGCDetails -XX:+PrintGCTimeStamps"
```

## H2 Database Usage

### 1. Accessing the Console

1. Start the application
2. Open browser to `http://localhost:8080/h2-console`
3. Enter JDBC URL: `jdbc:h2:file:./data/risk-scanner`
4. Username: `sa`
5. Password: (leave empty)
6. Click "Connect"

### 2. Common Queries

#### Check AI Settings

```sql
SELECT * FROM ai_settings;
```

#### View Cached Results

```sql
SELECT * FROM dependency_risk_cache 
WHERE created_at > '2024-01-01' 
ORDER BY created_at DESC;
```

#### View Suppressions

```sql
SELECT * FROM vulnerability_suppressions 
WHERE unsuppressed_at IS NULL;
```

#### Audit Trail

```sql
SELECT * FROM suppression_audit_log 
ORDER BY timestamp DESC 
LIMIT 100;
```

### 3. Database Management

#### Clear Cache

```sql
DELETE FROM dependency_risk_cache;
```

#### Reset Database

```bash
# Stop application
rm -rf ./data/risk-scanner.mv.db
./data/risk-scanner.trace.db
# Restart application
```

#### Backup Database

```bash
# Copy database file
cp ./data/risk-scanner.mv.db ./backup/risk-scanner-backup-$(date +%Y%m%d).mv.db
```

## Testing Strategy

### 1. Test Structure

```
src/test/java/
├── unit/                    # Unit tests
│   ├── service/            # Service layer tests
│   ├── controller/        # Controller tests
│   └── model/              # Model tests
├── integration/            # Integration tests
│   ├── api/               # API integration tests
│   ├── database/          # Database tests
│   └── external/          # External service tests
└── e2e/                   # End-to-end tests
    ├── web/               # Web UI tests
    └── desktop/           # Desktop app tests
```

### 2. Unit Testing

#### Service Layer Tests

```java
@ExtendWith(MockitoExtension.class)
class VulnerabilityMatchingServiceTest {
    
    @Mock
    private OsvVulnerabilityProvider osvProvider;
    
    @Mock
    private NvdVulnerabilityProvider nvdProvider;
    
    @InjectMocks
    private VulnerabilityMatchingService service;
    
    @Test
    void shouldAggregateVulnerabilitiesFromMultipleSources() {
        // Given
        DependencyCoordinate dependency = new DependencyCoordinate("com.example", "test", "1.0.0", "maven");
        
        Vulnerability osvVuln = createVulnerability("CVE-2024-OSV", VulnerabilitySource.OSV);
        Vulnerability nvdVuln = createVulnerability("CVE-2024-NVD", VulnerabilitySource.NVD);
        
        when(osvProvider.getVulnerabilities(dependency)).thenReturn(List.of(osvVuln));
        when(nvdProvider.getVulnerabilities(dependency)).thenReturn(List.of(nvdVuln));
        
        // When
        List<Vulnerability> result = service.getAllVulnerabilities(dependency);
        
        // Then
        assertThat(result).hasSize(2);
        assertThat(result).extracting("id").containsExactlyInAnyOrder("CVE-2024-OSV", "CVE-2024-NVD");
    }
    
    private Vulnerability createVulnerability(String id, VulnerabilitySource source) {
        return Vulnerability.builder()
            .id(id)
            .source(source)
            .title("Test Vulnerability")
            .severity(Severity.HIGH)
            .build();
    }
}
```

#### Controller Tests

```java
@WebMvcTest(VulnerabilityAnalysisController.class)
class VulnerabilityAnalysisControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private VulnerabilityMatchingService matchingService;
    
    @Test
    void shouldAnalyzeSingleDependency() throws Exception {
        // Given
        VulnerabilityFinding finding = createTestFinding();
        when(matchingService.getVulnerabilityFindings(any(), anyInt(), any()))
            .thenReturn(List.of(finding));
        
        // When & Then
        mockMvc.perform(get("/api/vulnerabilities/analyze")
                .param("groupId", "com.example")
                .param("artifactId", "test")
                .param("version", "1.0.0")
                .param("buildTool", "maven")
                .param("includeExplanations", "false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.findings").isArray())
            .andExpect(jsonPath("$.findings[0].id").value(finding.getId()));
    }
}
```

### 3. Integration Testing

#### Database Integration Tests

```java
@DataJpaTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_ON_EXIT=FALSE",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class VulnerabilitySuppressionRepositoryTest {
    
    @Autowired
    private VulnerabilitySuppressionRepository repository;
    
    @Test
    void shouldSaveAndRetrieveSuppression() {
        // Given
        VulnerabilitySuppression suppression = new VulnerabilitySuppression();
        suppression.setFindingId("CVE-2024-TEST:com.example:test");
        suppression.setReason("False positive");
        suppression.setSuppressedBy("test-user");
        
        // When
        VulnerabilitySuppression saved = repository.save(suppression);
        
        // Then
        Optional<VulnerabilitySuppression> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getReason()).isEqualTo("False positive");
    }
}
```

#### API Integration Tests

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "riskscanner.ai.provider=openai",
    "riskscanner.ai.model=gpt-4o-mini"
})
class VulnerabilityAnalysisIntegrationTest {
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @LocalServerPort
    private int port;
    
    @Test
    void shouldPerformCompleteVulnerabilityAnalysis() {
        // Given
        String url = "http://localhost:" + port + "/api/vulnerabilities/analyze";
        
        // When
        ResponseEntity<VulnerabilityAnalysisResponse> response = restTemplate.getForEntity(
            url + "?groupId=com.example&artifactId=test&version=1.0.0&buildTool=maven&includeExplanations=false",
            VulnerabilityAnalysisResponse.class);
        
        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getFindings()).isNotNull();
    }
}
```

### 4. Performance Testing

#### Load Testing Example

```java
@Test
void shouldHandleConcurrentVulnerabilityAnalysis() throws InterruptedException {
    int threadCount = 10;
    int requestsPerThread = 5;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
            try {
                for (int j = 0; j < requestsPerThread; j++) {
                    DependencyCoordinate dependency = new DependencyCoordinate(
                        "com.example", "test-" + Thread.currentThread().getId(), 
                        "1.0.0", "maven");
                    
                    List<VulnerabilityFinding> findings = service.getVulnerabilityFindings(
                        dependency, 0, AnalysisContext.defaultContext());
                    
                    assertNotNull(findings);
                }
            } finally {
                latch.countDown();
            }
        });
    }
    
    assertTrue(latch.await(60, TimeUnit.SECONDS));
    executor.shutdown();
}
```

## Debugging Techniques

### 1. Application Debugging

#### Remote Debugging

```bash
# Start with remote debugging enabled
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005"
```

#### IDE Debug Configuration

**IntelliJ IDEA:**
1. Create "Remote JVM Debug" configuration
2. Host: localhost
3. Port: 5005
4. Set breakpoints and start debugging

### 2. Logging Debug

#### Enable Debug Logging

```properties
# application-dev.properties
logging.level.com.riskscanner.dependencyriskanalyzer=DEBUG
logging.level.org.springframework.web=DEBUG
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
```

#### Structured Logging

```java
@Component
public class VulnerabilityAnalysisLogger {
    
    private static final Logger logger = LoggerFactory.getLogger(VulnerabilityAnalysisLogger.class);
    
    public void logAnalysisStart(DependencyCoordinate dependency, String correlationId) {
        logger.info("correlationId={}, action=ANALYSIS_START, dependency={}", 
            correlationId, dependency);
    }
    
    public void logAnalysisComplete(String correlationId, int vulnerabilityCount, long durationMs) {
        logger.info("correlationId={}, action=ANALYSIS_COMPLETE, vulnerabilityCount={}, durationMs={}", 
            correlationId, vulnerabilityCount, durationMs);
    }
    
    public void logAnalysisError(String correlationId, String error, Exception exception) {
        logger.error("correlationId={}, action=ANALYSIS_ERROR, error={}", 
            correlationId, error, exception);
    }
}
```

### 3. Database Debugging

#### H2 Console Debugging

```sql
-- Monitor active connections
SELECT * FROM INFORMATION_SCHEMA.SESSIONS;

-- Check table sizes
SELECT TABLE_NAME, ROW_COUNT FROM INFORMATION_SCHEMA.TABLES;

-- Monitor query performance
EXPLAIN SELECT * FROM dependency_risk_cache WHERE group_id = 'com.example';
```

#### Connection Pool Debugging

```properties
# Enable connection pool logging
logging.level.com.zaxxer.hikari=DEBUG
logging.level.com.zaxxer.hikari.HikariConfig=DEBUG
```

### 4. External Service Debugging

#### HTTP Client Debugging

```java
@Configuration
public class HttpClientConfig {
    
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .version(HttpClient.Version.HTTP_2)
            .build();
    }
    
    @Bean
    public RestTemplate restTemplate() {
        RestTemplate template = new RestTemplate();
        
        // Add request/response logging
        template.setInterceptors(List.of(
            new LoggingRequestInterceptor(),
            new LoggingResponseInterceptor()
        ));
        
        return template;
    }
}
```

#### Mock External Services

```java
@TestConfiguration
public class TestConfig {
    
    @Bean
    @Primary
    public VulnerabilityProvider mockVulnerabilityProvider() {
        return new VulnerabilityProvider() {
            @Override
            public List<Vulnerability> getVulnerabilities(DependencyCoordinate dependency) {
                return List.of(createMockVulnerability());
            }
            
            // ... other methods
        };
    }
}
```

## Performance Optimization

### 1. JVM Tuning

#### Development JVM Options

```bash
-Xmx2g -Xms1g -XX:+UseG1GC -XX:+PrintGCDetails -XX:+PrintGCTimeStamps
```

#### Production JVM Options

```bash
-Xmx4g -Xms2g -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+OptimizeStringConcat
-XX:+UseCompressedOops -XX:+UseCompressedClassPointers
```

### 2. Database Optimization

#### Connection Pool Configuration

```properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.idle-timeout=300000
spring.datasource.hikari.max-lifetime=1200000
spring.datasource.hikari.connection-timeout=20000
```

#### Query Optimization

```java
@Repository
public interface VulnerabilitySuppressionRepository extends JpaRepository<VulnerabilitySuppression, Long> {
    
    // Optimized query with index
    @Query("SELECT s FROM VulnerabilitySuppression s WHERE s.findingId = :findingId AND s.unsuppressedAt IS NULL")
    Optional<VulnerabilitySuppression> findActiveSuppression(@Param("findingId") String findingId);
    
    // Batch operations
    @Modifying
    @Query("DELETE FROM VulnerabilitySuppression s WHERE s.suppressedAt < :cutoffDate")
    int deleteOldSuppressions(@Param("cutoffDate") LocalDateTime cutoffDate);
}
```

### 3. Caching Strategy

#### Cache Configuration

```java
@Configuration
@EnableCaching
public class CacheConfig {
    
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .maximumSize(1000));
        return cacheManager;
    }
}
```

#### Cache Usage

```java
@Service
public class VulnerabilityMatchingService {
    
    @Cacheable(value = "vulnerabilities", key = "#dependency.toString()")
    public List<Vulnerability> getVulnerabilities(DependencyCoordinate dependency) {
        // Implementation
    }
    
    @CacheEvict(value = "vulnerabilities", key = "#dependency.toString()")
    public void evictCache(DependencyCoordinate dependency) {
        // Implementation
    }
}
```

## Troubleshooting

### 1. Common Issues

#### Build Issues

**Problem**: Compilation fails with missing dependencies
```bash
# Solution: Clean and rebuild
./mvnw clean compile
```

**Problem**: Tests fail with database connection errors
```bash
# Solution: Check if H2 console is running
curl http://localhost:8080/h2-console
```

#### Runtime Issues

**Problem**: Application starts but returns empty results
```bash
# Check logs for errors
tail -f logs/application.log

# Verify database connection
curl http://localhost:8080/h2-console
```

**Problem**: Gradle resolution fails
```bash
# Check Gradle installation
gradle --version

# Verify Gradle wrapper
./gradlew --version
```

### 2. Performance Issues

#### Memory Issues

```bash
# Monitor memory usage
jstat -gc <pid>

# Generate heap dump
jmap -dump:format=b,file=heap.hprof <pid>

# Analyze with VisualVM or Eclipse MAT
```

#### Slow Queries

```sql
-- Enable query logging
SET TRACE_LEVEL_SYSTEM_OUT 3;

-- Analyze slow queries
SELECT * FROM INFORMATION_SCHEMA.QUERY_STATISTICS;
```

### 3. Debug Checklist

- [ ] Check application logs for errors
- [ ] Verify database connection
- [ ] Test external service connectivity
- [ ] Check memory usage
- [ ] Verify configuration files
- [ ] Run tests to isolate issues
- [ ] Use debugger to trace execution
- [ ] Check for recent changes

This developer guide provides comprehensive information for effective development on Risk Scanner. For additional help, refer to the project documentation or create an issue on GitHub.
