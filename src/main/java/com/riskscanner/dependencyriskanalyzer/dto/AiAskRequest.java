package com.riskscanner.dependencyriskanalyzer.dto;

import java.util.List;

/**
 * A developer's question about one finding, together with the finding's details as shown in the UI.
 *
 * <p>Only what is in this request is sent to the AI: BuildAegis does not send project source code.
 *
 * @param dependency         {@code groupId:artifactId:version}
 * @param severity           CRITICAL / HIGH / MEDIUM / LOW
 * @param vulnerabilityCount number of known vulnerabilities
 * @param vulnerabilityIds   advisory ids, for example CVE-2024-1234
 * @param knownExploited     true if exploited in the wild (CISA KEV)
 * @param fixVersion         smallest version fixing all known vulnerabilities, if known
 * @param explanation        the risk explanation shown for the finding
 * @param recommendations    the recommendations shown for the finding
 * @param question           the question to answer (at most 500 characters)
 */
public record AiAskRequest(
        String dependency,
        String severity,
        Integer vulnerabilityCount,
        List<String> vulnerabilityIds,
        Boolean knownExploited,
        String fixVersion,
        String explanation,
        List<String> recommendations,
        String question
) {
}
