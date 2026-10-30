package com.riskscanner.dependencyriskanalyzer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.service.vulnerability.ExploitDataService;
import com.riskscanner.dependencyriskanalyzer.service.vulnerability.OsvAdvisoryParser;
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
    /** Created on first use, so constructing the service needs no network stack. */
    private volatile HttpClient httpClient;
    private final ExploitDataService exploitDataService;

    public MetadataEnrichmentService(ObjectMapper objectMapper, ExploitDataService exploitDataService) {
        this.exploitDataService = exploitDataService;
        this.objectMapper = objectMapper;
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
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        String ecosystem = "Maven";
        String packageName = dependency.groupId() + ":" + dependency.artifactId();

        OsvAdvisoryParser.Summary osv = queryOsv(ecosystem, packageName, dependency.version());
        ExploitDataService.ExploitInfo exploit = exploitDataService.lookup(osv.cveIds());
        ScmResult scm = fetchScmFromMavenCentralPom(dependency.groupId(), dependency.artifactId(), dependency.version());

        GithubResult gh = null;
        if (scm.githubRepo != null) {
            gh = fetchGithubRepo(scm.githubRepo);
        }

        return new DependencyEnrichmentDto(
                ecosystem,
                packageName,
                dependency.version(),
                osv.vulnerabilityCount(),
                osv.vulnerabilityIds(),
                scm.scmUrl,
                scm.githubRepo,
                gh == null ? null : gh.stars,
                gh == null ? null : gh.openIssues,
                gh == null ? null : gh.lastPushedAt,
                osv.suggestedFixVersion(),
                osv.maxSeverity(),
                osv.severityCounts().isEmpty() ? null : osv.severityCounts(),
                osv.vulnerabilityCount() == null ? null : exploit.knownExploited(),
                exploit.maxEpss()
        );
    }

    private HttpClient client() {
        HttpClient c = httpClient;
        if (c == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newHttpClient();
                }
                c = httpClient;
            }
        }
        return c;
    }

    private OsvAdvisoryParser.Summary queryOsv(String ecosystem, String packageName, String version) {
        try {
            String body = "{\"package\":{\"ecosystem\":\"" + ecosystem + "\",\"name\":\"" + escapeJson(packageName) + "\"},\"version\":\"" + escapeJson(version) + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.osv.dev/v1/query"))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "RiskScanner")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return OsvAdvisoryParser.Summary.unknown();
            }
            return OsvAdvisoryParser.parse(objectMapper.readTree(response.body()), packageName, version);
        } catch (Exception e) {
            return OsvAdvisoryParser.Summary.unknown();
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

            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
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

            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
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

    private record ScmResult(String scmUrl, String githubRepo) {}

    private record GithubResult(Integer stars, Integer openIssues, Instant lastPushedAt) {}
}
