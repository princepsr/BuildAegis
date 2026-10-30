package com.riskscanner.dependencyriskanalyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort dependency metadata enrichment.
 *
 * <p>Current enrichment sources:
 * <ul>
 *   <li>OSV.dev: vulnerability IDs and count</li>
 *   <li>Maven Central POM: SCM information (URL/connection)</li>
 *   <li>GitHub API: stars/open issues/last pushed time (only when SCM URL points to GitHub)</li>
 * </ul>
 *
 * <p>Failure behavior:
 * <ul>
 *   <li>Network/parse errors are swallowed and represented as missing enrichment fields.</li>
 *   <li>This service must not block the core scan/analyze flow; treat enrichment as optional.</li>
 * </ul>
 */
@Service
public class MetadataEnrichmentService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MetadataEnrichmentService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Enriches a dependency coordinate.
     *
     * <p>Currently only Maven coordinates are enriched (because OSV and Maven Central use Maven identifiers).
     * Gradle dependencies that are not Maven coordinates return an object with mostly-null fields.
     */
    public DependencyEnrichmentDto enrich(DependencyCoordinate dependency) {
        if (dependency == null) {
            throw new IllegalArgumentException("dependency must not be null");
        }

        if (!"maven".equalsIgnoreCase(dependency.buildTool())) {
            return new DependencyEnrichmentDto(
                    null,
                    null,
                    dependency.version(),
                    null,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        String ecosystem = "Maven";
        String packageName = dependency.groupId() + ":" + dependency.artifactId();

        OsvResult osv = queryOsv(ecosystem, packageName, dependency.version());
        ScmResult scm = fetchScmFromMavenCentralPom(dependency.groupId(), dependency.artifactId(), dependency.version());

        GithubResult gh = null;
        if (scm.githubRepo != null) {
            gh = fetchGithubRepo(scm.githubRepo);
        }

        return new DependencyEnrichmentDto(
                ecosystem,
                packageName,
                dependency.version(),
                osv.vulnerabilityCount,
                osv.vulnerabilityIds,
                scm.scmUrl,
                scm.githubRepo,
                gh == null ? null : gh.stars,
                gh == null ? null : gh.openIssues,
                gh == null ? null : gh.lastPushedAt,
                osv.suggestedFixVersion
        );
    }

    private OsvResult queryOsv(String ecosystem, String packageName, String version) {
        try {
            String body = "{\"package\":{\"ecosystem\":\"" + ecosystem + "\",\"name\":\"" + escapeJson(packageName) + "\"},\"version\":\"" + escapeJson(version) + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.osv.dev/v1/query"))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "RiskScanner")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return new OsvResult(null, List.of(), null);
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode vulns = root.path("vulns");
            if (!vulns.isArray()) {
                return new OsvResult(0, List.of(), null);
            }

            List<List<String>> fixedPerVuln = new ArrayList<>();
            for (JsonNode v : vulns) {
                fixedPerVuln.add(extractFixedVersions(v, packageName));
            }
            String suggested = UpgradeAdvisor.suggestFixVersion(version, fixedPerVuln);

            List<String> ids = new ArrayList<>();
            for (JsonNode v : vulns) {
                String id = v.path("id").asText(null);
                if (id != null) {
                    ids.add(id);
                }
                if (ids.size() >= 8) {
                    break;
                }
            }
            return new OsvResult(vulns.size(), ids, suggested);
        } catch (Exception e) {
            return new OsvResult(null, List.of(), null);
        }
    }

    private ScmResult fetchScmFromMavenCentralPom(String groupId, String artifactId, String version) {
        try {
            String base = "https://repo1.maven.org/maven2/";
            String groupPath = groupId.replace('.', '/');
            String pomUrl = base + groupPath + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".pom";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pomUrl))
                    .header("User-Agent", "RiskScanner")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return new ScmResult(null, null);
            }

            String pom = response.body();
            String scmUrl = extractFirstTagValue(pom, "url", "scm");
            if (scmUrl == null) {
                scmUrl = extractFirstTagValue(pom, "connection", "scm");
            }

            String githubRepo = scmUrl == null ? null : parseGithubRepo(scmUrl);
            return new ScmResult(scmUrl, githubRepo);
        } catch (Exception e) {
            return new ScmResult(null, null);
        }
    }

    private GithubResult fetchGithubRepo(String githubRepo) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + githubRepo))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "RiskScanner")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            Integer stars = root.path("stargazers_count").isNumber() ? root.get("stargazers_count").asInt() : null;
            Integer openIssues = root.path("open_issues_count").isNumber() ? root.get("open_issues_count").asInt() : null;
            Instant pushedAt = root.path("pushed_at").isTextual() ? Instant.parse(root.get("pushed_at").asText()) : null;

            return new GithubResult(stars, openIssues, pushedAt);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseGithubRepo(String scmUrl) {
        String s = scmUrl;
        s = s.replace("scm:git:", "");
        s = s.replace("scm:git@", "");
        s = s.replace("git@github.com:", "https://github.com/");
        s = s.replace("git://github.com/", "https://github.com/");
        s = s.replace("ssh://git@github.com/", "https://github.com/");
        if (s.contains("github.com")) {
            int idx = s.indexOf("github.com");
            s = s.substring(idx + "github.com".length());
            if (s.startsWith(":")) s = s.substring(1);
            if (s.startsWith("/")) s = s.substring(1);
            if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
            String[] parts = s.split("/");
            if (parts.length >= 2) {
                return parts[0] + "/" + parts[1];
            }
        }
        return null;
    }

    private String extractFirstTagValue(String xml, String tagName, String parentTag) {
        String parentStart = "<" + parentTag;
        int pIdx = xml.indexOf(parentStart);
        if (pIdx < 0) {
            return null;
        }
        int pEnd = xml.indexOf("</" + parentTag + ">", pIdx);
        if (pEnd < 0) {
            return null;
        }
        String region = xml.substring(pIdx, pEnd);
        String open = "<" + tagName + ">";
        String close = "</" + tagName + ">";
        int s = region.indexOf(open);
        int e = region.indexOf(close);
        if (s < 0 || e < 0 || e <= s) {
            return null;
        }
        return region.substring(s + open.length(), e).trim();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<String> extractFixedVersions(JsonNode vuln, String packageName) {
        List<String> fixed = new ArrayList<>();
        for (JsonNode affected : vuln.path("affected")) {
            if (!packageName.equals(affected.path("package").path("name").asText())) {
                continue;
            }
            for (JsonNode range : affected.path("ranges")) {
                for (JsonNode event : range.path("events")) {
                    String f = event.path("fixed").asText(null);
                    if (f != null) {
                        fixed.add(f);
                    }
                }
            }
        }
        return fixed;
    }

    private record OsvResult(Integer vulnerabilityCount, List<String> vulnerabilityIds, String suggestedFixVersion) {}

    private record ScmResult(String scmUrl, String githubRepo) {}

    private record GithubResult(Integer stars, Integer openIssues, Instant lastPushedAt) {}
}
