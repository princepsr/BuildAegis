package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.AiAskRequest;
import com.riskscanner.dependencyriskanalyzer.dto.AiSettingsResponse;
import com.riskscanner.dependencyriskanalyzer.service.AiSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAskServiceTest {

    private AiClient client;
    private AiSettingsService settings;
    private AiAskService service;
    private final List<Long> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        client = mock(AiClient.class);
        AiClientFactory factory = mock(AiClientFactory.class);
        when(factory.create(anyString(), anyString(), anyString())).thenReturn(client);

        settings = mock(AiSettingsService.class);
        when(settings.getSettings()).thenReturn(new AiSettingsResponse("openai", "m", null, "k", true, Instant.now()));
        when(settings.getApiKeyOrThrow()).thenReturn("k");

        service = new AiAskService(factory, settings);
        service.setRetrySleeper(sleeps::add);
    }

    private AiAskRequest request(String question) {
        return new AiAskRequest("org.apache.tomcat.embed:tomcat-embed-core:10.1.34", "CRITICAL", 11,
                List.of("CVE-2025-24813"), true, "10.1.36", "Remote code execution via partial PUT.",
                List.of("Upgrade to 10.1.36"), question);
    }

    @Test
    void returnsTheTrimmedAnswer() {
        when(client.generateCompletion(anyString())).thenReturn("  Only if you enable partial PUT.  \n");

        assertEquals("Only if you enable partial PUT.", service.ask(request("Am I affected if I only serve static files?")));
    }

    @Test
    void promptContainsTheFindingDetailsTheQuestionAndGuardrails() {
        when(client.generateCompletion(anyString())).thenReturn("ok");

        service.ask(request("Am I affected?"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(client).generateCompletion(captor.capture());
        String prompt = captor.getValue();
        assertTrue(prompt.contains("tomcat-embed-core:10.1.34"));
        assertTrue(prompt.contains("CVE-2025-24813"));
        assertTrue(prompt.contains("Exploited in the wild: yes"));
        assertTrue(prompt.contains("Smallest fixed version: 10.1.36"));
        assertTrue(prompt.contains("Developer's question: Am I affected?"));
        assertTrue(prompt.contains("cannot see the developer's code"));
    }

    @Test
    void emptyOrMissingQuestionIsRejectedWithoutCallingAi() {
        assertThrows(IllegalArgumentException.class, () -> service.ask(request("   ")));
        assertThrows(IllegalArgumentException.class, () -> service.ask(request(null)));
        assertThrows(IllegalArgumentException.class, () -> service.ask(null));
        verify(client, never()).generateCompletion(anyString());
    }

    @Test
    void overlongQuestionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.ask(request("x".repeat(501))));

        when(client.generateCompletion(anyString())).thenReturn("fine");
        assertEquals("fine", service.ask(request("x".repeat(500)))); // exactly at the limit is accepted
    }

    @Test
    void notConfiguredGivesAClearError() {
        when(settings.getSettings()).thenReturn(new AiSettingsResponse("openai", "m", null, null, false, null));

        var e = assertThrows(IllegalStateException.class, () -> service.ask(request("Q?")));

        assertTrue(e.getMessage().contains("not configured"));
        verify(client, never()).generateCompletion(anyString());
    }

    @Test
    void rateLimitsAreRetried() {
        when(client.generateCompletion(anyString())).thenThrow(new IllegalStateException("HTTP 429")).thenReturn("answer");

        assertEquals("answer", service.ask(request("Q?")));
        verify(client, times(2)).generateCompletion(anyString());
        assertEquals(1, sleeps.size());
    }

    @Test
    void providerErrorTextAndEmptyAnswersAreFailuresNotAnswers() {
        when(client.generateCompletion(anyString())).thenReturn("Error: HTTP 401 - invalid key");
        assertThrows(IllegalStateException.class, () -> service.ask(request("Q?")));

        when(client.generateCompletion(anyString())).thenReturn(" ");
        assertThrows(IllegalStateException.class, () -> service.ask(request("Q?")));
    }

    @Test
    void oversizedFieldsAndListsAreClippedInThePrompt() {
        var big = new AiAskRequest("g:a:1", "HIGH", 1, java.util.Collections.nCopies(30, "CVE-1"), false, null,
                "e".repeat(5000), java.util.Collections.nCopies(30, "rec"), "Q?");

        String prompt = AiAskService.buildPrompt(big);

        assertFalse(prompt.contains("e".repeat(2100)));
        assertEquals(10, prompt.split("CVE-1", -1).length - 1, "at most ten advisory ids");
        assertFalse(prompt.contains("Exploited in the wild"));
    }
}
