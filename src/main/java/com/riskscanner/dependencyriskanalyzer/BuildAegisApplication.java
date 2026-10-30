package com.riskscanner.dependencyriskanalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class BuildAegisApplication {

	public static void main(String[] args) {
		SpringApplicationBuilder builder = new SpringApplicationBuilder(BuildAegisApplication.class);

		if (isCliMode(args)) {
			// Command-line scan for CI (see CliScanRunner): no web server, quiet logging, exit code reflects the result
			System.exit(SpringApplication.exit(builder
					.web(WebApplicationType.NONE)
					.headless(true)
					.properties("spring.main.banner-mode=off", "logging.level.root=WARN")
					.run(args)));
		}

		// Not headless so the native file chooser can be opened for the project path
		builder.headless(false).run(args);
	}

	public static boolean isCliMode(String[] args) {
		return args != null && Arrays.stream(args).anyMatch(a -> a.startsWith("--buildaegis.cli.project="));
	}

}
