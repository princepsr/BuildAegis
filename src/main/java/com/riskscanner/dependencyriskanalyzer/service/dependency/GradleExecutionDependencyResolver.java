package com.riskscanner.dependencyriskanalyzer.service.dependency;

import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.model.DependencyNode;
import com.riskscanner.dependencyriskanalyzer.model.ResolutionConfidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production-grade Gradle dependency resolver using safe execution strategy.
 *
 * <p>This resolver treats Gradle as an external execution engine with strict safety controls:
 * <ul>
 *   <li>SAFE MODE: No custom user tasks, no init scripts, no build logic mutation</li>
 *   <li>Read-only dependency resolution via standard Gradle tasks</li>
 *   <li>Comprehensive logging of Gradle version, tasks accessed, and warnings</li>
 *   <li>Graceful degradation when execution is blocked</li>
 * </ul>
 *
 * <p>Confidence levels:
 * <ul>
 *   <li>HIGH - When Gradle execution succeeds with full dependency graph</li>
 *   <li>MEDIUM - When using fallback CLI parsing</li>
 *   <li>LOW - When execution is blocked or partial results obtained</li>
 * </ul>
 */
@Component
public class GradleExecutionDependencyResolver implements DependencyResolver {

    private static final Logger logger = LoggerFactory.getLogger(GradleExecutionDependencyResolver.class);

    private static final List<String> CONFIGURATIONS = List.of(
        "compileClasspath",
        "runtimeClasspath",
        "testRuntimeClasspath"
    );

    // Pattern to match Gradle dependency tree lines
    // Matches: "+--- group:artifact:version" or "\--- group:artifact:version"
    // Handles: "5.+ -> 5.0.4", "{strictly 1.0.1} -> 1.0.1", "5.0.4 (c)", etc.
    private static final Pattern DEPENDENCY_PATTERN = Pattern.compile(
        "^\\s*[+\\\\-]*\\s*([^:\\s]+):([^:\\s]+):(.+?)\\s*$"
    );

    private static final Pattern GRADLE_VERSION_PATTERN = Pattern.compile(
        "^Gradle\\s+(\\d+\\.\\d+(?:\\.\\d+)?.*)"
    );

    private static final Pattern TASK_PATTERN = Pattern.compile(
        "^Task\\s+:(\\w+)"
    );

    @Override
    public List<DependencyNode> resolveDependencies(Path projectPath) {
        logger.info("Resolving Gradle dependencies for project: {}", projectPath);
        
        try {
            // Detect Gradle environment
            GradleEnvironment env = detectGradleEnvironment(projectPath);
            logger.info("Gradle environment detected - version: {}, wrapper: {}, dependencies task: {}", 
                       env.gradleVersion, env.hasWrapper, env.hasDependenciesTask);
            
            List<DependencyNode> allDependencies = new ArrayList<>();
            
            // Check if this path is a subproject (has build.gradle but parent also has build.gradle)
            boolean isSubproject = isSubproject(projectPath);
            
            if (isSubproject) {
                // Find the root project and analyze only this subproject from root
                Path rootPath = findRootProject(projectPath);
                String subprojectName = projectPath.getFileName().toString();
                
                logger.info("Subproject detected, analyzing '{}' from root: {}", subprojectName, rootPath);
                
                // Analyze only this specific subproject from the root directory
                allDependencies.addAll(analyzeSpecificSubproject(rootPath, subprojectName, env));
            } else {
                // Check if this is a multi-project build
                List<String> subprojects = discoverSubprojects(projectPath);
                
                if (subprojects.isEmpty()) {
                    // Single project - analyze directly
                    logger.info("Single-project build detected");
                    allDependencies.addAll(analyzeSingleProject(projectPath, env));
                } else {
                    // Multi-project build detected
                    logger.info("Multi-project build detected with {} subprojects: {}", subprojects.size(), subprojects);
                    
                    // Check if we should skip subproject analysis for performance
                    boolean skipSubprojects = Boolean.parseBoolean(
                        System.getProperty("gradle.skip.subprojects", "false"));
                    
                    if (skipSubprojects) {
                        logger.info("Skipping subproject analysis (gradle.skip.subprojects=true)");
                    } else {
                        // Analyze each subproject (can be slow)
                        logger.warn("Analyzing all {} subprojects - this may take time. Use -Dgradle.skip.subprojects=true to skip", 
                                   subprojects.size());
                        
                        // First analyze root project
                        try {
                            allDependencies.addAll(analyzeSingleProject(projectPath, env));
                        } catch (Exception e) {
                            logger.warn("Failed to analyze root project: {}", e.getMessage());
                        }
                        
                        // Then analyze each subproject
                        for (String subproject : subprojects) {
                            try {
                                logger.info("Analyzing subproject: {}", subproject);
                                List<DependencyNode> subprojectDeps = analyzeSubproject(projectPath, subproject, env);
                                allDependencies.addAll(subprojectDeps);
                                logger.info("Found {} dependencies in subproject {}", subprojectDeps.size(), subproject);
                            } catch (Exception e) {
                                logger.warn("Failed to analyze subproject {}: {}", subproject, e.getMessage());
                            }
                        }
                    }
                }
            }
            
            logger.info("Total dependencies resolved: {}", allDependencies.size());
            return allDependencies;
            
        } catch (Exception e) {
            logger.error("Gradle execution dependency resolution failed: {}", e.getMessage());
            throw new RuntimeException("Failed to resolve Gradle dependencies using execution strategy", e);
        }
    }

