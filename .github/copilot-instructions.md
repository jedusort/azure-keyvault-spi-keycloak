# Copilot Custom Instructions – Azure Key Vault SPI for Keycloak

## 📚 Project context
- **Goal**: Provide a production-ready `VaultProvider` so that **Keycloak 26+** can read **secrets** (later : keys & certs) directly from **Azure Key Vault**, using the official Vault SPI.
- **Repository**: `azure-keyvault-spi-keycloak`
- **Main packages**
  - `org.devolia.kcvault.AzureKeyVaultProvider`
  - `org.devolia.kcvault.AzureKeyVaultProviderFactory`
  - `org.devolia.kcvault.cache` – Caffeine TTL + LRU cache
  - `org.devolia.kcvault.metrics` – Micrometer/Prometheus
- **Config keys**
  - `spi-vault-azure-kv-name` (String, required)
  - `spi-vault-azure-kv-cache-ttl` (Integer, default 60 s)
  - `spi-vault-azure-kv-cache-max` (Integer, default 1000)
- **Auth modes**
  1. Managed Identity / Workload ID (AKS)
  2. Service Principal – `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`

## ✅ Tasks Copilot is expected to perform
1. **Generate** skeleton classes, constructors, and Quarkus CDI annotations.
2. **Implement** `obtainSecret()` with :
   - Cache lookup → SecretClient call → metrics update → cache store.
   - Handle `404` ⇒ return `null`.
3. **Add** a parameterised Caffeine cache (`ttl`, `max`) and expose `invalidateSecret`.
4. **Wire** Micrometer counters  
   `keycloak_vault_azure_kv_requests_total{status="success|error"}`  
   `keycloak_vault_azure_kv_latency_seconds`.
5. **Write** JUnit 5 tests (Mockito for unit, Testcontainers for IT with Keycloak 26 + Azurite).
6. **Update** the Docker multistage image (stage Maven → stage builder + `kc.sh build`).
7. **Keep** code Java 17-compatible; follow Google Java Style (`mvn fmt:format` target).

## 🔒 Coding conventions
| Topic | Rule |
|-------|------|
| **Packages** | `org.devolia.kcvault.*` only. |
| **Dependencies** | Use Azure SDK `com.azure:azure-security-keyvault-secrets:latest`.<br>No Lombok, no unstable Quarkus APIs. |
| **Cache** | Use Caffeine (`com.github.ben-manes.caffeine`). |
| **Logging** | SLF4J → inherit Keycloak log config. Default level **INFO**; wrap secrets before logging. |
| **Metrics** | Register via CDI `@Singleton` class `AzureKeyVaultMetrics`. |
| **Exception mapping** | Convert SDK exceptions to Keycloak `VaultNotFoundException` / `VaultException`. |
| **Tests** | Place ITs under `src/test/java/.../integration`. Name pattern `*IT.java`. |

## ⚠️ Pitfalls to avoid
- **No hard-coded vault name** – always read `spi-vault-azure-kv-name`.
- **Do NOT cache indefinitely** – honour TTL param, default 60 s.
- **Do NOT rethrow raw Azure exceptions** – map them.
- **Do NOT add database drivers, Jakarta EE, or Spring** – keep JAR lean.
- **Do NOT change files outside `src/main|test` unless asked** (e.g., `pom.xml`, `.github/workflows`).

## 🛠️ Good practices
1. **Small commits** focused on a single change; reference issues if available.
2. **Run** `mvn verify` locally; Copilot Agent should ensure CI is green.
3. **Document** new public classes with Javadoc.
4. **Follow roadmap** comments in `README.md` for future features (multi-vault, fallback, keys/certs).
5. **Update** `CHANGELOG.md` with semantic-versioning entries (`Added`, `Changed`, `Fixed`).

---

*If additional info is required, ask clarifying questions before generating large changes.*
