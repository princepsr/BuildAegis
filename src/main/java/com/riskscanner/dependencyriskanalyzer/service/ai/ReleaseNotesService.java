package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Fetches the GitHub release notes of a library version, so the AI can describe upgrade risks from real changelog text
 * instead of guessing from memory.
 *
 * <p>Best-effort: a missing release, a network problem or GitHub's anonymous rate limit just means no notes. Results
 * (including "not found") are cached for the lifetime of the application.
 */
@Service
public class ReleaseNotesService {

    private static final Pattern SAFE_REPO = Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9_.+-]{1,60}");
    static final int MAX_NOTES_LENGTH = 3000;
    private static final int MAX_CACHE_ENTRIES = 500;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    /** Created on first use so that constructing the service needs no network stack. */
    private volatile HttpClient httpClient;

    /**
     * @param githubRepo repository as {@code owner/name}
     * @param version    library version the notes are wanted for
     * @return the release notes text (limited in size), or null if none could be found
     */
    public String fetch(String githubRepo, String version) {
        if (githubRepo == null || version == null
                || !SAFE_REPO.matcher(githubRepo).matches() || !SAFE_VERSION.matcher(version).matches()) {
            // the repo comes from a POM we do not control: never put unvalidated text into a URL
            return null;
        }

        String key = githubRepo + "@" + version;
        Optional<String> cached = cache.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }

        String notes = null;
        for (String tag : tagCandidates(version)) {
            try {
                String json = fetchJson("https://api.github.com/repos/" + githubRepo + "/releases/tags/" + tag);
                if (json != null) {
                    notes = extractNotes(objectMapper.readTree(json));
                    if (notes != null) {
                        break;
                    }
                }
            } catch (Exception e) {
                // best effort
            }
        }

        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, Optional.ofNullable(notes));
        return notes;
    }

    static List<String> tagCandidates(String version) {
        String bare = version.startsWith("v") ? version.substring(1) : version;
        return List.of("v" + bare, bare);
    }

    /** Release name and body from a GitHub "release by tag" response, limited in size; null if there is no text. */
    static String extractNotes(JsonNode release) {
        String body = release.path("body").asText("").trim();
        if (body.isEmpty()) {
            return null;
        }
        String name = release.path("name").asText("").trim();
        String text = name.isEmpty() ? body : name + "\n" + body;
        return text.length() > MAX_NOTES_LENGTH ? text.substring(0, MAX_NOTES_LENGTH) + "\n[truncated]" : text;
    }

    /** Overridable for tests. Returns the response body, or null when the release does not exist (404). */
    String fetchJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "BuildAegis")
                .GET()
                .build();
        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private HttpClient client() {
        HttpClient c = httpClient;
        if (c == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                }
                c = httpClient;
            }
        }
        return c;
    }
}