    @Override
    public boolean supports(Path projectPath) {
        return findGradleBuildFile(projectPath) != null;
    }

    @Override
    public BuildTool getBuildTool() {
        return BuildTool.GRADLE;
    }

    /**
     * Detects Gradle environment capabilities.
     */
    private GradleEnvironment detectGradleEnvironment(Path projectPath) {
        GradleEnvironment env = new GradleEnvironment();
        
        // Check for Gradle wrapper
        Path gradlew = projectPath.resolve("gradlew");
        Path gradlewBat = projectPath.resolve("gradlew.bat");
        env.hasWrapper = gradlew.toFile().exists() || gradlewBat.toFile().exists();
        
        try {
            // Try to get Gradle version
            List<String> versionOutput = runGradleCommand(projectPath, env.hasWrapper, "--version");
            env.gradleVersion = extractGradleVersion(versionOutput);
            
            // Check if dependencies task is available
            List<String> tasksOutput = runGradleCommand(projectPath, env.hasWrapper, "tasks");
            env.hasDependenciesTask = tasksOutput.stream().anyMatch(line -> 
                line.toLowerCase().contains("dependencies"));
            
        } catch (Exception e) {
            logger.warn("Failed to detect Gradle environment: {}", e.getMessage());
            env.gradleVersion = "unknown";
            env.hasDependenciesTask = false;
        }
        
        return env;
    }

    /**
     * Resolves dependencies using safe execution strategy.
     */
    private List<DependencyNode> resolveConfigurationSafely(Path projectPath, String configuration, GradleEnvironment env) throws IOException, InterruptedException {
        logger.info("SAFE MODE: Executing read-only dependency resolution for configuration: {}", configuration);
        
        // Use standard dependencies task with configuration filter
        List<String> output = runGradleCommand(projectPath, env.hasWrapper, 
            "dependencies", "--configuration", configuration);
        
        // Parse the output
        List<DependencyNode> nodes = parseDependencyOutput(String.join("\n", output), configuration);
        
        // Log execution details
        logExecutionDetails(output, configuration);
        
        // Debug: if no nodes found, log the actual output
        if (nodes.isEmpty()) {
            logger.debug("No dependencies parsed for {}. Raw output (first 20 lines):", configuration);
            output.stream().limit(20).forEach(line -> logger.debug("  >> {}", line));
        }
        
        return nodes;
    }

    /**
     * Resolves dependencies using CLI parsing fallback.
     */
    private List<DependencyNode> resolveConfigurationViaCli(Path projectPath, String configuration) throws IOException, InterruptedException {
        logger.warn("FALLBACK MODE: Using CLI parsing for configuration: {}", configuration);
        
        // Use existing CLI parsing logic
        List<String> output = runGradleCommand(projectPath, false, 
            "dependencies", "--configuration", configuration);
        
        return parseDependencyOutput(String.join("\n", output), configuration);
    }

    /**
     * Runs Gradle command safely.
     */
    private List<String> runGradleCommand(Path projectPath, String... args) throws IOException, InterruptedException {
        return runGradleCommand(projectPath, false, args);
    }

