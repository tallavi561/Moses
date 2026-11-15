package com.moses.monitor.ai;

import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class NlQueryController {

    /** Newest logs returned for one question; the response says when there are more. */
    static final int RESULT_LIMIT = 200;

    private final NlQueryTranslator translator;
    private final RestTemplate restTemplate;
    private final String searchServiceUrl;

    public NlQueryController(NlQueryTranslator translator,
                              RestTemplate restTemplate,
                              @Value("${moses.search-service.url}") String searchServiceUrl) {
        this.translator = translator;
        this.restTemplate = restTemplate;
        this.searchServiceUrl = Urls.normalize(searchServiceUrl);
    }

    public record NlQueryRequest(@NotBlank String question) {
    }

    /**
     * Free-text question -> structured query (LLM, or rule-based fallback) -> search-service.
     * Results are newest first, at most {@link #RESULT_LIMIT}; {@code hasMore} says if there are more.
     */
    @PostMapping("/query")
    public Object query(@RequestHeader(ServiceCatalog.PROJECT_HEADER) String projectId,
                        @RequestHeader(ServiceCatalog.ENVIRONMENT_HEADER) String environment,
                        @RequestBody NlQueryRequest request) {
        TranslatedQuery translated = translator.translate(request.question());

        UriComponentsBuilder uri = UriComponentsBuilder
                .fromHttpUrl(searchServiceUrl + "/api/search/page")
                .queryParam("from", translated.getFrom())
                .queryParam("to", translated.getTo())
                .queryParam("limit", RESULT_LIMIT);
        for (String word : translated.getWords()) {
            uri.queryParam("words", word);
        }
        if (translated.getLogLevel() != null) {
            uri.queryParam("logLevel", translated.getLogLevel());
        }
        if (translated.getService() != null) {
            uri.queryParam("service", translated.getService());
        }
        if (translated.getTraceId() != null) {
            uri.queryParam("traceId", translated.getTraceId());
        }

        // The question is answered from the asker's project and environment only: pass on the
        // scope api-gateway established, so search-service applies it.
        HttpHeaders scope = new HttpHeaders();
        scope.set(ServiceCatalog.PROJECT_HEADER, projectId);
        scope.set(ServiceCatalog.ENVIRONMENT_HEADER, environment);
        Map<?, ?> page = restTemplate.exchange(uri.build().toUri(), HttpMethod.GET, new HttpEntity<>(scope), Map.class)
                .getBody();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("interpretedAs", translated);
        response.put("results", page == null ? java.util.List.of() : page.get("logs"));
        response.put("hasMore", page != null && Boolean.TRUE.equals(page.get("hasMore")));
        return response;
    }
}
