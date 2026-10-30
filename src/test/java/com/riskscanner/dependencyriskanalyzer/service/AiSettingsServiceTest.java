package com.riskscanner.dependencyriskanalyzer.service;

import com.riskscanner.dependencyriskanalyzer.dto.AiSettingsRequest;
import com.riskscanner.dependencyriskanalyzer.model.AiSettingsEntity;
import com.riskscanner.dependencyriskanalyzer.repository.AiSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiSettingsServiceTest {

    @TempDir
    Path tmp;

    private final AtomicReference<AiSettingsEntity> stored = new AtomicReference<>();
    private CryptoService crypto;
    private AiSettingsService service;

    @BeforeEach
    void setUp() {
        AiSettingsRepository repo = mock(AiSettingsRepository.class);
        when(repo.findById(1L)).thenAnswer(i -> Optional.ofNullable(stored.get()));
        when(repo.save(any(AiSettingsEntity.class))).thenAnswer(i -> {
            stored.set(i.getArgument(0));
            return i.getArgument(0);
        });
        crypto = new CryptoService();
        ReflectionTestUtils.setField(crypto, "encryptionSecret", "");
        ReflectionTestUtils.setField(crypto, "keyFile", tmp.resolve("buildaegis.key").toString());
        service = new AiSettingsService(repo, crypto);
    }

    private AiSettingsRequest request(String apiKey) {
        return new AiSettingsRequest("openai", apiKey, "gpt-4o", null);
    }

    @Test
    void maskedKeyRevealsAtMostTheLastFourCharacters() {
        assertEquals("********wxyz", AiSettingsService.maskApiKey("sk-abcdefghijklmnopwxyz"));
        assertEquals("********", AiSettingsService.maskApiKey("short-key"));
        assertNull(AiSettingsService.maskApiKey(null));
        assertNull(AiSettingsService.maskApiKey("  "));
    }

    @Test
    void savedKeyIsStoredEncryptedAndNeverReturnedInClear() {
        var response = service.saveSettings(request("sk-abcdefghijklmnopwxyz"));

        assertEquals("********wxyz", response.apiKey());
        assertTrue(stored.get().isEncrypted());
        assertNotEquals("sk-abcdefghijklmnopwxyz", stored.get().getApiKeyCiphertext());

        assertEquals("********wxyz", service.getSettingsMasked().apiKey());
        // the real key is still available server-side
        assertEquals("sk-abcdefghijklmnopwxyz", service.getApiKeyOrThrow());
        assertEquals("sk-abcdefghijklmnopwxyz", service.getSettings().apiKey());
    }

    @Test
    void sendingTheMaskBackKeepsTheStoredKey() {
        service.saveSettings(request("sk-abcdefghijklmnopwxyz"));

        service.saveSettings(new AiSettingsRequest("openai", "********wxyz", "gpt-4o-mini", null));

        assertEquals("gpt-4o-mini", service.getModel());
        assertEquals("sk-abcdefghijklmnopwxyz", service.getApiKeyOrThrow());
    }

    @Test
    void aBlankKeyKeepsTheStoredKeyButIsRejectedWhenNothingIsStored() {
        assertThrows(IllegalArgumentException.class, () -> service.saveSettings(request(" ")));

        service.saveSettings(request("sk-abcdefghijklmnopwxyz"));
        service.saveSettings(request(""));
        assertEquals("sk-abcdefghijklmnopwxyz", service.getApiKeyOrThrow());
    }

    @Test
    void anewKeyReplacesTheOldOne() {
        service.saveSettings(request("sk-abcdefghijklmnopwxyz"));
        service.saveSettings(request("sk-brand-new-key-1234567"));

        assertEquals("sk-brand-new-key-1234567", service.getApiKeyOrThrow());
    }

    @Test
    void keysSavedInPlaintextByOlderVersionsAreEncryptedOnTheNextSave() {
        AiSettingsEntity legacy = new AiSettingsEntity();
        legacy.setId(1L);
        legacy.setProvider("openai");
        legacy.setModel("gpt-4o");
        legacy.setEncrypted(false);
        legacy.setApiKeyCiphertext("sk-legacy-plaintext-key-0000");
        stored.set(legacy);

        // legacy rows stay readable
        assertEquals("sk-legacy-plaintext-key-0000", service.getApiKeyOrThrow());

        service.saveSettings(new AiSettingsRequest("openai", "********0000", "gpt-4o", null));

        assertTrue(stored.get().isEncrypted());
        assertFalse(stored.get().getApiKeyCiphertext().contains("sk-legacy"));
        assertEquals("sk-legacy-plaintext-key-0000", service.getApiKeyOrThrow());
    }

    @Test
    void customProviderNeedsAnEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> service.saveSettings(new AiSettingsRequest("custom", "sk-abcdefghijklmnopwxyz", "m", " ")));
    }
}
