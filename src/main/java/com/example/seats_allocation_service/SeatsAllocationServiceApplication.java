package com.example.seats_allocation_service;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SeatsAllocationServiceApplication {

	@Bean
	public ObjectMapper objectMapper() {
		return new ObjectMapper();
	}

	public static void main(String[] args) {
		SpringApplication.run(SeatsAllocationServiceApplication.class, args);
	}

}
