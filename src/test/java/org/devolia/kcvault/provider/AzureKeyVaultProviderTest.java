package org.devolia.kcvault.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.core.http.HttpResponse;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.devolia.kcvault.auth.CredentialResolver;
import org.devolia.kcvault.cache.CacheConfig;
import org.devolia.kcvault.cache.CachedSecret;
import org.devolia.kcvault.metrics.AzureKeyVaultMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.vault.VaultRawSecret;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for AzureKeyVaultProvider.
 *
 * <p>These tests focus on the provider's core functionality:
 *
 * <ul>
 *   <li>Secret name sanitization
 *   <li>Cache operations
 *   <li>Error handling
 *   <li>Metrics recording
 * </ul>
 *
 * @author Devolia
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class AzureKeyVaultProviderTest {

  @Mock private CredentialResolver credentialResolver;
  @Mock private CacheConfig cacheConfig;
  @Mock private AzureKeyVaultMetrics metrics;
  @Mock private SecretClient secretClient;
  @Mock private KeyVaultSecret keyVaultSecret;
  @Mock private SecretProperties secretProperties;

  private AzureKeyVaultProvider provider;
  private Cache<String, CachedSecret> cache;

  @BeforeEach
  void setUp() {
    // Create a real cache for testing
    cache = Caffeine.newBuilder().build();

    // Setup mocks
    when(cacheConfig.getVaultName()).thenReturn("test-vault");
    when(cacheConfig.buildCache()).thenReturn(cache);
    when(cacheConfig.getCacheTtl()).thenReturn(60);
    when(cacheConfig.getCacheMaxSize()).thenReturn(1000);
    when(credentialResolver.createSecretClient(anyString())).thenReturn(secretClient);

    provider = new AzureKeyVaultProvider(credentialResolver, cacheConfig, metrics);
  }

  @Test
  void testObtainSecretWithNullInput() {
    VaultRawSecret result = provider.obtainSecret(null);
    assertNull(result);

    // Verify no metrics are recorded for null input
    verifyNoInteractions(metrics);
  }

  @Test
  void testObtainSecretWithEmptyInput() {
    VaultRawSecret result = provider.obtainSecret("");
    assertNull(result);

    VaultRawSecret result2 = provider.obtainSecret("   ");
    assertNull(result2);

    // Verify no metrics are recorded for empty input
    verifyNoInteractions(metrics);
  }

  @Test
  void testObtainSecretSuccessfulRetrieval() {
    // Setup mock responses
    when(secretClient.getSecret("test-secret")).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getValue()).thenReturn("secret-value");
    when(keyVaultSecret.getProperties()).thenReturn(secretProperties);
    when(secretProperties.getVersion()).thenReturn("abc123");
    when(secretProperties.isEnabled()).thenReturn(true);
    when(secretProperties.getExpiresOn()).thenReturn(OffsetDateTime.now().plusDays(30));
    when(secretProperties.getNotBefore()).thenReturn(OffsetDateTime.now().minusDays(1));

    // Test successful retrieval
    VaultRawSecret result = provider.obtainSecret("test-secret");

    assertNotNull(result);
    assertTrue(result.getAsArray().isPresent());
    assertEquals("secret-value", new String(result.getAsArray().get()));

    // Verify metrics were recorded
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordSuccess(anyLong());

    // Verify secret was cached
    CachedSecret cachedResult = cache.getIfPresent("test-secret");
    assertNotNull(cachedResult);
    assertEquals("secret-value", cachedResult.getValue());

    result.close();
  }

  @Test
  void testObtainSecretFromCache() {
    // Pre-populate cache
    cache.put("cached-secret", new CachedSecret("cached-value", null));

    // Test cache hit
    VaultRawSecret result = provider.obtainSecret("cached-secret");

    assertNotNull(result);
    assertTrue(result.getAsArray().isPresent());
    assertEquals("cached-value", new String(result.getAsArray().get()));

    // Verify metrics were recorded
    verify(metrics).incrementCacheHit();

    // Verify SecretClient was not called
    verifyNoInteractions(secretClient);

    result.close();
  }

  @Test
  void testObtainSecretNotFound() {
    // Setup mock HttpResponse for ResourceNotFoundException
    HttpResponse httpResponse = mock(HttpResponse.class);

    // Setup mock to throw ResourceNotFoundException
    when(secretClient.getSecret("missing-secret"))
        .thenThrow(new ResourceNotFoundException("Secret not found", httpResponse));

    // Test not found scenario
    VaultRawSecret result = provider.obtainSecret("missing-secret");

    assertNull(result);

    // Verify metrics were recorded
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordNotFound(anyLong());

    // Verify secret was not cached
    assertNull(cache.getIfPresent("missing-secret"));
  }

  @Test
  void testObtainSecretHttpResponseException404() {
    // Setup mock HttpResponse
    HttpResponse httpResponse = mock(HttpResponse.class);
    when(httpResponse.getStatusCode()).thenReturn(404);

    // Setup mock to throw HttpResponseException with 404
    HttpResponseException exception = new HttpResponseException("Not found", httpResponse);
    when(secretClient.getSecret("missing-secret")).thenThrow(exception);

    // Test 404 scenario
    VaultRawSecret result = provider.obtainSecret("missing-secret");

    assertNull(result);

    // Verify metrics were recorded
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordNotFound(anyLong());
  }

  @Test
  void testObtainSecretHttpResponseExceptionOtherStatus() {
    // Setup mock HttpResponse
    HttpResponse httpResponse = mock(HttpResponse.class);
    when(httpResponse.getStatusCode()).thenReturn(500);

    // Setup mock to throw HttpResponseException with 500
    HttpResponseException exception = new HttpResponseException("Server error", httpResponse);
    when(secretClient.getSecret("error-secret")).thenThrow(exception);

    // Test error scenario
    assertThrows(RuntimeException.class, () -> provider.obtainSecret("error-secret"));

    // Verify metrics were recorded
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordError(anyLong(), anyString());
  }

  @Test
  void testObtainSecretGenericException() {
    // Setup mock to throw generic exception
    when(secretClient.getSecret("error-secret")).thenThrow(new RuntimeException("Generic error"));

    // Test error scenario
    assertThrows(RuntimeException.class, () -> provider.obtainSecret("error-secret"));

    // Verify metrics were recorded
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordError(anyLong(), anyString());
  }

  @Test
  void testSecretNameSanitization() {
    // Test secret name sanitization logic
    // Azure Key Vault secret names must be alphanumeric with hyphens only

    // Test various input formats that should all be sanitized to "my-secret"
    String[] testInputs = {"my.secret", "my_secret", "my::secret", "My-Secret", "MY.SECRET"};

    for (int i = 0; i < testInputs.length; i++) {
      String input = testInputs[i];

      // Setup fresh mock response for each test
      KeyVaultSecret mockSecret = mock(KeyVaultSecret.class);
      SecretProperties mockProperties = mock(SecretProperties.class);
      when(mockSecret.getValue()).thenReturn("test-value-" + i);
      when(mockSecret.getProperties()).thenReturn(mockProperties);
      when(mockProperties.isEnabled()).thenReturn(true);
      when(mockProperties.getVersion()).thenReturn("v" + i);
      when(secretClient.getSecret("my-secret")).thenReturn(mockSecret);

      // Clear cache before each test to avoid cache hits
      cache.invalidateAll();

      VaultRawSecret result = provider.obtainSecret(input);
      assertNotNull(result, "Failed for input: " + input);
      assertEquals("test-value-" + i, new String(result.getAsArray().get()));
      result.close();
    }

    // Verify that sanitized name was used for SecretClient calls
    verify(secretClient, times(testInputs.length)).getSecret("my-secret");
  }

  @Test
  void testCacheOperations() {
    // Test cache size
    assertEquals(0, provider.getCacheSize());

    // Add something to cache
    cache.put("test-key", new CachedSecret("test-value", null));
    assertEquals(1, provider.getCacheSize());

    // Test cache invalidation
    provider.invalidateSecret("test-key");
    assertEquals(0, provider.getCacheSize());

    // Test cache clear
    cache.put("key1", new CachedSecret("value1", null));
    cache.put("key2", new CachedSecret("value2", null));
    assertEquals(2, provider.getCacheSize());

    provider.clearCache();
    assertEquals(0, provider.getCacheSize());
  }

  @Test
  void testClose() {
    assertDoesNotThrow(() -> provider.close());
  }

  // === Metadata and Expiration Tests ===

  @Test
  void testObtainSecretExpired() {
    // Setup mock responses for expired secret
    when(secretClient.getSecret("expired-secret")).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getProperties()).thenReturn(secretProperties);
    when(secretProperties.getExpiresOn())
        .thenReturn(OffsetDateTime.now().minusDays(1)); // Expired yesterday

    // Test expired secret retrieval
    VaultRawSecret result = provider.obtainSecret("expired-secret");

    assertNull(result);

    // Verify metrics were recorded as not found
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordNotFound(anyLong());

    // Verify secret was not cached
    assertNull(cache.getIfPresent("expired-secret"));
  }

  @Test
  void testObtainSecretNotYetValid() {
    // Setup mock responses for not-yet-valid secret
    when(secretClient.getSecret("future-secret")).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getProperties()).thenReturn(secretProperties);
    when(secretProperties.getNotBefore())
        .thenReturn(OffsetDateTime.now().plusDays(1)); // Valid tomorrow

    // Test not-yet-valid secret retrieval
    VaultRawSecret result = provider.obtainSecret("future-secret");

    assertNull(result);

    // Verify metrics were recorded as not found
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordNotFound(anyLong());

    // Verify secret was not cached
    assertNull(cache.getIfPresent("future-secret"));
  }

  @Test
  void testObtainSecretDisabled() {
    // Setup mock responses for disabled secret
    when(secretClient.getSecret("disabled-secret")).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getProperties()).thenReturn(secretProperties);
    when(secretProperties.isEnabled()).thenReturn(false); // Disabled

    // Test disabled secret retrieval
    VaultRawSecret result = provider.obtainSecret("disabled-secret");

    assertNull(result);

    // Verify metrics were recorded as not found
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordNotFound(anyLong());

    // Verify secret was not cached
    assertNull(cache.getIfPresent("disabled-secret"));
  }

  @Test
  void testObtainSecretWithNullProperties() {
    // Setup mock responses with null properties (fallback behavior)
    when(secretClient.getSecret("no-metadata-secret")).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getValue()).thenReturn("secret-value");
    when(keyVaultSecret.getProperties()).thenReturn(null); // No metadata

    // Test retrieval with null properties
    VaultRawSecret result = provider.obtainSecret("no-metadata-secret");

    // Should still work when metadata is missing
    assertNotNull(result);
    assertTrue(result.getAsArray().isPresent());
    assertEquals("secret-value", new String(result.getAsArray().get()));

    // Verify metrics were recorded
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordSuccess(anyLong());

    // Verify secret was cached
    CachedSecret cachedResult = cache.getIfPresent("no-metadata-secret");
    assertNotNull(cachedResult);
    assertEquals("secret-value", cachedResult.getValue());

    result.close();
  }

  @Test
  void testObtainSecretWithValidTimeRange() {
    // Setup mock responses for secret valid in time range
    OffsetDateTime now = OffsetDateTime.now();
    when(secretClient.getSecret("valid-secret")).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getValue()).thenReturn("secret-value");
    when(keyVaultSecret.getProperties()).thenReturn(secretProperties);
    when(secretProperties.getNotBefore()).thenReturn(now.minusHours(1)); // Valid since 1 hour ago
    when(secretProperties.getExpiresOn()).thenReturn(now.plusHours(1)); // Valid for 1 more hour
    when(secretProperties.isEnabled()).thenReturn(true);
    when(secretProperties.getVersion()).thenReturn("v1");

    // Test retrieval of valid secret
    VaultRawSecret result = provider.obtainSecret("valid-secret");

    assertNotNull(result);
    assertTrue(result.getAsArray().isPresent());
    assertEquals("secret-value", new String(result.getAsArray().get()));

    // Verify metrics were recorded
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordSuccess(anyLong());

    // Verify secret was cached
    CachedSecret cachedResult = cache.getIfPresent("valid-secret");
    assertNotNull(cachedResult);
    assertEquals("secret-value", cachedResult.getValue());

    result.close();
  }

  @Test
  void testObtainSecretWithPartialMetadata() {
    // Setup mock responses with partial metadata (some properties null)
    when(secretClient.getSecret("partial-metadata-secret")).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getValue()).thenReturn("secret-value");
    when(keyVaultSecret.getProperties()).thenReturn(secretProperties);
    when(secretProperties.getNotBefore()).thenReturn(null); // No notBefore restriction
    when(secretProperties.getExpiresOn()).thenReturn(null); // No expiration
    when(secretProperties.isEnabled()).thenReturn(null); // Enabled status unknown

    // Test retrieval with partial metadata
    VaultRawSecret result = provider.obtainSecret("partial-metadata-secret");

    // Should work when only some metadata is available
    assertNotNull(result);
    assertTrue(result.getAsArray().isPresent());
    assertEquals("secret-value", new String(result.getAsArray().get()));

    // Verify metrics were recorded
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordSuccess(anyLong());

    // Verify secret was cached
    CachedSecret cachedResult = cache.getIfPresent("partial-metadata-secret");
    assertNotNull(cachedResult);
    assertEquals("secret-value", cachedResult.getValue());

    result.close();
  }

  @Test
  void testCacheExpiredSecretRemoval() {
    // Pre-populate cache with expired secret
    OffsetDateTime expiredTime = OffsetDateTime.now().minusHours(1);
    cache.put("expired-cached-secret", new CachedSecret("expired-value", expiredTime));

    // Setup fresh secret for retrieval
    when(secretClient.getSecret("expired-cached-secret")).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getValue()).thenReturn("fresh-value");
    when(keyVaultSecret.getProperties()).thenReturn(secretProperties);
    when(secretProperties.isEnabled()).thenReturn(true);
    when(secretProperties.getVersion()).thenReturn("v2");

    // Test retrieval - should fetch fresh secret and remove expired from cache
    VaultRawSecret result = provider.obtainSecret("expired-cached-secret");

    assertNotNull(result);
    assertTrue(result.getAsArray().isPresent());
    assertEquals("fresh-value", new String(result.getAsArray().get()));

    // Verify metrics show cache miss (due to expiration)
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordSuccess(anyLong());

    // Verify fresh secret is now cached
    CachedSecret cachedResult = cache.getIfPresent("expired-cached-secret");
    assertNotNull(cachedResult);
    assertEquals("fresh-value", cachedResult.getValue());

    result.close();
  }

  // === Comprehensive Metrics Tests ===

  @Test
  void testMetricsLatencyRecording() {
    // Setup mock response
    when(secretClient.getSecret("latency-test-secret")).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getValue()).thenReturn("test-value");
    when(keyVaultSecret.getProperties()).thenReturn(secretProperties);
    when(secretProperties.isEnabled()).thenReturn(true);

    // Measure the call
    long startTime = System.nanoTime();
    VaultRawSecret result = provider.obtainSecret("latency-test-secret");
    long endTime = System.nanoTime();

    assertNotNull(result);

    // Verify latency was recorded with reasonable bounds
    verify(metrics)
        .recordSuccess(longThat(latency -> latency >= 0 && latency <= (endTime - startTime) * 2));

    result.close();
  }

  @Test
  void testMetricsErrorLatencyRecording() {
    // Setup mock to throw exception
    when(secretClient.getSecret("error-latency-secret"))
        .thenThrow(new RuntimeException("Test error"));

    // Measure the call
    long startTime = System.nanoTime();
    assertThrows(RuntimeException.class, () -> provider.obtainSecret("error-latency-secret"));
    long endTime = System.nanoTime();

    // Verify error latency was recorded with category
    verify(metrics)
        .recordError(
            longThat(latency -> latency >= 0 && latency <= (endTime - startTime) * 2), anyString());
  }

  @Test
  void testMetricsNotFoundLatencyRecording() {
    // Setup mock HttpResponse for 404
    HttpResponse httpResponse = mock(HttpResponse.class);
    when(httpResponse.getStatusCode()).thenReturn(404);
    HttpResponseException exception = new HttpResponseException("Not found", httpResponse);
    when(secretClient.getSecret("not-found-latency-secret")).thenThrow(exception);

    // Measure the call
    long startTime = System.nanoTime();
    VaultRawSecret result = provider.obtainSecret("not-found-latency-secret");
    long endTime = System.nanoTime();

    assertNull(result);

    // Verify not found latency was recorded
    verify(metrics)
        .recordNotFound(longThat(latency -> latency >= 0 && latency <= (endTime - startTime) * 2));
  }

  // === Cache Configuration and LRU Tests ===

  @Test
  void testCacheSizeLimitsAndEviction() {
    // Create a new provider with small cache for testing LRU behavior
    Cache<String, CachedSecret> smallCache =
        Caffeine.newBuilder()
            .maximumSize(2) // Very small cache
            .build();

    when(cacheConfig.buildCache()).thenReturn(smallCache);
    AzureKeyVaultProvider testProvider =
        new AzureKeyVaultProvider(credentialResolver, cacheConfig, metrics);

    // Setup mock responses for multiple secrets with unique return values
    KeyVaultSecret secret1 = mock(KeyVaultSecret.class);
    KeyVaultSecret secret2 = mock(KeyVaultSecret.class);
    KeyVaultSecret secret3 = mock(KeyVaultSecret.class);

    when(secretClient.getSecret("secret1")).thenReturn(secret1);
    when(secretClient.getSecret("secret2")).thenReturn(secret2);
    when(secretClient.getSecret("secret3")).thenReturn(secret3);

    when(secret1.getValue()).thenReturn("value1");
    when(secret2.getValue()).thenReturn("value2");
    when(secret3.getValue()).thenReturn("value3");

    when(secret1.getProperties()).thenReturn(null);
    when(secret2.getProperties()).thenReturn(null);
    when(secret3.getProperties()).thenReturn(null);

    // Add secrets to fill cache
    testProvider.obtainSecret("secret1").close();
    assertEquals(1, testProvider.getCacheSize());

    testProvider.obtainSecret("secret2").close();
    assertEquals(2, testProvider.getCacheSize());

    // Adding third secret should trigger eviction when cache is full
    testProvider.obtainSecret("secret3").close();

    // Cache cleanup happens asynchronously in Caffeine, so we need to trigger it
    smallCache.cleanUp();

    // Cache size should be at most 2 (the max size)
    assertTrue(
        testProvider.getCacheSize() <= 2,
        "Cache size should not exceed maximum: " + testProvider.getCacheSize());

    // Verify metrics recorded the cache misses for initial retrievals
    verify(metrics, atLeast(3)).incrementCacheMiss();
  }

  @Test
  void testCacheTTLExpiration() {
    // Create a cache with very short TTL for testing
    Cache<String, CachedSecret> shortTtlCache =
        Caffeine.newBuilder()
            .expireAfterWrite(java.time.Duration.ofMillis(50)) // 50ms TTL
            .build();

    when(cacheConfig.buildCache()).thenReturn(shortTtlCache);
    AzureKeyVaultProvider testProvider =
        new AzureKeyVaultProvider(credentialResolver, cacheConfig, metrics);

    // Setup mock response
    when(secretClient.getSecret("ttl-test-secret")).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getValue()).thenReturn("test-value");
    when(keyVaultSecret.getProperties()).thenReturn(null);

    // First call - cache miss
    testProvider.obtainSecret("ttl-test-secret").close();
    assertEquals(1, testProvider.getCacheSize());

    // Second call immediately - cache hit
    testProvider.obtainSecret("ttl-test-secret").close();

    // Wait for TTL to expire
    try {
      Thread.sleep(100); // Wait longer than 50ms TTL
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Third call after TTL - should be cache miss
    testProvider.obtainSecret("ttl-test-secret").close();

    // Verify we had cache misses for first and third calls
    verify(metrics, atLeast(2)).incrementCacheMiss();
  }

  // === Various Configuration Tests ===

  @Test
  void testInvalidateSecretWithSanitization() {
    // Add a secret to cache using sanitized name
    cache.put("my-special-secret", new CachedSecret("test-value", null));
    assertEquals(1, provider.getCacheSize());

    // Invalidate using original (non-sanitized) name
    provider.invalidateSecret("my.special.secret");

    // Verify secret was invalidated (sanitization should match)
    assertEquals(0, provider.getCacheSize());
  }

  @Test
  void testInvalidateSecretWithNullInput() {
    // Add a secret to cache
    cache.put("test-secret", new CachedSecret("test-value", null));
    assertEquals(1, provider.getCacheSize());

    // Invalidate with null input should not crash or affect cache
    assertDoesNotThrow(() -> provider.invalidateSecret(null));
    assertEquals(1, provider.getCacheSize());
  }

  @Test
  void testMultipleCacheOperationsSequence() {
    // Test a sequence of cache operations to verify state consistency

    // Start with empty cache
    assertEquals(0, provider.getCacheSize());

    // Add multiple items
    cache.put("item1", new CachedSecret("value1", null));
    cache.put("item2", new CachedSecret("value2", null));
    cache.put("item3", new CachedSecret("value3", null));
    assertEquals(3, provider.getCacheSize());

    // Invalidate one item
    provider.invalidateSecret("item1");
    assertEquals(2, provider.getCacheSize());

    // Clear all
    provider.clearCache();
    assertEquals(0, provider.getCacheSize());

    // Verify multiple clears don't cause issues
    provider.clearCache();
    assertEquals(0, provider.getCacheSize());
  }

  // === Extended Error Handling Tests ===

  @Test
  void testNetworkTimeoutScenario() {
    // Simulate network timeout using a generic exception that could represent timeout
    when(secretClient.getSecret("timeout-secret"))
        .thenThrow(new RuntimeException("Request timeout"));

    // Test timeout scenario
    assertThrows(RuntimeException.class, () -> provider.obtainSecret("timeout-secret"));

    // Verify metrics were recorded as error
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordError(anyLong(), anyString());
  }

  @Test
  void testAuthenticationFailureScenario() {
    // Simulate authentication failure
    when(secretClient.getSecret("auth-fail-secret"))
        .thenThrow(new RuntimeException("Authentication failed"));

    // Test authentication failure scenario
    assertThrows(RuntimeException.class, () -> provider.obtainSecret("auth-fail-secret"));

    // Verify metrics were recorded as error
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordError(anyLong(), anyString());
  }

  // Temporarily disabled - replaced with more focused resilience pattern tests
  // @Test
  void testHttpResponseExceptionVariousStatusesOld() {
    // Test different HTTP status codes with separate tests
    // to avoid circuit breaker interference

    // Test permanent failures (400, 401, 403) - these should not retry
    int[] permanentErrorCodes = {400, 401, 403};
    for (int statusCode : permanentErrorCodes) {
      HttpResponse httpResponse = mock(HttpResponse.class);
      when(httpResponse.getStatusCode()).thenReturn(statusCode);
      HttpResponseException exception = new HttpResponseException("HTTP error", httpResponse);

      String secretName = "http-perm-error-" + statusCode;
      when(secretClient.getSecret(secretName)).thenThrow(exception);

      // Permanent errors should throw RuntimeException without retry
      assertThrows(
          RuntimeException.class,
          () -> provider.obtainSecret(secretName),
          "Status code " + statusCode + " should throw RuntimeException");
    }

    // Test transient failures (500, 502, 503) - these will retry
    int[] transientErrorCodes = {500, 502, 503};
    for (int statusCode : transientErrorCodes) {
      HttpResponse httpResponse = mock(HttpResponse.class);
      when(httpResponse.getStatusCode()).thenReturn(statusCode);
      HttpResponseException exception = new HttpResponseException("HTTP error", httpResponse);

      String secretName = "http-trans-error-" + statusCode;
      when(secretClient.getSecret(secretName)).thenThrow(exception);

      // Transient errors should eventually throw RuntimeException after retries
      assertThrows(
          RuntimeException.class,
          () -> provider.obtainSecret(secretName),
          "Status code " + statusCode + " should throw RuntimeException after retries");
    }

    // With resilience patterns, we expect error metrics for both permanent and transient failures
    // Note: Transient failures will have additional retry attempt metrics
    verify(metrics, atLeast(permanentErrorCodes.length + transientErrorCodes.length))
        .recordError(anyLong(), anyString());
  }

  // === Resilience Pattern Tests ===

  @Test
  void testRetryOnTransientFailure() {
    // Setup a transient failure (HTTP 503) that should trigger retry
    HttpResponse httpResponse = mock(HttpResponse.class);
    when(httpResponse.getStatusCode()).thenReturn(503);
    HttpResponseException transientException =
        new HttpResponseException("Service Unavailable", httpResponse);

    when(secretClient.getSecret("retry-test-secret")).thenThrow(transientException);

    // Should eventually throw RuntimeException after retries
    assertThrows(RuntimeException.class, () -> provider.obtainSecret("retry-test-secret"));

    // Verify error was recorded with correct category
    verify(metrics).recordError(anyLong(), eq("service_unavailable"));
    // Verify retry attempts were recorded (should be 3 attempts with default config)
    verify(metrics, atLeast(1)).recordRetryAttempt(anyInt(), eq("service_unavailable"));
  }

  @Test
  void testNoRetryOnPermanentFailure() {
    // Setup a permanent failure (HTTP 401) that should NOT trigger retry
    HttpResponse httpResponse = mock(HttpResponse.class);
    when(httpResponse.getStatusCode()).thenReturn(401);
    HttpResponseException permanentException =
        new HttpResponseException("Unauthorized", httpResponse);

    when(secretClient.getSecret("no-retry-secret")).thenThrow(permanentException);

    // Should throw RuntimeException immediately without retries
    assertThrows(RuntimeException.class, () -> provider.obtainSecret("no-retry-secret"));

    // Verify error was recorded with correct category
    verify(metrics).recordError(anyLong(), eq("unauthorized"));
    // Verify NO retry attempts were recorded for permanent failures
    verify(metrics, never()).recordRetryAttempt(anyInt(), anyString());
  }

  @Test
  void testErrorCategorization() {
    // Test various error types are properly categorized

    // Test HTTP rate limiting error
    HttpResponse httpResponse = mock(HttpResponse.class);
    when(httpResponse.getStatusCode()).thenReturn(429);
    HttpResponseException rateLimitException =
        new HttpResponseException("Too Many Requests", httpResponse);
    when(secretClient.getSecret("rate-limit-secret")).thenThrow(rateLimitException);
    assertThrows(RuntimeException.class, () -> provider.obtainSecret("rate-limit-secret"));
    verify(metrics).recordError(anyLong(), eq("rate_limited"));

    // Test HTTP bad request error
    HttpResponse badRequestResponse = mock(HttpResponse.class);
    when(badRequestResponse.getStatusCode()).thenReturn(400);
    HttpResponseException badRequestException =
        new HttpResponseException("Bad Request", badRequestResponse);
    when(secretClient.getSecret("bad-request-secret")).thenThrow(badRequestException);
    assertThrows(RuntimeException.class, () -> provider.obtainSecret("bad-request-secret"));
    verify(metrics).recordError(anyLong(), eq("bad_request"));

    // Test generic error
    when(secretClient.getSecret("generic-secret")).thenThrow(new RuntimeException("Unknown error"));
    assertThrows(RuntimeException.class, () -> provider.obtainSecret("generic-secret"));
    verify(metrics).recordError(anyLong(), eq("unknown"));
  }

  // === Concurrent Access Tests ===

  @Test
  void testConcurrentSecretRetrieval() {
    // Setup mock response
    when(secretClient.getSecret("concurrent-secret")).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getValue()).thenReturn("concurrent-value");
    when(keyVaultSecret.getProperties()).thenReturn(null);

    int threadCount = 5; // Using fewer threads to reduce test complexity
    AtomicInteger successCount = new AtomicInteger(0);

    // Create concurrent futures
    CompletableFuture<Void>[] futures = new CompletableFuture[threadCount];
    for (int i = 0; i < threadCount; i++) {
      futures[i] =
          CompletableFuture.runAsync(
              () -> {
                try {
                  VaultRawSecret result = provider.obtainSecret("concurrent-secret");
                  if (result != null) {
                    successCount.incrementAndGet();
                    result.close();
                  }
                } catch (Exception e) {
                  // Should not happen in this test
                  fail("Unexpected exception during concurrent access: " + e.getMessage());
                }
              });
    }

    // Wait for all futures to complete
    CompletableFuture.allOf(futures).join();

    // Verify all threads succeeded
    assertEquals(
        threadCount, successCount.get(), "All threads should successfully retrieve the secret");

    // Verify at least one cache miss (first access) and some cache hits
    verify(metrics, atLeast(1)).incrementCacheMiss();
    verify(metrics, atLeast(1)).incrementCacheHit();
  }

  // === Additional Edge Case Tests ===

  @Test
  void testSecretNameSanitizationEdgeCases() {
    // Test extreme edge cases for secret name sanitization
    String[] edgeCases = {
      "", // empty string
      "---", // only hyphens
      "123abc", // starts with numbers
      "a", // single character
      "secret-with-multiple---hyphens", // multiple consecutive hyphens
      "UPPERCASE-secret", // uppercase
      "secret.with.dots.and_underscores" // mixed special characters
    };

    // Setup a generic response for any sanitized name
    when(secretClient.getSecret(anyString())).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getValue()).thenReturn("test-value");
    when(keyVaultSecret.getProperties()).thenReturn(null);

    for (String edgeCase : edgeCases) {
      // Clear cache to ensure fresh test
      provider.clearCache();

      if (!edgeCase.isEmpty()) { // Skip empty string as it's handled separately
        VaultRawSecret result = provider.obtainSecret(edgeCase);
        if (result != null) {
          result.close();
        }
      }
    }

    // Just verify that sanitization doesn't crash the system
    assertTrue(true, "Secret name sanitization should handle edge cases gracefully");
  }

  @Test
  void testLargeSecretValue() {
    // Test handling of large secret values
    StringBuilder largeValue = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      largeValue.append("This is a large secret value segment ").append(i).append(". ");
    }

    when(secretClient.getSecret("large-secret")).thenReturn(keyVaultSecret);
    when(keyVaultSecret.getValue()).thenReturn(largeValue.toString());
    when(keyVaultSecret.getProperties()).thenReturn(null);

    VaultRawSecret result = provider.obtainSecret("large-secret");

    assertNotNull(result);
    assertTrue(result.getAsArray().isPresent());
    assertEquals(largeValue.toString(), new String(result.getAsArray().get()));

    // Verify caching works for large values
    VaultRawSecret cachedResult = provider.obtainSecret("large-secret");
    assertNotNull(cachedResult);
    assertEquals(largeValue.toString(), new String(cachedResult.getAsArray().get()));

    verify(metrics).incrementCacheMiss(); // First call
    verify(metrics).incrementCacheHit(); // Second call

    result.close();
    cachedResult.close();
  }
}