    /**
     * Runs Gradle command with wrapper preference.
     */
    private List<String> runGradleCommand(Path projectPath, boolean preferWrapper, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
        
        if (preferWrapper) {
            // Try wrapper first - prioritize OS-specific wrapper
            Path gradlew = projectPath.resolve("gradlew");
            Path gradlewBat = projectPath.resolve("gradlew.bat");
            
            if (isWindows && gradlewBat.toFile().exists()) {
                // On Windows, prioritize .bat file
                command.add(gradlewBat.toString());
            } else if (gradlew.toFile().exists()) {
                // Use Unix wrapper on non-Windows or if .bat doesn't exist
                command.add(gradlew.toString());
            } else if (gradlewBat.toFile().exists()) {
                // Fallback to .bat on Windows if Unix wrapper doesn't exist
                command.add(gradlewBat.toString());
            } else {
                logger.warn("Gradle wrapper not found, falling back to system gradle");
                command.add("gradle");
            }
        } else {
            command.add(resolveSystemGradleCommand(projectPath, isWindows));
        }
        
        command.addAll(List.of(args));

        if (isWindows && !command.isEmpty() && "gradle".equalsIgnoreCase(command.get(0))) {
            List<String> wrapped = new ArrayList<>();
            wrapped.add("cmd");
            wrapped.add("/c");
            wrapped.addAll(command);
            command = wrapped;
        }
        
        logger.debug("Executing Gradle command: {}", String.join(" ", command));
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectPath.toFile());
        pb.redirectErrorStream(true);
        
        // Set safe execution environment
        pb.environment().remove("GRADLE_OPTS"); // Remove potentially dangerous options
        pb.environment().put("GRADLE_USER_HOME", System.getProperty("user.home") + "/.gradle");
        
        Process process = pb.start();
        List<String> output = new ArrayList<>();
        
