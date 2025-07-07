package org.devolia.kcvault.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
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
    verify(metrics).recordError(anyLong());
  }

  @Test
  void testObtainSecretGenericException() {
    // Setup mock to throw generic exception
    when(secretClient.getSecret("error-secret")).thenThrow(new RuntimeException("Generic error"));

    // Test error scenario
    assertThrows(RuntimeException.class, () -> provider.obtainSecret("error-secret"));

    // Verify metrics were recorded
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordError(anyLong());
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
}
