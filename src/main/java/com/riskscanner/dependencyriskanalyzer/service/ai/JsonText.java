package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Escapes text for embedding inside a hand-built JSON string.
 *
 * <p>Some provider clients assemble their request body with string formatting. Escaping only quotes and newlines
 * produces invalid JSON as soon as the text contains a backslash, tab or other control character (which prompts that
 * include release notes or file paths easily do). This escapes everything JSON requires.
 */
final class JsonText {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonText() {
    }

    /**
     * @return the JSON-escaped content of {@code text}, without the surrounding quotes
     */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        try {
            String quoted = MAPPER.writeValueAsString(text);
            return quoted.substring(1, quoted.length() - 1);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not escape text for JSON", e);
        }
    }
}
