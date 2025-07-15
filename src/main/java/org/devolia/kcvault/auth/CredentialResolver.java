package org.devolia.kcvault.auth;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves Azure credentials and creates SecretClient instances.
 *
 * <p>This class handles different authentication mechanisms for Azure Key Vault:
 *
 * <ol>
 *   <li><strong>Managed Identity</strong> - Preferred for Azure-hosted applications (AKS, App
 *       Service, etc.)
 *   <li><strong>Service Principal</strong> - For CI/CD and non-Azure environments using client
 *       credentials
 *   <li><strong>Default Azure Credential</strong> - Fallback that tries multiple auth methods
 * </ol>
 *
 * <p>Authentication precedence:
 *
 * <ul>
 *   <li>If <code>AZURE_CLIENT_ID</code>, <code>AZURE_CLIENT_SECRET</code>, and <code>
 *       AZURE_TENANT_ID</code> are all set → Service Principal authentication
 *   <li>If running in Azure with Managed Identity available → Managed Identity authentication
 *   <li>Otherwise → Default Azure Credential (development, CLI, etc.)
 * </ul>
 *
 * @author Devolia
 * @since 1.0.0
 */
public class CredentialResolver {

  private static final Logger logger = LoggerFactory.getLogger(CredentialResolver.class);

  // Environment variables for Service Principal authentication
  private static final String ENV_AZURE_TENANT_ID = "AZURE_TENANT_ID";
  private static final String ENV_AZURE_CLIENT_ID = "AZURE_CLIENT_ID";
  private static final String ENV_AZURE_CLIENT_SECRET = "AZURE_CLIENT_SECRET";

  // Timeout configuration for credential operations to prevent CI hangs
  private static final Duration CREDENTIAL_TIMEOUT = Duration.ofSeconds(30);

  /**
   * Creates a SecretClient for the specified Azure Key Vault.
   *
   * @param vaultName the name of the Azure Key Vault (not the full URL)
   * @return configured SecretClient instance
   * @throws IllegalArgumentException if vaultName is null or empty
   * @throws RuntimeException if credential resolution fails
   */
  public SecretClient createSecretClient(String vaultName) {
    if (vaultName == null || vaultName.trim().isEmpty()) {
      throw new IllegalArgumentException("Vault name cannot be null or empty");
    }

    String vaultUrl = buildVaultUrl(vaultName);
    TokenCredential credential = resolveCredential();

    SecretClient secretClient =
        new SecretClientBuilder().vaultUrl(vaultUrl).credential(credential).buildClient();

    logger.info("Created SecretClient for vault: {} (URL: {})", vaultName, vaultUrl);
    return secretClient;
  }

  /**
   * Resolves the appropriate Azure credential based on environment and configuration.
   *
   * @return the resolved TokenCredential
   * @throws RuntimeException if credential resolution fails
   */
  private TokenCredential resolveCredential() {
    // Check for Service Principal credentials
    String tenantId = System.getenv(ENV_AZURE_TENANT_ID);
    String clientId = System.getenv(ENV_AZURE_CLIENT_ID);
    String clientSecret = System.getenv(ENV_AZURE_CLIENT_SECRET);

    if (isServicePrincipalConfigured(tenantId, clientId, clientSecret)) {
      logger.info(
          "Using Service Principal authentication (tenant: {}, client: {})",
          tenantId,
          maskClientId(clientId));

      return new ClientSecretCredentialBuilder()
          .tenantId(tenantId)
          .clientId(clientId)
          .clientSecret(clientSecret)
          .build();
    }

    // Try Managed Identity if available
    if (isManagedIdentityAvailable()) {
      logger.info("Using Managed Identity authentication");

      return new ManagedIdentityCredentialBuilder()
          .maxRetry(1) // Reduce retries to fail faster in non-Azure environments
          .build();
    }

    // Fallback to Default Azure Credential
    logger.info("Using Default Azure Credential (development/CLI authentication)");
    logger.debug("This will try: environment variables, managed identity, Azure CLI, etc.");

    return new DefaultAzureCredentialBuilder().build();
  }

