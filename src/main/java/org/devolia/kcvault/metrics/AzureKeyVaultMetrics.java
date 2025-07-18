package org.devolia.kcvault.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metrics collector for Azure Key Vault operations.
 *
 * <p>This class provides Micrometer-based metrics for monitoring the Azure Key Vault provider:
 *
 * <ul>
 *   <li><strong>keycloak_vault_azure_kv_requests_total</strong> - Counter of vault requests by
 *       status
 *   <li><strong>keycloak_vault_azure_kv_latency_seconds</strong> - Histogram of request latencies
 *   <li><strong>keycloak_vault_azure_kv_cache_operations_total</strong> - Counter of cache
 *       operations
 * </ul>
 *
 * <p>Metrics are tagged with:
 *
 * <ul>
 *   <li><code>status</code> - success, error, not_found
 *   <li><code>operation</code> - get_secret, cache_hit, cache_miss
 *   <li><code>vault</code> - the vault name
 * </ul>
 *
 * @author Devolia
 * @since 1.0.0
 */
public class AzureKeyVaultMetrics {

  private static final Logger logger = LoggerFactory.getLogger(AzureKeyVaultMetrics.class);

  // Metric names
  private static final String REQUESTS_TOTAL = "keycloak_vault_azure_kv_requests_total";
  private static final String LATENCY_SECONDS = "keycloak_vault_azure_kv_latency_seconds";
  private static final String CACHE_OPERATIONS_TOTAL =
      "keycloak_vault_azure_kv_cache_operations_total";
  private static final String RETRY_ATTEMPTS_TOTAL = "keycloak_vault_azure_kv_retry_attempts_total";
  private static final String CIRCUIT_BREAKER_STATE =
      "keycloak_vault_azure_kv_circuit_breaker_state";

  // Status tags
  private static final String STATUS_SUCCESS = "success";
  private static final String STATUS_ERROR = "error";
  private static final String STATUS_NOT_FOUND = "not_found";

  // Operation tags
  private static final String OPERATION_CACHE_HIT = "cache_hit";
  private static final String OPERATION_CACHE_MISS = "cache_miss";
  private static final String OPERATION_GET_SECRET = "get_secret";

  private final MeterRegistry meterRegistry;
  private final String vaultName;

  // Counters
  private final Counter successCounter;
  private final Counter errorCounter;
  private final Counter notFoundCounter;
  private final Counter cacheHitCounter;
  private final Counter cacheMissCounter;

  // Timer for latency measurement
  private final Timer requestTimer;

  /**
   * Constructor for CDI injection.
   *
   * @param meterRegistry the Micrometer meter registry
   * @param vaultName the vault name for tagging metrics
   */
  public AzureKeyVaultMetrics(MeterRegistry meterRegistry, String vaultName) {
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry cannot be null");
    this.vaultName = vaultName != null ? vaultName : "unknown";

    // Initialize counters with base tags
    this.successCounter =
        Counter.builder(REQUESTS_TOTAL)
            .description("Total number of Azure Key Vault requests")
            .tag("status", STATUS_SUCCESS)
            .tag("vault", this.vaultName)
            .register(meterRegistry);

    this.errorCounter =
        Counter.builder(REQUESTS_TOTAL)
            .description("Total number of Azure Key Vault requests")
            .tag("status", STATUS_ERROR)
            .tag("vault", this.vaultName)
            .register(meterRegistry);

    this.notFoundCounter =
        Counter.builder(REQUESTS_TOTAL)
            .description("Total number of Azure Key Vault requests")
            .tag("status", STATUS_NOT_FOUND)
            .tag("vault", this.vaultName)
            .register(meterRegistry);

    this.cacheHitCounter =
        Counter.builder(CACHE_OPERATIONS_TOTAL)
            .description("Total number of cache operations")
            .tag("operation", OPERATION_CACHE_HIT)
            .tag("vault", this.vaultName)
            .register(meterRegistry);

    this.cacheMissCounter =
        Counter.builder(CACHE_OPERATIONS_TOTAL)
            .description("Total number of cache operations")
            .tag("operation", OPERATION_CACHE_MISS)
            .tag("vault", this.vaultName)
            .register(meterRegistry);

    // Initialize timer
    this.requestTimer =
        Timer.builder(LATENCY_SECONDS)
            .description("Latency of Azure Key Vault requests")
            .tag("vault", this.vaultName)
            .register(meterRegistry);

    logger.info("Initialized Azure Key Vault metrics for vault: {}", this.vaultName);
  }

  /**
   * Records a successful secret retrieval from Azure Key Vault.
   *
   * @param latencyNanos the request latency in nanoseconds
   */
  public void recordSuccess(long latencyNanos) {
    successCounter.increment();
    requestTimer.record(Duration.ofNanos(latencyNanos));

    // Record detailed operation metric for get_secret
    recordOperationMetric(OPERATION_GET_SECRET, STATUS_SUCCESS);

    logger.debug("Recorded successful vault request (latency: {}ms)", latencyNanos / 1_000_000);
  }

