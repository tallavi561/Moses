package com.moses.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // Used for search-service, which may be waking from a free-tier sleep (50s+): bounded, but generous.
        return builder
                .setConnectTimeout(java.time.Duration.ofSeconds(10))
                .setReadTimeout(java.time.Duration.ofSeconds(90))
                .build();
    }
}
