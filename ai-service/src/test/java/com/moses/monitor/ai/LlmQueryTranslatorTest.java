package com.moses.monitor.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moses.monitor.ai.llm.DisabledLlmClient;
import com.moses.monitor.ai.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmQueryTranslatorTest {

    private static final List<String> SERVICES = List.of("order-service", "payments-api");

    /** Catalog stub: no network, fixed names. */
    private static ServiceCatalog catalog(List<String> names) {
        return new ServiceCatalog("") {
            @Override
            public List<String> serviceNames() {
                return names;
            }
        };
    }

    private static LlmClient replying(Function<String, String> reply) {
        return new LlmClient() {
            @Override
            public String complete(String system, String user) {
                return reply.apply(user);
            }

            @Override
            public String describe() {
                return "fake";
            }
        };
    }

    private static LlmQueryTranslator translator(LlmClient llm) {
        return new LlmQueryTranslator(llm, new NaiveNlQueryTranslator(), catalog(SERVICES), new ObjectMapper());
    }

    @Test
    void parsesAStructuredReply() {
        Instant now = Instant.now();
        String json = """
                {"words":["Timeout"],"service":"ORDER-SERVICE","logLevel":4,"traceId":null,
                 "from":"%s","to":"%s"}""".formatted(now.minus(Duration.ofMinutes(10)), now);

        TranslatedQuery q = translator(replying(u -> json)).translate("errors in the order service, last 10 minutes");

        assertEquals("llm", q.getTranslator());
        assertEquals(List.of("timeout"), q.getWords());
        assertEquals("order-service", q.getService());
        assertEquals(4, q.getLogLevel());
        assertNull(q.getFallbackReason());
        assertTrue(Duration.between(q.getFrom(), q.getTo()).toMinutes() >= 9);
    }

    @Test
    void toleratesCodeFencesAndProse() {
        String reply = "Sure!\n```json\n{\"words\":[\"declined\"],\"service\":null,\"logLevel\":null}\n```";
        TranslatedQuery q = translator(replying(u -> reply)).translate("declined cards");

        assertEquals("llm", q.getTranslator());
        assertEquals(List.of("declined"), q.getWords());
        // No from/to given: defaults to the last hour ending now.
        assertEquals(Duration.ofHours(1), Duration.between(q.getFrom(), q.getTo()));
    }

    @Test
    void dropsUnknownServiceBadLevelAndClampsWindow() {
        Instant now = Instant.now();
        String json = """
                {"words":["a","b","c","d","e","f","g","h","i","j"],"service":"not-a-service","logLevel":9,
                 "traceId":"%s","from":"2020-01-01T00:00:00Z","to":"%s"}"""
                .formatted("x".repeat(500), now.plus(Duration.ofDays(3)));

        TranslatedQuery q = translator(replying(u -> json)).translate("whatever");

        assertNull(q.getService());
        assertNull(q.getLogLevel());
        assertNull(q.getTraceId());
        assertEquals(LlmQueryTranslator.MAX_WORDS, q.getWords().size());
        assertFalse(q.getTo().isAfter(Instant.now().plusSeconds(1)));
        assertTrue(Duration.between(q.getFrom(), q.getTo()).compareTo(LlmQueryTranslator.MAX_WINDOW) <= 0);
    }

    @Test
    void keepsATraceId() {
        String json = "{\"words\":[],\"traceId\":\"29ed4ca8-d956-456e-bbad-6f9da87bcc62\"}";
        TranslatedQuery q = translator(replying(u -> json)).translate("trace 29ed4ca8-d956-456e-bbad-6f9da87bcc62");

        assertEquals("29ed4ca8-d956-456e-bbad-6f9da87bcc62", q.getTraceId());
        assertTrue(q.getWords().isEmpty());
    }

    @Test
    void fallsBackOnGarbage() {
        TranslatedQuery q = translator(replying(u -> "I cannot help with that")).translate("show me errors");

        assertEquals("naive", q.getTranslator());
        assertNotNull(q.getFallbackReason());
        assertEquals(4, q.getLogLevel()); // naive translator still understood "errors"
    }

    @Test
    void fallsBackWhenTheProviderThrows() {
        LlmClient failing = replying(u -> {
            throw new IllegalStateException("boom");
        });
        TranslatedQuery q = translator(failing).translate("show me errors");

        assertEquals("naive", q.getTranslator());
        assertTrue(q.getFallbackReason().contains("IllegalStateException"));
    }

    @Test
    void usesNaiveWhenNoProviderIsConfigured() {
        TranslatedQuery q = translator(new DisabledLlmClient()).translate("timeout errors");

        assertEquals("naive", q.getTranslator());
        assertEquals("No LLM configured", q.getFallbackReason());
    }
}
