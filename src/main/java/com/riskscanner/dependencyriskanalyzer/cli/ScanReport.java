package com.riskscanner.dependencyriskanalyzer.cli;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyRiskDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates the result of a scan for command-line / CI use: severity per dependency, summary counts, the overall
 * risk score and which findings reach the "fail on" threshold.
 *
 * <p>The rules are the same as in the web UI (see {@code docs/architecture/risk-scoring.md}).
 */
public final class ScanReport {

    private ScanReport() {
    }

    /** Severity of a finding, ordered from least to most severe. */
    public enum Level {
        LOW, MEDIUM, HIGH, CRITICAL;

        /** Parses a threshold such as {@code high} (case-insensitive); {@code none} or blank means no threshold. */
        public static Level parseThreshold(String value) {
            if (value == null || value.isBlank() || value.equalsIgnoreCase("none")) {
                return null;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid fail-on value '" + value + "'. Use none, low, medium, high or critical.");
            }
        }
    }

    public record Finding(String dependency, Level severity, int vulnerabilities, Integer riskScore,
                          String fixVersion, boolean knownExploited) {
    }

    /**
     * @param uncheckedDependencies dependencies whose vulnerabilities could not be looked up (source unreachable or build
     *                              tool not supported), so "no findings" says nothing about them
     * @param counts                number of dependencies per severity (all dependencies, including clean ones as LOW)
     * @param overallRiskScore      0-100, see {@link #overallRiskScore(List)}
     * @param failOn                the threshold, or null if none was set
     * @param failing               dependencies with known vulnerabilities at or above the threshold, most severe first
     * @param failed                true if {@code failing} is not empty
     */
    public record Result(String projectPath, int totalDependencies, int vulnerableDependencies, int uncheckedDependencies,
                         Map<Level, Integer> counts, int overallRiskScore, Level failOn,
                         List<Finding> failing, boolean failed) {
    }

    public static Result evaluate(String projectPath, List<DependencyRiskDto> dependencies, Level failOn) {
        List<DependencyRiskDto> deps = dependencies == null ? List.of() : dependencies;

        Map<Level, Integer> counts = new EnumMap<>(Level.class);
        for (Level l : Level.values()) {
            counts.put(l, 0);
        }

        int vulnerable = 0;
        int unchecked = 0;
        List<Finding> failing = new ArrayList<>();
        for (DependencyRiskDto d : deps) {
            if (d.enrichment() == null || d.enrichment().vulnerabilityCount() == null) {
                unchecked++;
            }
            Level severity = severityOf(d);
            counts.merge(severity, 1, Integer::sum);

            int vulns = vulnerabilityCount(d);
            if (vulns > 0) {
                vulnerable++;
                // Only dependencies that actually have vulnerabilities can fail the build; clean ones are LOW by default
                if (failOn != null && severity.compareTo(failOn) >= 0) {
                    failing.add(new Finding(
                            d.groupId() + ":" + d.artifactId() + ":" + d.version(),
                            severity,
                            vulns,
                            d.riskScore(),
                            d.enrichment() == null ? null : d.enrichment().suggestedFixVersion(),
                            d.enrichment() != null && Boolean.TRUE.equals(d.enrichment().knownExploited())));
                }
            }
        }

        failing.sort(Comparator.comparing(Finding::severity).reversed()
                .thenComparing(Finding::riskScore, Comparator.nullsLast(Comparator.reverseOrder())));

        return new Result(projectPath, deps.size(), vulnerable, unchecked, counts, overallRiskScore(deps), failOn, failing, !failing.isEmpty());
    }

