package org.devolia.kcvault.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests with Testcontainers.
 *
 * <p>This class provides:
 *
 * <ul>
 *   <li>Container lifecycle management for Keycloak and Azurite
 *   <li>Network configuration between containers
 *   <li>Test environment setup and cleanup
 *   <li>Common utility methods for integration testing
 * </ul>
 *
 * @author Devolia
 * @since 1.0.0
 */
@Testcontainers
public abstract class BaseIntegrationTest {

  private static final Logger logger = LoggerFactory.getLogger(BaseIntegrationTest.class);

  // Container configuration constants
  protected static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.0.0";
  protected static final String AZURITE_IMAGE = "mcr.microsoft.com/azure-storage/azurite:latest";
  protected static final String AZURITE_NETWORK_ALIAS = "azurite";
  protected static final String KEYCLOAK_NETWORK_ALIAS = "keycloak";

  // Test configuration
  protected static final String TEST_VAULT_NAME = "test-vault";
  protected static final String KEYCLOAK_ADMIN_USER = "admin";
  protected static final String KEYCLOAK_ADMIN_PASSWORD = "admin";

  // Shared network for containers
  protected static Network network;

  // Container instances
  protected static GenericContainer<?> azuriteContainer;
  protected static GenericContainer<?> keycloakContainer;

  @BeforeAll
  static void setupContainers() {
    logger.info("Setting up integration test containers...");

    // Create shared network
    network = Network.newNetwork();

    // Setup Azurite container (Azure Storage emulator)
    setupAzuriteContainer();

    // Setup Keycloak container
    setupKeycloakContainer();

    logger.info("All containers started successfully");
  }

  /** Sets up the Azurite container for Azure Storage emulation. */
  private static void setupAzuriteContainer() {
    logger.info("Starting Azurite container...");

    azuriteContainer =
        new GenericContainer<>(DockerImageName.parse(AZURITE_IMAGE))
            .withNetwork(network)
            .withNetworkAliases(AZURITE_NETWORK_ALIAS)
            .withExposedPorts(10000, 10001, 10002) // Blob, Queue, Table services
            .withCommand(
                "azurite",
                "--blobHost",
                "0.0.0.0",
                "--queueHost",
                "0.0.0.0",
                "--tableHost",
                "0.0.0.0")
            .waitingFor(Wait.forLogMessage(".*Azurite Blob service is successfully listening.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2))
            .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("AZURITE"));

    azuriteContainer.start();
    logger.info(
        "Azurite container started at {}:{}",
        azuriteContainer.getHost(),
        azuriteContainer.getMappedPort(10000));
  }

  /** Sets up the Keycloak container with Azure Key Vault SPI configuration. */
  private static void setupKeycloakContainer() {
    logger.info("Starting Keycloak container...");

    keycloakContainer =
        new GenericContainer<>(DockerImageName.parse(KEYCLOAK_IMAGE))
            .withNetwork(network)
            .withNetworkAliases(KEYCLOAK_NETWORK_ALIAS)
            .withExposedPorts(8080)
            .withEnv(getKeycloakEnvironment())
            .withCommand("start-dev")
            .waitingFor(
                Wait.forHttp("/health/ready")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(5)))
            .withStartupTimeout(Duration.ofMinutes(5))
            .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("KEYCLOAK"));

    keycloakContainer.start();
    logger.info(
        "Keycloak container started at {}:{}",
        keycloakContainer.getHost(),
        keycloakContainer.getMappedPort(8080));
  }

  /** Gets the environment variables for Keycloak container configuration. */
  private static Map<String, String> getKeycloakEnvironment() {
    String azuriteHost = AZURITE_NETWORK_ALIAS + ":10000";

    // Using a HashMap builder approach since we have many entries
    Map<String, String> env = new java.util.HashMap<>();

    // Admin user
    env.put("KEYCLOAK_ADMIN", KEYCLOAK_ADMIN_USER);
    env.put("KEYCLOAK_ADMIN_PASSWORD", KEYCLOAK_ADMIN_PASSWORD);

    // Database
    env.put("KC_DB", "dev-mem");

    // Health and metrics
    env.put("KC_HEALTH_ENABLED", "true");
    env.put("KC_METRICS_ENABLED", "true");

    // Azure Key Vault SPI configuration
    env.put("KC_SPI_VAULT_AZURE_KV_ENABLED", "true");
    env.put("KC_SPI_VAULT_AZURE_KV_NAME", TEST_VAULT_NAME);
    env.put("KC_SPI_VAULT_AZURE_KV_CACHE_TTL", "30");
    env.put("KC_SPI_VAULT_AZURE_KV_CACHE_MAX", "100");

    // Azure Storage connection (pointing to Azurite)
    env.put(
        "AZURE_STORAGE_CONNECTION_STRING",
        "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://"
            + azuriteHost
            + "/devstoreaccount1;");

    // HTTP configuration
    env.put("KC_HTTP_ENABLED", "true");
    env.put("KC_HOSTNAME_STRICT", "false");
    env.put("KC_HOSTNAME_STRICT_HTTPS", "false");

    return env;
  }

  /** Gets the Keycloak admin base URL for testing. */
  protected String getKeycloakAdminUrl() {
    return String.format(
        "http://%s:%d", keycloakContainer.getHost(), keycloakContainer.getMappedPort(8080));
  }

  /** Gets the Azurite blob service URL for testing. */
  protected static String getAzuriteUrl() {
    return String.format(
        "http://%s:%d/devstoreaccount1",
        azuriteContainer.getHost(), azuriteContainer.getMappedPort(10000));
  }

  /** Waits for a condition with timeout. */
  protected void waitForCondition(
      String description, java.util.function.BooleanSupplier condition, Duration timeout) {
    logger.info("Waiting for condition: {}", description);

    long startTime = System.currentTimeMillis();
    long timeoutMs = timeout.toMillis();

    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() - startTime > timeoutMs) {
        fail("Timeout waiting for condition: " + description);
      }

      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while waiting for condition: " + description, e);
      }
    }

    logger.info("Condition satisfied: {}", description);
  }
}
