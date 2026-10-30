package com.riskscanner.dependencyriskanalyzer;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BuildAegisApplication {

	public static void main(String[] args) {
		// Not headless so the native file chooser can be opened for the project path
		new SpringApplicationBuilder(BuildAegisApplication.class).headless(false).run(args);
	}

}
