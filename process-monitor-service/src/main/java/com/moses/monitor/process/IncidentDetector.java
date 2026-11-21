package com.moses.monitor.process;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opens and closes {@link Incident}s by counting - no AI. Every check (once a minute by default):
 *
 * - Error spike: per service, ERROR/FATAL logs in the last window against the service's
 *   {@link AlertRule}: at least minErrors, and at least factor x its normal amount per window
 *   (the average over the previous day).
 * - Service down: a service that was alive in the last day and is now DOWN.
 *
 * An incident stays open while the condition holds and closes on its own one quiet window after
 * the last breach, so a flapping service is one incident, not a stream of alerts. Opening and
 * closing are announced in the environment's team chat ({@link IncidentNotifier}).
 */
@Component
public class IncidentDetector {

    private static final Logger log = LoggerFactory.getLogger(IncidentDetector.class);

    static final int ERROR_LEVEL = 4;
    private static final int TOP_ERRORS = 3;
    private static final int SAMPLE_LOGS = 500;

    private final MongoTemplate mongoTemplate;
    private final IncidentNotifier notifier;
    private final IncidentLibrary library;
    private final TraceReader traces;
    private final Duration window;
    private final Duration baselinePeriod;
    private final Duration downRecentlyAlive;

    public IncidentDetector(MongoTemplate mongoTemplate, IncidentNotifier notifier, IncidentLibrary library, TraceReader traces,
                            @Value("${moses.incidents.window-minutes:5}") int windowMinutes,
                            @Value("${moses.incidents.baseline-hours:24}") int baselineHours,
                            @Value("${moses.incidents.down-recently-alive-hours:24}") int downRecentlyAliveHours) {
        this.mongoTemplate = mongoTemplate;
        this.notifier = notifier;
        this.library = library;
        this.traces = traces;
        this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
        this.baselinePeriod = Duration.ofHours(Math.max(1, baselineHours));
        this.downRecentlyAlive = Duration.ofHours(Math.max(1, downRecentlyAliveHours));
    }

    @Scheduled(initialDelayString = "${moses.incidents.initial-delay-ms:30000}",
            fixedDelayString = "${moses.incidents.check-interval-ms:60000}")
    public void scheduledCheck() {
        try {
            check(Instant.now());
        } catch (RuntimeException e) {
            log.warn("incident check failed: {}", e.toString());
        }
    }

    /** The error-spike decision itself: enough errors, and well above normal. */
    static boolean isSpike(long errors, double baselinePerWindow, AlertRule rule) {
        return errors >= Math.max(1, rule.getMinErrors()) && errors >= rule.getFactor() * baselinePerWindow;
    }

    void check(Instant now) {
        Map<String, Incident> open = new HashMap<>();
        for (Incident i : mongoTemplate.find(new Query(Criteria.where("state").is(IncidentState.OPEN)), Incident.class)) {
            open.put(openKey(i), i);
        }
        Map<String, Map<String, AlertRule>> rules = new HashMap<>();
        checkErrorSpikes(now, open, rules);
        checkServicesDown(now, open, rules);
        checkFlows(now, open);
    }

    private static String openKey(Incident i) {
        return i.getKind() == IncidentKind.FLOW_BROKEN
                ? key(i.getProjectId(), i.getEnvironment(), "flow:" + i.getFlowId(), i.getKind())
                : key(i.getProjectId(), i.getEnvironment(), i.getServiceName(), i.getKind());
    }

    private static final int MAX_BROKEN_EXAMPLES = 5;
    private static final int MAX_FLOW_TRACES = 20_000;