  /**
   * Checks if Service Principal credentials are fully configured.
   *
   * @param tenantId the tenant ID
   * @param clientId the client ID
   * @param clientSecret the client secret
   * @return true if all Service Principal credentials are present
   */
  private boolean isServicePrincipalConfigured(
      String tenantId, String clientId, String clientSecret) {
    return tenantId != null
        && !tenantId.trim().isEmpty()
        && clientId != null
        && !clientId.trim().isEmpty()
        && clientSecret != null
        && !clientSecret.trim().isEmpty();
  }

  /**
   * Checks if Managed Identity is likely available.
   *
   * <p>This is a heuristic check based on common environment variables set by Azure services that
   * support Managed Identity.
   *
   * @return true if Managed Identity is likely available
   */
  private boolean isManagedIdentityAvailable() {
    // Check for common Azure environment indicators
    String msiEndpoint = System.getenv("MSI_ENDPOINT");
    String identityEndpoint = System.getenv("IDENTITY_ENDPOINT");
    String azureClientId = System.getenv("AZURE_CLIENT_ID");

    // Azure App Service / Azure Functions
    if (msiEndpoint != null && !msiEndpoint.trim().isEmpty()) {
      logger.debug("Detected Azure App Service/Functions environment (MSI_ENDPOINT present)");
      return true;
    }

    // Azure Container Instances / AKS with Workload Identity
    if (identityEndpoint != null && !identityEndpoint.trim().isEmpty()) {
      logger.debug("Detected Azure Container environment (IDENTITY_ENDPOINT present)");
      return true;
    }

    // AKS with Azure AD Workload Identity
    if (azureClientId != null
        && !azureClientId.trim().isEmpty()
        && System.getenv(ENV_AZURE_CLIENT_SECRET) == null) {
      logger.debug("Detected AKS Workload Identity environment (AZURE_CLIENT_ID without secret)");
      return true;
    }

    logger.debug("No Managed Identity environment detected");
    return false;
  }

  /**
   * Checks if the application is running in a CI/CD environment.
   *
   * <p>This method detects common CI/CD environment variables to help optimize credential
   * resolution and prevent timeouts during automated builds.
   *
   * @return true if running in a CI/CD environment
   */
  private boolean isInCiEnvironment() {
    // Common CI environment variables
    String[] ciIndicators = {
      "CI",
      "CONTINUOUS_INTEGRATION", // Generic CI indicators
      "GITHUB_ACTIONS", // GitHub Actions
      "JENKINS_URL", // Jenkins
      "BUILDKITE", // Buildkite
      "CIRCLECI", // CircleCI
      "TRAVIS", // Travis CI
      "GITLAB_CI", // GitLab CI
      "AZURE_PIPELINES", // Azure DevOps
      "BUILD_BUILDID", // Azure DevOps alternative
      "TEAMCITY_VERSION" // TeamCity
    };

    for (String indicator : ciIndicators) {
      String value = System.getenv(indicator);
      if (value != null && !value.trim().isEmpty()) {
        logger.debug("Detected CI environment: {} = {}", indicator, value);
        return true;
      }
    }

    logger.debug("No CI environment detected");
    return false;
  }

  /**
   * Builds the full Azure Key Vault URL from the vault name.
   *
   * @param vaultName the vault name
   * @return the full vault URL
   */
  private String buildVaultUrl(String vaultName) {
    // Azure Key Vault URLs follow the pattern: https://{vault-name}.vault.azure.net/
    return String.format("https://%s.vault.azure.net/", vaultName);
  }

  /**
   * Masks a client ID for safe logging.
   *
   * @param clientId the client ID to mask
   * @return masked client ID
   */
  private String maskClientId(String clientId) {
    if (clientId == null || clientId.length() <= 8) {
      return "****";
    }
    return clientId.substring(0, 4) + "****" + clientId.substring(clientId.length() - 4);
  }
}
