package com.moses.monitor.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Announces an incident opening or closing in the environment's team chat, as a system message
 * (chat-service's internal endpoint - not the AI agent). The chat shows it from the structured
 * event in the user's language; {@code text} is the plain-English fallback (and what the agent
 * sees if someone replies to the alert and asks it). A failed announcement is logged and dropped:
 * the incident itself is already saved and on the Incidents page.
 */
@Component
public class IncidentNotifier {

    private static final Logger log = LoggerFactory.getLogger(IncidentNotifier.class);

    private final RestTemplate http;
    private final String chatServiceUrl;
    private final String internalToken;

    public IncidentNotifier(@Value("${moses.chat-service.url:http://localhost:8087}") String chatServiceUrl,
                            @Value("${moses.internal.token:}") String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(5_000);
        this.http = new RestTemplate(factory);
        this.chatServiceUrl = chatServiceUrl.endsWith("/") ? chatServiceUrl.substring(0, chatServiceUrl.length() - 1) : chatServiceUrl;
        this.internalToken = internalToken;
    }

    /**
     * {@code similar}: earlier incidents like this one (see {@link IncidentLibrary#similar}), so the
     * alert can say at once "seen N times before, last fixed by ...".
     */
    public void opened(Incident incident, List<Incident> similar) {
        Incident lastFix = similar.stream()
                .filter(i -> i.getReviewStatus() == IncidentReview.SOLVED && i.getResolution() != null
                        && !i.getResolution().isBlank())
                .findFirst().orElse(null);
        String text = openedText(incident);
        if (!similar.isEmpty()) {
            text += " Seen " + similar.size() + " time(s) before.";
        }
        if (lastFix != null) {
            text += " Last fix: " + lastFix.getResolution();
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("seenBefore", (long) similar.size());
        extra.put("lastFix", lastFix == null ? null : truncate(lastFix.getResolution(), 500));
        extra.put("lastFixIncidentId", lastFix == null ? null : lastFix.getId());
        post(incident, "INCIDENT_OPENED", text, extra);
    }

    public void resolved(Incident incident) {
        post(incident, "INCIDENT_RESOLVED", "Resolved: " + incident.getServiceName() + " is back to normal (lasted "
                + duration(incident) + ").", Map.of());
    }

    static String openedText(Incident incident) {
        if (incident.getKind() == IncidentKind.FLOW_BROKEN) {
            String text = "Incident: requests from " + incident.getFlowEntry() + " are not reaching " + incident.getServiceName()
                    + " - " + incident.getBrokenTraces() + " of " + incident.getCheckedTraces() + " broke in the last "
                    + incident.getWindowMinutes() + " min (deadline " + incident.getFlowMaxSeconds() + "s).";
            if (!incident.getBrokenExamples().isEmpty()) {
                text += " First one stopped at " + incident.getBrokenExamples().get(0).stoppedAt() + ".";
            }
            return text;
        }
        if (incident.getKind() == IncidentKind.SERVICE_DOWN) {
            return "Incident: " + incident.getServiceName() + " is DOWN.";
        }
        String text = "Incident: " + incident.getServiceName() + " - " + incident.getCurrentErrors() + " errors in the last "
                + incident.getWindowMinutes() + " min (normally about " + Math.round(incident.getBaselineErrors()) + ").";
        if (!incident.getTopErrors().isEmpty()) {
            text += " Most common: " + incident.getTopErrors().get(0).message();
        }
        return text;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private void post(Incident incident, String type, String text, Map<String, Object> extra) {
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("INTERNAL_API_TOKEN is not set: incident {} not announced in chat", incident.getId());
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("incidentId", incident.getId());
        event.put("serviceName", incident.getServiceName());
        event.put("incidentKind", incident.getKind().name());
        event.put("errors", incident.getCurrentErrors());
        event.put("baseline", Math.round(incident.getBaselineErrors()));
        event.put("windowMinutes", incident.getWindowMinutes());
        event.put("topError", incident.getTopErrors().isEmpty() ? null : incident.getTopErrors().get(0).message());
        event.put("durationSeconds", incident.getClosedAt() == null ? null
                : Duration.between(incident.getOpenedAt(), incident.getClosedAt()).toSeconds());
        if (incident.getKind() == IncidentKind.FLOW_BROKEN) {
            event.put("flowEntry", incident.getFlowEntry());
            event.put("checkedTraces", incident.getCheckedTraces());
            event.put("brokenTraces", incident.getBrokenTraces());
            event.put("stoppedAt", incident.getBrokenExamples().isEmpty() ? null : incident.getBrokenExamples().get(0).stoppedAt());
            event.put("flowMaxSeconds", incident.getFlowMaxSeconds());
        }
        event.putAll(extra);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", incident.getProjectId());
        body.put("environment", incident.getEnvironment());
        body.put("text", text);
        body.put("event", event);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", internalToken);
        try {
            http.postForEntity(chatServiceUrl + "/internal/chat/system", new HttpEntity<>(body, headers), Void.class);
        } catch (RuntimeException e) {
            log.warn("could not announce incident {} in chat: {}", incident.getId(), e.toString());
        }
    }

    private static String duration(Incident incident) {
        long seconds = Duration.between(incident.getOpenedAt(),
                incident.getClosedAt() == null ? incident.getOpenedAt() : incident.getClosedAt()).toSeconds();
        if (seconds < 120) return seconds + "s";
        if (seconds < 7200) return (seconds / 60) + " min";
        return (seconds / 3600) + "h " + (seconds % 3600 / 60) + "min";
    }
}
