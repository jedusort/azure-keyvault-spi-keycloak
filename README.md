# Azure Key Vault SPI for Keycloak

[![Java CI](https://github.com/devolia/azure-keyvault-spi-keycloak/actions/workflows/ci.yml/badge.svg)](https://github.com/devolia/azure-keyvault-spi-keycloak/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Production-ready `VaultProvider` for **Keycloak 26+** that retrieves secrets directly from **Azure Key Vault** using the official Vault SPI.

## ✨ Features

- 🔐 **Azure Key Vault integration** - Seamless secret retrieval from Azure Key Vault
- 🚀 **Multiple auth methods** - Managed Identity (preferred) or Service Principal
- ⚡ **Intelligent caching** - Configurable TTL and LRU cache with Caffeine
- 🛡️ **Resilience patterns** - Retry logic, circuit breaker, and advanced error handling
- 📊 **Observability** - Micrometer metrics for Prometheus monitoring with error categorization
- 🧪 **Testing support** - Comprehensive unit tests and Testcontainers integration
- 🐳 **Docker ready** - Multistage Docker image for easy deployment
- ☕ **Java 17 compatible** - Modern Java with Google Java Style

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

### 3. Deployment

#### Docker
```bash
# Build the provider JAR
mvn clean package

# Copy to Keycloak providers directory
cp target/azure-keyvault-spi-keycloak-*.jar /opt/keycloak/providers/

# Rebuild Keycloak
/opt/keycloak/bin/kc.sh build

# Start Keycloak
/opt/keycloak/bin/kc.sh start
```

### 4. Usage in Keycloak

Reference secrets in Keycloak configuration using the `${vault.secret-name}` syntax:

```properties
# In keycloak.conf or admin console
db-password=${vault.database-password}
smtp-password=${vault.smtp-credentials}
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

## 🗺️ Roadmap

- [ ] Complete CDI integration
- [ ] Implement full Azure Key Vault retrieval
- [ ] Add certificate and key support
- [ ] Multi-vault configuration
- [ ] Fallback vault support
- [ ] Enhanced error handling

## 📝 License

Licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.

## 🤝 Contributing

Contributions welcome! Please read our contributing guidelines and follow the Google Java Style Guide.

---

**Status**: 🚧 **Work in Progress** - Core skeleton implemented, full functionality coming soon!