    /**
     * Every ACTIVE {@link TraceFlow}: the traces whose deadline passed during the last window are
     * checked, and too many broken ones open a FLOW_BROKEN incident (closed a quiet window after
     * the last breach, like an error spike). One trace read per environment covers all its flows.
     */
    private void checkFlows(Instant now, Map<String, Incident> open) {
        Map<String, List<TraceFlow>> byScope = new LinkedHashMap<>();
        for (TraceFlow f : mongoTemplate.find(new Query(Criteria.where("status").is(TraceFlowStatus.ACTIVE)), TraceFlow.class)) {
            byScope.computeIfAbsent(f.getProjectId() + "|" + f.getEnvironment(), k -> new ArrayList<>()).add(f);
        }
        java.util.Set<String> activeFlowIds = new java.util.HashSet<>();
        Instant deadlineFrom = now.minus(window);
        for (List<TraceFlow> flows : byScope.values()) {
            int longest = flows.stream().mapToInt(TraceFlow::getMaxSeconds).max().orElse(0);
            TraceFlow first = flows.get(0);
            List<FlowAnalysis.TraceHops> seen = traces.read(first.getProjectId(), first.getEnvironment(),
                    deadlineFrom.minusSeconds(longest), now, MAX_FLOW_TRACES);
            for (TraceFlow flow : flows) {
                activeFlowIds.add(flow.getId());
                FlowAnalysis.CheckResult result = FlowAnalysis.check(flow.getEntryService(), flow.getRoute(),
                        flow.getTargetService(), flow.getMaxSeconds(), seen, deadlineFrom, now);
                flow.setLastCheckedAt(now);
                flow.setLastCheckedTraces(result.checked());
                flow.setLastBrokenTraces(result.broken().size());
                mongoTemplate.save(flow);

                String k = key(flow.getProjectId(), flow.getEnvironment(), "flow:" + flow.getId(), IncidentKind.FLOW_BROKEN);
                Incident existing = open.get(k);
                boolean breach = result.checked() >= Math.max(1, flow.getMinTraces())
                        && !result.broken().isEmpty()
                        && result.brokenPercent() >= flow.getMaxBrokenPercent();
                if (breach) {
                    Incident incident = existing != null ? existing
                            : newIncident(new String[]{flow.getProjectId(), flow.getEnvironment(), flow.getTargetService()},
                            IncidentKind.FLOW_BROKEN, now);
                    incident.setFlowId(flow.getId());
                    incident.setFlowEntry(flow.getEntryService());
                    incident.setFlowRouteExample(flow.getRouteExample());
                    incident.setFlowMaxSeconds(flow.getMaxSeconds());
                    incident.setLastBreachAt(now);
                    incident.setCheckedTraces(result.checked());
                    incident.setBrokenTraces(result.broken().size());
                    incident.setPeakBrokenTraces(Math.max(incident.getPeakBrokenTraces(), result.broken().size()));
                    incident.setBrokenExamples(result.broken().stream().limit(MAX_BROKEN_EXAMPLES)
                            .map(b -> new Incident.BrokenTrace(b.traceId(), b.stoppedAt(), b.reason().name())).toList());
                    incident.setSignatures(new ArrayList<>(List.of("<flow:" + flow.getId() + ">")));
                    mongoTemplate.save(incident);
                    if (existing == null) {
                        open.put(k, incident);
                        notifier.opened(incident, library.similar(incident, IncidentLibrary.MAX_SEARCH));
                    }
                } else if (existing != null) {
                    existing.setCheckedTraces(result.checked());
                    existing.setBrokenTraces(result.broken().size());
                    if (existing.getLastBreachAt() == null || !existing.getLastBreachAt().isAfter(now.minus(window))) {
                        close(existing, now);
                    } else {
                        mongoTemplate.save(existing);
                    }
                }
            }
        }
        // A flow that was paused, rejected or deleted while broken: its incident ends.
        for (Incident i : List.copyOf(open.values())) {
            if (i.getKind() == IncidentKind.FLOW_BROKEN && i.getState() == IncidentState.OPEN
                    && !activeFlowIds.contains(i.getFlowId())) {
                close(i, now);
            }
        }
    }