    /**
     * Severity of one dependency: CRITICAL if a vulnerability is exploited in the wild (CISA KEV); otherwise the highest
     * real advisory severity; without severity data, 10+ vulnerabilities is CRITICAL, then the backend risk level, and as
     * a last resort the vulnerability count.
     */
    public static Level severityOf(DependencyRiskDto d) {
        int vulns = vulnerabilityCount(d);
        var enrichment = d.enrichment();

        if (enrichment != null && Boolean.TRUE.equals(enrichment.knownExploited())) {
            return Level.CRITICAL;
        }
        if (vulns > 0 && enrichment != null && enrichment.maxSeverity() != null) {
            try {
                return Level.valueOf(enrichment.maxSeverity().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // unknown label, fall through to the other rules
            }
        }
        if (vulns >= 10) {
            return Level.CRITICAL;
        }
        String backend = d.riskLevel() == null ? "" : d.riskLevel().name();
        switch (backend) {
            case "HIGH":
                return Level.HIGH;
            case "MEDIUM":
                return Level.MEDIUM;
            case "LOW":
                return Level.LOW;
            default:
                break;
        }
        if (vulns >= 5) {
            return Level.HIGH;
        }
        return vulns >= 2 ? Level.MEDIUM : Level.LOW;
    }

    /**
     * Overall risk score: the highest per-dependency score plus 1 point for each additional vulnerable dependency
     * (at most +5), capped at 100. Zero for an empty project.
     */
    public static int overallRiskScore(List<DependencyRiskDto> deps) {
        if (deps == null || deps.isEmpty()) {
            return 0;
        }
        int base = deps.stream().mapToInt(d -> d.riskScore() == null ? 0 : d.riskScore()).max().orElse(0);
        long vulnerable = deps.stream().filter(d -> vulnerabilityCount(d) > 0).count();
        return (int) Math.min(100, base + Math.min(5, Math.max(0, vulnerable - 1)));
    }

    /** Human-readable report for the console. */
    public static String toText(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("BuildAegis scan: ").append(r.projectPath()).append('\n');
        sb.append("Dependencies: ").append(r.totalDependencies())
                .append(", with known vulnerabilities: ").append(r.vulnerableDependencies()).append('\n');
        sb.append("Critical: ").append(r.counts().get(Level.CRITICAL))
                .append("  High: ").append(r.counts().get(Level.HIGH))
                .append("  Medium: ").append(r.counts().get(Level.MEDIUM))
                .append("  Low: ").append(r.counts().get(Level.LOW))
                .append("  Overall risk score: ").append(r.overallRiskScore()).append('\n');

        if (r.uncheckedDependencies() > 0) {
            sb.append("WARNING: ").append(r.uncheckedDependencies()).append(" of ").append(r.totalDependencies())
                    .append(" dependencies could not be checked for vulnerabilities (vulnerability source unreachable, or build tool not supported).\n");
        }

        if (r.failOn() == null) {
            sb.append("Result: no fail-on threshold set (exit code 0 unless the scan itself fails)\n");
            return sb.toString();
        }

        sb.append("Findings at or above ").append(r.failOn()).append(": ").append(r.failing().size()).append('\n');
        for (Finding f : r.failing()) {
            sb.append("  ").append(String.format("%-8s", f.severity())).append(' ').append(f.dependency())
                    .append("  vulnerabilities=").append(f.vulnerabilities());
            if (f.riskScore() != null) {
                sb.append("  risk=").append(f.riskScore());
            }
            if (f.fixVersion() != null) {
                sb.append("  fix=").append(f.fixVersion());
            }
            if (f.knownExploited()) {
                sb.append("  [exploited in the wild]");
            }
            sb.append('\n');
        }
        if (r.failed()) {
            sb.append("Result: FAILED\n");
        } else if (r.uncheckedDependencies() > 0) {
            // "no findings" is not a pass when some dependencies could not even be checked
            sb.append("Result: INCOMPLETE (").append(r.uncheckedDependencies()).append(" dependencies could not be checked)\n");
        } else {
            sb.append("Result: PASSED\n");
        }
        return sb.toString();
    }

    private static int vulnerabilityCount(DependencyRiskDto d) {
        return d.enrichment() == null || d.enrichment().vulnerabilityCount() == null ? 0 : d.enrichment().vulnerabilityCount();
    }
}
