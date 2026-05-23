---
title: "OAuth Authentication"
date: 2024-12-01T00:00:00+00:00
draft: false
weight: 4
toc: true
---

PostgreSQL 18 introduces support for OAuth bearer token authentication via the
SASL mechanism `OAUTHBEARER` (RFC 7628). The pgjdbc driver supports this
authentication method through a plugin-based token provider interface.

## Overview

When the PostgreSQL server is configured to require OAuth authentication
(`pg_hba.conf` with `oauth` method), the driver participates in the
OAUTHBEARER SASL exchange by supplying a bearer token. The token can be
provided either statically via a connection property or dynamically via a
custom token provider plugin.

## Quick Start

### Static Token

The simplest approach is to supply a pre-acquired bearer token directly:

```java
String url = "jdbc:postgresql://host/db?user=app&oauthToken=eyJhbGci...";
Connection conn = DriverManager.getConnection(url);
```

Or via properties:

```java
Properties props = new Properties();
props.setProperty("user", "app");
props.setProperty("oauthToken", "eyJhbGci...");
Connection conn = DriverManager.getConnection("jdbc:postgresql://host/db", props);
```

### Token Provider Plugin

For production use, implement the `OAuthTokenProvider` interface to acquire
tokens dynamically:

```java
import org.postgresql.plugin.OAuthTokenProvider;
import org.postgresql.plugin.OAuthTokenRequest;
import org.postgresql.util.PSQLException;

public class MyTokenProvider implements OAuthTokenProvider {
    @Override
    public String getToken(OAuthTokenRequest request) throws PSQLException {
        // Acquire token from your identity provider
        // request.getHost(), request.getUser(), request.getScope(), etc.
        // are available for context
        return fetchTokenFromIdP(request);
    }
}
```

Then configure the driver to use it:

```
jdbc:postgresql://host/db?user=app&oauthTokenProvider=com.example.MyTokenProvider
```

## Connection Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `oauthToken` | String | null | Static OAuth bearer token. If set, used directly without invoking a provider. |
| `oauthTokenProvider` | String | null | Fully-qualified class name of an `OAuthTokenProvider` implementation. |
| `oauthIssuerUrl` | String | null | OpenID Connect discovery URL, passed to the provider. |
| `oauthClientId` | String | null | OAuth client ID, passed to the provider. |
| `oauthScope` | String | null | OAuth scope, passed to the provider. |

If both `oauthToken` and `oauthTokenProvider` are set, the static token takes
precedence.

## Token Provider Interface

```java
package org.postgresql.plugin;

public interface OAuthTokenProvider {
    /**
     * Called when the server requests OAUTHBEARER authentication.
     *
     * @param request context about the authentication request
     * @return a valid bearer token, never null or empty
     * @throws PSQLException if a token cannot be obtained
     */
    String getToken(OAuthTokenRequest request) throws PSQLException;
}
```

The `OAuthTokenRequest` provides context to the provider:

| Method | Description |
|--------|-------------|
| `getHost()` | Server hostname |
| `getPort()` | Server port |
| `getUser()` | PostgreSQL username |
| `getDatabase()` | Target database name |
| `getDiscoveryUrl()` | OpenID Connect discovery URL (from config or server) |
| `getScope()` | Requested OAuth scope (from config or server) |
| `getClientId()` | OAuth client ID (from config) |

### Provider Instantiation

The provider class must have either:

- A constructor that accepts `java.util.Properties` (receives all connection properties), or
- A zero-argument constructor

The driver instantiates a new provider instance for each connection attempt.

## Token Provider Examples

### Client Credentials Grant

```java
public class ClientCredentialsProvider implements OAuthTokenProvider {
    private String clientId;
    private String clientSecret;
    private String tokenEndpoint;

    public ClientCredentialsProvider(Properties props) {
        this.clientId = props.getProperty("oauthClientId");
        this.clientSecret = props.getProperty("oauthClientSecret");
        this.tokenEndpoint = props.getProperty("oauthIssuerUrl")
            + "/oauth2/token";
    }

    @Override
    public String getToken(OAuthTokenRequest request) throws PSQLException {
        // Use your preferred HTTP client to POST to tokenEndpoint
        // with grant_type=client_credentials
        return performClientCredentialsGrant();
    }
}
```

### Cached Token with Refresh

```java
public class CachedTokenProvider implements OAuthTokenProvider {
    private static volatile String cachedToken;
    private static volatile long expiresAt;

    @Override
    public synchronized String getToken(OAuthTokenRequest request)
            throws PSQLException {
        if (cachedToken != null && System.currentTimeMillis() < expiresAt) {
            return cachedToken;
        }
        // Refresh token...
        TokenResponse resp = refreshToken(request);
        cachedToken = resp.accessToken;
        expiresAt = System.currentTimeMillis()
            + (resp.expiresInSeconds * 1000L) - 30_000L;
        return cachedToken;
    }
}
```

### Cloud Identity Providers

For AWS, Azure, or GCP, implement `OAuthTokenProvider` using the respective
cloud SDK. The driver has no cloud SDK dependencies itself — this is
intentionally delegated to your application.

```java
// Example: AWS IAM Identity Center
public class AwsOAuthProvider implements OAuthTokenProvider {
    @Override
    public String getToken(OAuthTokenRequest request) throws PSQLException {
        // Use AWS SDK to get an OIDC token
        // e.g., SsoOidcClient.createToken(...)
        return awsToken.accessToken();
    }
}
```

## Mechanism Selection

When the server advertises multiple SASL mechanisms (e.g., both `OAUTHBEARER`
and `SCRAM-SHA-256`), the driver selects the mechanism based on which
credentials are configured:

1. If `oauthToken` or `oauthTokenProvider` is set, the driver uses `OAUTHBEARER`.
2. Otherwise, the driver falls back to `SCRAM-SHA-256` using the `password` property.

To explicitly require OAuth authentication, use:

```
jdbc:postgresql://host/db?requireAuth=oauth&oauthTokenProvider=com.example.MyProvider
```

## requireAuth Support

The `requireAuth` connection parameter now accepts `oauth`:

```
requireAuth=oauth            # require OAuth authentication
requireAuth=!oauth           # reject OAuth, use another method
requireAuth=oauth,scram-sha-256  # allow either
```

## Server Configuration

On the PostgreSQL server side (version 18+), OAuth is configured in
`pg_hba.conf`:

```
# TYPE  DATABASE  USER  ADDRESS  METHOD  OPTIONS
host    all       all   0.0.0.0/0  oauth  issuer="https://idp.example.com" scope="openid postgres"
```

Refer to the [PostgreSQL documentation](https://www.postgresql.org/docs/18/auth-oauth.html)
for full server-side configuration details.

## Error Handling

If OAuth authentication fails, the server returns a JSON error response
containing optional discovery metadata:

- `status` — The OAuth error code (e.g., `invalid_token`)
- `openid-configuration` — OpenID Connect discovery URL
- `scope` — Required scope

The driver includes the server's status in the `PSQLException` message. The
discovery metadata can be used by applications to guide users toward the
correct identity provider configuration.

## Security Considerations

- Bearer tokens are sent in cleartext within the SASL exchange. Always use
  SSL/TLS connections (`sslmode=require` or stricter) when using OAuth
  authentication.
- Do not log or store tokens in connection URLs visible to other users.
  Prefer `oauthTokenProvider` over `oauthToken` in production.
- Token providers should implement appropriate token caching and refresh
  logic to avoid unnecessary round-trips to the identity provider.
- The driver clears static tokens from memory after use where possible, but
  Java's garbage collector may retain copies. For maximum security, use a
  token provider that manages token lifecycle.