  /**
   * Records a failed secret retrieval from Azure Key Vault.
   *
   * @param latencyNanos the request latency in nanoseconds
   */
  public void recordError(long latencyNanos) {
    errorCounter.increment();
    requestTimer.record(Duration.ofNanos(latencyNanos));
    logger.debug("Recorded error vault request (latency: {}ms)", latencyNanos / 1_000_000);
  }

  /**
   * Records a secret not found (404) response from Azure Key Vault.
   *
   * @param latencyNanos the request latency in nanoseconds
   */
  public void recordNotFound(long latencyNanos) {
    notFoundCounter.increment();
    requestTimer.record(Duration.ofNanos(latencyNanos));
    logger.debug("Recorded not found vault request (latency: {}ms)", latencyNanos / 1_000_000);
  }

  /** Records a cache hit operation. */
  public void incrementCacheHit() {
    cacheHitCounter.increment();
    logger.debug("Recorded cache hit");
  }

  /** Records a cache miss operation. */
  public void incrementCacheMiss() {
    cacheMissCounter.increment();
    logger.debug("Recorded cache miss");
  }

  /**
   * Records an error with specific error category for better monitoring.
   *
   * @param latencyNanos the request latency in nanoseconds
   * @param errorCategory the error category (e.g., "timeout", "authentication", "rate_limited")
   */
  public void recordError(long latencyNanos, String errorCategory) {
    Counter.builder(REQUESTS_TOTAL)
        .description("Total number of Azure Key Vault requests")
        .tag("status", "error")
        .tag("error_category", errorCategory != null ? errorCategory : "unknown")
        .tag("vault", vaultName)
        .register(meterRegistry)
        .increment();

    requestTimer.record(Duration.ofNanos(latencyNanos));
    logger.debug(
        "Recorded error vault request - category: {}, latency: {}ms",
        errorCategory,
        latencyNanos / 1_000_000);
  }

  /**
   * Records a retry attempt.
   *
   * @param attemptNumber the attempt number (1, 2, 3, etc.)
   * @param errorCategory the error category that triggered the retry
   */
  public void recordRetryAttempt(int attemptNumber, String errorCategory) {
    Counter.builder(RETRY_ATTEMPTS_TOTAL)
        .description("Total number of retry attempts")
        .tag("attempt", String.valueOf(attemptNumber))
        .tag("error_category", errorCategory != null ? errorCategory : "unknown")
        .tag("vault", vaultName)
        .register(meterRegistry)
        .increment();

    logger.debug("Recorded retry attempt {} for error category: {}", attemptNumber, errorCategory);
  }

  /**
   * Records circuit breaker state changes.
   *
   * @param state the circuit breaker state ("closed", "open", "half_open")
   */
  public void recordCircuitBreakerState(String state) {
    meterRegistry.gauge(
        CIRCUIT_BREAKER_STATE,
        io.micrometer.core.instrument.Tags.of(
            "state", state,
            "vault", vaultName),
        this,
        gauge -> getStateValue(state));

    logger.debug("Recorded circuit breaker state change: {}", state);
  }

  /** Gets a numeric value for circuit breaker state for gauge metric. */
  private double getStateValue(String state) {
    return switch (state) {
      case "closed" -> 0.0;
      case "half_open" -> 0.5;
      case "open" -> 1.0;
      default -> -1.0;
    };
  }

  /**
   * Gets the current count of successful requests.
   *
   * @return success request count
   */
  public double getSuccessCount() {
    return successCounter.count();
  }

  /**
   * Gets the current count of error requests.
   *
   * @return error request count
   */
  public double getErrorCount() {
    return errorCounter.count();
  }

  /**
   * Gets the current count of not found requests.
   *
   * @return not found request count
   */
  public double getNotFoundCount() {
    return notFoundCounter.count();
  }

  /**
   * Gets the current count of cache hits.
   *
   * @return cache hit count
   */
  public double getCacheHitCount() {
    return cacheHitCounter.count();
  }

  /**
   * Gets the current count of cache misses.
   *
   * @return cache miss count
   */
  public double getCacheMissCount() {
    return cacheMissCounter.count();
  }

  /**
   * Gets the mean request latency in milliseconds.
   *
   * @return mean latency in milliseconds
   */
  public double getMeanLatencyMs() {
    return requestTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  /**
   * Gets the total request count across all statuses.
   *
   * @return total request count
   */
  public double getTotalRequestCount() {
    return getSuccessCount() + getErrorCount() + getNotFoundCount();
  }

  /**
   * Gets the cache hit ratio as a percentage.
   *
   * @return cache hit ratio (0.0 to 1.0)
   */
  public double getCacheHitRatio() {
    double hits = getCacheHitCount();
    double total = hits + getCacheMissCount();
    return total > 0 ? hits / total : 0.0;
  }

  /**
   * Records detailed operation metrics for monitoring specific operations.
   *
   * @param operation the operation type (e.g., OPERATION_GET_SECRET)
   * @param status the operation status
   */
  private void recordOperationMetric(String operation, String status) {
    Counter.builder(REQUESTS_TOTAL)
        .tag("operation", operation)
        .tag("status", status)
        .tag("vault", vaultName)
        .register(meterRegistry)
        .increment();
  }
}
