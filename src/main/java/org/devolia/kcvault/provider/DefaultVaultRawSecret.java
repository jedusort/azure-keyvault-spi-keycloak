package org.devolia.kcvault.provider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.keycloak.vault.VaultRawSecret;

/**
 * Default implementation of VaultRawSecret that wraps a string secret value.
 *
 * <p>This class provides a simple implementation of the VaultRawSecret interface for storing
 * secrets retrieved from Azure Key Vault. It converts the string secret value to bytes using UTF-8
 * encoding.
 *
 * @author Devolia
 * @since 1.0.0
 */
public class DefaultVaultRawSecret implements VaultRawSecret {

  private final byte[] secretBytes;

  /**
   * Constructor that creates a VaultRawSecret from a string value.
   *
   * @param secretValue the secret value as a string
   */
  public DefaultVaultRawSecret(String secretValue) {
    if (secretValue == null) {
      this.secretBytes = new byte[0];
    } else {
      this.secretBytes = secretValue.getBytes(StandardCharsets.UTF_8);
    }
  }

  /**
   * Gets the secret value as a ByteBuffer.
   *
   * @return Optional containing the secret as a ByteBuffer, or empty if secret is null
   */
  @Override
  public Optional<ByteBuffer> get() {
    if (secretBytes.length == 0) {
      return Optional.empty();
    }
    return Optional.of(ByteBuffer.wrap(secretBytes.clone()));
  }

  /**
   * Gets the secret value as a byte array.
   *
   * @return Optional containing the secret as a byte array, or empty if secret is null
   */
  @Override
  public Optional<byte[]> getAsArray() {
    if (secretBytes.length == 0) {
      return Optional.empty();
    }
    return Optional.of(secretBytes.clone());
  }

  /**
   * Closes the secret and clears sensitive data. This implementation zeros out the internal byte
   * array.
   */
  @Override
  public void close() {
    // Clear the secret bytes for security
    if (secretBytes != null) {
      java.util.Arrays.fill(secretBytes, (byte) 0);
    }
  }
}
