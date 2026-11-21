package com.moses.monitor.process;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentRulesTest {

    @Test
    void spikeNeedsEnoughErrorsAndWellAboveNormal() {
        AlertRule rule = AlertRule.defaults("orders"); // at least 5, and 3x normal
        assertFalse(IncidentDetector.isSpike(4, 0, rule), "below the minimum");
        assertTrue(IncidentDetector.isSpike(5, 0, rule), "quiet service, 5 errors");
        assertTrue(IncidentDetector.isSpike(30, 10, rule), "3x its normal");
        assertFalse(IncidentDetector.isSpike(20, 10, rule), "noisy service at 2x is its normal noise");
    }

    @Test
    void customRuleIsUsed() {
        AlertRule rule = AlertRule.defaults("orders");
        rule.setMinErrors(50);
        rule.setFactor(1.5);
        assertFalse(IncidentDetector.isSpike(40, 0, rule));
        assertTrue(IncidentDetector.isSpike(60, 30, rule));
    }

    @Test
    void messagesThatDifferOnlyInValuesShareASignature() {
        assertEquals(ErrorSignature.of("Order 1234 failed: timeout after 3000ms"),
                ErrorSignature.of("order 98 failed: timeout after 5000ms"));
        assertEquals(ErrorSignature.of("user 'dana' not found (id 6afdb0f7-d513-0804-9030-c117aaaaaaaa)"),
                ErrorSignature.of("user 'avi' not found (id 11111111-2222-3333-4444-555555555555)"));
        assertEquals(ErrorSignature.of("connect to 10.0.0.5:5432 refused\n  at Foo.bar"),
                ErrorSignature.of("connect to 10.0.0.7:5432 refused\n  at Baz.qux"));
        assertFalse(ErrorSignature.of("payment declined").equals(ErrorSignature.of("payment timeout")));
    }

    @Test
    void errorBucketsGrowWithRange() {
        assertEquals(1, MetricsController.errorBucketMinutes(60));
        assertEquals(5, MetricsController.errorBucketMinutes(360));
        assertEquals(15, MetricsController.errorBucketMinutes(1440));
        assertEquals(60, MetricsController.errorBucketMinutes(10080));
    }
}
