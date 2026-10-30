package com.riskscanner.dependencyriskanalyzer;

import com.riskscanner.dependencyriskanalyzer.repository.AiSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks of the HTTP layer with the real Spring context and an in-memory database.
 * Each test rolls back its database changes.
 */
@SpringBootTest(
        classes = BuildAegisApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:apitest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "buildaegis.encryption.key-file=target/test-data/api-test.key"
        })
@AutoConfigureMockMvc
@Transactional
class ApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AiSettingsRepository aiSettingsRepository;

    private static RequestPostProcessor from(String remoteAddr) {
        return request -> {
            request.setRemoteAddr(remoteAddr);
            return request;
        };
    }

    // ---------- AI settings: the API key is never returned

    @Test
    void savedApiKeyIsStoredEncryptedAndOnlyEverReturnedMasked() throws Exception {
        String body = "{\"provider\":\"openai\",\"apiKey\":\"sk-abcdefghijklmnopwxyz\",\"model\":\"gpt-4o-mini\"}";

        mvc.perform(put("/api/ai/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value("********wxyz"));

        mvc.perform(get("/api/ai/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.apiKey").value("********wxyz"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sk-abcdefghijklmnopwxyz"))));

        var stored = aiSettingsRepository.findById(1L).orElseThrow();
        assertTrue(stored.isEncrypted());
        assertNotEquals("sk-abcdefghijklmnopwxyz", stored.getApiKeyCiphertext());
    }

    @Test
    void sendingTheMaskBackKeepsTheStoredKeyAndUpdatesTheRest() throws Exception {
        mvc.perform(put("/api/ai/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"openai\",\"apiKey\":\"sk-abcdefghijklmnopwxyz\",\"model\":\"gpt-4o-mini\"}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/ai/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"openai\",\"apiKey\":\"********wxyz\",\"model\":\"gpt-4o\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gpt-4o"))
                .andExpect(jsonPath("$.apiKey").value("********wxyz"));
    }

    @Test
    void settingsWithoutAKeyOnFirstSaveAreRejectedWithAReadableError() throws Exception {
        mvc.perform(put("/api/ai/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"openai\",\"model\":\"gpt-4o\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("apiKey is required"));
    }

    // ---------- errors are short, readable and free of internals

    @Test
    void askingWithoutAQuestionIsA400WithTheMessage() throws Exception {
        mvc.perform(post("/api/ai/ask").contentType(MediaType.APPLICATION_JSON).content("{\"dependency\":\"g:a:1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("question is required"));
    }

    @Test
    void askingWhenAiIsNotConfiguredExplainsWhat_ToDo() throws Exception {
        mvc.perform(post("/api/ai/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Is it bad?\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("not configured")));
    }

    @Test
    void testingTheConnectionWithoutConfigurationIsAReadable400NotAStackTrace() throws Exception {
        mvc.perform(post("/api/ai/test-connection"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("not configured")))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void analyzingWithoutAProjectPathIsA400() throws Exception {
        mvc.perform(post("/api/project/analyze").contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectPath\":\" \",\"forceRefresh\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("projectPath is required"));
    }

    @Test
    void analyzingAMissingFolderFailsWithAMessageButNoInternals() throws Exception {
        var result = mvc.perform(post("/api/project/analyze").contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectPath\":\"target/definitely-not-a-project\",\"forceRefresh\":true}"))
                .andReturn().getResponse();

        assertTrue(result.getStatus() >= 400, "must be an error");
        String body = result.getContentAsString();
        assertTrue(body.contains("\"error\""), body);
        assertFalse(body.contains("trace"), "no stack trace in the response");
        assertFalse(body.contains("java.lang"), "no exception class names in the response");
        assertFalse(body.contains("at com.riskscanner"), "no stack frames in the response");
    }

    // ---------- the file endpoints are for the local machine only

    @Test
    void fileListingWorksForLocalRequestsAndIsForbiddenForRemoteOnes() throws Exception {
        mvc.perform(get("/api/system/fs").with(from("127.0.0.1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray());

        mvc.perform(get("/api/system/fs").with(from("192.168.1.50")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());

        mvc.perform(post("/api/system/browse").with(from("203.0.113.7")))
                .andExpect(status().isForbidden());
    }

    @Test
    void listingAFileInsteadOfAFolderIsA400() throws Exception {
        mvc.perform(get("/api/system/fs").param("path", "pom.xml"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ---------- cached results and exports

    @Test
    void cachedResultsForAnUnknownProjectAreEmptyNotAnError() throws Exception {
        mvc.perform(get("/api/dashboard/cached-results").param("projectPath", "C:/never/analyzed"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void cachedResultsWithoutAProjectPathAreStillAvailable() throws Exception {
        mvc.perform(get("/api/dashboard/cached-results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void jsonAndPdfExportWithAnEmptyPathUseTheCache() throws Exception {
        mvc.perform(post("/api/export/json").contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectPath\":\"\",\"forceRefresh\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray());

        var pdf = mvc.perform(post("/api/export/pdf").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_PDF)
                .content("{\"projectPath\":\"\",\"forceRefresh\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(pdf, 0, 4));
    }

    @Test
    void pdfExportRejectsAJsonOnlyAcceptHeaderLikeTheOldUiSentIt() throws Exception {
        mvc.perform(post("/api/export/pdf").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("{\"projectPath\":\"\",\"forceRefresh\":false}"))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void springsOwnRequestErrorsKeepTheirStatusCodeWithAReadableBody() throws Exception {
        // malformed JSON
        mvc.perform(post("/api/project/analyze").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.trace").doesNotExist());

        // wrong HTTP method
        mvc.perform(get("/api/export/pdf"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").exists());

        // unsupported content type
        mvc.perform(post("/api/project/analyze").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").exists());

        // unknown API path
        mvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("stackTrace"))));
    }

    @Test
    void datesAreSerializedAsIsoStringsNotEpochSeconds() throws Exception {
        mvc.perform(post("/api/export/json").contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectPath\":\"\",\"forceRefresh\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyzedAt").value(org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*")));
    }
}
