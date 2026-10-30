package com.riskscanner.dependencyriskanalyzer.dto;

import java.time.Instant;
import java.util.List;

/**
 * Optional metadata attached to a dependency.
 *
 * <p>Populated by {@code MetadataEnrichmentService} (best-effort):
 * <ul>
 *   <li>OSV vulnerability count and IDs</li>
 *   <li>Maven Central POM SCM URL</li>
 *   <li>GitHub repository statistics when SCM points to GitHub</li>
 * </ul>
 */
public record DependencyEnrichmentDto(
        String ecosystem,
        String packageName,
        String version,
        Integer vulnerabilityCount,
        List<String> vulnerabilityIds,
        String scmUrl,
        String githubRepo,
        Integer githubStars,
        Integer githubOpenIssues,
        Instant githubLastPushedAt,
        String suggestedFixVersion
) {
}
