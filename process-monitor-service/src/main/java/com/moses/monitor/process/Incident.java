package com.moses.monitor.process;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One problem with one service, from the moment it was detected until it went back to normal.
 * While the problem goes on it stays the same incident (updated every check) instead of a new
 * alert every minute. Opened and closed by {@link IncidentDetector}.
 */
@Document(collection = "incidents")
public class Incident {

    /** One kind of error seen during the incident: messages that differ only in ids/numbers count as one. */
    /**
     * What the AI concluded when someone asked it to investigate (ai-service). Kept apart from the
     * team's review: people decide what goes into the library, the AI only proposes.
     * status: RUNNING, DONE or FAILED.
     */
    public record AiInvestigation(String status, String summary, String rootCause, String suggestedFix,
                                  String confidence, List<String> evidence, String error, String model,
                                  String requestedBy, Instant startedAt, Instant finishedAt, Integer steps) { }

    /** A trace of a broken flow: where it stopped and why (NOT_REACHED, TOO_SLOW, ERROR). */
    public record BrokenTrace(String traceId, String stoppedAt, String reason) { }

    public record ErrorGroup(String message, long count, Instant lastAt, String traceId, String signature) { }

    @Id
    private String id;
    @Field("project_id")
    private String projectId;
    private String environment;
    @Field("service_name")
    private String serviceName;
    private IncidentKind kind;
    private IncidentState state;
    @Field("opened_at")
    private Instant openedAt;
    @Field("closed_at")
    private Instant closedAt;
    /** Last check at which the condition still held; the incident closes a quiet window after it. */
    @Field("last_breach_at")
    private Instant lastBreachAt;
    @Field("window_minutes")
    private int windowMinutes;
    /** ERROR_SPIKE: errors in the latest window, the most in any window, and the normal amount per window. */
    @Field("current_errors")
    private long currentErrors;
    @Field("peak_errors")
    private long peakErrors;
    @Field("baseline_errors")
    private double baselineErrors;
    @Field("top_errors")
    private List<ErrorGroup> topErrors = new ArrayList<>();

    // ---- FLOW_BROKEN only (serviceName is the flow's target) ----
    @Field("flow_id")
    private String flowId;
    @Field("flow_entry")
    private String flowEntry;
    @Field("flow_route_example")
    private String flowRouteExample;
    @Field("flow_max_seconds")
    private Integer flowMaxSeconds;
    /** Latest window: traces checked and how many broke; and the highest broken count seen. */
    @Field("checked_traces")
    private int checkedTraces;
    @Field("broken_traces")
    private int brokenTraces;
    @Field("peak_broken_traces")
    private int peakBrokenTraces;
    @Field("broken_examples")
    private List<BrokenTrace> brokenExamples = new ArrayList<>();
    /**
     * Every kind of error seen during the incident ({@link ErrorSignature}), kept even after it
     * drops out of the top list - this is what "seen before" matches on.
     */
    private List<String> signatures = new ArrayList<>();

    // ---- the library part: what the team concluded ----
    @Field("review_status")
    private IncidentReview reviewStatus = IncidentReview.UNREVIEWED;
    @Field("root_cause")
    private String rootCause;
    private String resolution;
    /** User id of whoever wrote the review last ("agent" once the AI can write one). */
    @Field("reviewed_by")
    private String reviewedBy;
    @Field("reviewed_at")
    private Instant reviewedAt;
    @Field("ai_investigation")
    private AiInvestigation aiInvestigation;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public IncidentKind getKind() { return kind; }
    public void setKind(IncidentKind kind) { this.kind = kind; }
    public IncidentState getState() { return state; }
    public void setState(IncidentState state) { this.state = state; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public Instant getLastBreachAt() { return lastBreachAt; }
    public void setLastBreachAt(Instant lastBreachAt) { this.lastBreachAt = lastBreachAt; }
    public int getWindowMinutes() { return windowMinutes; }
    public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
    public long getCurrentErrors() { return currentErrors; }
    public void setCurrentErrors(long currentErrors) { this.currentErrors = currentErrors; }
    public long getPeakErrors() { return peakErrors; }
    public void setPeakErrors(long peakErrors) { this.peakErrors = peakErrors; }
    public double getBaselineErrors() { return baselineErrors; }
    public void setBaselineErrors(double baselineErrors) { this.baselineErrors = baselineErrors; }
    public List<ErrorGroup> getTopErrors() { return topErrors; }
    public void setTopErrors(List<ErrorGroup> topErrors) { this.topErrors = topErrors == null ? new ArrayList<>() : topErrors; }
    public String getFlowId() { return flowId; }
    public void setFlowId(String flowId) { this.flowId = flowId; }
    public String getFlowEntry() { return flowEntry; }
    public void setFlowEntry(String flowEntry) { this.flowEntry = flowEntry; }
    public String getFlowRouteExample() { return flowRouteExample; }
    public void setFlowRouteExample(String flowRouteExample) { this.flowRouteExample = flowRouteExample; }
    public Integer getFlowMaxSeconds() { return flowMaxSeconds; }
    public void setFlowMaxSeconds(Integer flowMaxSeconds) { this.flowMaxSeconds = flowMaxSeconds; }
    public int getCheckedTraces() { return checkedTraces; }
    public void setCheckedTraces(int checkedTraces) { this.checkedTraces = checkedTraces; }
    public int getBrokenTraces() { return brokenTraces; }
    public void setBrokenTraces(int brokenTraces) { this.brokenTraces = brokenTraces; }
    public int getPeakBrokenTraces() { return peakBrokenTraces; }
    public void setPeakBrokenTraces(int peakBrokenTraces) { this.peakBrokenTraces = peakBrokenTraces; }
    public List<BrokenTrace> getBrokenExamples() { return brokenExamples; }
    public void setBrokenExamples(List<BrokenTrace> brokenExamples) { this.brokenExamples = brokenExamples == null ? new ArrayList<>() : brokenExamples; }
    public List<String> getSignatures() { return signatures; }
    public void setSignatures(List<String> signatures) { this.signatures = signatures == null ? new ArrayList<>() : signatures; }
    public IncidentReview getReviewStatus() { return reviewStatus == null ? IncidentReview.UNREVIEWED : reviewStatus; }
    public void setReviewStatus(IncidentReview reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }
    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public AiInvestigation getAiInvestigation() { return aiInvestigation; }
    public void setAiInvestigation(AiInvestigation aiInvestigation) { this.aiInvestigation = aiInvestigation; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
}
