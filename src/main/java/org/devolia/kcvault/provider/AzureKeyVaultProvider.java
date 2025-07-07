package org.devolia.kcvault.provider;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import com.github.benmanes.caffeine.cache.Cache;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.OffsetDateTime;
import java.util.function.Supplier;
import org.devolia.kcvault.auth.CredentialResolver;
import org.devolia.kcvault.cache.CacheConfig;
import org.devolia.kcvault.cache.CachedSecret;
import org.devolia.kcvault.metrics.AzureKeyVaultMetrics;
import org.devolia.kcvault.resilience.ExceptionClassifier;
import org.devolia.kcvault.resilience.ResilienceConfig;
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
  private final ResilienceConfig resilienceConfig;
  private final Retry retry;
  private final CircuitBreaker circuitBreaker;

  /**
   * Constructor for CDI injection.
   *
   * @param credentialResolver resolver for Azure credentials
   * @param cacheConfig cache configuration
   * @param metrics metrics collector
   * @param resilienceConfig resilience patterns configuration
   */
  public AzureKeyVaultProvider(
      CredentialResolver credentialResolver,
      CacheConfig cacheConfig,
      AzureKeyVaultMetrics metrics,
      ResilienceConfig resilienceConfig) {

    this.vaultName = cacheConfig.getVaultName();
    this.metrics = metrics;
    this.secretCache = cacheConfig.buildCache();
    this.resilienceConfig = resilienceConfig;

    // Initialize Azure Key Vault client
    this.secretClient = credentialResolver.createSecretClient(vaultName);

    // Initialize retry configuration
    RetryConfig retryConfig =
        RetryConfig.custom()
            .maxAttempts(resilienceConfig.getRetryMaxAttempts())
            .waitDuration(resilienceConfig.getRetryBaseDelay())
            .retryOnException(ExceptionClassifier::isTransientFailure)
            .build();

    this.retry = Retry.of("azure-keyvault-" + vaultName, retryConfig);

    // Add retry event listeners for metrics
    retry
        .getEventPublisher()
        .onRetry(
            event -> {
              String errorCategory = ExceptionClassifier.getErrorCategory(event.getLastThrowable());
              metrics.recordRetryAttempt(event.getNumberOfRetryAttempts(), errorCategory);
              logger.debug(
                  "Retry attempt {} for vault operation, error: {}",
                  event.getNumberOfRetryAttempts(),
                  errorCategory);
            });

    // Initialize circuit breaker configuration
    if (resilienceConfig.isCircuitBreakerEnabled()) {
      CircuitBreakerConfig circuitBreakerConfig =
          CircuitBreakerConfig.custom()
              .failureRateThreshold(resilienceConfig.getCircuitBreakerFailureThreshold())
              .waitDurationInOpenState(resilienceConfig.getCircuitBreakerRecoveryTimeout())
              .recordException(ExceptionClassifier::isCircuitBreakerFailure)
              .slidingWindowSize(10)
              .minimumNumberOfCalls(5)
              .build();

      this.circuitBreaker = CircuitBreaker.of("azure-keyvault-" + vaultName, circuitBreakerConfig);

      // Add circuit breaker event listeners for metrics
      circuitBreaker
          .getEventPublisher()
          .onStateTransition(
              event -> {
                String fromState = event.getStateTransition().getFromState().name().toLowerCase();
                String toState = event.getStateTransition().getToState().name().toLowerCase();
                metrics.recordCircuitBreakerState(toState);
                logger.info(
                    "Circuit breaker state transition: {} -> {} for vault: {}",
                    fromState,
                    toState,
                    vaultName);
              });
    } else {
      this.circuitBreaker = null;
      logger.debug("Circuit breaker disabled for vault: {}", vaultName);
    }

    logger.info(
        "Initialized Azure Key Vault provider for vault: {} with resilience patterns", vaultName);
    logger.debug(
        "Cache configuration - TTL: {}s, Max entries: {}",
        cacheConfig.getCacheTtl(),
        cacheConfig.getCacheMaxSize());
    logger.debug(
        "Resilience configuration - Retry: max={}, baseDelay={}ms; CircuitBreaker: enabled={}",
        resilienceConfig.getRetryMaxAttempts(),
        resilienceConfig.getRetryBaseDelay().toMillis(),
        resilienceConfig.isCircuitBreakerEnabled());
  }

  /**
   * Constructor for CDI injection with default resilience configuration.
   *
   * @param credentialResolver resolver for Azure credentials
   * @param cacheConfig cache configuration
   * @param metrics metrics collector
   */
  public AzureKeyVaultProvider(
      CredentialResolver credentialResolver,
      CacheConfig cacheConfig,
      AzureKeyVaultMetrics metrics) {
    this(credentialResolver, cacheConfig, metrics, ResilienceConfig.defaultConfig());
  }

  /**
   * Retrieves a secret from Azure Key Vault with caching support and resilience patterns.
   *
   * <p>The method follows this flow:
   *
   * <ol>
   *   <li>Check local cache for the secret
   *   <li>If not cached, retrieve from Azure Key Vault with retry and circuit breaker protection
   *   <li>Update metrics (success/error counters and latency)
   *   <li>Cache the result (only successful retrievals)
   *   <li>Return the secret value or null if not found
   * </ol>
   *
   * <p>Resilience patterns applied:
   *
   * <ul>
   *   <li>Retry logic with exponential backoff for transient failures
   *   <li>Circuit breaker to prevent cascading failures
   *   <li>Comprehensive exception mapping with proper error categorization
   *   <li>Enhanced metrics for different error types
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

    // Retrieve from Azure Key Vault with resilience patterns
    long startTime = System.nanoTime();

    // Create the supplier for the vault operation
    Supplier<VaultRawSecret> vaultOperation = () -> retrieveSecretFromVault(secretName, startTime);

    try {
      // Apply circuit breaker if enabled
      if (circuitBreaker != null) {
        vaultOperation = CircuitBreaker.decorateSupplier(circuitBreaker, vaultOperation);
      }

      // Apply retry logic
      vaultOperation = Retry.decorateSupplier(retry, vaultOperation);

      // Execute the operation
      return vaultOperation.get();

    } catch (Exception e) {
      long latency = System.nanoTime() - startTime;
      String errorCategory = ExceptionClassifier.getErrorCategory(e);

      // Enhanced error handling with categorization
      if (e instanceof ResourceNotFoundException
          || (e instanceof HttpResponseException httpEx
              && httpEx.getResponse().getStatusCode() == 404)) {
        metrics.recordNotFound(latency);
        logger.debug("Secret not found in Azure Key Vault: {}", maskSecret(secretName));
        return null;
      }

      // Record error with category
      metrics.recordError(latency, errorCategory);
      logger.error(
          "Failed to retrieve secret from Azure Key Vault: {} (category: {})",
          maskSecret(secretName),
          errorCategory,
          e);

      throw new RuntimeException(
          "Error retrieving secret from Azure Key Vault: " + errorCategory, e);
    }
  }

  /**
   * Core method to retrieve secret from vault without resilience patterns. This is wrapped by
   * resilience decorators in obtainSecret().
   */
  private VaultRawSecret retrieveSecretFromVault(String secretName, long operationStartTime) {
    try {
      KeyVaultSecret secret = secretClient.getSecret(secretName);
      String secretValue = secret.getValue();
      SecretProperties properties = secret.getProperties();

      // Check if secret is expired or not yet valid (only if properties are available)
      if (properties != null) {
        OffsetDateTime now = OffsetDateTime.now();
        if (isSecretExpired(properties, now)) {
          metrics.recordNotFound(System.nanoTime() - operationStartTime);
          logger.debug("Secret is expired: {}", maskSecret(secretName));
          return null;
        }

        if (isSecretNotYetValid(properties, now)) {
          metrics.recordNotFound(System.nanoTime() - operationStartTime);
          logger.debug("Secret is not yet valid: {}", maskSecret(secretName));
          return null;
        }

        if (isSecretDisabled(properties)) {
          metrics.recordNotFound(System.nanoTime() - operationStartTime);
          logger.debug("Secret is disabled: {}", maskSecret(secretName));
          return null;
        }
      }

      // Cache the successful result with expiration metadata
      CachedSecret cachedSecretObj =
          new CachedSecret(secretValue, properties != null ? properties.getExpiresOn() : null);
      secretCache.put(secretName, cachedSecretObj);

      metrics.recordSuccess(System.nanoTime() - operationStartTime);
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

    } catch (Exception e) {
      // Let exceptions bubble up to be handled by resilience patterns
      throw e;
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
