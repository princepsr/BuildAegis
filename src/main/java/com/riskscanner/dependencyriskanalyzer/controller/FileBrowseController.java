package com.riskscanner.dependencyriskanalyzer.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lists folders and build files on the machine running the backend so the UI can offer an in-page
 * folder browser for the project path (browsers never expose full local paths).
 *
 * <p>Intended for local use: anyone who can reach this endpoint can list directory names.
 */
@RestController
@RequestMapping("/api/system")
public class FileBrowseController {

    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");

    /**
     * One entry of a directory listing.
     *
     * @param name      display name
     * @param path      absolute path
     * @param directory true for a folder, false for a build file
     */
    public record FsEntry(String name, String path, boolean directory) {
    }

    /**
     * A directory listing.
     *
     * @param path    the listed directory, or empty for the top-level list (home folder and drives/roots)
     * @param parent  the parent directory to navigate up to, or null at the top level
     * @param entries sub-folders (first) and build files
     */
    public record FsListing(String path, String parent, List<FsEntry> entries) {
    }

    /**
     * Lists the given directory; with no path, lists the home folder and the filesystem roots.
     */
    @GetMapping("/fs")
    public ResponseEntity<?> list(@RequestParam(name = "path", required = false) String path) {
        try {
            return ResponseEntity.ok(listDirectory(path));
        } catch (IllegalArgumentException | IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    FsListing listDirectory(String path) throws IOException {
        if (path == null || path.isBlank()) {
            return topLevel();
        }

        Path dir = Paths.get(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a folder: " + path);
        }

        List<FsEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                try {
                    if (Files.isDirectory(child)) {
                        if (!Files.isHidden(child)) {
                            entries.add(new FsEntry(name, child.toString(), true));
                        }
                    } else if (BUILD_FILES.contains(name)) {
                        entries.add(new FsEntry(name, child.toString(), false));
                    }
                } catch (IOException ignored) {
                    // skip entries that cannot be inspected
                }
            }
        } catch (IOException e) {
            throw new IOException("Cannot read folder: " + path);
        }

        entries.sort(Comparator.comparing((FsEntry e) -> !e.directory())
                .thenComparing(e -> e.name().toLowerCase()));

        Path parent = dir.getParent();
        return new FsListing(dir.toString(), parent == null ? "" : parent.toString(), entries);
    }

    private FsListing topLevel() {
        List<FsEntry> entries = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null && Files.isDirectory(Paths.get(home))) {
            entries.add(new FsEntry("Home (" + home + ")", home, true));
        }
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) {
                entries.add(new FsEntry(root.getPath(), root.getPath(), true));
            }
        }
        return new FsListing("", null, entries);
    }
}
