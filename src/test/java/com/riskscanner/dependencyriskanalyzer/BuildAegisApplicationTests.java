package com.riskscanner.dependencyriskanalyzer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
		classes = BuildAegisApplication.class,
		properties = {
				// never use (or lock) the developer's ./data database from a test
				"spring.datasource.url=jdbc:h2:mem:contexttest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
				"buildaegis.encryption.key-file=target/test-data/buildaegis.key"
		})
class BuildAegisApplicationTests {

	@Test
	void contextLoads() {
	}

}
