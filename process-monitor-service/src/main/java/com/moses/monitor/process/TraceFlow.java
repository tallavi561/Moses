package com.moses.monitor.process;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * An expected path for requests: a trace that starts at {@code entryService} (with a first log
 * of kind {@code route}, or any when null) should reach {@code targetService} within
 * {@code maxSeconds}. MoSes learns these from the traces and suggests them ({@link FlowLearner});
 * only ACTIVE ones - approved by the team or added by hand - are checked ({@link IncidentDetector}).
 */
@Document(collection = "trace_flows")
public class TraceFlow {

    public static final double DEFAULT_MAX_BROKEN_PERCENT = 10;
    public static final int DEFAULT_MIN_TRACES = 5;

    @Id
    private String id;
    @Field("project_id")
    private String projectId;
    private String environment;
    @Field("entry_service")
    private String entryService;
    /** {@link ErrorSignature} of the trace's first log at the entry service; null = every trace starting there. */
    private String route;
    /** One real first-log message of this route, to show people what it means. */
    @Field("route_example")
    private String routeExample;
    @Field("target_service")
    private String targetService;
    @Field("max_seconds")
    private int maxSeconds;
    /** An incident opens when at least this percent of the checked traces broke... */
    @Field("max_broken_percent")
    private double maxBrokenPercent = DEFAULT_MAX_BROKEN_PERCENT;
    /** ...out of at least this many traces in the window. */
    @Field("min_traces")
    private int minTraces = DEFAULT_MIN_TRACES;
    private TraceFlowStatus status;
    /** "moses" when learned, else the user id who added it. */
    @Field("created_by")
    private String createdBy;
    @Field("created_at")
    private Instant createdAt;
    @Field("decided_by")
    private String decidedBy;
    @Field("decided_at")
    private Instant decidedAt;

    // What the learner saw (refreshed on every learning run, whatever the status).
    @Field("learned_samples")
    private Integer learnedSamples;
    @Field("learned_reach_percent")
    private Double learnedReachPercent;
    @Field("learned_p95_seconds")
    private Double learnedP95Seconds;
    @Field("example_trace_id")
    private String exampleTraceId;
    @Field("learned_at")
    private Instant learnedAt;

    // The latest check of an ACTIVE flow.
    @Field("last_checked_at")
    private Instant lastCheckedAt;
    @Field("last_checked_traces")
    private Integer lastCheckedTraces;
    @Field("last_broken_traces")
    private Integer lastBrokenTraces;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getEntryService() { return entryService; }
    public void setEntryService(String entryService) { this.entryService = entryService; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public String getRouteExample() { return routeExample; }
    public void setRouteExample(String routeExample) { this.routeExample = routeExample; }
    public String getTargetService() { return targetService; }
    public void setTargetService(String targetService) { this.targetService = targetService; }
    public int getMaxSeconds() { return maxSeconds; }
    public void setMaxSeconds(int maxSeconds) { this.maxSeconds = maxSeconds; }
    public double getMaxBrokenPercent() { return maxBrokenPercent; }
    public void setMaxBrokenPercent(double maxBrokenPercent) { this.maxBrokenPercent = maxBrokenPercent; }
    public int getMinTraces() { return minTraces; }
    public void setMinTraces(int minTraces) { this.minTraces = minTraces; }
    public TraceFlowStatus getStatus() { return status; }
    public void setStatus(TraceFlowStatus status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public Integer getLearnedSamples() { return learnedSamples; }
    public void setLearnedSamples(Integer learnedSamples) { this.learnedSamples = learnedSamples; }
    public Double getLearnedReachPercent() { return learnedReachPercent; }
    public void setLearnedReachPercent(Double learnedReachPercent) { this.learnedReachPercent = learnedReachPercent; }
    public Double getLearnedP95Seconds() { return learnedP95Seconds; }
    public void setLearnedP95Seconds(Double learnedP95Seconds) { this.learnedP95Seconds = learnedP95Seconds; }
    public String getExampleTraceId() { return exampleTraceId; }
    public void setExampleTraceId(String exampleTraceId) { this.exampleTraceId = exampleTraceId; }
    public Instant getLearnedAt() { return learnedAt; }
    public void setLearnedAt(Instant learnedAt) { this.learnedAt = learnedAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public Integer getLastCheckedTraces() { return lastCheckedTraces; }
    public void setLastCheckedTraces(Integer lastCheckedTraces) { this.lastCheckedTraces = lastCheckedTraces; }
    public Integer getLastBrokenTraces() { return lastBrokenTraces; }
    public void setLastBrokenTraces(Integer lastBrokenTraces) { this.lastBrokenTraces = lastBrokenTraces; }
}
