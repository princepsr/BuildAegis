package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.AiAskRequest;
import com.riskscanner.dependencyriskanalyzer.service.AiSettingsService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Answers a developer's free-form question about one finding ("Is this exploitable if I only use the library for
 * JSON parsing?"). The AI sees only the finding's details from the request, never project source code, and is told
 * to say so when the details do not answer the question.
 */
@Service
@Lazy
public class AiAskService {

    static final int MAX_QUESTION_LENGTH = 500;
    private static final int MAX_FIELD_LENGTH = 2000;
    private static final int MAX_LIST_ITEMS = 10;

    private final AiClientFactory aiClientFactory;
    private final AiSettingsService aiSettingsService;

    /** Pause between retries; replaced in tests so they do not actually wait. */
    private AiRetry.Sleeper retrySleeper = Thread::sleep;

    public AiAskService(@Lazy AiClientFactory aiClientFactory, AiSettingsService aiSettingsService) {
        this.aiClientFactory = aiClientFactory;
        this.aiSettingsService = aiSettingsService;
    }

    void setRetrySleeper(AiRetry.Sleeper sleeper) {
        this.retrySleeper = sleeper;
    }

    /**
     * @return the AI's answer as plain text
     * @throws IllegalArgumentException if the question is missing or too long
     * @throws IllegalStateException    if AI is not configured, or the provider fails
     */
    public String ask(AiAskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        if (request.question().length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("question must be at most " + MAX_QUESTION_LENGTH + " characters");
        }

        var settings = aiSettingsService.getSettings();
        if (settings == null || !settings.configured()) {
            throw new IllegalStateException("AI is not configured. Please configure an AI provider and API key.");
        }
        AiClient client = aiClientFactory.create(settings.provider(), aiSettingsService.getApiKeyOrThrow(), settings.model());

        String prompt = buildPrompt(request);
        String answer = AiRetry.call(() -> {
            String text = client.generateCompletion(prompt);
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("AI provider returned an empty response");
            }
            if (text.startsWith("Error:")) {
                throw new IllegalStateException(text);
            }
            return text;
        }, AiRetry.DEFAULT_ATTEMPTS, AiRetry.DEFAULT_BASE_DELAY_MILLIS, retrySleeper);

        return answer.trim();
    }

    static String buildPrompt(AiAskRequest r) {
        StringBuilder p = new StringBuilder();
        p.append("You are a software supply-chain security assistant answering a developer's question about ONE dependency finding.\n");
        p.append("Use only the finding details below and general security knowledge. You cannot see the developer's code, ")
                .append("so if the answer depends on how the library is used, say what to check instead of guessing. ")
                .append("Be concise (at most 6 sentences) and plain English. Do not invent CVE details you are not sure of.\n\n");

        p.append("Finding details:\n");
        line(p, "Dependency", r.dependency());
        line(p, "Severity", r.severity());
        if (r.vulnerabilityCount() != null) {
            line(p, "Known vulnerabilities", String.valueOf(r.vulnerabilityCount()));
        }
        if (r.vulnerabilityIds() != null && !r.vulnerabilityIds().isEmpty()) {
            line(p, "Advisory ids", String.join(", ", limit(r.vulnerabilityIds())));
        }
        if (Boolean.TRUE.equals(r.knownExploited())) {
            line(p, "Exploited in the wild", "yes (CISA Known Exploited Vulnerabilities)");
        }
        line(p, "Smallest fixed version", r.fixVersion());
        line(p, "Risk explanation", r.explanation());
        if (r.recommendations() != null && !r.recommendations().isEmpty()) {
            line(p, "Recommendations", String.join(" | ", limit(r.recommendations())));
        }

        p.append("\nDeveloper's question: ").append(clip(r.question().trim(), MAX_QUESTION_LENGTH)).append("\n");
        return p.toString();
    }

    private static void line(StringBuilder p, String label, String value) {
        if (value != null && !value.isBlank()) {
            p.append(label).append(": ").append(clip(value.trim(), MAX_FIELD_LENGTH)).append("\n");
        }
    }

    private static List<String> limit(List<String> items) {
        return items.stream().limit(MAX_LIST_ITEMS).map(s -> clip(s == null ? "" : s, MAX_FIELD_LENGTH)).toList();
    }

    private static String clip(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
