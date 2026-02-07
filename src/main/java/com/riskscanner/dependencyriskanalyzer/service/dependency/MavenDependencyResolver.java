package com.riskscanner.dependencyriskanalyzer.service.dependency;

import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.model.DependencyNode;
import com.riskscanner.dependencyriskanalyzer.model.ResolutionConfidence;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maven dependency resolver using Maven Resolver (Aether).
 *
 * <p>This resolver:
 * <ul>
 *   <li>Parses pom.xml files using Maven Model</li>
 *   <li>Resolves transitive dependencies using Aether</li>
 *   <li>Builds dependency trees with proper scopes</li>
 *   <li>Handles dependency management and parent POM inheritance</li>
 *   <li>Supports multi-module Maven projects</li>
 *   <li>Discovers and analyzes all modules automatically</li>
 * </ul>
 */
@Component
public class MavenDependencyResolver implements DependencyResolver {

    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final Logger logger = LoggerFactory.getLogger(MavenDependencyResolver.class.getName());

    @Override
    public List<DependencyNode> resolveDependencies(Path projectPath) {
        try {
            // Find pom.xml
            Path pomFile = findPomFile(projectPath);
            if (pomFile == null) {
                throw new IllegalArgumentException("No pom.xml found in " + projectPath);
            }

            Model rootModel = parseRawModel(pomFile);
            List<String> modules = rootModel.getModules();
            boolean isAggregator = "pom".equals(rootModel.getPackaging()) && modules != null && !modules.isEmpty();

            if (isAggregator) {
                Set<String> reactorGa = new HashSet<>();
                for (String module : modules) {
                    try {
                        Path modulePom = pomFile.getParent().resolve(module).resolve("pom.xml");
                        if (Files.exists(modulePom)) {
                            Model m = parseRawModel(modulePom);
                            String gid = (m.getGroupId() == null || m.getGroupId().isBlank())
                                    ? (m.getParent() != null ? m.getParent().getGroupId() : null)
                                    : m.getGroupId();
                            if (gid != null && m.getArtifactId() != null) {
                                reactorGa.add(gid + ":" + m.getArtifactId());
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                List<DependencyNode> all = new ArrayList<>();
                for (String module : modules) {
                    try {
                        Path modulePom = pomFile.getParent().resolve(module).resolve("pom.xml");
                        if (Files.exists(modulePom)) {
                            all.addAll(analyzeSingleModule(modulePom.getParent(), modulePom, reactorGa));
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to analyze Maven module {}: {}", module, e.getMessage());
                    }
                }
                return all;
            }

            // Initialize repository system
            RepositorySystem repositorySystem = newRepositorySystem();
            RepositorySystemSession session = newRepositorySession(repositorySystem);

            // Create remote repositories
            List<RemoteRepository> repositories = List.of(
                    new RemoteRepository.Builder("central", "default", MAVEN_CENTRAL).build()
            );

            // Parse the POM to get project coordinates
            Model model = parseRawModel(pomFile);
            String groupId = model.getGroupId();
            if (groupId == null || groupId.isBlank()) {
                groupId = model.getParent() != null ? model.getParent().getGroupId() : "unknown";
            }
            String version = model.getVersion();
            if (version == null || version.isBlank()) {
                version = model.getParent() != null ? model.getParent().getVersion() : "1.0.0";
            }
            String packaging = model.getPackaging() != null ? model.getPackaging() : "jar";

            // Create root artifact representing this project with the pom file
            Artifact rootArtifact = new DefaultArtifact(
                groupId,
                model.getArtifactId(),
                "",
                "pom",
                version
            ).setFile(pomFile.toFile());

            // Create collect request with the pom file as root
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRoot(new Dependency(rootArtifact, "compile"));
            collectRequest.setRepositories(repositories);
            
            // Resolve versions from parent POM locally (avoid remote lookups)
            Map<String, String> managedVersions = resolveManagedVersionsFromParent(model, pomFile);
            
            // Add dependencies from POM with resolved versions
            for (org.apache.maven.model.Dependency dep : model.getDependencies()) {
                String depVersion = dep.getVersion();
                String key = dep.getGroupId() + ":" + dep.getArtifactId();
                
                // If no explicit version, get from parent's dependencyManagement
                if ((depVersion == null || depVersion.isBlank()) && managedVersions != null) {
                    depVersion = managedVersions.get(key);
                }
                
                // Skip if still no version (shouldn't happen with proper parent)
                if (depVersion == null || depVersion.isBlank()) {
                    System.out.println("[WARN] No version found for " + dep.getGroupId() + ":" + dep.getArtifactId() + ", skipping");
                    continue;
                }

                String classifier = (dep.getClassifier() != null) ? dep.getClassifier() : "";
                String extension;
                if (dep.getType() != null && !dep.getType().isBlank()) {
                    extension = dep.getType();
                } else if (dep.getArtifactId() != null && dep.getArtifactId().startsWith("spring-boot-starter")) {
                    extension = "pom";
                } else {
                    extension = "jar";
                }

                Artifact depArtifact = new DefaultArtifact(
                    dep.getGroupId(),
                    dep.getArtifactId(),
                    classifier,
                    extension,
                    depVersion
                );
                String scope = (dep.getScope() == null || dep.getScope().isBlank()) ? "compile" : dep.getScope();
                collectRequest.addDependency(new Dependency(depArtifact, scope));
            }

            // Resolve the dependency tree
            org.eclipse.aether.collection.CollectResult collectResult = repositorySystem
                    .collectDependencies(session, collectRequest);
            org.eclipse.aether.graph.DependencyNode aetherRoot = collectResult.getRoot();

            // Now resolve to get the full tree with transitive deps
            DependencyRequest dependencyRequest = new DependencyRequest(aetherRoot, null);
            repositorySystem.resolveDependencies(session, dependencyRequest);

            // Convert to our model - children of root are direct dependencies
            List<DependencyNode> roots = new ArrayList<>();
            java.util.Set<String> declaredDirectGa = new java.util.HashSet<>();
            for (org.apache.maven.model.Dependency d : model.getDependencies()) {
                if (d.getGroupId() != null && d.getArtifactId() != null) {
                    declaredDirectGa.add(d.getGroupId() + ":" + d.getArtifactId());
                }
            }

            for (org.eclipse.aether.graph.DependencyNode child : aetherRoot.getChildren()) {
                Artifact a = child.getArtifact();
                boolean isDirect = false;
                if (a != null) {
                    isDirect = declaredDirectGa.contains(a.getGroupId() + ":" + a.getArtifactId());
                }
                roots.add(convertNode(child, List.of(), isDirect));
            }
            return roots;

        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve Maven dependencies", e);
        }
    }

    @Override
    public boolean supports(Path projectPath) {
        return findPomFile(projectPath) != null;
    }

    @Override
    public BuildTool getBuildTool() {
        return BuildTool.MAVEN;
    }

    private Path findPomFile(Path projectPath) {
        Path pomFile = projectPath.resolve("pom.xml");
        if (Files.exists(pomFile)) {
            return pomFile;
        }
        
        // If the path itself is a pom.xml file
        if (projectPath.getFileName().toString().equals("pom.xml")) {
            return projectPath;
        }
        
        return null;
    }

    private Model parseRawModel(Path pomFile) throws IOException {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try (FileReader fileReader = new FileReader(pomFile.toFile())) {
            return reader.read(fileReader);
        } catch (XmlPullParserException e) {
            throw new IOException("Failed to parse POM file", e);
        }
    }

    private Map<String, String> resolveManagedVersionsFromParent(Model model) {
        Map<String, String> versions = new HashMap<>();
        
        // Recursively resolve parent chain
        Model currentModel = model;
        int level = 0;
        while (currentModel != null && currentModel.getParent() != null) {
            level++;
            org.apache.maven.model.Parent parentInfo = currentModel.getParent();
            Map<String, String> parentVersions = resolveSingleParentPom(parentInfo);
            
            // Parent versions take precedence (add first, so they can be overridden)
            Map<String, String> merged = new HashMap<>(parentVersions);
            merged.putAll(versions);
            versions = merged;
            
            // Move to next parent
            currentModel = loadParentModel(parentInfo);
        }
        return versions;
    }

    private Map<String, String> resolveBomVersions(String groupId, String artifactId, String version) {
        return resolveBomVersions(groupId, artifactId, version, null);
    }

    private Map<String, String> resolveManagedVersionsFromParent(Model model, Path pomFile) {
        Map<String, String> versions = new HashMap<>();

        Model currentModel = model;
        Path currentPom = pomFile;
        while (currentModel != null && currentModel.getParent() != null) {
            org.apache.maven.model.Parent parentInfo = currentModel.getParent();

            Model parentModel = loadParentModel(currentPom, parentInfo);
            if (parentModel == null) {
                break;
            }

            Map<String, String> properties = buildPropertyMap(parentModel);
            if (parentModel.getDependencyManagement() != null) {
                for (org.apache.maven.model.Dependency dep : parentModel.getDependencyManagement().getDependencies()) {
                    if ("import".equals(dep.getScope()) && "pom".equals(dep.getType())) {
                        String bomVersion = dep.getVersion();
                        if (bomVersion != null && !bomVersion.isBlank()) {
                            bomVersion = resolveProperty(bomVersion, properties);
                            if (bomVersion != null && !bomVersion.contains("${")) {
                                Path moduleDir = currentPom.getParent();
                                Path rootDir = moduleDir != null ? moduleDir.getParent() : null;
                                versions.putAll(resolveBomVersions(dep.getGroupId(), dep.getArtifactId(), bomVersion, rootDir));
                            }
                        }
                    } else {
                        String key = dep.getGroupId() + ":" + dep.getArtifactId();
                        String v = dep.getVersion();
                        if (v != null && !v.isBlank()) {
                            v = resolveProperty(v, properties);
                            if (v != null && !v.isBlank() && !v.contains("${")) {
                                versions.putIfAbsent(key, v);
                            }
                        }
                    }
                }
            }

            currentModel = parentModel;

            String rel = parentInfo.getRelativePath();
            if (rel == null || rel.isBlank()) {
                rel = "../pom.xml";
            }
            Path candidate = currentPom.getParent().resolve(rel).normalize();
            currentPom = candidate;
        }

        return versions;
    }
    
    private Model loadParentModel(Path childPom, org.apache.maven.model.Parent parent) {
        if (parent == null) {
            return null;
        }

        try {
            String rel = parent.getRelativePath();
            if (rel == null || rel.isBlank()) {
                rel = "../pom.xml";
            }
            Path localParentPom = childPom.getParent().resolve(rel).normalize();
            if (Files.exists(localParentPom)) {
                return parseRawModel(localParentPom);
            }
        } catch (Exception ignored) {
        }

        return loadParentModel(parent);
    }

    private Model loadParentModel(org.apache.maven.model.Parent parent) {
        if (parent == null) return null;

        try {
            String relativePath = parent.getGroupId().replace('.', '/') + '/' +
                                 parent.getArtifactId() + '/' + parent.getVersion() + '/' +
                                 parent.getArtifactId() + '-' + parent.getVersion() + ".pom";
            File localRepo = new File(System.getProperty("user.home"), ".m2/repository");
            File parentPom = new File(localRepo, relativePath);

            if (!parentPom.exists()) {
                String centralUrl = MAVEN_CENTRAL + relativePath;
                java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(parent.getArtifactId(), ".pom");
                try (java.io.InputStream is = new java.net.URL(centralUrl).openStream()) {
                    java.nio.file.Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                parentPom = tempFile.toFile();
                parentPom.deleteOnExit();
            }

            MavenXpp3Reader reader = new MavenXpp3Reader();
            try (FileReader fileReader = new FileReader(parentPom)) {
                return reader.read(fileReader);
            }
        } catch (Exception e) {
            System.err.println("[WARN] Failed to load parent model: " + e.getMessage());
            return null;
        }
    }
    
    private Map<String, String> resolveSingleParentPom(org.apache.maven.model.Parent parent) {
        Map<String, String> versions = new HashMap<>();
        
        if (parent == null) {
            return versions;
        }
        
        try {
            String parentGroupId = parent.getGroupId();
            String parentArtifactId = parent.getArtifactId();
            String parentVersion = parent.getVersion();
            
            // Build path to parent POM in local repo
            String relativePath = parentGroupId.replace('.', '/') + '/' + 
                                 parentArtifactId + '/' + parentVersion + '/' + 
                                 parentArtifactId + '-' + parentVersion + ".pom";
            File localRepo = new File(System.getProperty("user.home"), ".m2/repository");
            File parentPom = new File(localRepo, relativePath);
            
            // Download from Maven Central if not in local repo
            if (!parentPom.exists()) {
                String centralUrl = MAVEN_CENTRAL + relativePath;
                java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(parentArtifactId, ".pom");
                try (java.io.InputStream is = new java.net.URL(centralUrl).openStream()) {
                    java.nio.file.Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                parentPom = tempFile.toFile();
                parentPom.deleteOnExit();
            }
            
            // Parse parent POM and extract managed versions with property resolution
            MavenXpp3Reader reader = new MavenXpp3Reader();
            try (FileReader fileReader = new FileReader(parentPom)) {
                Model parentModel = reader.read(fileReader);
                
                // Build property map for resolving ${...} references
                Map<String, String> properties = buildPropertyMap(parentModel);
                
                if (parentModel.getDependencyManagement() != null) {
                    for (org.apache.maven.model.Dependency dep : parentModel.getDependencyManagement().getDependencies()) {
                        // Handle BOM imports (scope=import, type=pom)
                        if ("import".equals(dep.getScope()) && "pom".equals(dep.getType())) {
                            String bomVersion = dep.getVersion();
                            if (bomVersion != null && !bomVersion.isBlank()) {
                                bomVersion = resolveProperty(bomVersion, properties);
                                if (!bomVersion.contains("${")) {
                                    Map<String, String> bomVersions = resolveBomVersions(dep.getGroupId(), dep.getArtifactId(), bomVersion);
                                    versions.putAll(bomVersions);
                                }
                            }
                        } else {
                            String key = dep.getGroupId() + ":" + dep.getArtifactId();
                            String version = dep.getVersion();
                            if (version != null && !version.isBlank()) {
                                // Resolve property references like ${jackson-bom.version}
                                version = resolveProperty(version, properties);
                                if (version != null && !version.isBlank() && !version.contains("${")) {
                                    versions.put(key, version);
                                }
                            }
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("[WARN] Failed to resolve parent POM: " + e.getMessage());
        }
        
        return versions;
    }

    private Map<String, String> resolveBomVersions(String groupId, String artifactId, String version, Path localSearchRoot) {
        Map<String, String> versions = new HashMap<>();
        try {
            if (localSearchRoot != null) {
                Path localBomPom = localSearchRoot.resolve(artifactId).resolve("pom.xml");
                if (Files.exists(localBomPom)) {
                    Model bomModel = parseRawModel(localBomPom);
                    Map<String, String> bomProperties = buildPropertyMap(bomModel);

                    if (bomModel.getDependencyManagement() != null) {
                        for (org.apache.maven.model.Dependency dep : bomModel.getDependencyManagement().getDependencies()) {
                            String key = dep.getGroupId() + ":" + dep.getArtifactId();
                            String depVersion = dep.getVersion();
                            if (depVersion != null && !depVersion.isBlank()) {
                                depVersion = resolveProperty(depVersion, bomProperties);
                                if (depVersion != null && !depVersion.isBlank() && !depVersion.contains("${")) {
                                    versions.put(key, depVersion);
                                }
                            }
                        }
                    }
                    return versions;
                }
            }

            String bomPath = groupId.replace('.', '/') + '/' + 
                            artifactId + '/' + version + '/' + 
                            artifactId + '-' + version + ".pom";
            
            // Try local repo first
            File localRepo = new File(System.getProperty("user.home"), ".m2/repository");
            File bomFile = new File(localRepo, bomPath);
            
            // Download from Maven Central if not in local repo
            if (!bomFile.exists()) {
                String centralUrl = MAVEN_CENTRAL + bomPath;
                java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(artifactId, ".pom");
                try (java.io.InputStream is = new java.net.URL(centralUrl).openStream()) {
                    java.nio.file.Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                bomFile = tempFile.toFile();
                bomFile.deleteOnExit();
            }
            
            // Parse BOM and extract versions
            MavenXpp3Reader reader = new MavenXpp3Reader();
            try (FileReader fileReader = new FileReader(bomFile)) {
                Model bomModel = reader.read(fileReader);
                Map<String, String> bomProperties = buildPropertyMap(bomModel);
                
                if (bomModel.getDependencyManagement() != null) {
                    for (org.apache.maven.model.Dependency dep : bomModel.getDependencyManagement().getDependencies()) {
                        String key = dep.getGroupId() + ":" + dep.getArtifactId();
                        String depVersion = dep.getVersion();
                        if (depVersion != null && !depVersion.isBlank()) {
                            depVersion = resolveProperty(depVersion, bomProperties);
                            if (depVersion != null && !depVersion.isBlank() && !depVersion.contains("${")) {
                                versions.put(key, depVersion);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[WARN] Failed to resolve BOM: " + groupId + ":" + artifactId + ":" + version + " - " + e.getMessage());
        }
        return versions;
    }

    private Map<String, String> buildPropertyMap(Model model) {
        Map<String, String> properties = new HashMap<>();
        if (model == null) {
            return properties;
        }

        if (model.getProperties() != null) {
            model.getProperties().forEach((k, v) -> {
                if (k != null && v != null) {
                    properties.put(String.valueOf(k), String.valueOf(v));
                }
            });
        }

        if (model.getVersion() != null) {
            properties.putIfAbsent("project.version", model.getVersion());
            properties.putIfAbsent("pom.version", model.getVersion());
        }
        if (model.getGroupId() != null) {
            properties.putIfAbsent("project.groupId", model.getGroupId());
            properties.putIfAbsent("pom.groupId", model.getGroupId());
        }
        if (model.getArtifactId() != null) {
            properties.putIfAbsent("project.artifactId", model.getArtifactId());
            properties.putIfAbsent("pom.artifactId", model.getArtifactId());
        }

        return properties;
    }

    private String resolveProperty(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        
        // Replace ${property.name} with actual value
        String result = value;
        if (properties != null) {
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                String placeholder = "${" + entry.getKey() + "}";
                if (result.contains(placeholder)) {
                    result = result.replace(placeholder, entry.getValue());
                }
            }
        }
        return result;
    }

    private DependencyNode convertNode(
            org.eclipse.aether.graph.DependencyNode aetherNode,
            List<String> pathFromRoot,
            boolean direct
    ) {
        Artifact artifact = aetherNode.getArtifact();
        String scope = "compile";
        if (aetherNode.getDependency() != null && aetherNode.getDependency().getScope() != null) {
            scope = aetherNode.getDependency().getScope();
        }
        
        DependencyCoordinate coordinate = new DependencyCoordinate(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion(),
                "maven",
                scope,
                direct
        );

        DependencyNode node = new DependencyNode(coordinate, scope, pathFromRoot, direct, ResolutionConfidence.HIGH);

        List<String> childPath = new ArrayList<>(pathFromRoot);
        childPath.add(coordinate.toString());
        for (org.eclipse.aether.graph.DependencyNode child : aetherNode.getChildren()) {
            node.addChild(convertNode(child, childPath, false));
        }
        return node;
    }

    private RepositorySystem newRepositorySystem() {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

        RepositorySystem system = locator.getService(RepositorySystem.class);
        if (system == null) {
            throw new IllegalStateException("Failed to initialize Maven Resolver RepositorySystem (service locator returned null)");
        }
        return system;
    }

    private RepositorySystemSession newRepositorySession(RepositorySystem system) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = new LocalRepository(System.getProperty("user.home") + File.separator + ".m2" + File.separator + "repository");
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        return session;
    }

    /**
     * Discovers Maven modules from pom.xml.
     */
    private List<String> discoverModules(Path pomFile) {
        try {
            Model model = parseRawModel(pomFile);
            List<String> modules = model.getModules();
            
            if (modules == null || modules.isEmpty()) {
                logger.debug("No modules found in pom.xml");
                return new ArrayList<>();
            }
            
            logger.debug("Discovered {} modules: {}", modules.size(), modules);
            return new ArrayList<>(modules);
            
        } catch (Exception e) {
            logger.warn("Failed to discover modules: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Analyzes a single Maven module.
     */
    private List<DependencyNode> analyzeSingleModule(Path modulePath, Path pomFile) {
        return analyzeSingleModule(modulePath, pomFile, Set.of());
    }

    private List<DependencyNode> analyzeSingleModule(Path modulePath, Path pomFile, Set<String> reactorGa) {
        try {
            // Initialize repository system
            RepositorySystem repositorySystem = newRepositorySystem();
            RepositorySystemSession session = newRepositorySession(repositorySystem);
            
            // Create remote repositories
            List<RemoteRepository> repositories = List.of(
                    new RemoteRepository.Builder("central", "default", MAVEN_CENTRAL).build()
            );
            
            // Parse the POM to get project coordinates
            Model model = parseRawModel(pomFile);
            String groupId = model.getGroupId();
            if (groupId == null || groupId.isBlank()) {
                groupId = model.getParent() != null ? model.getParent().getGroupId() : "unknown";
            }
            String version = model.getVersion();
            if (version == null || version.isBlank()) {
                version = model.getParent() != null ? model.getParent().getVersion() : "1.0.0";
            }
            String packaging = model.getPackaging() != null ? model.getPackaging() : "jar";

            Map<String, String> properties = buildPropertyMap(model);
            if (model.getParent() != null) {
                Model parentModel = loadParentModel(pomFile, model.getParent());
                if (parentModel != null) {
                    Map<String, String> parentProps = buildPropertyMap(parentModel);
                    parentProps.forEach(properties::putIfAbsent);

                    if ((version == null || version.isBlank()) && parentModel.getVersion() != null) {
                        version = parentModel.getVersion();
                    }
                }
            }

            if (version != null && !version.isBlank()) {
                properties.putIfAbsent("project.version", version);
                properties.putIfAbsent("pom.version", version);
            }
            
            // Skip BOM projects (packaging=pom) with no dependencies
            if ("pom".equals(packaging)) {
                // Check if this is a BOM project (dependency management only)
                boolean hasDependencyManagement = model.getDependencyManagement() != null && 
                                                  !model.getDependencyManagement().getDependencies().isEmpty();
                boolean hasActualDependencies = model.getDependencies() != null && 
                                              !model.getDependencies().isEmpty();
                
                if (!hasActualDependencies && hasDependencyManagement) {
                    logger.info("Skipping BOM project (dependency management only): {}", model.getArtifactId());
                    return new ArrayList<>();
                }
                
                // If it has actual dependencies, analyze it normally
                if (!hasActualDependencies) {
                    logger.info("BOM project has no runtime dependencies, skipping: {}", model.getArtifactId());
                    return new ArrayList<>();
                }
            }

            // Create root artifact representing this project with pom file
            // Use "pom" type and empty classifier so Aether treats it as a POM project
            Artifact rootArtifact = new DefaultArtifact(
                groupId,
                model.getArtifactId(),
                "",
                "pom",
                version
            ).setFile(pomFile.toFile());

            // Create collect request WITHOUT setting a root artifact
            // We'll add dependencies directly - this prevents Aether from trying to
            // resolve the local module artifact from Maven Central
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRepositories(repositories);
            
            // DON'T set a root dependency - this was causing Aether to try to download
            // the module artifact itself from Central. Instead, we'll collect
            // dependencies starting from an empty root.
            // collectRequest.setRoot(new Dependency(rootArtifact, "compile"));

            // Resolve versions from parent POM locally (avoid remote lookups)
            Map<String, String> managedVersions = resolveManagedVersionsFromParent(model, pomFile);
            
            // Add dependencies from POM with resolved versions
            for (org.apache.maven.model.Dependency dep : model.getDependencies()) {
                String depVersion = dep.getVersion();
                String key = dep.getGroupId() + ":" + dep.getArtifactId();

                // Skip reactor (multi-module) dependencies - they are analyzed as modules separately
                if (reactorGa != null && reactorGa.contains(key)) {
                    continue;
                }

                if (depVersion != null && !depVersion.isBlank()) {
                    depVersion = resolveProperty(depVersion, properties);
                }
                if ((depVersion == null || depVersion.isBlank()) && managedVersions.containsKey(key)) {
                    depVersion = managedVersions.get(key);
                }

                if (depVersion != null && depVersion.contains("${")) {
                    depVersion = resolveProperty(depVersion, properties);
                }
                
                Artifact depArtifact = new DefaultArtifact(
                    dep.getGroupId(),
                    dep.getArtifactId(),
                    dep.getType() != null ? dep.getType() : "jar",
                    depVersion != null ? depVersion : "LATEST"
                );
                String scope = (dep.getScope() == null || dep.getScope().isBlank()) ? "compile" : dep.getScope();
                collectRequest.addDependency(new Dependency(depArtifact, scope));
            }
            
            // Collect and resolve dependencies
            org.eclipse.aether.collection.CollectResult collectResult = repositorySystem
                    .collectDependencies(session, collectRequest);
            org.eclipse.aether.graph.DependencyNode aetherRoot = collectResult.getRoot();

            // Now resolve to get the full tree with transitive deps
            DependencyRequest dependencyRequest = new DependencyRequest(aetherRoot, null);
            repositorySystem.resolveDependencies(session, dependencyRequest);
            
            // Convert to our model - children of root are direct dependencies
            List<DependencyNode> roots = new ArrayList<>();
            java.util.Set<String> declaredDirectGa = new java.util.HashSet<>();
            for (org.apache.maven.model.Dependency d : model.getDependencies()) {
                if (d.getGroupId() != null && d.getArtifactId() != null) {
                    declaredDirectGa.add(d.getGroupId() + ":" + d.getArtifactId());
                }
            }

            for (org.eclipse.aether.graph.DependencyNode child : aetherRoot.getChildren()) {
                Artifact a = child.getArtifact();
                boolean isDirect = false;
                if (a != null) {
                    isDirect = declaredDirectGa.contains(a.getGroupId() + ":" + a.getArtifactId());
                }
                roots.add(convertNode(child, List.of(), isDirect));
            }
            return roots;
            
        } catch (Exception e) {
            logger.error("Failed to analyze Maven module {}: {}", modulePath, e.getMessage());
            return new ArrayList<>();
        }
    }
}
