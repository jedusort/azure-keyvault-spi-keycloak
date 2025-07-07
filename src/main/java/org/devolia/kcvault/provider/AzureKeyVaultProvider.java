package org.devolia.kcvault.provider;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.OffsetDateTime;
import org.devolia.kcvault.auth.CredentialResolver;
import org.devolia.kcvault.cache.CacheConfig;
import org.devolia.kcvault.cache.CachedSecret;
import org.devolia.kcvault.metrics.AzureKeyVaultMetrics;
import org.keycloak.vault.VaultProvider;
import org.keycloak.vault.VaultRawSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Key Vault implementation of Keycloak's VaultProvider SPI.
 *
 * <p>This provider enables Keycloak 26+ to retrieve secrets directly from Azure Key Vault using
 * managed identity or service principal authentication. Features include:
 *
 * <ul>
 *   <li>Configurable Caffeine-based caching with TTL and LRU policies
 *   <li>Micrometer metrics for monitoring
 *   <li>Proper error handling and logging
 *   <li>Support for both Managed Identity and Service Principal auth
 * </ul>
 *
 * <p>Configuration is handled through Keycloak's standard SPI configuration:
 *
 * <pre>
 * spi-vault-azure-kv-name=my-vault
 * spi-vault-azure-kv-cache-ttl=60
 * spi-vault-azure-kv-cache-max=1000
 * </pre>
 *
 * @author Devolia
 * @since 1.0.0
 */
public class AzureKeyVaultProvider implements VaultProvider {

  private static final Logger logger = LoggerFactory.getLogger(AzureKeyVaultProvider.class);

  private final SecretClient secretClient;
  private final Cache<String, CachedSecret> secretCache;
  private final AzureKeyVaultMetrics metrics;
  private final String vaultName;

  /**
   * Constructor for CDI injection.
   *
   * @param credentialResolver resolver for Azure credentials
   * @param cacheConfig cache configuration
   * @param metrics metrics collector
   */
  public AzureKeyVaultProvider(
      CredentialResolver credentialResolver,
      CacheConfig cacheConfig,
      AzureKeyVaultMetrics metrics) {

    this.vaultName = cacheConfig.getVaultName();
    this.metrics = metrics;
    this.secretCache = cacheConfig.buildCache();

    // Initialize Azure Key Vault client
    this.secretClient = credentialResolver.createSecretClient(vaultName);

    logger.info("Initialized Azure Key Vault provider for vault: {}", vaultName);
    logger.debug(
        "Cache configuration - TTL: {}s, Max entries: {}",
        cacheConfig.getCacheTtl(),
        cacheConfig.getCacheMaxSize());
  }

  /**
   * Retrieves a secret from Azure Key Vault with caching support.
   *
   * <p>The method follows this flow:
   *
   * <ol>
   *   <li>Check local cache for the secret
   *   <li>If not cached, retrieve from Azure Key Vault
   *   <li>Update metrics (success/error counters and latency)
   *   <li>Cache the result (only successful retrievals)
   *   <li>Return the secret value or null if not found
   * </ol>
   *
   * <p>Handles Azure Key Vault exceptions gracefully:
   *
   * <ul>
   *   <li>404 Not Found → returns null (secret doesn't exist)
   *   <li>Other exceptions → logged and re-thrown as VaultProvider exceptions
   * </ul>
   *
   * @param vaultSecretId the secret identifier (name) in Azure Key Vault
   * @return the secret value, or null if the secret doesn't exist
   * @throws RuntimeException if an error occurs during secret retrieval
   */
  @Override
  public VaultRawSecret obtainSecret(String vaultSecretId) {
    if (vaultSecretId == null || vaultSecretId.trim().isEmpty()) {
      logger.warn("Attempted to retrieve secret with null or empty ID");
      return null;
    }

    final String secretName = sanitizeSecretName(vaultSecretId);
    logger.debug("Retrieving secret: {} (sanitized: {})", vaultSecretId, secretName);

    // Check cache first
    CachedSecret cachedSecret = secretCache.getIfPresent(secretName);
    if (cachedSecret != null && cachedSecret.isValid()) {
      logger.debug("Secret found in cache: {}", maskSecret(secretName));
      metrics.incrementCacheHit();
      return new DefaultVaultRawSecret(cachedSecret.getValue());
    } else if (cachedSecret != null && cachedSecret.isExpired()) {
      // Remove expired secret from cache
      secretCache.invalidate(secretName);
      logger.debug("Removed expired secret from cache: {}", maskSecret(secretName));
    }

    // Record cache miss
    metrics.incrementCacheMiss();

    // Retrieve from Azure Key Vault
    long startTime = System.nanoTime();
    try {
      KeyVaultSecret secret = secretClient.getSecret(secretName);
      String secretValue = secret.getValue();
      SecretProperties properties = secret.getProperties();

      // Check if secret is expired or not yet valid (only if properties are available)
      if (properties != null) {
        OffsetDateTime now = OffsetDateTime.now();
        if (isSecretExpired(properties, now)) {
          metrics.recordNotFound(System.nanoTime() - startTime);
          logger.debug("Secret is expired: {}", maskSecret(secretName));
          return null;
        }

        if (isSecretNotYetValid(properties, now)) {
          metrics.recordNotFound(System.nanoTime() - startTime);
          logger.debug("Secret is not yet valid: {}", maskSecret(secretName));
          return null;
        }

        if (isSecretDisabled(properties)) {
          metrics.recordNotFound(System.nanoTime() - startTime);
          logger.debug("Secret is disabled: {}", maskSecret(secretName));
          return null;
        }
      }

      // Cache the successful result with expiration metadata
      CachedSecret cachedSecretObj =
          new CachedSecret(secretValue, properties != null ? properties.getExpiresOn() : null);
      secretCache.put(secretName, cachedSecretObj);

      metrics.recordSuccess(System.nanoTime() - startTime);
      if (properties != null) {
        logger.debug(
            "Successfully retrieved and cached secret: {} (version: {}, expires: {})",
            maskSecret(secretName),
            properties.getVersion(),
            properties.getExpiresOn());
      } else {
        logger.debug("Successfully retrieved and cached secret: {}", maskSecret(secretName));
      }

      return new DefaultVaultRawSecret(secretValue);

    } catch (ResourceNotFoundException e) {
      metrics.recordNotFound(System.nanoTime() - startTime);
      logger.debug("Secret not found in Azure Key Vault: {}", maskSecret(secretName));
      return null;

    } catch (HttpResponseException e) {
      if (e.getResponse().getStatusCode() == 404) {
        metrics.recordNotFound(System.nanoTime() - startTime);
        logger.debug("Secret not found in Azure Key Vault (404): {}", maskSecret(secretName));
        return null;
      } else {
        metrics.recordError(System.nanoTime() - startTime);
        logger.error(
            "HTTP error retrieving secret from Azure Key Vault: {} (status: {})",
            maskSecret(secretName),
            e.getResponse().getStatusCode(),
            e);
        throw new RuntimeException("Error retrieving secret from Azure Key Vault", e);
      }

    } catch (Exception e) {
      metrics.recordError(System.nanoTime() - startTime);
      logger.error("Failed to retrieve secret from Azure Key Vault: {}", maskSecret(secretName), e);
      throw new RuntimeException("Error retrieving secret from Azure Key Vault", e);
    }
  }