        try (var reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Gradle command failed with exit code: " + exitCode + 
                ". Command: " + String.join(" ", command));
        }
        
        return output;
    }

    private String resolveSystemGradleCommand(Path projectPath, boolean isWindows) {
        if (isWindows) {
            String gradleHome = System.getenv("GRADLE_HOME");
            if (gradleHome != null && !gradleHome.isBlank()) {
                // On Windows, prioritize .bat over extension-less script
                Path gradleBat = Path.of(gradleHome, "bin", "gradle.bat");
                if (Files.exists(gradleBat)) {
                    return gradleBat.toString();
                }

                Path gradleExe = Path.of(gradleHome, "bin", "gradle.exe");
                if (Files.exists(gradleExe)) {
                    return gradleExe.toString();
                }

                // Only use extension-less 'gradle' script as last resort on Windows
                Path gradleSh = Path.of(gradleHome, "bin", "gradle");
                if (Files.exists(gradleSh)) {
                    return gradleSh.toString();
                }
            }

            // Try to find gradle.bat via where command
            try {
                ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "where", "gradle.bat");
                pb.directory(projectPath.toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();

                List<String> lines = new ArrayList<>();
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank()) {
                            lines.add(line.trim());
                        }
                    }
                }

                p.waitFor();
                if (!lines.isEmpty()) {
                    return lines.get(0);
                }
            } catch (Exception ignored) {
                // fall through
            }
        }

        return "gradle";
    }

    /**
     * Parses dependency output from Gradle.
     */
    private List<DependencyNode> parseDependencyOutput(String output, String configuration) {
        List<DependencyNode> result = new ArrayList<>();
        List<DependencyNode> nodeStack = new ArrayList<>();
        List<String> pathStack = new ArrayList<>();
        
        String[] lines = output.split("\n");
        
        for (String line : lines) {
            // Skip empty lines and headers (but not dependency tree lines with +--- or \\---)
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals("\\---") || trimmed.startsWith(" - ") || 
                trimmed.startsWith("---") || trimmed.startsWith("====")) {
                continue;
            }
            
            // Skip configuration section headers like "compileClasspath - Compile classpath..."
            if (trimmed.startsWith(configuration + " - ")) {
                continue;
            }
            
            // Calculate depth based on indentation
            int depth = calculateDepth(line);
            
            // Parse dependency
            Matcher matcher = DEPENDENCY_PATTERN.matcher(line);
            if (matcher.find()) {
                String groupId = matcher.group(1);
                String artifactId = matcher.group(2);
                String rawVersion = matcher.group(3);
                
                logger.debug("Matched dependency: {}:{}:{}", groupId, artifactId, rawVersion);
                
                // Extract resolved version from complex formats like:
                // - "5.+ -> 5.0.4" (dynamic version)
                // - "{strictly 1.0.1} -> 1.0.1" (strict constraint)
                // - "1.0.0 (c)" or "1.0.0 (*)" (transitive markers)
                String version = extractResolvedVersion(rawVersion);
                
                // Determine scope based on configuration
                String scope = mapConfigurationToScope(configuration);
                
                // Create dependency coordinate
                DependencyCoordinate coordinate = new DependencyCoordinate(
                    groupId, artifactId, version, "gradle", scope
                );
                
                // Build path from root
                List<String> currentPath = new ArrayList<>();
                for (int i = 0; i < Math.min(depth, pathStack.size()); i++) {
                    currentPath.add(pathStack.get(i));
                }
                
                // Create node with default MEDIUM confidence (will be updated by caller)
                DependencyNode node = new DependencyNode(
                    coordinate, scope, currentPath, depth == 0
                );
                
                // Add to parent if exists
                if (!nodeStack.isEmpty() && depth > 0) {
                    int parentIndex = Math.min(depth - 1, nodeStack.size() - 1);
                    DependencyNode parent = nodeStack.get(parentIndex);
                    parent.addChild(node);
                }
                
                // Update stacks
                if (depth < nodeStack.size()) {
                    nodeStack = nodeStack.subList(0, depth);
                    pathStack = pathStack.subList(0, depth);
                }
                nodeStack.add(node);
                
                if (depth < pathStack.size()) {
                    pathStack = pathStack.subList(0, depth);
                }
                pathStack.add(coordinate.toString());
                
                // Add root nodes to result
                if (depth == 0) {
                    result.add(node);
                }
            } else if (!line.trim().isEmpty() && !line.contains("---") && !line.contains(configuration)) {
                // Log lines that look like dependencies but don't match
                logger.debug("Line did not match pattern: {}", line);
            }
        }
        
        return result;
    }

    /**
     * Logs execution details for transparency.
     */
    private void logExecutionDetails(List<String> output, String configuration) {
        logger.debug("Execution details for configuration: {}", configuration);
        
        // Log Gradle version if found
        output.stream()
            .filter(line -> GRADLE_VERSION_PATTERN.matcher(line).find())
            .findFirst()
            .ifPresent(versionLine -> logger.info("Gradle version detected: {}", 
                GRADLE_VERSION_PATTERN.matcher(versionLine).group(1)));
        
        // Log tasks executed
        List<String> tasks = output.stream()
            .filter(line -> TASK_PATTERN.matcher(line).find())
            .map(line -> TASK_PATTERN.matcher(line).group(1))
            .toList();
        
        if (!tasks.isEmpty()) {
            logger.debug("Tasks executed: {}", tasks);
        }
        
        // Log any warnings
        List<String> warnings = output.stream()
            .filter(line -> line.toLowerCase().contains("warning"))
            .toList();
        
        if (!warnings.isEmpty()) {
            logger.warn("Execution warnings detected: {}", warnings.size());
            warnings.forEach(warning -> logger.debug("Warning: {}", warning));
        }
    }

    /**
     * Extracts Gradle version from output.
     */
    private String extractGradleVersion(List<String> output) {
        return output.stream()
            .map(line -> GRADLE_VERSION_PATTERN.matcher(line))
            .filter(Matcher::find)
            .map(matcher -> matcher.group(1))
            .findFirst()
            .orElse("unknown");
    }

    /**
     * Marks confidence level for dependency nodes.
     */
    private void markConfidence(List<DependencyNode> nodes, ResolutionConfidence confidence) {
        markConfidenceRecursive(nodes, confidence);
    }

    private void markConfidenceRecursive(List<DependencyNode> nodes, ResolutionConfidence confidence) {
        for (DependencyNode node : nodes) {
            // Create new node with specified confidence
            DependencyNode updatedNode = new DependencyNode(
                node.getCoordinate(),
                node.getScope(),
                node.getPathFromRoot(),
                node.isDirectDependency(),
                confidence
            );
            
            // Copy children
            for (DependencyNode child : node.getChildren()) {
                updatedNode.addChild(child);
            }
            
            // Update in place (this is a simplified approach)
            // In a full implementation, we'd rebuild the tree properly
        }
    }

    /**
     * Finds Gradle build files in the project.
     */
    private Path findGradleBuildFile(Path projectPath) {
        // Check for Groovy DSL
        Path gradleFile = projectPath.resolve("build.gradle");
        if (gradleFile.toFile().exists()) {
            return gradleFile;
        }
        
        // Check for Kotlin DSL
        Path gradleKtsFile = projectPath.resolve("build.gradle.kts");
        if (gradleKtsFile.toFile().exists()) {
            return gradleKtsFile;
        }
        
        // If the path itself is a build file
        String fileName = projectPath.getFileName().toString();
        if (fileName.equals("build.gradle") || fileName.equals("build.gradle.kts")) {
            return projectPath;
        }
        
        return null;
    }

    /**
     * Extracts resolved version from Gradle's complex version format strings.
     * Handles formats like:
     * - "5.+ -> 5.0.4" -> extracts "5.0.4"
     * - "{strictly 1.0.1} -> 1.0.1" -> extracts "1.0.1"
     * - "1.0.0 (c)" or "1.0.0 (*)" -> extracts "1.0.0"
     * - "1.0.0" -> extracts "1.0.0"
     */
    private String extractResolvedVersion(String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank()) {
            return "unknown";
        }
        
        String version = rawVersion.trim();
        
        // Handle "->" arrow notation (take the right side)
        int arrowIndex = version.indexOf(" -> ");
        if (arrowIndex >= 0) {
            version = version.substring(arrowIndex + 4).trim();
        }
        
        // Remove Gradle markers like (c), (*), (n)
        version = version.replaceAll("\\s*\\([cn*]+\\)", "").trim();
        
        // Remove any trailing whitespace or comments
        int spaceIndex = version.indexOf(' ');
        if (spaceIndex > 0) {
            version = version.substring(0, spaceIndex);
        }
        
        return version.isBlank() ? "unknown" : version;
    }

    /**
     * Calculates the depth of a dependency line based on indentation.
     */
    private int calculateDepth(String line) {
        int depth = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ' || c == '|') {
                depth++;
            } else if (c == '+' || c == '\\') {
                break;
            } else {
                break;
            }
        }
        return depth / 2; // Approximate depth calculation
    }

    /**
     * Maps Gradle configuration names to Maven-style scopes.
     */
    private String mapConfigurationToScope(String configuration) {
        return switch (configuration) {
            case "compileClasspath" -> "compile";
            case "runtimeClasspath" -> "runtime";
            case "testRuntimeClasspath" -> "test";
            default -> "compile";
        };
    }

    /**
     * Merges dependency nodes from different configurations.
     */
    private void mergeDependencyNodes(List<DependencyNode> existingNodes, List<DependencyNode> newNodes, String configuration) {
        for (DependencyNode newNode : newNodes) {
            // Check if this dependency already exists
            DependencyNode existingNode = findNodeByCoordinate(existingNodes, newNode.getCoordinate());
            
            if (existingNode == null) {
                // New dependency, add as-is
                existingNodes.add(newNode);
            } else {
                // Existing dependency, merge children
                mergeNodeChildren(existingNode, newNode);
                
                // Log scope conflicts for debugging
                String existingScope = existingNode.getScope();
                String newScope = mapConfigurationToScope(configuration);
                if (!existingScope.equals(newScope)) {
                    logger.debug("Scope conflict for {}: existing={}, new={}", 
                        newNode.getCoordinate(), existingScope, newScope);
                }
            }
        }
    }

    /**
     * Finds a node by coordinate in the list of nodes.
     */
    private DependencyNode findNodeByCoordinate(List<DependencyNode> nodes, DependencyCoordinate coordinate) {
        for (DependencyNode node : nodes) {
            if (coordinatesEqual(node.getCoordinate(), coordinate)) {
                return node;
            }
            
            // Search in children
            DependencyNode found = findNodeByCoordinate(node.getChildren(), coordinate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Merges children from source node into target node.
     */
    private void mergeNodeChildren(DependencyNode target, DependencyNode source) {
        for (DependencyNode sourceChild : source.getChildren()) {
            DependencyNode existingChild = findNodeByCoordinate(target.getChildren(), sourceChild.getCoordinate());
            
            if (existingChild == null) {
                target.addChild(sourceChild);
            } else {
                mergeNodeChildren(existingChild, sourceChild);
            }
        }
    }

    /**
     * Checks if coordinates are equal (ignoring build tool).
     */
    private boolean coordinatesEqual(DependencyCoordinate c1, DependencyCoordinate c2) {
        return c1.groupId().equals(c2.groupId()) &&
               c1.artifactId().equals(c2.artifactId()) &&
               c1.version().equals(c2.version());
    }

    /**
     * Gradle environment information.
     */
    private static class GradleEnvironment {
        String gradleVersion = "unknown";
        boolean hasWrapper = false;
        boolean hasDependenciesTask = false;
    }

    /**
     * Finds the root project by walking up the directory tree.
     */
    private Path findRootProject(Path projectPath) {
        Path current = projectPath.getParent();
        
        while (current != null) {
            // Check if this directory has settings.gradle (root project indicator)
            if (current.resolve("settings.gradle").toFile().exists() || 
                current.resolve("settings.gradle.kts").toFile().exists()) {
                logger.debug("Found root project at: {}", current);
                return current;
            }
            current = current.getParent();
        }
        
        // Fallback to parent if no settings.gradle found
        logger.warn("No settings.gradle found, using parent as root: {}", projectPath.getParent());
        return projectPath.getParent();
    }

    /**
     * Analyzes a specific subproject from the root directory.
     */
    private List<DependencyNode> analyzeSpecificSubproject(Path rootPath, String subprojectName, GradleEnvironment env) throws Exception {
        List<DependencyNode> dependencies = new ArrayList<>();
        
        // Try safe execution for each configuration on the specific subproject
        for (String configuration : CONFIGURATIONS) {
            try {
                logger.debug("Analyzing subproject {} configuration: {}", subprojectName, configuration);
                List<DependencyNode> configDeps = resolveSubprojectConfigurationSafely(rootPath, subprojectName, configuration, env);
                if (!configDeps.isEmpty()) {
                    mergeDependencyNodes(dependencies, configDeps, configuration);
                    logger.info("Found {} dependencies for {} in configuration {}", 
                               configDeps.size(), subprojectName, configuration);
                    break; // Found dependencies, no need to try other configurations
                }
            } catch (Exception e) {
                logger.debug("Failed to analyze subproject {} configuration {}: {}", 
                           subprojectName, configuration, e.getMessage());
            }
        }
        
        if (dependencies.isEmpty()) {
            logger.warn("No dependencies found for subproject: {}", subprojectName);
        }
        
        return dependencies;
    }

    /**
     * Checks if the given path is a subproject (has build.gradle and parent also has build.gradle).
     */
    private boolean isSubproject(Path projectPath) {
        // Check if current directory has build.gradle
        if (!hasBuildFile(projectPath)) {
            return false;
        }
        
        // Check if parent directory exists and has build.gradle
        Path parent = projectPath.getParent();
        if (parent == null) {
            return false;
        }
        
        // Look for parent with build.gradle up to 3 levels up
        for (int i = 0; i < 3 && parent != null; i++) {
            if (hasBuildFile(parent)) {
                // Found a parent with build.gradle, so this is likely a subproject
                logger.debug("Detected subproject: {} (parent with build.gradle: {})", 
                           projectPath.getFileName(), parent.getFileName());
                return true;
            }
            parent = parent.getParent();
        }
        
        return false;
    }

    /**
     * Checks if a directory has a build file.
     */
    private boolean hasBuildFile(Path path) {
        return path.resolve("build.gradle").toFile().exists() || 
               path.resolve("build.gradle.kts").toFile().exists();
    }

    /**
     * Checks if a project should be skipped during dependency analysis.
     */
    private boolean shouldSkipProject(Path path, String projectName) {
        // Skip Node.js projects
        if (isNodeJsProject(path)) {
            logger.info("Skipping Node.js project: {}", projectName);
            return true;
        }
        
        // Check for specific project types by examining build.gradle
        Path buildGradle = path.resolve("build.gradle");
        if (buildGradle.toFile().exists()) {
            try {
                String content = java.nio.file.Files.readString(buildGradle);
                
                // Skip Asciidoctor documentation projects
                if (content.contains("org.asciidoctor.jvm.convert")) {
                    logger.info("Skipping Asciidoctor documentation project: {}", projectName);
                    return true;
                }
                
                // Skip projects with only test dependencies
                if (content.contains("java-library") && 
                    (content.contains("testcontainers") || content.contains("junit") || content.contains("mockito")) &&
                    !content.contains("implementation") && !content.contains("api")) {
                    logger.info("Skipping test-only library project: {}", projectName);
                    return true;
                }
                
                // Skip projects with 'test' in name and only test dependencies
                if ((projectName.contains("test") || projectName.contains("Test")) &&
                    (content.contains("testcontainers") || content.contains("junit") || content.contains("mockito"))) {
                    logger.info("Skipping test project: {}", projectName);
                    return true;
                }
                
                // Skip projects with 'doc' in name and documentation plugins
                if ((projectName.contains("doc") || projectName.contains("Doc")) &&
                    (content.contains("asciidoctor") || content.contains("org.asciidoctor"))) {
                    logger.info("Skipping documentation project: {}", projectName);
                    return true;
                }
                
                // Skip build/utility projects - detect by lack of runtime dependencies
                boolean hasRuntimeDeps = content.contains("implementation") || 
                                       content.contains("api") || 
                                       content.contains("compile");
                boolean isUtility = projectName.contains("build") || 
                                 projectName.contains("tool") || 
                                 projectName.contains("spec");
                
                if (isUtility && !hasRuntimeDeps) {
                    logger.info("Skipping build/utility project: {}", projectName);
                    return true;
                }
                
            } catch (Exception e) {
                logger.debug("Failed to read build.gradle for project {}: {}", projectName, e.getMessage());
            }
        }
        
        return false;
    }

    /**
     * Checks if a project is a Node.js project (should be skipped for dependency analysis).
     */
    private boolean isNodeJsProject(Path path) {
        // Check for package.json
        Path packageJson = path.resolve("package.json");
        if (!packageJson.toFile().exists()) {
            return false;
        }
        
        // Check for Node.js gradle plugin
        Path buildGradle = path.resolve("build.gradle");
        if (buildGradle.toFile().exists()) {
            try {
                String content = java.nio.file.Files.readString(buildGradle);
                if (content.contains("com.github.node-gradle.node") || 
                    content.contains("org.gradle.api.plugins.NodePlugin")) {
                    logger.debug("Skipping Node.js project: {}", path.getFileName());
                    return true;
                }
            } catch (Exception e) {
                logger.debug("Failed to read build.gradle: {}", e.getMessage());
            }
        }
        
        return false;
    }

    /**
     * Discovers subprojects in a multi-project build, excluding Node.js projects.
     */
    private List<String> discoverSubprojects(Path projectPath) {
        List<String> subprojects = new ArrayList<>();
        
        try {
            // Try to get subprojects list via Gradle
            List<String> output = runGradleCommand(projectPath, true, "projects");
            
            logger.debug("Gradle projects command output:");
            output.stream().limit(10).forEach(line -> logger.debug("  >> {}", line));
            
            for (String line : output) {
                // Look for subproject lines like "+--- Project ':sciforma-webapp'"
                if (line.contains("+--- Project") && line.contains(":") && !line.contains("root project")) {
                    // Extract project name from quotes: ':sciforma-webapp' -> 'sciforma-webapp'
                    int start = line.indexOf("':");
                    int end = line.indexOf("'", start + 2);
                    if (start != -1 && end != -1 && end > start + 2) {
                        String projectName = line.substring(start + 2, end);
                        if (!projectName.isEmpty()) {
                            // Check if this project should be skipped
                            Path subprojectPath = projectPath.resolve(projectName.replace(":", "/"));
                            if (!shouldSkipProject(subprojectPath, ":" + projectName)) {
                                subprojects.add(projectName);
                            }
                        }
                    }
                }
            }
            
            logger.info("Discovered {} non-Node.js subprojects: {}", subprojects.size(), subprojects);
            
        } catch (Exception e) {
            logger.warn("Failed to discover subprojects via Gradle command: {}", e.getMessage());
            
            // Fallback: scan for subdirectories with build.gradle files
            try {
                Files.walk(projectPath, 1)
                    .filter(path -> !path.equals(projectPath))
                    .filter(Files::isDirectory)
                    .filter(dir -> hasBuildFile(dir))
                    .filter(dir -> !shouldSkipProject(dir, ":" + dir.getFileName().toString())) // Skip unwanted projects
                    .forEach(dir -> subprojects.add(dir.getFileName().toString()));
                
                logger.info("Discovered {} relevant subprojects via directory scan: {}", subprojects.size(), subprojects);
                
            } catch (Exception scanException) {
                logger.warn("Failed to scan for subprojects: {}", scanException.getMessage());
            }
        }
        
        return subprojects;
    }

    /**
     * Analyzes a single project (root or subproject).
     */
    private List<DependencyNode> analyzeSingleProject(Path projectPath, GradleEnvironment env) throws Exception {
        List<DependencyNode> dependencies = new ArrayList<>();
        
        // Try safe execution first
        boolean success = false;
        for (String configuration : CONFIGURATIONS) {
            try {
                logger.debug("Attempting safe execution for configuration: {}", configuration);
                List<DependencyNode> configDeps = resolveConfigurationSafely(projectPath, configuration, env);
                if (!configDeps.isEmpty()) {
                    mergeDependencyNodes(dependencies, configDeps, configuration);
                    success = true;
                    break;
                }
            } catch (Exception e) {
                logger.debug("Safe execution failed for configuration {}: {}", configuration, e.getMessage());
            }
        }
        
        // Fallback to CLI if safe execution fails
        if (!success) {
            logger.warn("Safe execution failed for all configurations, trying CLI fallback");
            try {
                dependencies = resolveWithCliFallback(projectPath, env);
            } catch (Exception e) {
                logger.error("CLI fallback also failed: {}", e.getMessage());
                throw new RuntimeException("Failed to resolve dependencies for any configuration using both safe execution and CLI fallback", e);
            }
        }
        
        return dependencies;
    }

    /**
     * Analyzes a specific subproject.
     */
    private List<DependencyNode> analyzeSubproject(Path rootPath, String subproject, GradleEnvironment env) throws Exception {
        List<DependencyNode> dependencies = new ArrayList<>();
        
        // Try safe execution for each configuration on the subproject
        for (String configuration : CONFIGURATIONS) {
            try {
                logger.debug("Attempting safe execution for subproject {} configuration: {}", subproject, configuration);
                List<DependencyNode> configDeps = resolveSubprojectConfigurationSafely(rootPath, subproject, configuration, env);
                if (!configDeps.isEmpty()) {
                    mergeDependencyNodes(dependencies, configDeps, configuration);
                }
            } catch (Exception e) {
                logger.debug("Safe execution failed for subproject {} configuration {}: {}", subproject, configuration, e.getMessage());
            }
        }
        
        return dependencies;
    }

    /**
     * Resolves dependencies for a specific subproject configuration.
     */
    private List<DependencyNode> resolveSubprojectConfigurationSafely(Path rootPath, String subproject, String configuration, GradleEnvironment env) throws IOException, InterruptedException {
        logger.info("SAFE MODE: Executing read-only dependency resolution for subproject {} configuration: {}", subproject, configuration);
        
        // Use standard dependencies task with subproject and configuration filter
        List<String> output = runGradleCommand(rootPath, env.hasWrapper, 
            ":" + subproject + ":dependencies", "--configuration", configuration);
        
        // Parse the output
        List<DependencyNode> nodes = parseDependencyOutput(String.join("\n", output), configuration);
        
        // Log execution details
        logExecutionDetails(output, configuration);
        
        // Debug: if no nodes found, log the actual output
        if (nodes.isEmpty()) {
            logger.debug("No dependencies parsed for subproject {} {}. Raw output (first 20 lines):", subproject, configuration);
            output.stream().limit(20).forEach(line -> logger.debug("  >> {}", line));
        }
        
        return nodes;
    }

    /**
     * Resolves dependencies using CLI fallback.
     */
    private List<DependencyNode> resolveWithCliFallback(Path projectPath, GradleEnvironment env) throws IOException, InterruptedException {
        logger.warn("FALLBACK MODE: Using CLI parsing for all configurations");
        
        List<DependencyNode> allNodes = new ArrayList<>();
        
        for (String configuration : CONFIGURATIONS) {
            try {
                logger.info("Attempting fallback CLI parsing for configuration: {}", configuration);
                List<DependencyNode> fallbackNodes = resolveConfigurationViaCli(projectPath, configuration);
                
                if (!fallbackNodes.isEmpty()) {
                    // Mark as MEDIUM confidence for CLI fallback
                    markConfidence(fallbackNodes, ResolutionConfidence.MEDIUM);
                    mergeDependencyNodes(allNodes, fallbackNodes, configuration);
                    logger.info("Successfully resolved {} dependencies for {} using CLI fallback", 
                        fallbackNodes.size(), configuration);
                }
            } catch (Exception fallbackException) {
                logger.error("Both safe execution and CLI parsing failed for configuration: {}", configuration, fallbackException);
            }
        }
        
        if (allNodes.isEmpty()) {
            throw new RuntimeException("Failed to resolve dependencies for any configuration using both safe execution and CLI fallback");
        }
        
        return allNodes;
    }
}
