package com.moses.monitor.ai.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moses.monitor.ai.ServiceCatalog;
import com.moses.monitor.ai.Urls;
import com.moses.monitor.ai.llm.LlmClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Edit this dashboard in your own words": the project's model turns the instruction into chart
 * settings ({@link DashboardEditor}), which are then saved through process-monitor's PATCH - the
 * same validation a manual edit goes through. Uses the project's (or environment's) own key, like
 * the chat agent; with none set up it answers 409 so the UI can point at Settings.
 */
@RestController
@RequestMapping("/api/ai/dashboards")
public class DashboardEditController {

    private static final Logger log = LoggerFactory.getLogger(DashboardEditController.class);
    private static final String PROJECT = "X-Moses-Project-Id";
    private static final String ENVIRONMENT = "X-Moses-Environment";
    private static final String USER = "X-Moses-User-Id";

    private final LlmClient llm;
    private final ServiceCatalog catalog;
    private final ObjectMapper mapper;
    private final RestTemplate http;
    private final String processMonitorUrl;

    public DashboardEditController(LlmClient llm, ServiceCatalog catalog, ObjectMapper mapper,
                                   @Value("${moses.process-monitor.url:}") String processMonitorUrl) {
        this.llm = llm;
        this.catalog = catalog;
        this.mapper = mapper;
        // The JDK client can send PATCH; the default HttpURLConnection one can't.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = new RestTemplate(factory);
        this.processMonitorUrl = Urls.normalize(processMonitorUrl);
    }

    public record EditRequest(@NotBlank @Size(max = 1000) String instruction) { }

    @PostMapping("/{id}/edit")
    public Map<String, Object> edit(@RequestHeader(PROJECT) String projectId,
                                    @RequestHeader(ENVIRONMENT) String environment,
                                    @RequestHeader(value = USER, required = false) String userId,
                                    @PathVariable String id,
                                    @Valid @RequestBody EditRequest request) {
        if (!llm.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No AI model is set up for this project - add one in Settings > AI model");
        }
        HttpHeaders scope = new HttpHeaders();
        scope.set(PROJECT, projectId);
        scope.set(ENVIRONMENT, environment);
        if (userId != null) {
            scope.set(USER, userId); // so a private dashboard is found for its owner
        }
        String url = processMonitorUrl + "/api/process/dashboards/" + id;

        Map<?, ?> dashboard = call(url, HttpMethod.GET, new HttpEntity<>(scope));
        DashboardEditor.Current current = current(dashboard);
        List<String> services = catalog.serviceNames();

        DashboardEditor.Edit edit;
        try {
            String reply = llm.complete(DashboardEditor.systemPrompt(current, services), request.instruction());
            edit = DashboardEditor.parse(reply, current, services, mapper);
        } catch (Exception e) {
            // The instruction itself is not logged.
            log.warn("Dashboard edit failed with {} ({})", e.getClass().getSimpleName(), llm.describe());
            // Not 502/503/504: api-gateway retries those (cold starts), which would call the model again.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "The AI model could not handle this request, try again or rephrase it");
        }

        if (!edit.changes().isEmpty()) {
            HttpHeaders json = new HttpHeaders(scope);
            json.setContentType(MediaType.APPLICATION_JSON);
            dashboard = call(url, HttpMethod.PATCH, new HttpEntity<>(edit.changes(), json));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dashboard", dashboard);
        response.put("changed", !edit.changes().isEmpty());
        response.put("summary", edit.summary());
        response.put("note", edit.note());
        return response;
    }

    private Map<?, ?> call(String url, HttpMethod method, HttpEntity<?> entity) {
        try {
            return http.exchange(url, method, entity, Map.class).getBody();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dashboard not found");
        }
    }

    @SuppressWarnings("unchecked")
    private static DashboardEditor.Current current(Map<?, ?> d) {
        if (d == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dashboard not found");
        }
        Object services = d.get("services");
        Object minutes = d.get("minutesBack");
        return new DashboardEditor.Current(
                (String) d.get("title"),
                (String) d.get("scope"),
                (String) d.get("serviceName"),
                services instanceof List<?> l ? (List<String>) l : List.of(),
                d.get("metric") instanceof String m ? m : "cpu",
                minutes instanceof Number n ? n.intValue() : 60);
    }
}
