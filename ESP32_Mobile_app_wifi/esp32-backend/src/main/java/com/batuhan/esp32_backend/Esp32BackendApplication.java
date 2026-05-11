package com.batuhan.esp32_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Spring Boot uygulamasının başlangıç sınıfıdır
@SpringBootApplication

// Cihaz bağlantı kontrolü gibi zamanlanmış görevlerin çalışmasını sağlar
@EnableScheduling
public class Esp32BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(Esp32BackendApplication.class, args);
	}

}