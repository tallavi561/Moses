package com.moses.monitor.process;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowAnalysisTest {

    private static final Instant T0 = Instant.parse("2026-11-21T10:00:00Z");

    private static FlowAnalysis.TraceHops trace(String id, int startSec, String firstMessage, Object... hops) {
        List<FlowAnalysis.Hop> list = new ArrayList<>();
        list.add(new FlowAnalysis.Hop("gateway", T0.plusSeconds(startSec), firstMessage, 2));
        for (int i = 0; i < hops.length; i += 3) {
            list.add(new FlowAnalysis.Hop((String) hops[i], T0.plusSeconds(startSec + (Integer) hops[i + 1]), "x", (Integer) hops[i + 2]));
        }
        return new FlowAnalysis.TraceHops(id, list);
    }

    @Test
    void suggestsTheBroadFlowWhenEveryRouteReachesTheTarget() {
        List<FlowAnalysis.TraceHops> traces = new ArrayList<>();
        for (int i = 0; i < 20; i++) traces.add(trace("a" + i, i, "POST /orders " + i, "orders", 1, 2, "payments", 2, 2));
        for (int i = 0; i < 20; i++) traces.add(trace("b" + i, i, "GET /orders/" + i, "orders", 1, 2));
        List<FlowAnalysis.Suggestion> s = FlowAnalysis.suggest(traces, 10, 90);
        // gateway -> orders: every trace (broad). gateway -> payments: only the POST route.
        assertTrue(s.stream().anyMatch(x -> x.route() == null && x.targetService().equals("orders") && x.samples() == 40));
        assertTrue(s.stream().noneMatch(x -> x.route() != null && x.targetService().equals("orders")), "covered by the broad one");
        FlowAnalysis.Suggestion pay = s.stream().filter(x -> x.targetService().equals("payments")).findFirst().orElseThrow();
        assertEquals(ErrorSignature.of("POST /orders 1"), pay.route());
        assertEquals(100.0, pay.reachPercent());
        assertEquals(2.0, pay.p95Seconds());
        assertEquals(6, FlowAnalysis.suggestedMaxSeconds(pay.p95Seconds()));
    }

    @Test
    void tooFewOrTooUnreliablePathsAreNotSuggested() {
        List<FlowAnalysis.TraceHops> traces = new ArrayList<>();
        for (int i = 0; i < 20; i++) traces.add(i < 15 ? trace("a" + i, i, "POST /x", "cache", 1, 2) : trace("a" + i, i, "POST /x"));
        assertTrue(FlowAnalysis.suggest(traces, 10, 90).isEmpty(), "75% reach is not a rule");
        assertTrue(FlowAnalysis.suggest(traces.subList(0, 5), 10, 90).isEmpty(), "not enough samples");
    }

    @Test
    void checkFindsMissingSlowAndFailedTraces() {
        List<FlowAnalysis.TraceHops> traces = List.of(
                trace("ok", 0, "POST /orders 1", "orders", 1, 2, "payments", 2, 2),
                trace("missing", 0, "POST /orders 2", "orders", 1, 2),
                trace("slow", 0, "POST /orders 3", "orders", 1, 2, "payments", 9, 2),
                trace("error", 0, "POST /orders 4", "orders", 1, 4, "payments", 2, 2),
                trace("other-route", 0, "GET /health", "orders", 1, 2),
                trace("too-recent", 100, "POST /orders 5", "orders", 1, 2));
        FlowAnalysis.CheckResult r = FlowAnalysis.check("gateway", ErrorSignature.of("POST /orders 9"), "payments", 5,
                traces, T0, T0.plusSeconds(60));
        assertEquals(4, r.checked());
        assertEquals(List.of("missing:orders:NOT_REACHED", "slow:orders:TOO_SLOW", "error:orders:ERROR"),
                r.broken().stream().map(b -> b.traceId() + ":" + b.stoppedAt() + ":" + b.reason()).toList());
        assertEquals(75.0, r.brokenPercent());
    }
}
