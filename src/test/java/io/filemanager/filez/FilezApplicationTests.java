package io.filemanager.filez;

import io.filemanager.filez.config.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")  // Use test profile to avoid conflicts
class FilezApplicationTests {

	@Test
	void contextLoads() {
		// This test verifies that the Spring application context loads successfully
		// with all the beans configured properly, including database connections
	}
}