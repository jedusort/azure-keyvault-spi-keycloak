# Integration Tests Documentation

This document explains how to run the integration tests for the Azure Key Vault SPI for Keycloak.

## Overview

The integration tests use [Testcontainers](https://www.testcontainers.org/) to spin up real Docker containers for:
- **Azurite**: Microsoft Azure Storage Emulator for testing blob storage operations
- **Keycloak**: For end-to-end testing of the SPI integration

## Prerequisites

### System Requirements
- Java 17+
- Maven 3.6+
- Docker (with Docker daemon running)
- At least 2GB available RAM for containers

### Docker Setup
Make sure Docker is installed and running:
```bash
docker --version
docker info
```

## Test Structure

### Test Categories

1. **Unit Tests** (`src/test/java/**/provider/**/*Test.java`):
   - Fast, isolated tests with mocked dependencies
   - Run with: `mvn test`
   - Excluded integration tests automatically

2. **Integration Tests** (`src/test/java/**/integration/**/*Test.java`):
   - Full-stack tests with real containers
   - Run with: `mvn verify -Pintegration-tests` or `mvn test -Dtest=*IntegrationTest`

### Available Integration Tests

#### 1. TestcontainersSmokeTest
- **Purpose**: Verify Testcontainers setup
- **Containers**: Azurite only
- **Runtime**: ~10-15 seconds
- **Run**: `mvn test -Dtest=TestcontainersSmokeTest`

#### 2. AzuriteIntegrationTest
- **Purpose**: Test Azure Storage emulation with Azurite
- **Containers**: Azurite only
- **Features**: Blob operations, test secret setup, UTF-8 handling
- **Runtime**: ~8-12 seconds
- **Run**: `mvn test -Dtest=AzuriteIntegrationTest`

#### 3. AzureKeyVaultIntegrationTest
- **Purpose**: Full end-to-end testing with Keycloak
- **Containers**: Azurite + Keycloak 26
- **Features**: SPI registration, configuration, secret retrieval, cache testing
- **Runtime**: ~2-5 minutes (includes Keycloak startup)
- **Run**: `mvn test -Dtest=AzureKeyVaultIntegrationTest`

## Running Tests

### Quick Start - Run All Unit Tests
```bash
mvn clean test
```
This runs only unit tests (72 tests) and excludes integration tests for speed.

### Run Specific Integration Test
```bash
# Test Azurite setup only (fastest)
mvn test -Dtest=AzuriteIntegrationTest

# Test full Keycloak integration (slower)
mvn test -Dtest=AzureKeyVaultIntegrationTest
```

### Run All Integration Tests
```bash
# Using Maven profile
mvn clean verify -Pintegration-tests

# Using Maven Failsafe plugin directly
mvn clean compile test-compile failsafe:integration-test failsafe:verify
```

### Skip Integration Tests in CI
```bash
# Only run unit tests (default behavior)
mvn clean test

# Skip all tests
mvn clean install -DskipTests
```

## Test Configuration

### Environment Variables
The tests use the following environment configuration:

**Azurite Configuration:**
- Container: `mcr.microsoft.com/azure-storage/azurite:latest`
- Ports: 10000 (Blob), 10001 (Queue), 10002 (Table)
- Connection String: Uses default Azurite development account

**Keycloak Configuration:**
- Container: `quay.io/keycloak/keycloak:26.0.0`
- Port: 8080
- Admin User: `admin` / `admin`
- Database: H2 in-memory

### Test Resources

#### `src/test/resources/keycloak-test.conf`
Keycloak configuration for integration tests:
```properties
# Azure Key Vault SPI Configuration
spi-vault-azure-kv-enabled=true
spi-vault-azure-kv-name=test-vault
spi-vault-azure-kv-cache-ttl=30
spi-vault-azure-kv-cache-max=100
```

#### `src/test/resources/test-secrets.json`
Test secrets data loaded into Azurite:
```json
{
  "secrets": [
    {
      "name": "database-password",
      "value": "MyDatabasePassword123!",
      "enabled": true
    }
  ]
}
```

## Troubleshooting

### Common Issues

#### 1. Docker Not Available
```
Error: Could not find a valid Docker environment
```
**Solution**: Make sure Docker is installed and running:
```bash
sudo systemctl start docker  # Linux
docker version
```

#### 2. Port Conflicts
```
Error: Port 8080 is already in use
```
**Solution**: Testcontainers automatically assigns random ports. If this persists, check for other Docker containers:
```bash
docker ps
docker stop $(docker ps -q)  # Stop all containers
```

#### 3. Memory Issues
```
Error: Cannot create container
```
**Solution**: Ensure at least 2GB RAM is available:
```bash
docker system prune  # Clean up unused containers/images
```

#### 4. Image Pull Failures
```
Error: Unable to pull image
```
**Solution**: Check internet connectivity and Docker Hub access:
```bash
docker pull mcr.microsoft.com/azure-storage/azurite:latest
docker pull quay.io/keycloak/keycloak:26.0.0
```

### Test Timeouts

If tests timeout, you can increase the timeout values:

```bash
# Increase Surefire timeout
mvn test -Dtest=AzureKeyVaultIntegrationTest -Dsurefire.timeout=600

# Or use system property in tests
export TESTCONTAINERS_STARTUP_TIMEOUT=300
```

### Debug Output

Enable debug logging:
```bash
# Maven debug
mvn test -X -Dtest=AzuriteIntegrationTest

# Test logging (see slf4j-simple.properties)
mvn test -Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG
```

## Performance Optimization

### Image Caching
Testcontainers reuses pulled images. First run will be slower due to image downloads:
- Azurite: ~88MB
- Keycloak: ~500MB+

### Container Reuse
For development, enable container reuse:
```bash
# Add to ~/.testcontainers.properties
testcontainers.reuse.enable=true
```

### Parallel Execution
Run tests in parallel:
```bash
mvn test -Dtest=*IntegrationTest -DforkCount=2 -DreuseForks=false
```

## CI/CD Integration

### GitHub Actions Example
```yaml
- name: Run Integration Tests
  run: |
    mvn clean verify -Pintegration-tests
  env:
    TESTCONTAINERS_RYUK_DISABLED: true
```

### Docker-in-Docker
For containerized CI environments:
```yaml
services:
  docker:
    image: docker:dind
    privileged: true
```

## Advanced Configuration

### Custom Test Profiles

Create additional Maven profiles for different test scenarios:

```xml
<profile>
    <id>smoke-tests</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <includes>
                        <include>**/*SmokeTest.java</include>
                    </includes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

### Test Data Customization

Modify `test-secrets.json` to add custom test scenarios:
```json
{
  "secrets": [
    {
      "name": "custom-secret",
      "value": "Custom test value",
      "contentType": "text/plain",
      "enabled": true
    }
  ]
}
```

## Metrics and Monitoring

Integration tests also validate:
- Micrometer metrics collection
- Cache hit/miss ratios
- Error rates and resilience patterns
- Performance under concurrent load

Access metrics during test execution:
```
GET http://localhost:{keycloak-port}/metrics
```

## Contributing

When adding new integration tests:

1. Follow the naming convention: `*IntegrationTest.java`
2. Place in `src/test/java/org/devolia/kcvault/integration/`
3. Extend `BaseIntegrationTest` for shared container lifecycle
4. Add appropriate `@Order` annotations for test sequencing
5. Include proper cleanup in `@AfterEach` or `@AfterAll`
6. Document any new test scenarios in this file

## Support

For issues with integration tests:
1. Check this documentation first
2. Review test logs in `target/surefire-reports/`
3. Verify Docker environment
4. Check GitHub Issues for known problems