    private void checkErrorSpikes(Instant now, Map<String, Incident> open, Map<String, Map<String, AlertRule>> rules) {
        Instant from = now.minus(window);
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, String[]> scopes = new HashMap<>();
        List<Document> pipeline = List.of(
                new Document("$match", new Document("timestamp", new Document("$gte", from).append("$lte", now))
                        .append("log_level", new Document("$gte", ERROR_LEVEL))),
                new Document("$group", new Document("_id", new Document("p", "$project_id")
                        .append("e", "$environment").append("s", "$service_name"))
                        .append("n", new Document("$sum", 1))));
        for (Document row : mongoTemplate.getCollection("logs").aggregate(pipeline)) {
            Document id = row.get("_id", Document.class);
            String p = id.getString("p");
            String e = id.getString("e");
            String s = id.getString("s");
            if (p == null || e == null || s == null) {
                continue;
            }
            String k = key(p, e, s, IncidentKind.ERROR_SPIKE);
            counts.put(k, new long[]{((Number) row.get("n")).longValue()});
            scopes.put(k, new String[]{p, e, s});
        }
        // Open spikes whose service has gone quiet have no row above: they count as zero errors.
        for (Incident i : open.values()) {
            if (i.getKind() == IncidentKind.ERROR_SPIKE) {
                String k = key(i.getProjectId(), i.getEnvironment(), i.getServiceName(), IncidentKind.ERROR_SPIKE);
                counts.putIfAbsent(k, new long[]{0});
                scopes.putIfAbsent(k, new String[]{i.getProjectId(), i.getEnvironment(), i.getServiceName()});
            }
        }

        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            String[] scope = scopes.get(entry.getKey());
            long errors = entry.getValue()[0];
            AlertRule rule = ruleFor(rules, scope[0], scope[1], scope[2]);
            Incident existing = open.get(entry.getKey());
            if (rule.isMuted()) {
                if (existing != null) {
                    close(existing, now);
                }
                continue;
            }
            boolean candidate = errors >= Math.max(1, rule.getMinErrors());
            double baseline = candidate || existing != null ? baseline(scope, from) : 0;
            if (candidate && isSpike(errors, baseline, rule)) {
                Incident incident = existing != null ? existing : newIncident(scope, IncidentKind.ERROR_SPIKE, now);
                incident.setLastBreachAt(now);
                incident.setCurrentErrors(errors);
                incident.setPeakErrors(Math.max(incident.getPeakErrors(), errors));
                incident.setBaselineErrors(baseline);
                incident.setTopErrors(topErrors(scope, from, now));
                addSignatures(incident);
                mongoTemplate.save(incident);
                if (existing == null) {
                    open.put(entry.getKey(), incident);
                    notifier.opened(incident, library.similar(incident, IncidentLibrary.MAX_SEARCH));
                }
            } else if (existing != null) {
                existing.setCurrentErrors(errors);
                if (existing.getLastBreachAt() == null || !existing.getLastBreachAt().isAfter(now.minus(window))) {
                    close(existing, now);
                } else {
                    mongoTemplate.save(existing);
                }
            }
        }
    }

    private void checkServicesDown(Instant now, Map<String, Incident> open, Map<String, Map<String, AlertRule>> rules) {
        Instant aliveSince = now.minus(downRecentlyAlive);
        Map<String, ProcessState> down = new HashMap<>();
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(ProcessStatus.DOWN.name()),
                new Criteria().orOperator(Criteria.where("last_seen").gte(aliveSince),
                        Criteria.where("last_probe_at").gte(aliveSince))));
        for (ProcessState s : mongoTemplate.find(query, ProcessState.class)) {
            down.put(key(s.getProjectId(), s.getEnvironment(), s.getServiceName(), IncidentKind.SERVICE_DOWN), s);
        }
        for (Map.Entry<String, ProcessState> entry : down.entrySet()) {
            ProcessState s = entry.getValue();
            if (ruleFor(rules, s.getProjectId(), s.getEnvironment(), s.getServiceName()).isMuted()) {
                continue;
            }
            Incident existing = open.get(entry.getKey());
            if (existing != null) {
                existing.setLastBreachAt(now);
                mongoTemplate.save(existing);
            } else {
                Incident incident = newIncident(new String[]{s.getProjectId(), s.getEnvironment(), s.getServiceName()},
                        IncidentKind.SERVICE_DOWN, now);
                incident.setLastBreachAt(now);
                incident.setSignatures(new ArrayList<>(List.of(IncidentLibrary.DOWN_SIGNATURE)));
                mongoTemplate.save(incident);
                open.put(entry.getKey(), incident);
                notifier.opened(incident, library.similar(incident, IncidentLibrary.MAX_SEARCH));
            }
        }
        for (Incident i : List.copyOf(open.values())) {
            if (i.getKind() == IncidentKind.SERVICE_DOWN && i.getState() == IncidentState.OPEN) {
                String k = key(i.getProjectId(), i.getEnvironment(), i.getServiceName(), IncidentKind.SERVICE_DOWN);
                boolean muted = ruleFor(rules, i.getProjectId(), i.getEnvironment(), i.getServiceName()).isMuted();
                if (!down.containsKey(k) || muted) {
                    close(i, now);
                }
            }
        }
    }

    private static final int MAX_SIGNATURES = 20;

    private static void addSignatures(Incident incident) {
        List<String> all = new ArrayList<>(incident.getSignatures());
        for (Incident.ErrorGroup g : incident.getTopErrors()) {
            if (g.signature() != null && !g.signature().isEmpty() && !all.contains(g.signature()) && all.size() < MAX_SIGNATURES) {
                all.add(g.signature());
            }
        }
        incident.setSignatures(all);
    }

    private void close(Incident incident, Instant now) {
        incident.setState(IncidentState.CLOSED);
        incident.setClosedAt(now);
        mongoTemplate.save(incident);
        notifier.resolved(incident);
    }

    private Incident newIncident(String[] scope, IncidentKind kind, Instant now) {
        Incident incident = new Incident();
        incident.setProjectId(scope[0]);
        incident.setEnvironment(scope[1]);
        incident.setServiceName(scope[2]);
        incident.setKind(kind);
        incident.setState(IncidentState.OPEN);
        incident.setOpenedAt(now);
        incident.setWindowMinutes((int) window.toMinutes());
        return incident;
    }

    /** Average ERROR/FATAL logs per window over the baseline period before the current window. */
    private double baseline(String[] scope, Instant windowStart) {
        long count = mongoTemplate.count(new Query(errorsOf(scope)
                .and("timestamp").gte(windowStart.minus(baselinePeriod)).lt(windowStart)), "logs");
        return (double) count / ((double) baselinePeriod.toMinutes() / window.toMinutes());
    }

    private List<Incident.ErrorGroup> topErrors(String[] scope, Instant from, Instant now) {
        Query query = new Query(errorsOf(scope).and("timestamp").gte(from).lte(now))
                .with(Sort.by(Sort.Direction.DESC, "timestamp")).limit(SAMPLE_LOGS);
        query.fields().include("raw_message").include("timestamp").include("trace_id");
        Map<String, Incident.ErrorGroup> groups = new LinkedHashMap<>();
        for (Document d : mongoTemplate.find(query, Document.class, "logs")) {
            String message = d.getString("raw_message");
            String signature = ErrorSignature.of(message);
            Incident.ErrorGroup g = groups.get(signature);
            Instant at = d.getDate("timestamp") == null ? null : d.getDate("timestamp").toInstant();
            // Logs come newest first: the first one of a kind is its latest, keep its text and trace.
            groups.put(signature, g == null
                    ? new Incident.ErrorGroup(truncate(message), 1, at, d.getString("trace_id"), signature)
                    : new Incident.ErrorGroup(g.message(), g.count() + 1, g.lastAt(),
                    g.traceId() != null ? g.traceId() : d.getString("trace_id"), signature));
        }
        List<Incident.ErrorGroup> top = new ArrayList<>(groups.values());
        top.sort(Comparator.comparingLong(Incident.ErrorGroup::count).reversed());
        return List.copyOf(top.subList(0, Math.min(TOP_ERRORS, top.size())));
    }

    private static Criteria errorsOf(String[] scope) {
        return Criteria.where("project_id").is(scope[0]).and("environment").is(scope[1])
                .and("service_name").is(scope[2]).and("log_level").gte(ERROR_LEVEL);
    }

    private AlertRule ruleFor(Map<String, Map<String, AlertRule>> cache, String projectId, String environment, String service) {
        Map<String, AlertRule> forScope = cache.computeIfAbsent(projectId + "|" + environment, k -> {
            Map<String, AlertRule> m = new HashMap<>();
            mongoTemplate.find(new Query(Criteria.where("project_id").is(projectId).and("environment").is(environment)),
                    AlertRule.class).forEach(r -> m.put(r.getServiceName(), r));
            return m;
        });
        AlertRule rule = forScope.get(service);
        return rule != null ? rule : AlertRule.defaults(service);
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }

    private static String key(String projectId, String environment, String service, IncidentKind kind) {
        return projectId + "|" + environment + "|" + service + "|" + kind;
    }
}
