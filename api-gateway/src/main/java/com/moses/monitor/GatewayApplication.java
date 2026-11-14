package com.moses.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // Don't throw on 4xx/5xx - the proxy must relay the real downstream status, not swallow it.
        //
        // The JDK HttpClient, not the default HttpURLConnection: the latter cannot relay a
        // downstream 401 on a request with a body (it throws "cannot retry due to server
        // authentication, in streaming mode"), which turned a wrong password at sign-in into
        // a 500 after a long retry loop instead of a plain 401.
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
        return builder
                .requestFactory(() -> {
                    var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
                    factory.setReadTimeout(java.time.Duration.ofSeconds(100));
                    return factory;
                })
                .errorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public void handleError(org.springframework.http.client.ClientHttpResponse response) {
                // no-op: let the proxy controller inspect the raw response itself
            }
        }).build();
    }
}
