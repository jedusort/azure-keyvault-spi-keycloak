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

    // Build the SPI JAR first
    buildSpiJar();

    // Setup Azurite container (Azure Storage emulator)
    setupAzuriteContainer();

    // Setup Keycloak container
    setupKeycloakContainer();

    logger.info("All containers started successfully");
  }

  /**
   * Resolves the project version from Maven properties or POM file.
   *
   * <p>This method attempts to read the version from Maven project properties first, then from the
   * POM file if needed, and falls back to a default version only if all else fails.
   *
   * @return the resolved project version
   */
  private static String resolveProjectVersion() {
    // First try system property (set by Maven during build)
    String version = System.getProperty("project.version");
    if (version != null && !version.startsWith("${")) {
      return version;
    }

    // Try to read from POM file
    try {
      String projectRoot = System.getProperty("user.dir");
      java.nio.file.Path pomPath = java.nio.file.Paths.get(projectRoot, "pom.xml");

      if (java.nio.file.Files.exists(pomPath)) {
        String pomContent = java.nio.file.Files.readString(pomPath);
        java.util.regex.Pattern versionPattern =
            java.util.regex.Pattern.compile("<version>([^<]+)</version>");
        java.util.regex.Matcher matcher = versionPattern.matcher(pomContent);

        if (matcher.find()) {
          String pomVersion = matcher.group(1);
          if (!pomVersion.startsWith("${")) {
            return pomVersion;
          }
        }
      }
    } catch (Exception e) {
      logger.warn("Failed to read version from POM file", e);
    }

    // Final fallback - should be avoided in production
    logger.warn("Using fallback version - consider setting project.version system property");
    return "1.0.0-beta.1";
  }

  /** Builds the SPI JAR before starting containers. */
  private static void buildSpiJar() {
    logger.info("Building SPI JAR...");

    try {
      // Get project root directory
      String projectRoot = System.getProperty("user.dir");
      String version = resolveProjectVersion();

      java.nio.file.Path jarPath =
          java.nio.file.Paths.get(
              projectRoot, "target", "azure-keyvault-spi-keycloak-" + version + ".jar");

      // Check if JAR already exists and is recent
      if (java.nio.file.Files.exists(jarPath)) {
        logger.info("SPI JAR already exists: {}", jarPath);
        return;
      }

      // Build the JAR using Maven
      ProcessBuilder pb = new ProcessBuilder("mvn", "clean", "package", "-DskipTests", "-q");
      pb.directory(new java.io.File(projectRoot));
      pb.inheritIO();

      Process process = pb.start();
      int exitCode = process.waitFor();

      if (exitCode != 0) {
        throw new RuntimeException("Failed to build SPI JAR, exit code: " + exitCode);
      }

      if (!java.nio.file.Files.exists(jarPath)) {
        throw new RuntimeException("SPI JAR not found after build: " + jarPath);
      }

      logger.info("SPI JAR built successfully: {}", jarPath);
    } catch (Exception e) {
      throw new RuntimeException("Failed to build SPI JAR", e);
    }
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

    // Get the path to the built JAR
    String projectRoot = System.getProperty("user.dir");
    String version = resolveProjectVersion();
    String jarPath = projectRoot + "/target/azure-keyvault-spi-keycloak-" + version + ".jar";

    keycloakContainer =
        new GenericContainer<>(DockerImageName.parse(KEYCLOAK_IMAGE))
            .withNetwork(network)
            .withNetworkAliases(KEYCLOAK_NETWORK_ALIAS)
            .withExposedPorts(8080, 9000) // Expose both main and management ports
            .withEnv(getKeycloakEnvironment())
            .withFileSystemBind(jarPath, "/opt/keycloak/providers/azure-keyvault-spi-keycloak.jar")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/bash"))
            .withCommand(
                "-c", "/opt/keycloak/bin/kc.sh build && " + "/opt/keycloak/bin/kc.sh start-dev")
            .waitingFor(
                Wait.forHttp("/health/ready")
                    .forPort(9000) // Health endpoints are on management port 9000
                    .withStartupTimeout(Duration.ofMinutes(10))) // Increased timeout for build
            .withStartupTimeout(Duration.ofMinutes(10))
            .withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("KEYCLOAK"));

    keycloakContainer.start();
    logger.info(
        "Keycloak container started at {}:{} (main) and {}:{} (management)",
        keycloakContainer.getHost(),
        keycloakContainer.getMappedPort(8080),
        keycloakContainer.getHost(),
        keycloakContainer.getMappedPort(9000));
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

  /** Gets the Keycloak management base URL for health and metrics. */
  protected String getKeycloakManagementUrl() {
    return String.format(
        "http://%s:%d", keycloakContainer.getHost(), keycloakContainer.getMappedPort(9000));
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
