package org.devolia.kcvault.resilience;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ResilienceConfig.
 *
 * @author Devolia
 * @since 1.0.0
 */
class ResilienceConfigTest {

  @Test
  void testDefaultConfig() {
    ResilienceConfig config = ResilienceConfig.defaultConfig();

    assertEquals(3, config.getRetryMaxAttempts());
    assertEquals(Duration.ofMillis(1000), config.getRetryBaseDelay());
    assertTrue(config.isCircuitBreakerEnabled());
    assertEquals(5, config.getCircuitBreakerFailureThreshold());
    assertEquals(Duration.ofMillis(30000), config.getCircuitBreakerRecoveryTimeout());
    assertEquals(Duration.ofMillis(5000), config.getConnectionTimeout());
    assertEquals(Duration.ofMillis(10000), config.getReadTimeout());
  }

  @Test
  void testCustomConfig() {
    ResilienceConfig config =
        new ResilienceConfig(
            5, // retryMaxAttempts
            2000, // retryBaseDelayMs
            false, // circuitBreakerEnabled
            10, // circuitBreakerFailureThreshold
            60000, // circuitBreakerRecoveryTimeoutMs
            8000, // connectionTimeoutMs
            15000 // readTimeoutMs
            );

    assertEquals(5, config.getRetryMaxAttempts());
    assertEquals(Duration.ofMillis(2000), config.getRetryBaseDelay());
    assertFalse(config.isCircuitBreakerEnabled());
    assertEquals(10, config.getCircuitBreakerFailureThreshold());
    assertEquals(Duration.ofMillis(60000), config.getCircuitBreakerRecoveryTimeout());
    assertEquals(Duration.ofMillis(8000), config.getConnectionTimeout());
    assertEquals(Duration.ofMillis(15000), config.getReadTimeout());
  }

  @Test
  void testValidationSuccess() {
    ResilienceConfig validConfig = new ResilienceConfig(1, 500, true, 3, 15000, 2000, 5000);

    // Should not throw any exceptions
    assertDoesNotThrow(() -> validConfig.validate());
  }

  @Test
  void testValidationFailures() {
    // Test negative retry attempts
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          ResilienceConfig config = new ResilienceConfig(-1, 1000, true, 5, 30000, 5000, 10000);
          config.validate();
        });

    // Test zero retry base delay
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          ResilienceConfig config = new ResilienceConfig(3, 0, true, 5, 30000, 5000, 10000);
          config.validate();
        });

    // Test zero circuit breaker threshold
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          ResilienceConfig config = new ResilienceConfig(3, 1000, true, 0, 30000, 5000, 10000);
          config.validate();
        });

    // Test zero connection timeout
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          ResilienceConfig config = new ResilienceConfig(3, 1000, true, 5, 30000, 0, 10000);
          config.validate();
        });
  }

  @Test
  void testZeroRetriesAllowed() {
    // Zero retries should be valid (disables retry)
    ResilienceConfig config = new ResilienceConfig(0, 1000, true, 5, 30000, 5000, 10000);
    assertDoesNotThrow(() -> config.validate());
    assertEquals(0, config.getRetryMaxAttempts());
  }
}
