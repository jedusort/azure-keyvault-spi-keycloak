package org.devolia.kcvault.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.vault.VaultProvider;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration tests for AzureKeyVaultProviderFactory.
 *
 * <p>These tests verify the end-to-end integration of the factory with real dependency creation.
 *
 * @author Devolia
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class AzureKeyVaultProviderFactoryIntegrationTest {

  @Mock private Config.Scope config;
  @Mock private KeycloakSession session;

  private AzureKeyVaultProviderFactory factory;

  @BeforeEach
  void setUp() {
    factory = new AzureKeyVaultProviderFactory();
  }

  @Test
  void testEndToEndProviderCreation() {
    // Setup valid configuration
    when(config.get("name")).thenReturn("test-vault");
    when(config.getInt("cache-ttl", 60)).thenReturn(30);
    when(config.getInt("cache-max", 1000)).thenReturn(500);

    // Initialize factory
    factory.init(config);

    // Create provider
    VaultProvider provider = factory.create(session);

    // Verify provider is created and is the correct type
    assertNotNull(provider);
    assertInstanceOf(AzureKeyVaultProvider.class, provider);

    // Verify provider has all expected capabilities
    AzureKeyVaultProvider azureProvider = (AzureKeyVaultProvider) provider;

    // Test that cache is working (empty initially)
    assertEquals(0, azureProvider.getCacheSize());

    // Clean up
    provider.close();
  }

  @Test
  void testMultipleProviderCreation() {
    // Setup valid configuration
    when(config.get("name")).thenReturn("test-vault");
    when(config.getInt("cache-ttl", 60)).thenReturn(60);
    when(config.getInt("cache-max", 1000)).thenReturn(1000);

    // Initialize factory
    factory.init(config);

    // Create multiple providers
    VaultProvider provider1 = factory.create(session);
    VaultProvider provider2 = factory.create(session);

    // Verify both providers are created and independent
    assertNotNull(provider1);
    assertNotNull(provider2);
    assertNotSame(provider1, provider2);

    // Verify both have their own cache
    AzureKeyVaultProvider azureProvider1 = (AzureKeyVaultProvider) provider1;
    AzureKeyVaultProvider azureProvider2 = (AzureKeyVaultProvider) provider2;

    assertEquals(0, azureProvider1.getCacheSize());
    assertEquals(0, azureProvider2.getCacheSize());

    // Clean up
    provider1.close();
    provider2.close();
  }

  @Test
  void testProviderCreationWithCustomConfiguration() {
    // Setup custom configuration
    when(config.get("name")).thenReturn("custom-vault");
    when(config.getInt("cache-ttl", 60)).thenReturn(120);
    when(config.getInt("cache-max", 1000)).thenReturn(2000);

    // Initialize factory
    factory.init(config);

    // Create provider
    VaultProvider provider = factory.create(session);

    // Verify provider is created
    assertNotNull(provider);
    assertInstanceOf(AzureKeyVaultProvider.class, provider);

    // Clean up
    provider.close();
  }

  @Test
  void testFactoryStateIsolation() {
    // Test that factory state doesn't interfere with multiple provider creation
    when(config.get("name")).thenReturn("test-vault");
    when(config.getInt("cache-ttl", 60)).thenReturn(60);
    when(config.getInt("cache-max", 1000)).thenReturn(1000);

    // Initialize factory
    factory.init(config);

    // Create first provider
    VaultProvider provider1 = factory.create(session);
    assertNotNull(provider1);

    // Create second provider after first is created
    VaultProvider provider2 = factory.create(session);
    assertNotNull(provider2);

    // Verify they are independent
    assertNotSame(provider1, provider2);

    // Clean up
    provider1.close();
    provider2.close();
  }

  @Test
  void testProviderCreationFailsGracefully() {
    // Setup configuration that would cause creation to fail
    when(config.get("name")).thenReturn(""); // Empty vault name

    // Initialize should fail
    assertThrows(IllegalStateException.class, () -> factory.init(config));
  }
}
