package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseNotesServiceTest {

    /** Serves canned responses instead of calling GitHub. */
    private static class Fake extends ReleaseNotesService {
        final List<String> urls = new ArrayList<>();
        String bodyForTag = null;      // returned when the URL ends with this tag
        String tag = null;
        boolean fail = false;

        @Override
        String fetchJson(String url) throws Exception {
            urls.add(url);
            if (fail) {
                throw new IllegalStateException("HTTP 403");
            }
            return tag != null && url.endsWith("/" + tag) ? bodyForTag : null;
        }
    }

    @Test
    void triesTagWithVPrefixThenWithout() {
        Fake f = new Fake();
        f.tag = "2.0.1";
        f.bodyForTag = "{\"name\":\"2.0.1\",\"body\":\"Fixes CVE-2024-1.\"}";

        String notes = f.fetch("spring-projects/spring-framework", "2.0.1");

        assertEquals("2.0.1\nFixes CVE-2024-1.", notes);
        assertEquals(List.of(
                "https://api.github.com/repos/spring-projects/spring-framework/releases/tags/v2.0.1",
                "https://api.github.com/repos/spring-projects/spring-framework/releases/tags/2.0.1"), f.urls);
    }

    @Test
    void versionAlreadyStartingWithVIsNotDoublePrefixed() {
        assertEquals(List.of("v1.2.3", "1.2.3"), ReleaseNotesService.tagCandidates("v1.2.3"));
    }

    @Test
    void unsafeRepositoryOrVersionNeverReachesAUrl() {
        Fake f = new Fake();

        assertNull(f.fetch("evil/../../admin", "1.0"));
        assertNull(f.fetch("owner/repo?x=1", "1.0"));
        assertNull(f.fetch("owner/repo", "1.0/../x"));
        assertNull(f.fetch("owner/repo", "1.0 2.0"));
        assertNull(f.fetch(null, "1.0"));
        assertNull(f.fetch("owner/repo", null));
        assertTrue(f.urls.isEmpty(), "no request may be made for unvalidated input");
    }

    @Test
    void missingReleaseGivesNullAndIsCachedSoGithubIsNotAskedAgain() {
        Fake f = new Fake();

        assertNull(f.fetch("o/r", "9.9.9"));
        int callsAfterFirst = f.urls.size();
        assertNull(f.fetch("o/r", "9.9.9"));

        assertEquals(callsAfterFirst, f.urls.size());
    }

    @Test
    void foundNotesAreCached() {
        Fake f = new Fake();
        f.tag = "1.0.0";
        f.bodyForTag = "{\"body\":\"notes\"}";

        assertEquals("notes", f.fetch("o/r", "1.0.0"));
        int calls = f.urls.size();
        assertEquals("notes", f.fetch("o/r", "1.0.0"));
        assertEquals(calls, f.urls.size());
    }

    @Test
    void networkErrorsMeanNoNotesNotAnException() {
        Fake f = new Fake();
        f.fail = true;
        assertNull(f.fetch("o/r", "1.0.0"));
    }

    @Test
    void emptyBodiesAreIgnoredAndLongOnesTruncated() throws Exception {
        var mapper = new ObjectMapper();
        assertNull(ReleaseNotesService.extractNotes(mapper.readTree("{\"name\":\"x\",\"body\":\"  \"}")));
        assertNull(ReleaseNotesService.extractNotes(mapper.readTree("{}")));

        String longNotes = ReleaseNotesService.extractNotes(mapper.readTree("{\"body\":\"" + "x".repeat(5000) + "\"}"));
        assertTrue(longNotes.length() < 3100);
        assertTrue(longNotes.endsWith("[truncated]"));
    }
}
