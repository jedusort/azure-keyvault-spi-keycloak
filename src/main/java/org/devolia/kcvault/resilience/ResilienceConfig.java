package org.devolia.kcvault.resilience;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for resilience patterns including retry logic and circuit breaker.
 *
 * <p>This class manages configuration for:
 *
 * <ul>
 *   <li>Retry policies with exponential backoff and jitter
 *   <li>Circuit breaker failure thresholds and recovery timeouts
 *   <li>Connection and read timeouts for Azure SDK
 * </ul>
 *
 * @author Devolia
 * @since 1.0.0
 */
public class ResilienceConfig {

  private static final Logger logger = LoggerFactory.getLogger(ResilienceConfig.class);

  // Default values
  public static final int DEFAULT_RETRY_MAX_ATTEMPTS = 3;
  public static final long DEFAULT_RETRY_BASE_DELAY_MS = 1000;
  public static final boolean DEFAULT_CIRCUIT_BREAKER_ENABLED = true;
  public static final int DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD = 5;
  public static final long DEFAULT_CIRCUIT_BREAKER_RECOVERY_TIMEOUT_MS = 30000;
  public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 5000;
  public static final long DEFAULT_READ_TIMEOUT_MS = 10000;

  private final int retryMaxAttempts;
  private final Duration retryBaseDelay;
  private final boolean circuitBreakerEnabled;
  private final int circuitBreakerFailureThreshold;
  private final Duration circuitBreakerRecoveryTimeout;
  private final Duration connectionTimeout;
  private final Duration readTimeout;

  /**
   * Constructor with configuration values.
   *
   * @param retryMaxAttempts maximum retry attempts (default: 3)
   * @param retryBaseDelayMs base delay for exponential backoff in milliseconds (default: 1000)
   * @param circuitBreakerEnabled whether circuit breaker is enabled (default: true)
   * @param circuitBreakerFailureThreshold failure threshold for circuit breaker (default: 5)
   * @param circuitBreakerRecoveryTimeoutMs recovery timeout in milliseconds (default: 30000)
   * @param connectionTimeoutMs connection timeout in milliseconds (default: 5000)
   * @param readTimeoutMs read timeout in milliseconds (default: 10000)
   */
  public ResilienceConfig(
      int retryMaxAttempts,
      long retryBaseDelayMs,
      boolean circuitBreakerEnabled,
      int circuitBreakerFailureThreshold,
      long circuitBreakerRecoveryTimeoutMs,
      long connectionTimeoutMs,
      long readTimeoutMs) {

    this.retryMaxAttempts = retryMaxAttempts;
    this.retryBaseDelay = Duration.ofMillis(retryBaseDelayMs);
    this.circuitBreakerEnabled = circuitBreakerEnabled;
    this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold;
    this.circuitBreakerRecoveryTimeout = Duration.ofMillis(circuitBreakerRecoveryTimeoutMs);
    this.connectionTimeout = Duration.ofMillis(connectionTimeoutMs);
    this.readTimeout = Duration.ofMillis(readTimeoutMs);

    logger.debug(
        "Resilience configuration initialized - Retry: max={}, baseDelay={}ms; "
            + "CircuitBreaker: enabled={}, threshold={}, recovery={}ms; "
            + "Timeouts: connection={}ms, read={}ms",
        retryMaxAttempts,
        retryBaseDelayMs,
        circuitBreakerEnabled,
        circuitBreakerFailureThreshold,
        circuitBreakerRecoveryTimeoutMs,
        connectionTimeoutMs,
        readTimeoutMs);
  }

  /**
   * Creates configuration with default values.
   *
   * @return default resilience configuration
   */
  public static ResilienceConfig defaultConfig() {
    return new ResilienceConfig(
        DEFAULT_RETRY_MAX_ATTEMPTS,
        DEFAULT_RETRY_BASE_DELAY_MS,
        DEFAULT_CIRCUIT_BREAKER_ENABLED,
        DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD,
        DEFAULT_CIRCUIT_BREAKER_RECOVERY_TIMEOUT_MS,
        DEFAULT_CONNECTION_TIMEOUT_MS,
        DEFAULT_READ_TIMEOUT_MS);
  }

  /**
   * Gets the maximum retry attempts.
   *
   * @return retry max attempts
   */
  public int getRetryMaxAttempts() {
    return retryMaxAttempts;
  }

  /**
   * Gets the base delay for exponential backoff.
   *
   * @return base delay duration
   */
  public Duration getRetryBaseDelay() {
    return retryBaseDelay;
  }

  /**
   * Checks if circuit breaker is enabled.
   *
   * @return true if circuit breaker is enabled
   */
  public boolean isCircuitBreakerEnabled() {
    return circuitBreakerEnabled;
  }

  /**
   * Gets the circuit breaker failure threshold.
   *
   * @return failure threshold
   */
  public int getCircuitBreakerFailureThreshold() {
    return circuitBreakerFailureThreshold;
  }

  /**
   * Gets the circuit breaker recovery timeout.
   *
   * @return recovery timeout duration
   */
  public Duration getCircuitBreakerRecoveryTimeout() {
    return circuitBreakerRecoveryTimeout;
  }

  /**
   * Gets the connection timeout.
   *
   * @return connection timeout duration
   */
  public Duration getConnectionTimeout() {
    return connectionTimeout;
  }

  /**
   * Gets the read timeout.
   *
   * @return read timeout duration
   */
  public Duration getReadTimeout() {
    return readTimeout;
  }

  /**
   * Validates the resilience configuration.
   *
   * @throws IllegalArgumentException if configuration is invalid
   */
  public void validate() {
    if (retryMaxAttempts < 0) {
      throw new IllegalArgumentException(
          "Retry max attempts cannot be negative: " + retryMaxAttempts);
    }

    if (retryBaseDelay.isNegative() || retryBaseDelay.isZero()) {
      throw new IllegalArgumentException("Retry base delay must be positive: " + retryBaseDelay);
    }

    if (circuitBreakerFailureThreshold <= 0) {
      throw new IllegalArgumentException(
          "Circuit breaker failure threshold must be positive: " + circuitBreakerFailureThreshold);
    }

    if (circuitBreakerRecoveryTimeout.isNegative() || circuitBreakerRecoveryTimeout.isZero()) {
      throw new IllegalArgumentException(
          "Circuit breaker recovery timeout must be positive: " + circuitBreakerRecoveryTimeout);
    }

    if (connectionTimeout.isNegative() || connectionTimeout.isZero()) {
      throw new IllegalArgumentException(
          "Connection timeout must be positive: " + connectionTimeout);
    }

    if (readTimeout.isNegative() || readTimeout.isZero()) {
      throw new IllegalArgumentException("Read timeout must be positive: " + readTimeout);
    }

    logger.debug("Resilience configuration validation passed");
  }
}
