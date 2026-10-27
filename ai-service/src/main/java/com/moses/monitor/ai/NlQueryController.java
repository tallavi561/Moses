package com.moses.monitor.ai;

import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class NlQueryController {

    private final NlQueryTranslator translator;
    private final RestTemplate restTemplate;
    private final String searchServiceUrl;

    public NlQueryController(NlQueryTranslator translator,
                              RestTemplate restTemplate,
                              @Value("${moses.search-service.url}") String searchServiceUrl) {
        this.translator = translator;
        this.restTemplate = restTemplate;
        this.searchServiceUrl = normalize(searchServiceUrl);
    }

    /**
     * Render's {@code fromService} "hostport" property yields a bare "host:port" with no scheme
     * (unlike the "http://host:port" local default), which {@link UriComponentsBuilder#fromHttpUrl}
     * would otherwise reject.
     */
    private static String normalize(String url) {
        if (url == null || url.isBlank() || url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "http://" + url;
    }

    public record NlQueryRequest(@NotBlank String question) {
    }

    @PostMapping("/query")
    public Object query(@RequestBody NlQueryRequest request) {
        TranslatedQuery translated = translator.translate(request.question());

        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromHttpUrl(searchServiceUrl + "/api/search")
                .queryParam("from", translated.getFrom())
                .queryParam("to", translated.getTo());
        for (String word : translated.getWords()) {
            uriBuilder.queryParam("words", word);
        }
        if (translated.getLogLevel() != null) {
            uriBuilder.queryParam("logLevel", translated.getLogLevel());
        }
        if (translated.getWords().isEmpty()) {
            return Map.of(
                    "interpretedAs", translated,
                    "results", List.of(),
                    "note", "No searchable terms extracted from the question - nothing sent to search-service.");
        }

        Object results = restTemplate.getForObject(uriBuilder.build().toUri(), Object.class);
        return Map.of("interpretedAs", translated, "results", results);
    }
}
