package org.devolia.kcvault.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DefaultVaultRawSecret.
 *
 * @author Devolia
 * @since 1.0.0
 */
class DefaultVaultRawSecretTest {

  @Test
  void testCreateWithValidSecret() {
    String secretValue = "test-secret-value";
    DefaultVaultRawSecret secret = new DefaultVaultRawSecret(secretValue);

    // Test get()
    Optional<ByteBuffer> byteBuffer = secret.get();
    assertTrue(byteBuffer.isPresent());
    assertEquals(secretValue, StandardCharsets.UTF_8.decode(byteBuffer.get()).toString());

    // Test getAsArray()
    Optional<byte[]> byteArray = secret.getAsArray();
    assertTrue(byteArray.isPresent());
    assertEquals(secretValue, new String(byteArray.get(), StandardCharsets.UTF_8));

    secret.close();
  }

  @Test
  void testCreateWithNullSecret() {
    DefaultVaultRawSecret secret = new DefaultVaultRawSecret(null);

    // Test get()
    Optional<ByteBuffer> byteBuffer = secret.get();
    assertFalse(byteBuffer.isPresent());

    // Test getAsArray()
    Optional<byte[]> byteArray = secret.getAsArray();
    assertFalse(byteArray.isPresent());

    secret.close();
  }

  @Test
  void testCreateWithEmptySecret() {
    DefaultVaultRawSecret secret = new DefaultVaultRawSecret("");

    // Test get()
    Optional<ByteBuffer> byteBuffer = secret.get();
    assertFalse(byteBuffer.isPresent());

    // Test getAsArray()
    Optional<byte[]> byteArray = secret.getAsArray();
    assertFalse(byteArray.isPresent());

    secret.close();
  }

  @Test
  void testSecretIsCopiedOnRetrieval() {
    String secretValue = "test-secret";
    DefaultVaultRawSecret secret = new DefaultVaultRawSecret(secretValue);

    // Get byte arrays
    byte[] array1 = secret.getAsArray().get();
    byte[] array2 = secret.getAsArray().get();

    // Verify they are equal but not the same instance
    assertArrayEquals(array1, array2);
    assertNotSame(array1, array2);

    // Modify one array
    array1[0] = 'X';

    // Verify the other array is not affected
    assertNotEquals(array1[0], array2[0]);

    secret.close();
  }

  @Test
  void testClose() {
    DefaultVaultRawSecret secret = new DefaultVaultRawSecret("test-secret");

    // Verify secret is accessible before close
    assertTrue(secret.getAsArray().isPresent());

    // Close the secret
    assertDoesNotThrow(() -> secret.close());

    // The secret should still be accessible after close in this implementation
    // (The internal array is zeroed but the methods still work)
    assertTrue(secret.getAsArray().isPresent());
  }

  @Test
  void testUnicodeSecret() {
    String unicodeSecret = "こんにちは世界"; // "Hello World" in Japanese
    DefaultVaultRawSecret secret = new DefaultVaultRawSecret(unicodeSecret);

    // Test UTF-8 encoding/decoding
    byte[] bytes = secret.getAsArray().get();
    String decoded = new String(bytes, StandardCharsets.UTF_8);
    assertEquals(unicodeSecret, decoded);

    secret.close();
  }
}
