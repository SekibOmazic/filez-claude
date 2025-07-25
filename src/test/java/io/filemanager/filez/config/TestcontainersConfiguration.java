package io.filemanager.filez.config;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	public S3MockContainer s3mock() {
		return new S3MockContainer("4.6.0")  // Match the library version
				.withInitialBuckets("filemanager-bucket");
	}

	@Bean
	@ServiceConnection
	public PostgreSQLContainer<?> postgresContainer() {
		return new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
				.withDatabaseName("filemanager")
				.withUsername("filemanager")
				.withPassword("filemanager")
				.withReuse(true);
	}
}