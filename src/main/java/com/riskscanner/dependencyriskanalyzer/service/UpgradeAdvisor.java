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
     * Picks the smallest version that fixes the given vulnerabilities, preferring a version on the same major line
     * as {@code current}: for each vulnerability the lowest fixed version above {@code current}, then the highest of
     * those. Only if no vulnerability has a fix on the current major line, the lowest fix on any line is used.
     * Use {@link #fixNote} to learn which vulnerabilities the suggestion does not cover.
     *
     * @param fixedVersionsPerVulnerability for each vulnerability, the list of versions in which it was fixed
     * @return the suggested version, or {@code null} when none is known
     */
    public static String suggestFixVersion(String current, List<List<String>> fixedVersionsPerVulnerability) {
        if (current == null) {
            return null;
        }
        String sameLine = pick(current, fixedVersionsPerVulnerability, true);
        return sameLine != null ? sameLine : pick(current, fixedVersionsPerVulnerability, false);
    }

    /**
     * Explains vulnerabilities that the suggested version does not fix because they are only fixed on another
     * major line, or {@code null} when the suggestion covers everything that has a known fix.
     */
    public static String fixNote(String suggested, List<List<String>> fixedVersionsPerVulnerability) {
        if (suggested == null) {
            return null;
        }
        int remaining = 0;
        String needed = null;
        for (List<String> fixes : fixedVersionsPerVulnerability) {
            boolean covered = false;
            for (String f : fixes) {
                if (sameMajor(f, suggested) && compareVersions(f, suggested) <= 0) {
                    covered = true;
                    break;
                }
            }
            if (covered || fixes.isEmpty()) {
                continue;
            }
            remaining++;
            String lowest = null;
            for (String f : fixes) {
                if (lowest == null || compareVersions(f, lowest) < 0) {
                    lowest = f;
                }
            }
            if (needed == null || compareVersions(lowest, needed) > 0) {
                needed = lowest;
            }
        }
        if (remaining == 0) {
            return null;
        }
        List<String> parts = numericParts(suggested);
        String line = parts.size() > 1 ? parts.get(0) + "." + parts.get(1) + ".x" : parts.isEmpty() ? suggested : parts.get(0) + ".x";
        return remaining + " of " + fixedVersionsPerVulnerability.size() + " vulnerabilities are not fixed in this minor version (" + line
                + "); fixing them needs a major upgrade to " + needed + ".";
    }

    private static String pick(String current, List<List<String>> fixedPerVuln, boolean sameMajorOnly) {
        String best = null;
        for (List<String> fixes : fixedPerVuln) {
            String lowestAbove = null;
            for (String f : fixes) {
                if (sameMajorOnly && !sameMajor(f, current)) {
                    continue;
                }
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

    private static boolean sameMajor(String a, String b) {
        List<String> x = numericParts(a);
        List<String> y = numericParts(b);
        return !x.isEmpty() && !y.isEmpty() && x.get(0).equals(y.get(0));
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
