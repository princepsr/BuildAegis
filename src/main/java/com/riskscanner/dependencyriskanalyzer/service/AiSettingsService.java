package com.riskscanner.dependencyriskanalyzer.service;

import com.riskscanner.dependencyriskanalyzer.dto.AiSettingsRequest;
import com.riskscanner.dependencyriskanalyzer.dto.AiSettingsResponse;
import com.riskscanner.dependencyriskanalyzer.model.AiSettingsEntity;
import com.riskscanner.dependencyriskanalyzer.repository.AiSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Stores and retrieves AI configuration (provider/model/API key).
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>Settings are stored as a single-row record keyed by {@link #SETTINGS_ID}.</li>
 *   <li>The API key is stored encrypted if {@link CryptoService#isEncryptionConfigured()} returns true.</li>
 *   <li>If encryption is enabled, changing {@code buildaegis.encryption.secret} will prevent decrypting
 *   previously stored API keys.</li>
 * </ul>
 */
@Service
public class AiSettingsService {

    private static final long SETTINGS_ID = 1L;

    private final AiSettingsRepository aiSettingsRepository;
    private final CryptoService cryptoService;

    public AiSettingsService(AiSettingsRepository aiSettingsRepository, CryptoService cryptoService) {
        this.aiSettingsRepository = aiSettingsRepository;
        this.cryptoService = cryptoService;
    }

    @Transactional(readOnly = true)
    public AiSettingsResponse getSettings() {
        return aiSettingsRepository.findById(SETTINGS_ID)
                .map(entity -> {
                    String apiKey = cryptoService.decrypt(entity.getApiKeyCiphertext(), entity.isEncrypted());
                    return new AiSettingsResponse(
                            entity.getProvider(),
                            entity.getModel(),
                            entity.getCustomEndpoint(),
                            apiKey,
                            true,
                            entity.getUpdatedAt());
                })
                .orElseGet(() -> new AiSettingsResponse(
                        "openai",
                        "gpt-4o",
                        null,
                        null,
                        false,
                        null));
    }

    @Transactional
    public AiSettingsResponse saveSettings(AiSettingsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request must not be null");
        }
        if (request.provider() == null || request.provider().isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (request.apiKey() == null || request.apiKey().isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        if ("custom".equals(request.provider()) && (request.customEndpoint() == null || request.customEndpoint().isBlank())) {
            throw new IllegalArgumentException("customEndpoint is required for custom provider");
        }

        AiSettingsEntity entity = aiSettingsRepository.findById(SETTINGS_ID).orElseGet(AiSettingsEntity::new);
        entity.setId(SETTINGS_ID);
        entity.setProvider(request.provider().trim());
        entity.setModel(request.model().trim());
        entity.setCustomEndpoint("custom".equals(request.provider()) ? request.customEndpoint().trim() : null);

        boolean encrypt = cryptoService.isEncryptionConfigured();
        entity.setEncrypted(encrypt);
        entity.setApiKeyCiphertext(encrypt ? cryptoService.encrypt(request.apiKey().trim()) : request.apiKey().trim());
        entity.setUpdatedAt(Instant.now());

        aiSettingsRepository.save(entity);

        return new AiSettingsResponse(entity.getProvider(), entity.getModel(), entity.getCustomEndpoint(), request.apiKey(), true, entity.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public String getApiKeyOrThrow() {
        AiSettingsEntity entity = aiSettingsRepository.findById(SETTINGS_ID)
                .orElseThrow(() -> new IllegalStateException("AI settings are not configured. Set /api/ai/settings first."));

        String apiKey = cryptoService.decrypt(entity.getApiKeyCiphertext(), entity.isEncrypted());
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AI settings are not configured. Set /api/ai/settings first.");
        }
        return apiKey;
    }

    @Transactional(readOnly = true)
    public String getProvider() {
        return aiSettingsRepository.findById(SETTINGS_ID).map(AiSettingsEntity::getProvider).orElse("openai");
    }

    @Transactional(readOnly = true)
    public String getModel() {
        return aiSettingsRepository.findById(SETTINGS_ID).map(AiSettingsEntity::getModel).orElse("gpt-4o");
    }

    @Transactional(readOnly = true)
    public String getCustomEndpoint() {
        return aiSettingsRepository.findById(SETTINGS_ID).map(AiSettingsEntity::getCustomEndpoint).orElse(null);
    }
}
