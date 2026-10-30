package com.riskscanner.dependencyriskanalyzer.desktop;

import com.riskscanner.dependencyriskanalyzer.BuildAegisApplication;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * JavaFX desktop wrapper for the BuildAegis application.
 *
 * <p>This class starts the Spring Boot backend in-process and opens the standard web UI
 * ({@code http://localhost:8080/buildaegis}) in an Edge/Chrome app window or the default browser, with a small
 * JavaFX window to quit the server. {@code -Dbuildaegis.embedded=true} uses a JavaFX {@link WebView} instead.
 *
 * <p>Build/run:
 * <ul>
 *   <li>Compiled only when the Maven profile {@code -Pdesktop} is enabled.</li>
 *   <li>Run with {@code .\mvnw -Pdesktop javafx:run}.</li>
 * </ul>
 */
public class DesktopApplication extends Application {

    static {
        // Runs when the class loads, i.e. before JavaFX initializes its graphics pipeline (main() can be too late).
        // Direct3D texture errors (issue #20) occur on some GPUs/drivers; default to the software pipeline
        // unless the user chose one explicitly (e.g. -Dprism.order=d3d).
        if (System.getProperty("prism.order") == null) {
            System.setProperty("prism.order", "sw");
        }
    }

    private static final String URL = "http://localhost:8080/buildaegis";

    private ConfigurableApplicationContext springContext;

    @Override
    public void init() {
        springContext = new SpringApplicationBuilder(BuildAegisApplication.class)
                .headless(false)
                .properties(
                        "server.port=8080",
                        "spring.main.web-application-type=servlet"
                )
                .run();
    }

    @Override
    public void start(Stage stage) {
        // The embedded WebView is slow on JavaFX's software pipeline, so by default the UI opens in a
        // Chromium app window (or the default browser) and this window only controls the server.
        // Use -Dbuildaegis.embedded=true to render the UI inside this window instead.
        if (Boolean.getBoolean("buildaegis.embedded")) {
            showEmbedded(stage);
            return;
        }

        Label status = new Label("BuildAegis is running at " + URL);
        Button open = new Button("Open BuildAegis");
        open.setOnAction(e -> openUi());
        Button quit = new Button("Quit");
        quit.setOnAction(e -> Platform.exit());

        VBox root = new VBox(12, status, new HBox(8, open, quit));
        root.setPadding(new Insets(16));
        stage.setTitle("BuildAegis");
        stage.setScene(new Scene(root, 420, 100));
        stage.show();

        // Closing this window stops the server
        stage.setOnCloseRequest(event -> Platform.exit());

        if (!openUi()) {
            showEmbedded(new Stage());
        }
    }

    private void showEmbedded(Stage stage) {
        WebView webView = new WebView();
        webView.getEngine().load(URL);

        stage.setTitle("BuildAegis");
        stage.setScene(new Scene(webView, 1200, 800));
        stage.show();
        stage.setOnCloseRequest(event -> Platform.exit());
    }

    /**
     * Opens the UI in an Edge/Chrome app window if available, otherwise in the default browser.
     *
     * @return false if no external browser could be started
     */
    private boolean openUi() {
        List<String> candidates = List.of(
                "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
                "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
                "C:/Program Files/Google/Chrome/Application/chrome.exe",
                "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe"
        );
        for (String exe : candidates) {
            if (new File(exe).isFile()) {
                try {
                    new ProcessBuilder(exe, "--app=" + URL).start();
                    return true;
                } catch (IOException ignored) {
                    // try the next candidate
                }
            }
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(URL));
                return true;
            }
        } catch (Exception ignored) {
            // fall through to the embedded view
        }
        return false;
    }

    @Override
    public void stop() {
        try {
            if (springContext != null) {
                springContext.close();
            }
        } finally {
            Platform.exit();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
