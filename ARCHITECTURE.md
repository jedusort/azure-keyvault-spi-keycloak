
# Azure Key Vault SPI for Keycloak – Architecture

## Goal

A production ready `VaultProvider` that enables **Keycloak 26+** to resolve **secrets** stored in **Azure Key Vault** via the official Vault SPI. Future roadmap covers keys & certificates and multi-vault support.

## High‑level diagram

```
┌─────────────────────────────────────────────────────────────┐
│                       keycloak.conf                         │
│  spi-vault-azure-kv-enabled=true                            │
│  spi-vault-azure-kv-name=<vault>                            │
│  spi-vault-azure-kv-cache-ttl=60                            │
│  spi-vault-azure-kv-cache-max=1000                          │
└────────────▲───────────────────────────────────────────────┘
             │ Quarkus / SmallRye Config
┌────────────┴───────────────────────────────────────────────┐
│   AzureKeyVaultConfigResolver  (CDI)                       │
└────────────▲───────────────────────────────┬───────────────┘
             │ injects                       │
   Vault SPI │                               │  Micrometer
┌────────────┴──────────────┐   ┌────────────┴──────────────┐
│  AzureKeyVaultProvider    │   │  AzureKeyVaultMetrics     │
│  • obtainSecret()         │   │  Counter + Histogram      │
│  • clearCache()           │   └────────────┬──────────────┘
│  ──────────────────────── │                │
│  Caffeine TTL/LRU Cache   │                ▼
│  Azure SDK SecretClient   │        Prometheus / OTEL       │
└────────────▲──────────────┘
             │ factory
┌────────────┴───────────────────────────────────────────────┐
│ AzureKeyVaultProviderFactory (build‑time registration)      │
└─────────────────────────────────────────────────────────────┘
```

## Package layout (Java 17)

- `com.devolia.kcvault`  – root
  - `provider`  – `AzureKeyVaultProvider`, `Factory`
  - `cache`     – `CacheConfig`
  - `auth`      – `CredentialResolver`
  - `metrics`   – `AzureKeyVaultMetrics`

## Configuration parameters

| Key                            | Default  | Purpose                 |
| ------------------------------ | -------- | ----------------------- |
| `spi-vault-azure-kv-enabled`   | `true`   | Enable provider         |
| `spi-vault-azure-kv-name`      | *(none)* | Azure Key Vault name    |
| `spi-vault-azure-kv-cache-ttl` | `60`     | Cache TTL (seconds)     |
| `spi-vault-azure-kv-cache-max` | `1000`   | Max cache entries (LRU) |

Environment variables (Service Principal auth): `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`.

