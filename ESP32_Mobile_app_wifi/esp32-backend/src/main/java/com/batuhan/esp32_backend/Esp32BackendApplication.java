package com.batuhan.esp32_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Esp32BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(Esp32BackendApplication.class, args);
	}

}
