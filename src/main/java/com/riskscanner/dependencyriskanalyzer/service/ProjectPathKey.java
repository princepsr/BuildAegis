package com.riskscanner.dependencyriskanalyzer.service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;

/**
 * Normalizes a project path (folder or build file) into a stable key, so that analyzing
 * {@code C:/proj}, {@code C:\proj\} and {@code C:/proj/pom.xml} all refer to the same project.
 */
public final class ProjectPathKey {

    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");

    private ProjectPathKey() {
    }

    public static String of(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return "";
        }
        String trimmed = projectPath.trim();
        try {
            Path path = Paths.get(trimmed).toAbsolutePath().normalize();
            Path fileName = path.getFileName();
            if (fileName != null && BUILD_FILES.contains(fileName.toString()) && path.getParent() != null) {
                path = path.getParent();
            }
            String key = path.toString();
            // Windows paths are case-insensitive
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    ? key.toLowerCase(Locale.ROOT)
                    : key;
        } catch (InvalidPathException e) {
            return trimmed;
        }
    }
}
