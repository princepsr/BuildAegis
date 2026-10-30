package com.riskscanner.dependencyriskanalyzer.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoServiceTest {

    @TempDir
    Path tmp;

    private CryptoService service(String secret, Path keyFile) {
        CryptoService s = new CryptoService();
        ReflectionTestUtils.setField(s, "encryptionSecret", secret);
        ReflectionTestUtils.setField(s, "keyFile", keyFile == null ? "" : keyFile.toString());
        return s;
    }

    @Test
    void generatesAKeyFileWhenNoSecretIsConfiguredAndEncryptsWithIt() throws Exception {
        Path keyFile = tmp.resolve("data").resolve("buildaegis.key");
        CryptoService s = service("", keyFile);

        assertTrue(s.isEncryptionConfigured());
        assertTrue(Files.isRegularFile(keyFile), "key file must be created (including missing parent folders)");
        assertFalse(Files.readString(keyFile).isBlank());

        String cipher = s.encrypt("sk-secret-value");
        assertNotEquals("sk-secret-value", cipher);
        assertEquals("sk-secret-value", s.decrypt(cipher, true));
    }

    @Test
    void theSameKeyFileIsReusedByANewInstanceSoStoredValuesSurviveARestart() {
        Path keyFile = tmp.resolve("buildaegis.key");
        String cipher = service("", keyFile).encrypt("my-api-key");

        CryptoService afterRestart = service("", keyFile);
        assertEquals("my-api-key", afterRestart.decrypt(cipher, true));
    }

    @Test
    void aConfiguredSecretTakesPrecedenceAndNoKeyFileIsCreated() {
        Path keyFile = tmp.resolve("unused.key");
        CryptoService s = service("my-configured-secret", keyFile);

        assertTrue(s.isEncryptionConfigured());
        assertEquals("v", s.decrypt(s.encrypt("v"), true));
        assertFalse(Files.exists(keyFile));
    }

    @Test
    void differentKeysCannotDecryptEachOther() {
        String cipher = service("", tmp.resolve("a.key")).encrypt("value");
        CryptoService other = service("", tmp.resolve("b.key"));

        assertThrows(IllegalStateException.class, () -> other.decrypt(cipher, true));
    }

    @Test
    void fallsBackToPlaintextOnlyWhenNoKeyCanBeCreated() throws Exception {
        // a regular file where a directory is needed: the key file cannot be created
        Path blocker = Files.writeString(tmp.resolve("blocker"), "x");
        CryptoService s = service("", blocker.resolve("sub").resolve("buildaegis.key"));

        assertFalse(s.isEncryptionConfigured());
        assertEquals("plain", s.encrypt("plain"));
    }

    @Test
    void valuesStoredInPlaintextByOlderVersionsRemainReadable() {
        CryptoService s = service("", tmp.resolve("buildaegis.key"));
        assertEquals("legacy-plain-key", s.decrypt("legacy-plain-key", false));
    }
}
