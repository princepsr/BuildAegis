package com.riskscanner.dependencyriskanalyzer.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void badInputIsA400WithTheMessage() {
        var r = handler.handleBadRequest(new IllegalArgumentException("projectPath is required"));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertEquals("projectPath is required", r.getBody().get("error"));

        var r2 = handler.handleBadRequest(new IllegalStateException("AI is not configured."));
        assertEquals(HttpStatus.BAD_REQUEST, r2.getStatusCode());
    }

    @Test
    void otherFailuresAre500WithoutExceptionDetails() {
        var r = handler.handleOther(new Exception("No supported build file found in: C:/x"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
        assertEquals("No supported build file found in: C:/x", r.getBody().get("error"));
        assertEquals(1, r.getBody().size(), "only the error message, nothing like a stack trace or class name");
    }

    @Test
    void missingMessageFallsBackToAGenericOne() {
        assertEquals("Internal server error", handler.handleOther(new NullPointerException()).getBody().get("error"));
        assertEquals("Invalid request", handler.handleBadRequest(new IllegalArgumentException()).getBody().get("error"));
    }

    @Test
    void apiKeysAndTokensAreRedactedFromMessages() {
        String msg = ApiExceptionHandler.sanitize(
                "Incorrect API key provided: sk-proj-abcdef1234567890. Authorization: Bearer abcdefghijklmnop", "x");
        assertFalse(msg.contains("sk-proj-abcdef1234567890"));
        assertFalse(msg.contains("abcdefghijklmnop"));
        assertTrue(msg.contains("[redacted]"));

        assertFalse(ApiExceptionHandler.sanitize("key=AIzaSyA1234567890abcdef rejected", "x").contains("AIzaSyA1234567890abcdef"));
    }

    @Test
    void messagesAreSingleLineAndLengthLimited() {
        String multi = ApiExceptionHandler.sanitize("line one\n\tline two\r\nline three", "x");
        assertEquals("line one line two line three", multi);

        String longMsg = ApiExceptionHandler.sanitize("a".repeat(1000), "x");
        assertEquals(303, longMsg.length());
        assertTrue(longMsg.endsWith("..."));
    }

    @Test
    void ordinaryTextIsNotMangled() {
        assertEquals("Failed to resolve dependencies for C:/proj: timeout",
                ApiExceptionHandler.sanitize("Failed to resolve dependencies for C:/proj: timeout", "x"));
    }
}
