package com.riskscanner.dependencyriskanalyzer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic helpers for suggesting an upgrade target and describing the kind of behaviour change
 * to expect when moving between two versions.
 */
public final class UpgradeAdvisor {

    private static final Pattern SPLIT = Pattern.compile("[.\\-+_]");

    private UpgradeAdvisor() {
    }

    /**
     * Compares two version strings. Numeric segments are compared numerically; a version with a
     * pre-release qualifier (e.g. {@code 1.0-beta}) sorts before the same version without one.
     */
    public static int compareVersions(String a, String b) {
        String[] x = SPLIT.split(a);
        String[] y = SPLIT.split(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            boolean pNum = p.matches("\\d+");
            boolean qNum = q.matches("\\d+");
            int c;
            if (pNum && qNum) {
                c = Long.compare(Long.parseLong(p), Long.parseLong(q));
            } else if (pNum) {
                c = 1;
            } else if (qNum) {
                c = -1;
            } else {
                c = p.compareToIgnoreCase(q);
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /**
     * Picks the smallest version that fixes all given vulnerabilities: for each vulnerability the lowest
     * fixed version above {@code current}, then the highest of those.
     *
     * @param fixedVersionsPerVulnerability for each vulnerability, the list of versions in which it was fixed
     * @return the suggested version, or {@code null} when none is known
     */
    public static String suggestFixVersion(String current, List<List<String>> fixedVersionsPerVulnerability) {
        if (current == null) {
            return null;
        }
        String best = null;
        for (List<String> fixes : fixedVersionsPerVulnerability) {
            String lowestAbove = null;
            for (String f : fixes) {
                if (compareVersions(f, current) > 0 && (lowestAbove == null || compareVersions(f, lowestAbove) < 0)) {
                    lowestAbove = f;
                }
            }
            if (lowestAbove != null && (best == null || compareVersions(lowestAbove, best) > 0)) {
                best = lowestAbove;
            }
        }
        return best;
    }

    /**
     * Describes the expected behaviour-change risk of upgrading from {@code current} to {@code target},
     * based on which version segment changes.
     */
    public static String describeUpgrade(String current, String target) {
        List<String> a = numericParts(current);
        List<String> b = numericParts(target);
        String base = "Upgrade to " + target + " (smallest known version fixing the reported vulnerabilities). ";
        if (!a.isEmpty() && !b.isEmpty() && !a.get(0).equals(b.get(0))) {
            return base + "This is a major version change: expect breaking API or behaviour changes; review the release notes and run your tests.";
        }
        if (a.size() > 1 && b.size() > 1 && !a.get(1).equals(b.get(1))) {
            return base + "This is a minor version change: usually backward compatible, but check release notes for deprecations and changed defaults.";
        }
        return base + "This looks like a patch-level change: behaviour changes are unlikely.";
    }

    private static List<String> numericParts(String version) {
        List<String> parts = new ArrayList<>();
        for (String s : SPLIT.split(version)) {
            if (s.matches("\\d+")) {
                parts.add(String.valueOf(Long.parseLong(s)));
            } else {
                break;
            }
        }
        return parts;
    }
}
