package com.riskscanner.dependencyriskanalyzer.dto;

import java.time.Instant;

public record AiSettingsResponse(
        String provider,
        String model,
        String customEndpoint,
        String apiKey,
        boolean configured,
        Instant updatedAt
) {
}
