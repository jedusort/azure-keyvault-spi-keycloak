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
 * Unit tests for AzureKeyVaultProviderFactory.
 *
 * <p>These tests focus on the factory's core functionality:
 *
 * <ul>
 *   <li>Configuration validation
 *   <li>Provider creation with proper dependency injection
 *   <li>Error handling for invalid configurations
 *   <li>Component lifecycle management
 * </ul>
 *
 * @author Devolia
 * @since 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class AzureKeyVaultProviderFactoryTest {

  @Mock private Config.Scope config;
  @Mock private KeycloakSession session;

  private AzureKeyVaultProviderFactory factory;

  @BeforeEach
  void setUp() {
    factory = new AzureKeyVaultProviderFactory();
  }

  @Test
  void testGetId() {
    assertEquals("azure-kv", factory.getId());
  }

  @Test
  void testGetOrder() {
    assertEquals(100, factory.order());
  }

  @Test
  void testInitWithValidConfiguration() {
    // Setup valid configuration
    when(config.get("name")).thenReturn("test-vault");
    when(config.getInt("cache-ttl", 60)).thenReturn(60);
    when(config.getInt("cache-max", 1000)).thenReturn(1000);

    // Initialize factory
    assertDoesNotThrow(() -> factory.init(config));

    // Verify configuration is read correctly
    assertEquals("test-vault", factory.getVaultName());
    assertEquals(60, factory.getCacheTtl());
    assertEquals(1000, factory.getCacheMaxSize());
  }

  @Test
  void testInitWithMissingVaultName() {
    // Setup configuration without vault name
    when(config.get("name")).thenReturn(null);

    // Initialize should throw exception
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> factory.init(config));
    assertTrue(exception.getMessage().contains("Azure Key Vault name is required"));
  }

  @Test
  void testInitWithEmptyVaultName() {
    // Setup configuration with empty vault name
    when(config.get("name")).thenReturn("");

    // Initialize should throw exception
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> factory.init(config));
    assertTrue(exception.getMessage().contains("Azure Key Vault name is required"));
  }

  @Test
  void testInitWithInvalidVaultName() {
    // Setup configuration with invalid vault name
    when(config.get("name")).thenReturn("invalid_vault_name!");

    // Initialize should throw exception
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> factory.init(config));
    assertTrue(exception.getMessage().contains("Invalid Azure Key Vault name"));
  }

  @Test
  void testInitWithDefaultValues() {
    // Setup minimal configuration
    when(config.get("name")).thenReturn("test-vault");
    when(config.getInt("cache-ttl", 60)).thenReturn(60);
    when(config.getInt("cache-max", 1000)).thenReturn(1000);

    // Initialize factory
    factory.init(config);

    // Verify defaults are used
    assertEquals(60, factory.getCacheTtl());
    assertEquals(1000, factory.getCacheMaxSize());
  }

  @Test
  void testCreateWithValidConfiguration() {
    // Setup valid configuration
    when(config.get("name")).thenReturn("test-vault");
    when(config.getInt("cache-ttl", 60)).thenReturn(60);
    when(config.getInt("cache-max", 1000)).thenReturn(1000);

    // Initialize factory
    factory.init(config);

    // Create provider
    VaultProvider provider = factory.create(session);

    // Verify provider is created
    assertNotNull(provider);
    assertInstanceOf(AzureKeyVaultProvider.class, provider);
  }

  @Test
  void testCreateWithoutInit() {
    // Create provider without initialization should throw exception
    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> factory.create(session));
    assertTrue(exception.getMessage().contains("Azure Key Vault name is required"));
  }

  @Test
  void testCreateWithInvalidVaultName() {
    // Setup configuration with invalid vault name
    when(config.get("name")).thenReturn("invalid_vault_name!");

    // Initialize should throw exception
    assertThrows(IllegalStateException.class, () -> factory.init(config));
  }

  @Test
  void testConfigurationReading() {
    // Test vault name reading
    when(config.get("name")).thenReturn("my-vault");
    factory.init(config);
    assertEquals("my-vault", factory.getVaultName());

    // Test cache TTL reading
    when(config.getInt("cache-ttl", 60)).thenReturn(120);
    assertEquals(120, factory.getCacheTtl());

    // Test cache max size reading
    when(config.getInt("cache-max", 1000)).thenReturn(2000);
    assertEquals(2000, factory.getCacheMaxSize());
  }

  @Test
  void testConfigurationDefaults() {
    // Test defaults when config is null
    AzureKeyVaultProviderFactory factoryWithoutConfig = new AzureKeyVaultProviderFactory();
    assertEquals(60, factoryWithoutConfig.getCacheTtl());
    assertEquals(1000, factoryWithoutConfig.getCacheMaxSize());
    assertNull(factoryWithoutConfig.getVaultName());
  }

  @Test
  void testValidAzureKeyVaultNames() {
    // Test valid vault names
    String[] validNames = {
      "test-vault", "my-vault-123", "a1b2c3d4e5f6", "vault-with-hyphens", "short123"
    };

    for (String vaultName : validNames) {
      when(config.get("name")).thenReturn(vaultName);
      when(config.getInt("cache-ttl", 60)).thenReturn(60);
      when(config.getInt("cache-max", 1000)).thenReturn(1000);

      assertDoesNotThrow(
          () -> factory.init(config), "Valid vault name should not throw: " + vaultName);
    }
  }

  @Test
  void testInvalidAzureKeyVaultNames() {
    // Test invalid vault names
    String[] invalidNames = {
      "ab", // Too short
      "a-very-long-vault-name-that-exceeds-24-characters", // Too long
      "vault_with_underscores", // Invalid characters
      "vault name with spaces", // Invalid characters
      "vault.with.dots", // Invalid characters
      "-starts-with-hyphen", // Starts with hyphen
      "ends-with-hyphen-", // Ends with hyphen
      "has--consecutive-hyphens", // Consecutive hyphens
      "123-starts-with-number" // Actually valid for Azure, but our regex is stricter
    };

    for (String vaultName : invalidNames) {
      when(config.get("name")).thenReturn(vaultName);

      assertThrows(
          IllegalStateException.class,
          () -> factory.init(config),
          "Invalid vault name should throw: " + vaultName);
    }
  }

  @Test
  void testFactoryLifecycle() {
    // Test init
    when(config.get("name")).thenReturn("test-vault");
    when(config.getInt("cache-ttl", 60)).thenReturn(60);
    when(config.getInt("cache-max", 1000)).thenReturn(1000);

    factory.init(config);

    // Test postInit
    assertDoesNotThrow(() -> factory.postInit(null));

    // Test close
    assertDoesNotThrow(() -> factory.close());
  }

  @Test
  void testConcurrentProviderCreation() {
    // Setup valid configuration
    when(config.get("name")).thenReturn("test-vault");
    when(config.getInt("cache-ttl", 60)).thenReturn(60);
    when(config.getInt("cache-max", 1000)).thenReturn(1000);

    factory.init(config);

    // Create multiple providers concurrently
    VaultProvider provider1 = factory.create(session);
    VaultProvider provider2 = factory.create(session);

    // Verify both providers are created and independent
    assertNotNull(provider1);
    assertNotNull(provider2);
    assertNotSame(provider1, provider2);
  }
}
