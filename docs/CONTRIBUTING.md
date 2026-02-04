# Contributing to Risk Scanner

Thank you for your interest in contributing to Risk Scanner! This document provides comprehensive guidelines for contributing to the project, including adding new resolvers, vulnerability sources, and extending AI providers.

## Getting Started

### Prerequisites

- **Java 21** (JDK)
- **Maven 3.8+** (or use the provided Maven Wrapper)
- **Git** for version control
- **IDE** (IntelliJ IDEA recommended)

### Development Setup

1. **Fork and Clone**
```bash
git clone https://github.com/your-username/risk-scanner.git
cd risk-scanner
```

2. **Build the Project**
```bash
./mvnw clean compile
```

3. **Run Tests**
```bash
./mvnw test
```

4. **Start Development Server**
```bash
./mvnw spring-boot:run
```

5. **Access the Application**
- Web UI: `http://localhost:8080`
- H2 Console: `http://localhost:8080/h2-console`

## Project Structure

```
src/
├── main/java/com/riskscanner/dependencyriskanalyzer/
│   ├── controller/           # REST API controllers
│   ├── service/              # Business logic
│   │   ├── dependency/       # Dependency resolution
│   │   ├── vulnerability/    # Vulnerability detection
│   │   └── ai/              # AI integration
│   ├── model/               # Domain models
│   │   └── vulnerability/    # Vulnerability-specific models
│   ├── dto/                 # Data transfer objects
│   ├── repository/          # Data access layer
│   └── config/              # Configuration classes
├── test/java/               # Unit and integration tests
├── desktop/java/           # JavaFX desktop application
└── resources/
    ├── static/              # Web UI
    └── application.properties # Configuration
docs/                       # Documentation
```

## Adding New Dependency Resolvers

### Overview

Dependency resolvers are responsible for extracting dependency information from build files. Each resolver implements the `DependencyResolver` interface.

### Step 1: Implement the Interface

Create a new class implementing `DependencyResolver`:

```java
package com.riskscanner.dependencyriskanalyzer.service.dependency;

import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.model.DependencyNode;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Service
public class NewBuildToolResolver implements DependencyResolver {

    @Override
    public boolean supports(Path projectPath) {
        // Check if this resolver can handle the project
        return projectPath.resolve("buildfile.extension").toFile().exists();
    }

    @Override
    public ResolutionConfidence getConfidence() {
        // Return the confidence level for this resolver
        return ResolutionConfidence.HIGH; // or MEDIUM, LOW
    }

    @Override
    public int getPriority() {
        // Return priority (higher = more preferred)
        return 100;
    }

    @Override
    public List<DependencyNode> resolveDependencies(Path projectPath) {
        // Implement dependency resolution logic
        return List.of();
    }

    @Override
    public Optional<DependencyCoordinate> detectSingleDependency(Path buildFile) {
        // Implement single dependency detection
        return Optional.empty();
    }
}
```

### Step 2: Security Considerations

For resolvers that execute external commands:

```java
public class SecureResolver implements DependencyResolver {
    
    private ProcessBuilder createSecureProcess(String... command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        
        // Set secure working directory
        pb.directory(secureWorkingDirectory);
        
        // Restrict environment variables
        pb.environment().clear();
        pb.environment().put("PATH", System.getenv("PATH"));
        
        // Redirect error stream
        pb.redirectErrorStream(true);
        
        // Set timeout
        // Implement timeout logic in execution method
        
        return pb;
    }
    
    private String executeSecurely(ProcessBuilder pb) throws IOException, InterruptedException {
        Process process = pb.start();
        
        try {
            // Wait with timeout
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Process timeout");
            }
            
            // Read and validate output
            String output = new String(process.getInputStream().readAllBytes());
            return sanitizeOutput(output);
            
        } finally {
            process.destroyForcibly();
        }
    }
    
    private String sanitizeOutput(String output) {
        // Implement output sanitization
        return output;
    }
}
```

### Step 3: Register the Resolver

Add the resolver to `DependencyResolverFactory`:

```java
@Component
public class DependencyResolverFactory {
    
    @Autowired
    private List<DependencyResolver> resolvers;
    
    public Optional<DependencyResolver> getResolver(Path projectPath) {
        return resolvers.stream()
            .filter(resolver -> resolver.supports(projectPath))
            .max(Comparator.comparing(DependencyResolver::getPriority));
    }
}
```

### Step 4: Write Tests

Create comprehensive tests for your resolver:

