package com.moses.monitor.ai.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardEditorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> KNOWN = List.of("checkout", "payments", "search");
    private static final DashboardEditor.Current CURRENT =
            new DashboardEditor.Current("prod overview", "ENVIRONMENT", null, List.of(), "cpu", 60);

    private static DashboardEditor.Edit parse(String reply) throws Exception {
        return DashboardEditor.parse(reply, CURRENT, KNOWN, MAPPER);
    }

    @Test
    void appliesValidChanges() throws Exception {
        var edit = parse("Sure: {\"services\":[\"Checkout\"],\"metric\":\"memory\",\"minutesBack\":360,"
                + "\"summary\":\"done\",\"note\":null}");
        assertEquals(Map.of("services", List.of("checkout"), "metric", "memory", "minutesBack", 360), edit.changes());
        assertEquals("done", edit.summary());
        assertNull(edit.note());
    }

    @Test
    void unchangedFieldsAreLeftOut() throws Exception {
        var edit = parse("{\"title\":\"prod overview\",\"metric\":\"cpu\",\"minutesBack\":60,\"summary\":\"x\"}");
        assertTrue(edit.changes().isEmpty());
    }

    @Test
    void snapsRangeToWhatTheChartOffers() {
        assertEquals(60, DashboardEditor.snap(120));
        assertEquals(360, DashboardEditor.snap(240));
        assertEquals(1440, DashboardEditor.snap(2880));
        assertEquals(10080, DashboardEditor.snap(43200));
        assertEquals(15, DashboardEditor.snap(5));
    }

    @Test
    void unknownServicesAreDroppedAndReported() throws Exception {
        var edit = parse("{\"services\":[\"payments\",\"billing\"],\"summary\":\"x\",\"note\":null}");
        assertEquals(List.of("payments"), edit.changes().get("services"));
        assertTrue(edit.note().contains("billing"));
    }

    @Test
    void onlyUnknownServicesKeepsTheCurrentList() throws Exception {
        var edit = parse("{\"services\":[\"billing\"],\"summary\":\"x\"}");
        assertTrue(edit.changes().isEmpty());
        assertTrue(edit.note().contains("billing"));
    }

    @Test
    void invalidMetricIsIgnoredAndModelNoteIsKept() throws Exception {
        var edit = parse("{\"metric\":\"disk\",\"summary\":\"x\",\"note\":\"Only CPU and memory are available.\"}");
        assertTrue(edit.changes().isEmpty());
        assertEquals("Only CPU and memory are available.", edit.note());
    }

    @Test
    void longTitleIsCut() throws Exception {
        var edit = parse("{\"title\":\"" + "a".repeat(300) + "\",\"summary\":\"x\"}");
        assertEquals(120, ((String) edit.changes().get("title")).length());
    }
}
