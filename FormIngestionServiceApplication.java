package com.loblaw.ingestionservice;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition  // URL: http://localhost:8081/swagger-ui.html#/
public class FormIngestionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(FormIngestionServiceApplication.class, args);
	}

}
