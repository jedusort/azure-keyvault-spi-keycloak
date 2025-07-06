package org.devolia.kcvault.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.core.http.HttpResponse;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.devolia.kcvault.auth.CredentialResolver;
import org.devolia.kcvault.cache.CacheConfig;
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

  private AzureKeyVaultProvider provider;
  private Cache<String, String> cache;

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

    // Test successful retrieval
    VaultRawSecret result = provider.obtainSecret("test-secret");

    assertNotNull(result);
    assertTrue(result.getAsArray().isPresent());
    assertEquals("secret-value", new String(result.getAsArray().get()));

    // Verify metrics were recorded
    verify(metrics).incrementCacheMiss();
    verify(metrics).recordSuccess(anyLong());

    // Verify secret was cached
    assertEquals("secret-value", cache.getIfPresent("test-secret"));

    result.close();
  }

  @Test
  void testObtainSecretFromCache() {
    // Pre-populate cache
    cache.put("cached-secret", "cached-value");

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
      when(mockSecret.getValue()).thenReturn("test-value-" + i);
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
    cache.put("test-key", "test-value");
    assertEquals(1, provider.getCacheSize());

    // Test cache invalidation
    provider.invalidateSecret("test-key");
    assertEquals(0, provider.getCacheSize());

    // Test cache clear
    cache.put("key1", "value1");
    cache.put("key2", "value2");
    assertEquals(2, provider.getCacheSize());

    provider.clearCache();
    assertEquals(0, provider.getCacheSize());
  }

  @Test
  void testClose() {
    assertDoesNotThrow(() -> provider.close());
  }
}
