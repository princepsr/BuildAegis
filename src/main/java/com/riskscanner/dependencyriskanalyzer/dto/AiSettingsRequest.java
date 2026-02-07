package com.riskscanner.dependencyriskanalyzer.dto;

public record AiSettingsRequest(
        String provider,
        String apiKey,
        String model,
        String customEndpoint
) {
}
