package com.moses.monitor.process;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The counting behind trace flows, kept free of Mongo so it can be tested directly: what paths
 * the traces usually take (to suggest flows), and which traces of a flow broke (to check it).
 */
final class FlowAnalysis {

    /** One service's part in a trace: when it first logged, its first message, its worst level. */
    record Hop(String service, Instant at, String message, int level) { }

    /** One trace, hops ordered by time; the first hop is where the request entered. */
    record TraceHops(String traceId, List<Hop> hops) {
        Hop entry() {
            return hops.get(0);
        }

        Hop hopOf(String service) {
            return hops.stream().filter(h -> h.service().equals(service)).findFirst().orElse(null);
        }

        boolean hasError() {
            return hops.stream().anyMatch(h -> h.level() >= IncidentDetector.ERROR_LEVEL);
        }
    }

    record Suggestion(String entryService, String route, String routeExample, String targetService,
                      int samples, double reachPercent, double p95Seconds, String exampleTraceId) { }

    enum BreakReason { NOT_REACHED, TOO_SLOW, ERROR }

    record Broken(String traceId, String stoppedAt, BreakReason reason) { }

    record CheckResult(int checked, List<Broken> broken) {
        double brokenPercent() {
            return checked == 0 ? 0 : broken.size() * 100.0 / checked;
        }
    }

    private FlowAnalysis() {
    }

    /**
     * Paths that nearly all traces of an entry point take. Traces are grouped by where they
     * entered and the kind of their first message (the "route"), and also by entry alone; a
     * target reached by at least {@code minReachPercent} of a group of at least {@code minSamples}
     * traces is suggested. When the entry alone already qualifies for a target, the per-route
     * suggestions for that target are dropped - the broader flow covers them.
     */
    static List<Suggestion> suggest(List<TraceHops> traces, int minSamples, double minReachPercent) {
        Map<String, List<TraceHops>> groups = new LinkedHashMap<>();
        for (TraceHops t : traces) {
            if (t.hops().isEmpty()) continue;
            Hop entry = t.entry();
            groups.computeIfAbsent(entry.service() + "\u0000", k -> new ArrayList<>()).add(t);
            groups.computeIfAbsent(entry.service() + "\u0000" + ErrorSignature.of(entry.message()), k -> new ArrayList<>()).add(t);
        }
        List<Suggestion> broad = new ArrayList<>();
        List<Suggestion> specific = new ArrayList<>();
        for (Map.Entry<String, List<TraceHops>> g : groups.entrySet()) {
            List<TraceHops> group = g.getValue();
            if (group.size() < minSamples) continue;
            String[] key = g.getKey().split("\u0000", -1);
            String entryService = key[0];
            String route = key[1].isEmpty() ? null : key[1];
            Map<String, List<Double>> delays = new HashMap<>();
            Map<String, String> example = new HashMap<>();
            for (TraceHops t : group) {
                Instant start = t.entry().at();
                for (Hop h : t.hops().subList(1, t.hops().size())) {
                    if (h.service().equals(entryService)) continue;
                    delays.computeIfAbsent(h.service(), k -> new ArrayList<>())
                            .add(Duration.between(start, h.at()).toMillis() / 1000.0);
                    example.putIfAbsent(h.service(), t.traceId());
                }
            }
            for (Map.Entry<String, List<Double>> d : delays.entrySet()) {
                double reach = d.getValue().size() * 100.0 / group.size();
                if (reach < minReachPercent) continue;
                Suggestion s = new Suggestion(entryService, route, route == null ? null : group.get(0).entry().message(),
                        d.getKey(), group.size(), round1(reach), round1(percentile(d.getValue(), 95)), example.get(d.getKey()));
                (route == null ? broad : specific).add(s);
            }
        }
        List<Suggestion> result = new ArrayList<>(broad);
        for (Suggestion s : specific) {
            boolean covered = broad.stream().anyMatch(b -> b.entryService().equals(s.entryService())
                    && b.targetService().equals(s.targetService()));
            if (!covered) result.add(s);
        }
        result.sort(Comparator.comparingInt(Suggestion::samples).reversed());
        return result;
    }

    /** A sensible deadline for a suggested flow: three times its usual slow case, at least 2 s. */
    static int suggestedMaxSeconds(double p95Seconds) {
        return (int) Math.min(600, Math.max(2, Math.ceil(p95Seconds * 3)));
    }

    /**
     * Checks one flow against the traces whose deadline (entry + maxSeconds) fell in
     * [deadlineFrom, deadlineTo). A trace is broken if it never reached the target, reached it
     * too late, or logged an error on the way.
     */
    static CheckResult check(String entryService, String route, String targetService, int maxSeconds,
                             List<TraceHops> traces, Instant deadlineFrom, Instant deadlineTo) {
        int checked = 0;
        List<Broken> broken = new ArrayList<>();
        for (TraceHops t : traces) {
            if (t.hops().isEmpty()) continue;
            Hop entry = t.entry();
            if (!entry.service().equals(entryService)) continue;
            if (route != null && !route.equals(ErrorSignature.of(entry.message()))) continue;
            Instant deadline = entry.at().plusSeconds(maxSeconds);
            if (deadline.isBefore(deadlineFrom) || !deadline.isBefore(deadlineTo)) continue;
            checked++;
            Hop target = t.hopOf(targetService);
            String stoppedAt = t.hops().stream().filter(h -> !h.at().isAfter(deadline))
                    .reduce((a, b) -> b).map(Hop::service).orElse(entryService);
            if (t.hasError()) {
                String where = t.hops().stream().filter(h -> h.level() >= IncidentDetector.ERROR_LEVEL)
                        .findFirst().map(Hop::service).orElse(stoppedAt);
                broken.add(new Broken(t.traceId(), where, BreakReason.ERROR));
            } else if (target == null) {
                broken.add(new Broken(t.traceId(), stoppedAt, BreakReason.NOT_REACHED));
            } else if (target.at().isAfter(deadline)) {
                broken.add(new Broken(t.traceId(), stoppedAt, BreakReason.TOO_SLOW));
            }
        }
        return new CheckResult(checked, broken);
    }

    static double percentile(List<Double> values, int p) {
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
