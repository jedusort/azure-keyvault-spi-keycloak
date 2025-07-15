# Azure Key Vault SPI for Keycloak

[![Java CI](https://github.com/jedusort/azure-keyvault-spi-keycloak/actions/workflows/ci.yml/badge.svg)](https://github.com/jedusort/azure-keyvault-spi-keycloak/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Coverage](https://img.shields.io/badge/Coverage-90%25-brightgreen.svg)](https://github.com/jedusort/azure-keyvault-spi-keycloak)
[![Version](https://img.shields.io/badge/Version-1.1.0-blue.svg)](https://github.com/jedusort/azure-keyvault-spi-keycloak/releases)

Production-ready `VaultProvider` for **Keycloak 26+** that retrieves secrets directly from **Azure Key Vault** using the official Vault SPI.

## ✨ Features

- 🔐 **Azure Key Vault integration** - Complete secret retrieval from Azure Key Vault with proper metadata handling
- 🚀 **Multiple auth methods** - Managed Identity (preferred) or Service Principal with auto-detection
- ⚡ **Intelligent caching** - Configurable TTL and LRU cache with Caffeine, respecting secret expiration
- 🛡️ **Resilience patterns** - Circuit breaker, exponential backoff retry, and timeout management
- 📊 **Observability** - Comprehensive Micrometer metrics with error categorization and circuit breaker state
- 🧪 **Testing support** - >90% unit test coverage and full Testcontainers integration with Keycloak 26+
- 🐳 **Docker ready** - Multistage Docker image with optimized build process
- ☕ **Java 17 compatible** - Modern Java with Google Java Style formatting
- 🔄 **VaultRawSecret support** - Full metadata extraction and expiration handling
- 🚨 **Production-ready error handling** - Comprehensive exception mapping and graceful degradation

## 🚀 Quick Start

### 1. Configuration

Add to your `keycloak.conf`:

```properties
# Enable the Azure Key Vault provider
spi-vault-azure-kv-enabled=true

# Required: Your Azure Key Vault name
spi-vault-azure-kv-name=my-vault

# Optional: Cache settings
spi-vault-azure-kv-cache-ttl=60      # Cache TTL in seconds (default: 60)
spi-vault-azure-kv-cache-max=1000    # Max cache entries (default: 1000)

# Optional: Resilience patterns (NEW in v1.1.0)
spi-vault-azure-kv-retry-max-attempts=3                    # Max retry attempts (default: 3)
spi-vault-azure-kv-retry-base-delay=1000                  # Base delay in ms (default: 1000)
spi-vault-azure-kv-circuit-breaker-enabled=true           # Enable circuit breaker (default: true)
spi-vault-azure-kv-circuit-breaker-failure-threshold=5    # Failure threshold (default: 5)
spi-vault-azure-kv-circuit-breaker-recovery-timeout=30000 # Recovery timeout in ms (default: 30000)
spi-vault-azure-kv-connection-timeout=5000                # Connection timeout in ms (default: 5000)
spi-vault-azure-kv-read-timeout=10000                     # Read timeout in ms (default: 10000)
```

### 2. Authentication

#### Option A: Managed Identity (Recommended for Azure)
No additional configuration needed. Works automatically in:
- Azure Kubernetes Service (AKS)
- Azure App Service
- Azure Container Instances
- Azure Virtual Machines

#### Option B: Service Principal
Set environment variables:
```bash
export AZURE_TENANT_ID="your-tenant-id"
export AZURE_CLIENT_ID="your-client-id"
export AZURE_CLIENT_SECRET="your-client-secret"
```

### 3. Installation

#### Option A: Maven Dependency (Recommended)
Add to your `pom.xml`:
```xml
<dependency>
    <groupId>org.devolia</groupId>
    <artifactId>azure-keyvault-spi-keycloak</artifactId>
    <version>1.0.0</version>
</dependency>
```

#### Option B: Direct Download
```bash
# Download the latest release
wget https://github.com/jedusort/azure-keyvault-spi-keycloak/releases/latest/download/azure-keyvault-spi-keycloak-1.0.0.jar

# Download checksum for verification
wget https://github.com/jedusort/azure-keyvault-spi-keycloak/releases/latest/download/azure-keyvault-spi-keycloak-1.0.0.jar.sha256

# Verify checksum
sha256sum -c azure-keyvault-spi-keycloak-1.0.0.jar.sha256
```

#### Option C: GitHub Packages
If you have access to GitHub Packages, add the repository to your `pom.xml`:
```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/jedusort/azure-keyvault-spi-keycloak</url>
    </repository>
</repositories>
```

### 4. Deployment

#### Docker
```bash
# Using pre-built JAR
wget https://github.com/jedusort/azure-keyvault-spi-keycloak/releases/latest/download/azure-keyvault-spi-keycloak-1.0.0.jar

# Copy to Keycloak providers directory
cp azure-keyvault-spi-keycloak-*.jar /opt/keycloak/providers/

# Rebuild Keycloak
/opt/keycloak/bin/kc.sh build

# Start Keycloak
/opt/keycloak/bin/kc.sh start
```

#### Dockerfile
```dockerfile
FROM quay.io/keycloak/keycloak:26.0

# Download and install the provider
RUN curl -L https://github.com/jedusort/azure-keyvault-spi-keycloak/releases/latest/download/azure-keyvault-spi-keycloak-1.0.0.jar \
    -o /opt/keycloak/providers/azure-keyvault-spi-keycloak.jar

# Build Keycloak with the provider
RUN /opt/keycloak/bin/kc.sh build

# Configure your vault settings
COPY keycloak.conf /opt/keycloak/conf/
```

### 5. Usage in Keycloak

Reference secrets in Keycloak configuration using the `${vault.secret-name}` syntax:

```properties
# In keycloak.conf or admin console
db-password=${vault.database-password}
smtp-password=${vault.smtp-credentials}
ldap-bind-credential=${vault.ldap-password}
```

### 6. Advanced Usage

#### Secret Name Mapping
The provider automatically sanitizes secret names for Azure Key Vault compatibility:
- `my.secret` → `my-secret`
- `my_secret` → `my-secret`  
- `my::secret` → `my-secret`
- `My-Secret` → `my-secret`

#### Cache Management
```bash
# Clear cache via JMX or management endpoint
curl -X POST http://keycloak:8080/management/vault/cache/clear

# Invalidate specific secret
curl -X POST http://keycloak:8080/management/vault/cache/invalidate?secret=my-secret
```

#### Health Check
```bash
# Check vault connectivity
curl http://keycloak:8080/health/vault
```

## 📊 Monitoring

The provider exposes Micrometer metrics:

### Core Metrics
- `keycloak_vault_azure_kv_requests_total{status="success|error|not_found"}` - Request counters
- `keycloak_vault_azure_kv_latency_seconds` - Request latency histogram
- `keycloak_vault_azure_kv_cache_operations_total{operation="hit|miss"}` - Cache metrics

### Enhanced Error Metrics (NEW in v1.1.0)
- `keycloak_vault_azure_kv_requests_total{status="error", error_category="..."}` - Categorized errors
- `keycloak_vault_azure_kv_retry_attempts_total{attempt="1|2|3", error_category="..."}` - Retry tracking
- `keycloak_vault_azure_kv_circuit_breaker_state{state="closed|open|half_open"}` - Circuit breaker state

### Error Categories
- `timeout` - Network timeouts and connection issues
- `rate_limited` - HTTP 429 responses from Azure
- `server_error` - HTTP 5xx responses from Azure
- `authentication` - Authentication and authorization failures
- `not_found` - Secret not found (404)
- `network` - DNS and network connectivity issues

## 🏗️ Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed design documentation.

## 🧪 Development

### Prerequisites
- Java 17+
- Maven 3.8+
- Docker (for integration tests)

### Build
```bash
mvn clean verify
```

### Run Tests
```bash
# Unit tests only
mvn test

# Integration tests with Testcontainers
mvn verify -Pit
```

### Code Formatting
```bash
mvn fmt:format
```

### Code Quality
```bash
# Run static analysis
mvn spotbugs:check pmd:check

# Run security checks
mvn dependency-check:check
```

## 🚀 CI/CD Pipeline

This project uses GitHub Actions for automated CI/CD:

### Automated Testing
- **Multi-platform testing**: Ubuntu, Windows, macOS
- **Multi-version support**: Java 17, 21
- **Comprehensive coverage**: Unit tests + Integration tests with Testcontainers
- **Code quality**: SpotBugs, PMD, Google Java Style formatting checks
- **Security scanning**: CodeQL analysis and OWASP dependency checking

### Automated Publishing
- **GitHub Packages**: Automatic publication on push to main branch
- **Release artifacts**: JAR files with sources, javadoc, and SHA256 checksums
- **Version management**: Tag-based releases with automatic changelog generation

### CI/CD Workflows
- **`ci.yml`**: Runs on every push/PR - testing, quality checks, security scanning
- **`publish.yml`**: Publishes to GitHub Packages on main branch and tagged releases
- **`release.yml`**: Creates GitHub releases with artifacts and release notes

### Build Status
[![CI/CD Pipeline](https://github.com/jedusort/azure-keyvault-spi-keycloak/actions/workflows/ci.yml/badge.svg)](https://github.com/jedusort/azure-keyvault-spi-keycloak/actions/workflows/ci.yml)
[![Publish](https://github.com/jedusort/azure-keyvault-spi-keycloak/actions/workflows/publish.yml/badge.svg)](https://github.com/jedusort/azure-keyvault-spi-keycloak/actions/workflows/publish.yml)
[![Release](https://github.com/jedusort/azure-keyvault-spi-keycloak/actions/workflows/release.yml/badge.svg)](https://github.com/jedusort/azure-keyvault-spi-keycloak/actions/workflows/release.yml)

### Optimizing Security Scanning

To speed up OWASP dependency checking (reduce from 30+ minutes to ~5 minutes), add an NVD API key:

1. Request a free API key at https://nvd.nist.gov/developers/request-an-api-key
2. Go to repository **Settings** > **Secrets and variables** > **Actions**
3. Add a new secret named `NVD_API_KEY` with your API key

The pipeline will automatically use the API key if available for faster vulnerability scanning.

## 🗺️ Future Roadmap

- [ ] Certificate and key support (beyond secrets)
- [ ] Multi-vault configuration
- [ ] Fallback vault support
- [ ] Azure Key Vault RBAC integration
- [ ] Secret versioning support
- [ ] Bulk secret operations

## 📝 License

Licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.

## 🤝 Contributing

Contributions welcome! Please read our contributing guidelines and follow the Google Java Style Guide.

## 🏆 Changelog

### v1.1.0 (July 2025)
- ✅ **Complete Azure Key Vault integration** - Full secret retrieval implementation
- ✅ **Advanced error handling** - Circuit breaker, retry logic, and comprehensive exception mapping
- ✅ **VaultRawSecret support** - Proper secret metadata and expiration handling
- ✅ **Enhanced metrics** - Categorized error tracking and circuit breaker state monitoring
- ✅ **Comprehensive testing** - Unit tests with >90% coverage and Testcontainers integration
- ✅ **Production resilience** - Timeout management, jitter, and graceful degradation
- ✅ **CI/CD Pipeline** - Automated testing, quality checks, and GitHub Packages publication
- ✅ **Multi-platform support** - Testing on Ubuntu, Windows, and macOS
- ✅ **Security scanning** - CodeQL analysis and OWASP dependency checking
- ✅ **Automated releases** - Tag-based releases with artifacts and checksums

### v1.0.0 (July 2025)
- ✅ **Initial release** - Core SPI implementation with caching and basic metrics

---

**Status**: ✅ **Production Ready** - Full functionality implemented and tested!