package com.riskscanner.dependencyriskanalyzer.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    public ResponseEntity<?> list(@RequestParam(name = "path", required = false) String path, HttpServletRequest request) {
        if (!isLoopback(request)) {
            return forbidden();
        }
        try {
            return ResponseEntity.ok(listDirectory(path));
        } catch (IllegalArgumentException | IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * These endpoints expose the local file system, so they only answer requests that come from this machine,
     * even if the server was made reachable from the network.
     */
    static boolean isLoopback(HttpServletRequest request) {
        String address = request == null ? null : request.getRemoteAddr();
        if (address == null) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ResponseEntity<T> forbidden() {
        return (ResponseEntity<T>) ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "File browsing is only available from the machine running BuildAegis."));
    }

    /**
     * Opens the operating system's file dialog on the machine running the backend and returns the chosen
     * build file or folder as {@code {"path": "..."}}.
     *
     * <p>Returns 204 when the dialog was cancelled and 501 when no dialog can be shown (headless server or
     * unsupported system), in which case the UI falls back to the in-page folder browser.
     */
    @PostMapping("/browse")
    public ResponseEntity<Map<String, String>> browse(@RequestParam(name = "initial", required = false) String initial,
                                                      HttpServletRequest request) throws Exception {
        if (!isLoopback(request)) {
            return forbidden();
        }
        String chosen;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            chosen = browseWindows(initial);
        } else if (!GraphicsEnvironment.isHeadless()) {
            chosen = browseSwing();
        } else {
            chosen = null;
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "File dialog is not available on this server."));
        }

        if (chosen == null || chosen.isBlank()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(Map.of("path", chosen));
    }

    /**
     * Runs the native Windows dialog in its own PowerShell process so it can take the foreground; a dialog
     * opened from this background server process is otherwise hidden behind the browser by Windows.
     */
    private String browseWindows(String initial) throws Exception {
        Path script = Files.createTempFile("buildaegis-file-dialog", ".ps1");
        try {
            try (InputStream in = new ClassPathResource("native-file-dialog.ps1").getInputStream()) {
                Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            }
            List<String> command = new ArrayList<>(List.of(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-STA",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString()));
            if (initial != null && !initial.isBlank()) {
                command.add(initial);
            }

            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("File dialog failed: "
                        + new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim());
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            // The script prints only the selected path; ignore anything PowerShell adds before it
            String[] lines = output.split("\\R");
            return lines.length == 0 ? null : lines[lines.length - 1].trim();
        } finally {
            Files.deleteIfExists(script);
        }
    }

    /**
     * Non-Windows fallback: Swing file chooser (needs a display).
     */
    private String browseSwing() throws Exception {
        AtomicReference<File> selected = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFileChooser chooser = new JFileChooser() {
                @Override
                protected JDialog createDialog(Component parent) {
                    JDialog dialog = super.createDialog(parent);
                    dialog.setAlwaysOnTop(true);
                    return dialog;
                }
            };
            chooser.setDialogTitle("Select project folder or build file");
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                selected.set(chooser.getSelectedFile());
            }
        });
        return selected.get() == null ? null : selected.get().getAbsolutePath();
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
