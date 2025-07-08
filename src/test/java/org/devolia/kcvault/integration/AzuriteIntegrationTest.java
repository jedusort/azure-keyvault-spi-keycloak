package org.devolia.kcvault.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for Azure Storage simulation with Azurite.
 *
 * @author Devolia
 * @since 1.0.0
 */
@Testcontainers
class AzuriteIntegrationTest {

  private static final Logger logger = LoggerFactory.getLogger(AzuriteIntegrationTest.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static BlobServiceClient blobServiceClient;

  @Container
  static GenericContainer<?> azuriteContainer =
      new GenericContainer<>(
              DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:latest"))
          .withExposedPorts(10000, 10001, 10002)
          .withCommand(
              "azurite",
              "--blobHost",
              "0.0.0.0",
              "--queueHost",
              "0.0.0.0",
              "--tableHost",
              "0.0.0.0")
          .waitingFor(Wait.forLogMessage(".*Azurite Blob service is successfully listening.*", 1));

  @BeforeAll
  static void setupBlobClient() {
    logger.info("Setting up blob service client for Azurite...");

    String connectionString =
        String.format(
            "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://%s:%d/devstoreaccount1;",
            azuriteContainer.getHost(), azuriteContainer.getMappedPort(10000));

    blobServiceClient =
        new BlobServiceClientBuilder().connectionString(connectionString).buildClient();

    logger.info("Blob service client setup completed");
  }

  @Test
  void testAzuriteContainerIsRunning() {
    logger.info("Testing Azurite container status...");

    assertTrue(azuriteContainer.isRunning(), "Azurite container should be running");

    String host = azuriteContainer.getHost();
    Integer port = azuriteContainer.getMappedPort(10000);

    assertNotNull(host, "Container host should not be null");
    assertNotNull(port, "Container port should not be null");
    assertTrue(port > 0, "Container port should be positive");

    logger.info("Azurite container test passed at {}:{}", host, port);
  }

  @Test
  void testBlobServiceClientConnection() {
    logger.info("Testing blob service client connection...");

    assertNotNull(blobServiceClient, "Blob service client should not be null");

    // Test that we can list containers (should be empty initially)
    assertDoesNotThrow(
        () -> {
          long containerCount = blobServiceClient.listBlobContainers().stream().count();
          logger.info("Found {} blob containers", containerCount);
        },
        "Should be able to list blob containers");

    logger.info("Blob service client connection test passed");
  }

  @Test
  void testCreateContainerAndBlob() throws IOException {
    logger.info("Testing container and blob creation...");

    String containerName = "test-vault";
    String blobName = "test-secret";
    String blobContent = "test-secret-value";

    // Create container
    BlobContainerClient containerClient =
        blobServiceClient.createBlobContainerIfNotExists(containerName);
    assertNotNull(containerClient, "Container client should not be null");

    // Create blob
    BlobClient blobClient = containerClient.getBlobClient(blobName);
    byte[] blobBytes = blobContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    blobClient.upload(new java.io.ByteArrayInputStream(blobBytes), blobBytes.length, true);

    // Verify blob exists and has correct content
    assertTrue(blobClient.exists(), "Blob should exist");

    String downloadedContent = blobClient.downloadContent().toString();
    assertEquals(
        blobContent, downloadedContent, "Downloaded content should match uploaded content");

    logger.info("Container and blob creation test passed");
  }

  @Test
  void testSetupTestSecretsFromConfig() throws IOException {
    logger.info("Testing test secrets setup from configuration...");

    // Read test secrets from configuration file
    InputStream secretsStream = getClass().getResourceAsStream("/test-secrets.json");
    assertNotNull(secretsStream, "test-secrets.json should be available");

    JsonNode secretsConfig = objectMapper.readTree(secretsStream);
    JsonNode secrets = secretsConfig.get("secrets");
    assertNotNull(secrets, "Secrets array should be present");

    // Create a container for our test vault
    String containerName = "test-vault-config";
    BlobContainerClient containerClient =
        blobServiceClient.createBlobContainerIfNotExists(containerName);

    int secretsCreated = 0;
    // Create each secret as a blob
    for (JsonNode secret : secrets) {
      if (secret.get("enabled").asBoolean()) {
        String secretName = secret.get("name").asText();
        String secretValue = secret.get("value").asText();

        BlobClient blobClient = containerClient.getBlobClient(secretName);
        byte[] secretBytes = secretValue.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        blobClient.upload(new java.io.ByteArrayInputStream(secretBytes), secretBytes.length, true);

        // Verify the secret was created correctly
        assertTrue(blobClient.exists(), "Secret " + secretName + " should exist");
        String downloadedValue = blobClient.downloadContent().toString();
        assertEquals(secretValue, downloadedValue, "Secret value should match for " + secretName);

        secretsCreated++;
        logger.debug("Created and verified test secret: {}", secretName);
      }
    }

    assertTrue(secretsCreated > 0, "At least one secret should be created");
    logger.info("Test secrets setup completed. Created {} secrets", secretsCreated);
  }

  @Test
  void testMultipleContainers() {
    logger.info("Testing multiple container operations...");

    // Create multiple containers
    String[] containerNames = {"vault1", "vault2", "vault3"};

    for (String containerName : containerNames) {
      BlobContainerClient containerClient =
          blobServiceClient.createBlobContainerIfNotExists(containerName);
      assertNotNull(containerClient, "Container client should not be null for " + containerName);

      // Add a test blob to each container
      BlobClient blobClient = containerClient.getBlobClient("test-secret");
      String secretValue = "secret-for-" + containerName;
      byte[] secretBytes = secretValue.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      blobClient.upload(new java.io.ByteArrayInputStream(secretBytes), secretBytes.length, true);

      // Verify the blob
      assertTrue(blobClient.exists(), "Blob should exist in " + containerName);
      assertEquals(
          secretValue,
          blobClient.downloadContent().toString(),
          "Content should match for " + containerName);
    }

    // Verify all containers exist
    long containerCount =
        blobServiceClient.listBlobContainers().stream()
            .filter(
                container -> java.util.Arrays.asList(containerNames).contains(container.getName()))
            .count();

    assertEquals(containerNames.length, containerCount, "All test containers should exist");

    logger.info("Multiple container operations test passed");
  }
}