```java
@ExtendWith(MockitoExtension.class)
class NewBuildToolResolverTest {
    
    @InjectMocks
    private NewBuildToolResolver resolver;
    
    @Test
    void testSupportsValidProject() {
        Path projectPath = Paths.get("src/test/resources/sample-project");
        assertTrue(resolver.supports(projectPath));
    }
    
    @Test
    void testResolveDependencies() {
        Path projectPath = Paths.get("src/test/resources/sample-project");
        List<DependencyNode> dependencies = resolver.resolveDependencies(projectPath);
        
        assertNotNull(dependencies);
        assertFalse(dependencies.isEmpty());
        
        // Validate dependency structure
        dependencies.forEach(dep -> {
            assertNotNull(dep.getCoordinate());
            assertNotNull(dep.getScope());
        });
    }
    
    @Test
    void testSecurityConstraints() {
        // Test that resolver doesn't execute arbitrary code
        Path maliciousPath = Paths.get("/etc/passwd");
        assertFalse(resolver.supports(maliciousPath));
    }
}
```

## Adding New Vulnerability Sources

### Overview

Vulnerability sources provide vulnerability data from external databases. Each source implements the `VulnerabilityProvider` interface.

### Step 1: Implement the Interface

```java
package com.riskscanner.dependencyriskanalyzer.service.vulnerability;

import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.model.vulnerability.Vulnerability;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class NewVulnerabilityProvider implements VulnerabilityProvider {

    @Override
    public String getName() {
        return "New Vulnerability Source";
    }

    @Override
    public VulnerabilitySource getSource() {
        return VulnerabilitySource.NEW_SOURCE;
    }

    @Override
    public List<Vulnerability> getVulnerabilities(DependencyCoordinate dependency) {
        // Implement vulnerability fetching logic
        return List.of();
    }

    @Override
    public boolean isHealthy() {
        // Check if the source is available
        return true;
    }

    @Override
    public void warmup() {
        // Implement cache warming if needed
    }
}
```

### Step 2: HTTP Client Implementation

For web-based sources:

```java
@Service
public class WebVulnerabilityProvider implements VulnerabilityProvider {
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    
    public WebVulnerabilityProvider(@Value("${vulnerability.new-source.url}") String url,
                                   @Value("${vulnerability.new-source.api-key:}") String apiKey) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
        this.baseUrl = url;
        this.apiKey = apiKey;
    }
    
    @Override
    public List<Vulnerability> getVulnerabilities(DependencyCoordinate dependency) {
        try {
            String url = buildQueryUrl(dependency);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return parseResponse(response.body());
            } else {
                log.warn("Failed to fetch vulnerabilities: {}", response.statusCode());
                return List.of();
            }
            
        } catch (Exception e) {
            log.error("Error fetching vulnerabilities", e);
            return List.of();
        }
    }
    
    private String buildQueryUrl(DependencyCoordinate dependency) {
        return String.format("%s/vulnerabilities?group=%s&artifact=%s&version=%s",
            baseUrl, dependency.groupId(), dependency.artifactId(), dependency.version());
    }
    
    private List<Vulnerability> parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            List<Vulnerability> vulnerabilities = new ArrayList<>();
            
            for (JsonNode node : root.path("vulnerabilities")) {
                Vulnerability vuln = Vulnerability.builder()
                    .id(node.path("id").asText())
                    .source(getSource())
                    .title(node.path("title").asText())
                    .description(node.path("description").asText())
                    .severity(parseSeverity(node.path("severity").asText()))
                    .affectedVersions(parseVersions(node.path("affectedVersions")))
                    .build();
                
                vulnerabilities.add(vuln);
            }
            
            return vulnerabilities;
            
        } catch (Exception e) {
            log.error("Error parsing vulnerability response", e);
            return List.of();
        }
    }
}
```

### Step 3: Add to Vulnerability Matching Service

Register your provider in `VulnerabilityMatchingService`:

```java
@Service
public class VulnerabilityMatchingService {
    
    @Autowired
    private List<VulnerabilityProvider> providers;
    
    @PostConstruct
    public void initialize() {
        log.info("Initialized vulnerability matching service with {} providers: {}", 
            providers.size(), 
            providers.stream().map(VulnerabilityProvider::getName).toList());
    }
    
    public List<Vulnerability> getAllVulnerabilities(DependencyCoordinate dependency) {
        return providers.parallelStream()
            .filter(VulnerabilityProvider::isHealthy)
            .flatMap(provider -> provider.getVulnerabilities(dependency).stream())
            .collect(Collectors.toList());
    }
}
```

### Step 4: Configuration

