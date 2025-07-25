package io.filemanager.filez;

import io.filemanager.filez.config.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

public class TestFilezApplication {

	public static void main(String[] args) {
		System.setProperty("spring.profiles.active", "local");

		SpringApplication.from(FilezApplication::main)
				.with(TestcontainersConfiguration.class)
				.run(args);
	}
}