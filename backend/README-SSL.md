# TLS / PKIX troubleshooting (OpenAI `https://api.openai.com`)

The backend uses a **standard, verifying** TLS stack (Apache HttpClient 5 + JVM or custom trust material). **Do not** enable `http.client.openai.trust-all-certificates` in production.

On startup, if `ssl.startup-validation.enabled=true` (default), the app performs a **non-fatal** TLS probe to `https://api.openai.com` using the same `SSLContext` as outbound HTTP clients. Check logs for `SSL startup validation SUCCESS` or `FAILED`.

## Configuration (application.properties or `config/local-keys.properties`)

| Property | Purpose |
|----------|---------|
| `ssl.truststore.path` | Optional path to a truststore file (JKS, PKCS12, etc.) containing extra CAs (e.g. corporate root, inspection CA). |
| `ssl.truststore.password` | Truststore password (can be empty for some types). |
| `ssl.truststore.type` | Optional; leave blank for `KeyStore.getDefaultType()`, or set `JKS`, `PKCS12`, etc. |
| `proxy.host` / `proxy.port` | Optional HTTP proxy (`http` scheme) for outbound traffic, including HTTPS via CONNECT. |
| `ssl.startup-validation.enabled` | Set `false` to skip the startup TLS probe (e.g. air-gapped CI). |

## 1. If you see a PKIX / certificate path error

- Confirm **Java 11+** (Java 17 is used by this project’s toolchain):

  ```bash
  java -version
  ```

- Ensure the **full chain** presented by the server (or SSL inspection appliance) is trusted by either the **JVM default cacerts** or your **`ssl.truststore.path`** file.

## 2. If you are behind a corporate HTTP proxy

Set:

```properties
proxy.host=your.proxy.company.com
proxy.port=8080
```

If the proxy itself uses TLS or needs authentication, extend the stack later (this build configures a standard HTTP `CONNECT` proxy as above).

## 3. Export and import a server / inspection certificate

Inspect the chain OpenAI (or your proxy) presents:

```bash
openssl s_client -showcerts -connect api.openai.com:443 </dev/null
```

Save the PEM for the CA you need as `openai-or-proxy-ca.crt`, then import into the JVM truststore **or** a dedicated truststore file.

**Import into JVM cacerts** (Linux/macOS example; default password is often `changeit`):

```bash
keytool -importcert -trustcacerts -noprompt \
  -alias openai-or-proxy-ca \
  -file openai-or-proxy-ca.crt \
  -keystore "$JAVA_HOME/lib/security/cacerts" \
  -storepass changeit
```

On Windows, adjust paths, e.g. `%JAVA_HOME%\lib\security\cacerts`.

## 4. Prefer a dedicated truststore (recommended for prod)

Create a PKCS12 or JKS truststore that contains only the extra CAs you need, then point the app at it:

```properties
ssl.truststore.path=C:/secrets/company-truststore.p12
ssl.truststore.password=your-secret
ssl.truststore.type=PKCS12
```

Restart the application and confirm `SSL startup validation SUCCESS` in logs.

## 5. Related application properties

- OpenAI HTTP timeouts (also used by the shared `RestTemplate` bean from `SslConfig`):  
  `http.client.openai.connect-timeout-ms`, `http.client.openai.read-timeout-ms`
- **Local dev only** (insecure): `http.client.openai.trust-all-certificates=true` — **never** in production.
