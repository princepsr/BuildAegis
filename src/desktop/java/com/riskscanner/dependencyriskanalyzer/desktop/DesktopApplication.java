package com.riskscanner.dependencyriskanalyzer.desktop;

import com.riskscanner.dependencyriskanalyzer.BuildAegisApplication;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * JavaFX desktop wrapper for the BuildAegis application.
 *
 * <p>This class starts the Spring Boot backend in-process and loads the standard web UI
 * ({@code http://localhost:8080/buildaegis}) in a JavaFX {@link WebView}.
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
        WebView webView = new WebView();
        webView.getEngine().load("http://localhost:8080/buildaegis");

        Scene scene = new Scene(webView, 1200, 800);
        stage.setTitle("BuildAegis");
        stage.setScene(scene);
        stage.show();

        stage.setOnCloseRequest(event -> {
            Platform.exit();
        });
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
