# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial CHANGELOG.md documentation

## [1.0.0-beta.1] - 2025-07-17

### Added
- **Azure Key Vault Integration**: Complete SPI implementation for Keycloak 26+
- **Multi-Authentication Support**: 
  - Managed Identity authentication for Azure-hosted environments
  - Service Principal authentication with client credentials
  - Default Azure Credential fallback for development
- **Intelligent Caching**: Configurable TTL cache with Caffeine
  - Default 60 seconds TTL
  - Default 1000 entries maximum
  - Cache invalidation support
- **Comprehensive Metrics**: Micrometer integration with Prometheus support
  - Request counters with success/error status
  - Latency histograms for performance monitoring
  - Operation-specific metrics tracking
- **Resilience Patterns**: Configurable resilience with introspection support
- **Multi-Platform Support**: Tested on Linux, Windows, and macOS
- **CI/CD Pipeline**: Complete GitHub Actions workflow
  - Multi-platform testing (Ubuntu, Windows, macOS)
  - Java 17/21 compatibility
  - Code quality analysis (SpotBugs, PMD, formatting)
  - Security analysis (CodeQL, dependency vulnerability scanning)
  - Automated publishing to GitHub Packages

### Technical Details
- **Keycloak Compatibility**: Keycloak 26.0.0+
- **Java Requirements**: Java 17+ (tested with Java 17 and 21)
- **Azure SDK Version**: 
  - `azure-security-keyvault-secrets`: 4.10.0
  - `azure-identity`: 1.16.2
- **Build System**: Maven 3.8+
- **Testing**: Comprehensive unit tests with 72 test cases
- **Documentation**: Complete setup and configuration guide

### Configuration
- `spi-vault-azure-kv-name`: Azure Key Vault name (required)
- `spi-vault-azure-kv-cache-ttl`: Cache TTL in seconds (default: 60)
- `spi-vault-azure-kv-cache-max`: Maximum cache entries (default: 1000)

### Security
- Dependency vulnerability scanning with OWASP
- CodeQL security analysis
- Secure credential handling with Azure SDK best practices
- No hardcoded secrets or credentials

### Performance
- Intelligent caching reduces Azure API calls
- Optimized for high-throughput environments
- Memory-efficient with bounded cache
- Metrics for performance monitoring

---

## Release Notes Format

### Types of Changes
- **Added** for new features
- **Changed** for changes in existing functionality
- **Deprecated** for soon-to-be removed features
- **Removed** for now removed features
- **Fixed** for any bug fixes
- **Security** for vulnerability fixes

### Version Numbering
This project follows [Semantic Versioning](https://semver.org/):
- **MAJOR** version for incompatible API changes
- **MINOR** version for backwards-compatible functionality additions
- **PATCH** version for backwards-compatible bug fixes
- **Pre-release** identifiers (alpha, beta, rc) for testing versions

### Links
[Unreleased]: https://github.com/jedusort/azure-keyvault-spi-keycloak/compare/v1.0.0-beta.1...HEAD
[1.0.0-beta.1]: https://github.com/jedusort/azure-keyvault-spi-keycloak/releases/tag/v1.0.0-beta.1