Add configuration properties:

```properties
# New vulnerability source configuration
vulnerability.new-source.url=https://api.new-vulnerability-source.com
vulnerability.new-source.api-key=${NEW_SOURCE_API_KEY:}
vulnerability.new-source.timeout=30s
vulnerability.new-source.rate-limit=100
```

### Step 5: Testing

```java
@ExtendWith(MockitoExtension.class)
class NewVulnerabilityProviderTest {
    
    @Mock
    private HttpClient httpClient;
    
    @InjectMocks
    private NewVulnerabilityProvider provider;
    
    @Test
    void testGetVulnerabilities() throws Exception {
        // Setup mock response
        String mockResponse = """
            {
                "vulnerabilities": [
                    {
                        "id": "CVE-2024-TEST",
                        "title": "Test Vulnerability",
                        "severity": "HIGH",
                        "description": "Test description"
                    }
                ]
            }
            """;
        
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(HttpResponse.of(mockResponse, 200));
        
        // Test
        DependencyCoordinate dependency = new DependencyCoordinate("com.example", "test", "1.0.0", "maven");
        List<Vulnerability> vulnerabilities = provider.getVulnerabilities(dependency);
        
        // Verify
        assertEquals(1, vulnerabilities.size());
        assertEquals("CVE-2024-TEST", vulnerabilities.get(0).getId());
    }
}
```

## Extending AI Providers

### Overview

AI providers generate human-friendly explanations of vulnerabilities. Each provider implements the `AiClient` interface.

### Step 1: Implement the Interface

```java
package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import org.springframework.stereotype.Component;

@Component
public class NewAiClient implements AiClient {
    
    private final String apiKey;
    private final String model;
    
    public NewAiClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }
    
    @Override
    public DependencyRiskAnalysisResult analyzeDependencyRisk(DependencyCoordinate dependency, DependencyEnrichmentDto enrichment) {
        // Implement dependency risk analysis
        return new DependencyRiskAnalysisResult(RiskLevel.UNKNOWN, null, "Not implemented", List.of());
    }
    
    @Override
    public String generateCompletion(String prompt) {
        // Implement text generation
        try {
            // Call AI service API
            return callAiService(prompt);
        } catch (Exception e) {
            throw new RuntimeException("AI service call failed", e);
        }
    }
    
    @Override
    public void testConnection() {
        // Test connection to AI service
        generateCompletion("test");
    }
    
    private String callAiService(String prompt) {
        // Implement actual API call
        return "AI response";
    }
}
```

### Step 2: Register in Factory

Add your client to `AiClientFactory`:

```java
@Component
public class AiClientFactory {
    
    public AiClient create(String provider, String apiKey, String model) {
        return switch (provider.toLowerCase()) {
            case "openai" -> new OpenAiClient(apiKey, model);
            case "claude" -> new ClaudeClient(apiKey, model);
            case "gemini" -> new GeminiClient(apiKey, model);
            case "ollama" -> new OllamaClient(model, ollamaBaseUrl);
            case "new-provider" -> new NewAiClient(apiKey, model);
            default -> throw new IllegalArgumentException("Unsupported AI provider: " + provider);
        };
    }
}
```

### Step 3: Update Documentation

Update the README.md to include your new provider:

```markdown
### AI Providers
- **OpenAI**: GPT models via OpenAI API
- **Claude**: Anthropic Claude models
- **Gemini**: Google Gemini models
- **Ollama**: Local models via Ollama
- **New Provider**: Description of your provider
```

## Testing Guidelines

### Unit Testing

- **Coverage**: Maintain >80% test coverage
- **Mocking**: Use Mockito for external dependencies
- **Assertions**: Use AssertJ for readable assertions
- **Test Data**: Use realistic test data

```java
@ExtendWith(MockitoExtension.class)
class ExampleTest {
    
    @Mock
    private ExternalService externalService;
    
    @InjectMocks
    private ServiceUnderTest service;
    
    @Test
    void shouldProcessDataCorrectly() {
        // Given
        Data input = new Data("test");
        when(externalService.process(input)).thenReturn(new Result("processed"));
        
        // When
        Result result = service.process(input);
        
        // Then
        assertThat(result.getValue()).isEqualTo("processed");
        verify(externalService).process(input);
    }
}
```

### Integration Testing

