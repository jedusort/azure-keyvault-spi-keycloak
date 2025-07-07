package org.devolia.kcvault.cache;

import java.time.OffsetDateTime;

/**
 * Wrapper class for cached secrets that includes metadata for expiration handling.
 *
 * <p>This class stores the secret value along with expiration metadata to enable smart cache
 * invalidation based on secret expiration times rather than just fixed TTL.
 *
 * @author Devolia
 * @since 1.0.0
 */
public class CachedSecret {

  private final String value;
  private final OffsetDateTime expiresOn;
  private final OffsetDateTime cachedAt;

  /**
   * Constructor for a cached secret with expiration metadata.
   *
   * @param value the secret value
   * @param expiresOn when the secret expires (null if no expiration)
   */
  public CachedSecret(String value, OffsetDateTime expiresOn) {
    this.value = value;
    this.expiresOn = expiresOn;
    this.cachedAt = OffsetDateTime.now();
  }

  /**
   * Gets the secret value.
   *
   * @return the secret value
   */
  public String getValue() {
    return value;
  }

  /**
   * Gets the secret expiration time.
   *
   * @return the expiration time, or null if no expiration
   */
  public OffsetDateTime getExpiresOn() {
    return expiresOn;
  }

  /**
   * Gets when this secret was cached.
   *
   * @return the cache timestamp
   */
  public OffsetDateTime getCachedAt() {
    return cachedAt;
  }

  /**
   * Checks if the cached secret is expired.
   *
   * @return true if the secret is expired
   */
  public boolean isExpired() {
    return expiresOn != null && OffsetDateTime.now().isAfter(expiresOn);
  }

  /**
   * Checks if the cached secret is still valid (not expired).
   *
   * @return true if the secret is still valid
   */
  public boolean isValid() {
    return !isExpired();
  }
}
