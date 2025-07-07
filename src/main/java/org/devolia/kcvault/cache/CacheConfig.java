package org.devolia.kcvault.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration and factory for the secret cache.
 *
 * <p>This class manages the Caffeine cache configuration for storing Azure Key Vault secrets with
 * appropriate TTL and LRU policies.
 *
 * <p>Cache features:
 *
 * <ul>
 *   <li>Time-based expiration (TTL) - configurable, default 60 seconds
 *   <li>Size-based eviction (LRU) - configurable, default 1000 entries
 *   <li>Thread-safe operations
 *   <li>Automatic cleanup of expired entries
 * </ul>
 *
 * @author Devolia
 * @since 1.0.0
 */
public class CacheConfig {

  private static final Logger logger = LoggerFactory.getLogger(CacheConfig.class);

  private final String vaultName;
  private final int cacheTtl;
  private final int cacheMaxSize;

  /**
   * Constructor with configuration values.
   *
   * @param vaultName the Azure Key Vault name
   * @param cacheTtl cache TTL in seconds
   * @param cacheMaxSize maximum number of cache entries
   */
  public CacheConfig(String vaultName, int cacheTtl, int cacheMaxSize) {
    this.vaultName = vaultName;
    this.cacheTtl = cacheTtl;
    this.cacheMaxSize = cacheMaxSize;

    logger.debug(
        "Cache configuration initialized - TTL: {}s, Max size: {}", cacheTtl, cacheMaxSize);
  }

  /**
   * Builds and configures the Caffeine cache for secrets.
   *
   * @return configured cache instance
   */
  public Cache<String, CachedSecret> buildCache() {
    Cache<String, CachedSecret> cache =
        Caffeine.newBuilder()
            .maximumSize(cacheMaxSize)
            .expireAfterWrite(Duration.ofSeconds(cacheTtl))
            .recordStats() // Enable statistics for monitoring
            .build();

    logger.info("Built secret cache with TTL: {}s, Max size: {}", cacheTtl, cacheMaxSize);
    return cache;
  }

  /**
   * Gets the Azure Key Vault name.
   *
   * @return the vault name
   */
  public String getVaultName() {
    return vaultName;
  }

  /**
   * Gets the cache TTL in seconds.
   *
   * @return cache TTL
   */
  public int getCacheTtl() {
    return cacheTtl;
  }

  /**
   * Gets the maximum cache size.
   *
   * @return maximum number of cache entries
   */
  public int getCacheMaxSize() {
    return cacheMaxSize;
  }

  /**
   * Validates the cache configuration.
   *
   * @throws IllegalArgumentException if configuration is invalid
   */
  public void validate() {
    if (vaultName == null || vaultName.trim().isEmpty()) {
      throw new IllegalArgumentException("Vault name cannot be null or empty");
    }

    if (cacheTtl <= 0) {
      throw new IllegalArgumentException("Cache TTL must be positive: " + cacheTtl);
    }

    if (cacheMaxSize <= 0) {
      throw new IllegalArgumentException("Cache max size must be positive: " + cacheMaxSize);
    }

    logger.debug("Cache configuration validation passed");
  }
}
