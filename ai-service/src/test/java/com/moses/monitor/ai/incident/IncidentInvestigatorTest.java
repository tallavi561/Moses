package com.moses.monitor.ai.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentInvestigatorTest {

    private final IncidentInvestigator investigator = new IncidentInvestigator(null, null, new ObjectMapper(), 5);

    @Test
    void readsTheJsonEvenWithTextAroundIt() {
        var f = investigator.parse("Here you go:\n```json\n{\"summary\":\"DB pool ran out\",\"rootCause\":\"pool of 10\","
                + "\"suggestedFix\":\"raise to 50\",\"confidence\":\"HIGH\",\"evidence\":[\"timeout x40\",\"\",\"pool=10 in config\"]}\n```");
        assertEquals("DB pool ran out", f.summary());
        assertEquals("pool of 10", f.rootCause());
        assertEquals("raise to 50", f.suggestedFix());
        assertEquals("high", f.confidence());
        assertEquals(List.of("timeout x40", "pool=10 in config"), f.evidence());
    }

    @Test
    void unknownConfidenceIsLowAndProseBecomesTheSummary() {
        assertEquals("low", investigator.parse("{\"summary\":\"x\",\"confidence\":\"certain\"}").confidence());
        var f = investigator.parse("I think the database is down.");
        assertEquals("I think the database is down.", f.summary());
        assertNull(f.rootCause());
        assertEquals("low", f.confidence());
    }

    @Test
    void promptAsksForTheUsersLanguage() {
        assertTrue(IncidentInvestigator.systemPrompt("Shop", "prod", null, null, "he").contains("in Hebrew"));
        assertTrue(IncidentInvestigator.systemPrompt("Shop", "prod", null, null, "en").contains("in English"));
    }
}
