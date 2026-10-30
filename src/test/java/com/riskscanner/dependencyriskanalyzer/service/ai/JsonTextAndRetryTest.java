package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTextAndRetryTest {

    // ---------- JsonText

    @Test
    void escapedTextRoundTripsThroughJson() throws Exception {
        String nasty = "quote \" backslash \\ tab \t newline \n windows C:\\Users\\x unicode \u00e9\u4e2d control \u0001 end";
        String body = "{\"prompt\":\"" + JsonText.escape(nasty) + "\"}";

        assertEquals(nasty, new ObjectMapper().readTree(body).get("prompt").asText());
    }

    @Test
    void oldStyleEscapingWasNotEnough() throws Exception {
        String text = "path C:\\Users\\x";
        String oldStyle = text.replace("\"", "\\\"").replace("\n", "\\n");
        assertThrows(Exception.class, () -> new ObjectMapper().readTree("{\"p\":\"" + oldStyle + "\"}"),
                "an unescaped backslash makes the old hand-built JSON invalid (\\U is not a JSON escape)");

        // the new escaping handles it
        assertEquals(text, new ObjectMapper().readTree("{\"p\":\"" + JsonText.escape(text) + "\"}").get("p").asText());
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", JsonText.escape(null));
    }

    // ---------- AiRetry

    private static class Sleeps implements AiRetry.Sleeper {
        final List<Long> delays = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            delays.add(millis);
        }
    }

    @Test
    void retriesRateLimitErrorsAndThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        Sleeps sleeps = new Sleeps();

        String result = AiRetry.call(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("HTTP 429 Too Many Requests");
            }
            return "ok";
        }, 3, 100, sleeps);

        assertEquals("ok", result);
        assertEquals(3, calls.get());
        assertEquals(2, sleeps.delays.size());
        assertTrue(sleeps.delays.get(1) >= sleeps.delays.get(0), "backoff grows");
    }

    @Test
    void givesUpAfterTheMaximumNumberOfAttempts() {
        AtomicInteger calls = new AtomicInteger();

        var e = assertThrows(IllegalStateException.class, () -> AiRetry.call(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("rate limit exceeded");
        }, 3, 1, new Sleeps()));

        assertEquals(3, calls.get());
        assertTrue(e.getMessage().contains("rate limit"));
    }

    @Test
    void doesNotRetryErrorsThatRetryingCannotFix() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> AiRetry.call(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("HTTP 401 invalid api key");
        }, 3, 1, new Sleeps()));

        assertEquals(1, calls.get());
    }

    @Test
    void honorsRetryAfterSecondsButCapsIt() {
        assertEquals(7000, AiRetry.delayFor(new RuntimeException("429 Retry-After: 7"), 1, 1000));
        assertEquals(30_000, AiRetry.delayFor(new RuntimeException("429 retry-after 600"), 1, 1000));
    }

    @Test
    void recognizesTemporaryFailuresIncludingNestedCauses() {
        assertTrue(AiRetry.isRetryable(new RuntimeException("Gemini request failed", new RuntimeException("HTTP 503"))));
        assertTrue(AiRetry.isRetryable(new RuntimeException("The model is overloaded")));
        assertTrue(AiRetry.isRetryable(new RuntimeException("request timed out")));
        assertFalse(AiRetry.isRetryable(new RuntimeException("Invalid JSON in request")));
        assertFalse(AiRetry.isRetryable(new RuntimeException((String) null)));
    }

    @Test
    void successOnFirstTryDoesNotSleep() {
        Sleeps sleeps = new Sleeps();
        assertEquals("fine", AiRetry.call(() -> "fine", 3, 1000, sleeps));
        assertTrue(sleeps.delays.isEmpty());
    }
}
