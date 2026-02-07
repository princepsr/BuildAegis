package com.riskscanner.dependencyriskanalyzer.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * JPA entity for persisted AI provider settings.
 *
 * <p>This table is used as a single-row settings store (see {@code AiSettingsService}).
 * The {@code encrypted} flag indicates whether {@code apiKeyCiphertext} is encrypted using
 * {@code buildaegis.encryption.secret}.
 */
@Entity
@Table(name = "ai_settings")
public class AiSettingsEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "custom_endpoint")
    private String customEndpoint;

    @Lob
    @Column(name = "api_key_ciphertext", nullable = false)
    private String apiKeyCiphertext;

    @Column(nullable = false)
    private boolean encrypted;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getCustomEndpoint() {
        return customEndpoint;
    }

    public void setCustomEndpoint(String customEndpoint) {
        this.customEndpoint = customEndpoint;
    }

    public String getApiKeyCiphertext() {
        return apiKeyCiphertext;
    }

    public void setApiKeyCiphertext(String apiKeyCiphertext) {
        this.apiKeyCiphertext = apiKeyCiphertext;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public void setEncrypted(boolean encrypted) {
        this.encrypted = encrypted;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