- **Database**: Use @DataJpaTest for JPA tests
- **Web Layer**: Use @WebMvcTest for controller tests
- **Full Stack**: Use @SpringBootTest for end-to-end tests

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "riskscanner.ai.provider=openai",
    "riskscanner.ai.model=gpt-4o-mini"
})
class IntegrationTest {
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Test
    void shouldAnalyzeVulnerability() {
        // Test full vulnerability analysis flow
    }
}
```

### Performance Testing

- **Load Testing**: Use JMeter or similar for load testing
- **Memory Testing**: Monitor memory usage during analysis
- **Concurrent Testing**: Test concurrent vulnerability scanning

## Code Style and Conventions

### Java Code Style

- **Formatting**: Use Google Java Style Guide
- **Naming**: Use descriptive, camelCase names
- **Comments**: Javadoc for public APIs
- **Constants**: Use static final for constants

```java
/**
 * Service for analyzing dependency vulnerabilities.
 * 
 * <p>This service coordinates vulnerability detection across multiple sources
 * and provides risk assessment with confidence scoring.
 */
@Service
public class VulnerabilityAnalysisService {
    
    private static final Logger logger = LoggerFactory.getLogger(VulnerabilityAnalysisService.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    
    /**
     * Analyzes vulnerabilities for the given dependency.
     * 
     * @param dependency the dependency to analyze
     * @return list of vulnerability findings
     * @throws AnalysisException if analysis fails
     */
    public List<VulnerabilityFinding> analyzeDependency(DependencyCoordinate dependency) {
        // Implementation
    }
}
```

### Error Handling

- **Exceptions**: Use specific exception types
- **Logging**: Log errors with appropriate levels
- **User Messages**: Provide clear, actionable error messages

```java
public class VulnerabilityAnalysisException extends RuntimeException {
    
    public VulnerabilityAnalysisException(String message) {
        super(message);
    }
    
    public VulnerabilityAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}

// Usage
try {
    return analyzeVulnerability(dependency);
} catch (NetworkException e) {
    logger.error("Network error during vulnerability analysis", e);
    throw new VulnerabilityAnalysisException("Unable to connect to vulnerability sources", e);
} catch (ParseException e) {
    logger.error("Failed to parse vulnerability data", e);
    throw new VulnerabilityAnalysisException("Invalid vulnerability data format", e);
}
```

### Logging

- **Levels**: Use appropriate log levels (ERROR, WARN, INFO, DEBUG)
- **Context**: Include relevant context in log messages
- **Structured**: Use structured logging when possible

```java
logger.info("Starting vulnerability analysis for dependency: {} in project: {}", 
    dependency, projectPath);

logger.error("Failed to fetch vulnerabilities from source: {} for dependency: {}", 
    sourceName, dependency, exception);

logger.debug("Cache hit for vulnerability data: {} with {} entries", 
    cacheKey, vulnerabilities.size());
```

## Submitting Changes

### Pull Request Process

1. **Create Feature Branch**
```bash
git checkout -b feature/new-resolver
```

2. **Make Changes**
- Implement your feature
- Add tests
- Update documentation

3. **Run Tests**
```bash
./mvnw clean test
```

4. **Commit Changes**
```bash
git add .
git commit -m "Add new build tool resolver"
```

5. **Push and Create PR**
```bash
git push origin feature/new-resolver
```

### Pull Request Template

```markdown
## Description
Brief description of the changes

## Type of Change
- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Documentation update

## Testing
- [ ] Unit tests added
- [ ] Integration tests added
- [ ] Manual testing completed

## Checklist
- [ ] Code follows style guidelines
- [ ] Self-review completed
- [ ] Documentation updated
- [ ] Tests pass locally
```

## Code Review Guidelines

### Reviewer Checklist

- **Functionality**: Does the code work as intended?
- **Security**: Are there any security concerns?
- **Performance**: Will this affect performance?
- **Testing**: Are tests comprehensive?
- **Documentation**: Is documentation updated?
- **Style**: Does code follow project conventions?

### Review Process

1. **Automated Checks**: CI/CD pipeline runs tests
2. **Code Review**: At least one maintainer reviews
3. **Security Review**: Security-focused review for sensitive changes
4. **Approval**: Maintainer approval required for merge

## Getting Help

### Resources

- **Documentation**: `docs/` directory
- **Architecture**: `docs/ARCHITECTURE.md`
- **Security**: `docs/SECURITY.md`
- **Developer Guide**: `docs/DEV_GUIDE.md`

### Community

- **Issues**: GitHub Issues for bug reports and feature requests
- **Discussions**: GitHub Discussions for questions and ideas
- **Email**: maintainer@example.com for private questions

Thank you for contributing to Risk Scanner! Your contributions help make Java projects more secure.
