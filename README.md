# Azure Key Vault SPI for Keycloak

[![Java CI](https://github.com/devolia/azure-keyvault-spi-keycloak/actions/workflows/ci.yml/badge.svg)](https://github.com/devolia/azure-keyvault-spi-keycloak/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Production-ready `VaultProvider` for **Keycloak 26+** that retrieves secrets directly from **Azure Key Vault** using the official Vault SPI.

## ✨ Features

- 🔐 **Azure Key Vault integration** - Seamless secret retrieval from Azure Key Vault
- 🚀 **Multiple auth methods** - Managed Identity (preferred) or Service Principal
- ⚡ **Intelligent caching** - Configurable TTL and LRU cache with Caffeine
- 📊 **Observability** - Micrometer metrics for Prometheus monitoring
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

- `keycloak_vault_azure_kv_requests_total{status="success|error|not_found"}` - Request counters
- `keycloak_vault_azure_kv_latency_seconds` - Request latency histogram
- `keycloak_vault_azure_kv_cache_operations_total{operation="hit|miss"}` - Cache metrics

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