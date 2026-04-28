# TLS / PKIX (Jira, GitHub, Groq LLM)

All outbound HTTPS clients share **one** `SSLContext` from `SslConfig`: **Apache HttpClient 5** with normal PKIX verification (no certificate bypass).

- **Default:** JVM trust anchors (`JAVA_HOME/lib/security/cacerts`) — enough for public Atlassian Cloud, GitHub, and Groq when the JDK is current.
- **Corporate SSL inspection / extra roots:** Set `ssl.truststore.path` to a PKCS12/JKS file. That file is **merged** into a single KeyStore **with** the JVM `cacerts`, so public CAs keep working while your corporate CA is trusted.

## Configuration (`application.properties` or `config/local-keys.properties`)

| Property | Purpose |
|----------|---------|
| `ssl.enabled` | Default `true`. When `false`, uses `SSLContext.getDefault()` only — no custom file merge (emergency/local). |
| `ssl.truststore.path` | Optional extra CA bundle (merged with JVM cacerts when set). |
| `ssl.truststore.password` | Truststore password (optional for some types). |
| `ssl.truststore.type` | Optional; blank = `KeyStore.getDefaultType()`, or `JKS`, `PKCS12`, etc. |
| `proxy.host` / `proxy.port` | HTTP `CONNECT` proxy for all HTTPS clients (Jira, GitHub, Groq LLM). |
| `ssl.startup-validation.enabled` | When `true`, probes `ssl.startup-validation.llm-url` (Groq) and `ssl.startup-validation.github-url` at startup using the **same** `SSLContext`. |
| `ssl.startup-validation.fail-fast` | When `true` (default), startup **throws** if any probe fails (PKIX, timeout, etc.). Set `false` only for CI/air-gap with no egress. |

## Startup logs

Look for:

- `SSL configured: mode=...` — JVM-only vs merged trust material.
- `Merged JVM cacerts (...) with custom trust material: jdkCerts=... extraCerts=...`
- `Apache HttpClient (integration ...)` / `(Groq LLM)` — PKIX enabled, timeouts.
- `SSL startup validation SUCCESS` — probes passed.
- Or `IllegalStateException` from `SslStartupValidator` when fail-fast is on.

## PKIX errors

1. Confirm **Java 17+** (`java -version`).
2. Import missing CA certificates into **`ssl.truststore.path`** (recommended) or into JVM `cacerts`.
3. Behind a proxy: set `proxy.host` / `proxy.port`.
4. If bootstrap fails only in CI with no internet: `ssl.startup-validation.enabled=false` or `ssl.startup-validation.fail-fast=false`.

### Inspect TLS chain

```bash
openssl s_client -showcerts -connect api.groq.com:443 </dev/null
```

Import the CA PEM into a PKCS12 truststore:

```properties
ssl.truststore.path=C:/secrets/corp-truststore.p12
ssl.truststore.password=secret
ssl.truststore.type=PKCS12
```

Restart and confirm `SSL startup validation SUCCESS`.

## Related timeouts

| Client | Properties |
|--------|----------------|
| Jira / GitHub / shared `RestTemplate` | `http.client.connect-timeout-ms`, `http.client.read-timeout-ms` |
| Groq LLM | `http.client.openai.connect-timeout-ms`, `http.client.openai.read-timeout-ms` |