  /**
   * Closes the provider and cleans up resources. Currently no cleanup is needed as Azure SDK
   * handles resource management.
   */
  @Override
  public void close() {
    logger.debug("Closing Azure Key Vault provider for vault: {}", vaultName);
    // Azure SDK SecretClient doesn't require explicit cleanup
    // Cache cleanup is handled by Caffeine
  }

  /**
   * Invalidates a specific secret from the cache. Useful for cache management and testing.
   *
   * @param secretName the name of the secret to invalidate
   */
  public void invalidateSecret(String secretName) {
    if (secretName != null) {
      secretCache.invalidate(sanitizeSecretName(secretName));
      logger.debug("Invalidated secret from cache: {}", maskSecret(secretName));
    }
  }

  /** Clears the entire secret cache. Useful for cache management and testing. */
  public void clearCache() {
    secretCache.invalidateAll();
    logger.debug("Cleared all secrets from cache");
  }

  /**
   * Gets the current cache size.
   *
   * @return number of cached secrets
   */
  public long getCacheSize() {
    return secretCache.estimatedSize();
  }

  /**
   * Sanitizes secret names to ensure they're valid for Azure Key Vault. Azure Key Vault secret
   * names must be alphanumeric with hyphens only.
   *
   * @param secretId the original secret identifier
   * @return sanitized secret name
   */
  private String sanitizeSecretName(String secretId) {
    if (secretId == null) {
      return null;
    }
    // Replace invalid characters with hyphens and ensure no consecutive hyphens
    return secretId
        .replaceAll("[^a-zA-Z0-9-]", "-")
        .replaceAll("-+", "-")
        .replaceAll("^-|-$", "")
        .toLowerCase();
  }

  /**
   * Masks secret names for safe logging.
   *
   * @param secretName the secret name to mask
   * @return masked secret name for logging
   */
  private String maskSecret(String secretName) {
    if (secretName == null || secretName.length() <= 3) {
      return "***";
    }
    return secretName.substring(0, 2) + "***" + secretName.substring(secretName.length() - 1);
  }

  /**
   * Checks if a secret is expired based on its expiration metadata.
   *
   * @param properties the secret properties
   * @param now the current time
   * @return true if the secret is expired
   */
  private boolean isSecretExpired(SecretProperties properties, OffsetDateTime now) {
    OffsetDateTime expiresOn = properties.getExpiresOn();
    return expiresOn != null && now.isAfter(expiresOn);
  }

  /**
   * Checks if a secret is not yet valid based on its activation metadata.
   *
   * @param properties the secret properties
   * @param now the current time
   * @return true if the secret is not yet valid
   */
  private boolean isSecretNotYetValid(SecretProperties properties, OffsetDateTime now) {
    OffsetDateTime notBefore = properties.getNotBefore();
    return notBefore != null && now.isBefore(notBefore);
  }

  /**
   * Checks if a secret is disabled.
   *
   * @param properties the secret properties
   * @return true if the secret is disabled
   */
  private boolean isSecretDisabled(SecretProperties properties) {
    Boolean enabled = properties.isEnabled();
    return enabled != null && !enabled;
  }
}
