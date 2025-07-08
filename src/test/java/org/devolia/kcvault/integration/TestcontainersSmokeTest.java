package org.devolia.kcvault.integration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Simple smoke test to verify Testcontainers setup is working.
 *
 * @author Devolia
 * @since 1.0.0
 */
@Testcontainers
class TestcontainersSmokeTest {

  private static final Logger logger = LoggerFactory.getLogger(TestcontainersSmokeTest.class);

  @Container
  static GenericContainer<?> azuriteContainer =
      new GenericContainer<>(
              DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:latest"))
          .withExposedPorts(10000, 10001, 10002)
          .withCommand("azurite", "--blobHost", "0.0.0.0");

  @Test
  void testAzuriteContainerStarts() {
    logger.info("Testing Azurite container startup...");

    assertTrue(azuriteContainer.isRunning(), "Azurite container should be running");

    String host = azuriteContainer.getHost();
    Integer port = azuriteContainer.getMappedPort(10000);

    assertNotNull(host, "Container host should not be null");
    assertNotNull(port, "Container port should not be null");
    assertTrue(port > 0, "Container port should be positive");

    logger.info("Azurite container started successfully at {}:{}", host, port);
  }
}
