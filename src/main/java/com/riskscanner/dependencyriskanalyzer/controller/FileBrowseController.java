package com.riskscanner.dependencyriskanalyzer.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lets the UI pick a project folder or build file with the native file chooser.
 *
 * <p>Browsers never expose full local paths, so the chooser is opened by the backend. This only works
 * when the backend runs on the user's own machine with a display (standalone or desktop mode).
 */
@RestController
@RequestMapping("/api/system")
public class FileBrowseController {

    /**
     * Opens a file chooser and returns the selected path as {@code {"path": "..."}}.
     * Returns 204 when the dialog was cancelled and 501 when no display is available.
     */
    @PostMapping("/browse")
    public ResponseEntity<Map<String, String>> browse() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "File browsing is not available on this server. Enter the path manually."));
        }

        AtomicReference<File> selected = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select project folder or build file");
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                selected.set(chooser.getSelectedFile());
            }
        });

        File file = selected.get();
        if (file == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(Map.of("path", file.getAbsolutePath()));
    }
}
