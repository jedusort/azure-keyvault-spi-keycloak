package org.devolia.kcvault.provider;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.vault.VaultProvider;
import org.keycloak.vault.VaultProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating AzureKeyVaultProvider instances.
 *
 * <p>This factory is responsible for:
 *
 * <ul>
 *   <li>Validating configuration parameters
 *   <li>Creating provider instances with proper dependency injection
 *   <li>Registering the provider with Keycloak's SPI system
 * </ul>
 *
 * <p>The factory reads configuration from Keycloak's standard SPI configuration system:
 *
 * <pre>
 * spi-vault-azure-kv-name=my-vault           # Required: Azure Key Vault name
 * spi-vault-azure-kv-cache-ttl=60            # Optional: Cache TTL in seconds (default: 60)
 * spi-vault-azure-kv-cache-max=1000          # Optional: Max cache entries (default: 1000)
 * </pre>
 *
 * @author Devolia
 * @since 1.0.0
 */
public class AzureKeyVaultProviderFactory implements VaultProviderFactory {

  private static final Logger logger = LoggerFactory.getLogger(AzureKeyVaultProviderFactory.class);

  /**
   * The provider ID used for registration with Keycloak's SPI system. This corresponds to the
   * configuration prefix: spi-vault-azure-kv-*
   */
  public static final String PROVIDER_ID = "azure-kv";

  // Configuration keys
  private static final String CONFIG_VAULT_NAME = "name";
  private static final String CONFIG_CACHE_TTL = "cache-ttl";
  private static final String CONFIG_CACHE_MAX = "cache-max";

  // Default values
  private static final int DEFAULT_CACHE_TTL = 60;
  private static final int DEFAULT_CACHE_MAX = 1000;

  private Config.Scope config;

  /**
   * Creates a new VaultProvider instance.
   *
   * <p>This method is called by Keycloak for each request that needs vault access. The provider
   * instances are typically short-lived and should delegate to longer-lived cached components for
   * expensive operations.
   *
   * @param session the Keycloak session
   * @return a new AzureKeyVaultProvider instance
   */
  @Override
  public VaultProvider create(KeycloakSession session) {
    logger.debug("Creating Azure Key Vault provider instance");

    // TODO: Implement proper CDI integration
    // For now, create a simple instance that will be enhanced with CDI

    // Validate required configuration
    String vaultName = getVaultName();
    if (vaultName == null || vaultName.trim().isEmpty()) {
      throw new IllegalStateException(
          "Azure Key Vault name is required. Please configure 'spi-vault-azure-kv-name' in keycloak.conf");
    }

    // TODO: Create and inject dependencies
    // CredentialResolver credentialResolver = createCredentialResolver();
    // CacheConfig cacheConfig = createCacheConfig();
    // AzureKeyVaultMetrics metrics = createMetrics();

    // return new AzureKeyVaultProvider(credentialResolver, cacheConfig, metrics);

    // STUB: Return a minimal instance for now
    logger.warn("STUB: Returning minimal AzureKeyVaultProvider instance");
    return null; // Will be implemented with proper CDI injection
  }

  /**
   * Initializes the factory with configuration.
   *
   * <p>This method is called once during Keycloak startup to initialize the factory with its
   * configuration scope.
   *
   * @param config the configuration scope for this provider
   */
  @Override
  public void init(Config.Scope config) {
    this.config = config;

    logger.info("Initializing Azure Key Vault provider factory");
    logger.debug(
        "Configuration - Vault: {}, Cache TTL: {}s, Cache Max: {}",
        getVaultName(),
        getCacheTtl(),
        getCacheMaxSize());

    // Validate required configuration early
    validateConfiguration();
  }

  /**
   * Post-initialization hook called after all providers are initialized.
   *
   * @param factory the Keycloak session factory
   */
  @Override
  public void postInit(KeycloakSessionFactory factory) {
    logger.debug("Post-initialization of Azure Key Vault provider factory");
    // No additional initialization needed
  }

  /** Closes the factory and cleans up resources. */
  @Override
  public void close() {
    logger.debug("Closing Azure Key Vault provider factory");
    // No cleanup needed as providers handle their own resources
  }

  /**
   * Returns the unique identifier for this provider.
   *
   * @return the provider ID
   */
  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  /**
   * Returns the order/priority of this provider. Lower values have higher priority.
   *
   * @return the provider order
   */
  @Override
  public int order() {
    return 100; // Standard priority
  }

  /**
   * Gets the Azure Key Vault name from configuration.
   *
   * @return the vault name, or null if not configured
   */
  public String getVaultName() {
    return config != null ? config.get(CONFIG_VAULT_NAME) : null;
  }

  /**
   * Gets the cache TTL from configuration.
   *
   * @return the cache TTL in seconds
   */
  public int getCacheTtl() {
    if (config == null) {
      return DEFAULT_CACHE_TTL;
    }
    return config.getInt(CONFIG_CACHE_TTL, DEFAULT_CACHE_TTL);
  }

  /**
   * Gets the maximum cache size from configuration.
   *
   * @return the maximum number of cache entries
   */
  public int getCacheMaxSize() {
    if (config == null) {
      return DEFAULT_CACHE_MAX;
    }
    return config.getInt(CONFIG_CACHE_MAX, DEFAULT_CACHE_MAX);
  }

  /** Validates the configuration and logs warnings for missing optional parameters. */
  private void validateConfiguration() {
    String vaultName = getVaultName();
    if (vaultName == null || vaultName.trim().isEmpty()) {
      logger.error("Required configuration 'spi-vault-azure-kv-name' is missing or empty");
      throw new IllegalStateException(
          "Azure Key Vault name is required. Please configure 'spi-vault-azure-kv-name' in keycloak.conf");
    }

    int cacheTtl = getCacheTtl();
    if (cacheTtl <= 0) {
      logger.warn("Invalid cache TTL: {}. Using default: {}", cacheTtl, DEFAULT_CACHE_TTL);
    }

    int cacheMax = getCacheMaxSize();
    if (cacheMax <= 0) {
      logger.warn("Invalid cache max size: {}. Using default: {}", cacheMax, DEFAULT_CACHE_MAX);
    }

    logger.info("Azure Key Vault provider configured successfully for vault: {}", vaultName);
  }
}
