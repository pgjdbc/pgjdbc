# OAuth/OAUTHBEARER Authentication Support

## Overview

PostgreSQL 18 adds OAuth bearer token authentication via the SASL mechanism
`OAUTHBEARER` (RFC 7628). This document describes the design for supporting it
in pgjdbc using a plugin-based token provider.

## Protocol

OAuth authentication reuses the existing SASL authentication message flow
(AUTH_REQ_SASL=10, AUTH_REQ_SASL_CONTINUE=11, AUTH_REQ_SASL_FINAL=12). The
server advertises `OAUTHBEARER` as a SASL mechanism name.

### Handshake

```
Client                                  Server
  |                                       |
  |<-- AuthenticationSASL(OAUTHBEARER) ---|  (areq=10, mechanism list)
  |                                       |
  |--- SASLInitialResponse -------------->|  (bearer token in RFC 7628 format)
  |                                       |
  |<-- AuthenticationSASLFinal ---------->|  (areq=12, success)
  |    OR                                 |
  |<-- AuthenticationSASLContinue ------->|  (areq=11, JSON error + discovery)
  |                                       |
  |--- SASLResponse (empty, "\x01") ----->|  (client acknowledges failure)
  |                                       |
  |<-- ErrorResponse --------------------|  (connection rejected)
```

### Initial Response Format (RFC 7628)

```
n,,\x01auth=Bearer <token>\x01\x01
```

- `n,,` — GS2 header (no channel binding, no authzid)
- `\x01` — field separator (0x01)
- `auth=Bearer <token>` — the OAuth bearer token
- `\x01\x01` — end of fields

### Server Error Response (AUTH_REQ_SASL_CONTINUE)

On failure, the server sends a JSON object containing OpenID discovery info:

```json
{
  "status": "invalid_token",
  "openid-configuration": "https://idp.example.com/.well-known/openid-configuration",
  "scope": "openid postgres"
}
```

The client must respond with a single `\x01` byte to acknowledge, after which
the server sends an ErrorResponse and closes the connection.

## Design

### Plugin Interface

```java
package org.postgresql.plugin;

public interface OAuthTokenProvider {

  /**
   * Called when the server requests OAUTHBEARER authentication.
   *
   * <p>Implementations acquire a bearer token through whatever mechanism is
   * appropriate: reading from a cache, performing a client credentials grant,
   * initiating a device authorization flow, etc.</p>
   *
   * @param info context about the authentication request (server host, port,
   *     user, database, and any discovery metadata from a prior failed attempt)
   * @return a valid bearer token, never null or empty
   * @throws PSQLException if a token cannot be obtained
   */
  String getToken(OAuthTokenRequest info) throws PSQLException;
}
```

### Token Request Context

```java
package org.postgresql.plugin;

import org.checkerframework.checker.nullness.qual.Nullable;

public class OAuthTokenRequest {
  private final String host;
  private final int port;
  private final String user;
  private final String database;
  private final @Nullable String discoveryUrl;
  private final @Nullable String scope;

  // constructor, getters
}
```

The `discoveryUrl` and `scope` fields are populated from the server's error
JSON if this is a retry after a failed OAUTHBEARER exchange. On the first
attempt they will be null unless the user configures them explicitly via
connection properties.

### Connection Properties

| Property | Description |
|----------|-------------|
| `oauthToken` | Static bearer token. Simplest usage — no plugin needed. |
| `oauthTokenProvider` | Fully-qualified class name of an `OAuthTokenProvider` implementation. |
| `oauthIssuerUrl` | OpenID discovery URL. Passed to the provider in `OAuthTokenRequest`. |
| `oauthClientId` | OAuth client ID. Passed to the provider. |
| `oauthScope` | Requested scope. Passed to the provider. |

Priority: if `oauthToken` is set, use it directly. Otherwise delegate to
`oauthTokenProvider`. If neither is set and the server requests OAUTHBEARER,
fail with a clear error message.

### Authenticator Class

```java
package org.postgresql.core.v3;

final class OAuthBearerAuthenticator {

  private final PGStream pgStream;
  private final String token;

  OAuthBearerAuthenticator(PGStream pgStream, String token) {
    this.pgStream = pgStream;
    this.token = token;
  }

  void handleAuthenticationSASL() throws IOException {
    // Send SASLInitialResponse with mechanism "OAUTHBEARER"
    // and initial-response = "n,,\x01auth=Bearer <token>\x01\x01"
    byte[] mechanism = "OAUTHBEARER".getBytes(StandardCharsets.UTF_8);
    byte[] initialResponse = buildInitialResponse();

    pgStream.sendChar(PgMessageType.SASL_INITIAL_RESPONSE);
    pgStream.sendInteger4(Integer.BYTES
        + mechanism.length + 1
        + Integer.BYTES + initialResponse.length);
    pgStream.send(mechanism);
    pgStream.sendChar(0);
    pgStream.sendInteger4(initialResponse.length);
    pgStream.send(initialResponse);
    pgStream.flush();
  }

  /**
   * Called if server responds with AUTH_REQ_SASL_CONTINUE (failure).
   * Parses the JSON error, sends the required dummy response, then throws.
   */
  OAuthDiscoveryInfo handleAuthenticationSASLContinue(int length)
      throws IOException, PSQLException {
    String json = pgStream.receiveString(length);
    // Parse discovery info from JSON
    OAuthDiscoveryInfo discovery = parseDiscovery(json);

    // Must send a single 0x01 byte to acknowledge failure
    pgStream.sendChar(PgMessageType.SASL_INITIAL_RESPONSE);
    pgStream.sendInteger4(Integer.BYTES + 1);
    pgStream.sendChar(1);
    pgStream.flush();

    return discovery;
  }

  private byte[] buildInitialResponse() {
    String response = "n,,auth=Bearer " + token + "";
    return response.getBytes(StandardCharsets.UTF_8);
  }
}
```

### Integration into ConnectionFactoryImpl

In `doAuthentication`, the AUTH_REQ_SASL case currently reads the mechanism
list and unconditionally creates a `ScramAuthenticator`. The change:

```java
case AUTH_REQ_SASL:
  List<String> mechanisms = readSaslMechanisms(pgStream);

  if (mechanisms.contains("OAUTHBEARER") && hasOAuthConfig(info)) {
    AuthMethod.checkAuth(authMethods, AuthMethod.OAUTH);
    String token = resolveOAuthToken(info, host, user, /* discovery */ null);
    oauthAuthenticator = new OAuthBearerAuthenticator(pgStream, token);
    oauthAuthenticator.handleAuthenticationSASL();
  } else if (mechanisms.stream().anyMatch(m -> m.startsWith("SCRAM-SHA-"))) {
    AuthMethod.checkAuth(authMethods, AuthMethod.SCRAM_SHA_256);
    // ... existing SCRAM path ...
  } else {
    throw unsupportedMechanism(mechanisms);
  }
  break;
```

### Mechanism Selection Strategy

When the server advertises multiple mechanisms:

1. If the user has configured OAuth credentials (`oauthToken` or
   `oauthTokenProvider`), prefer `OAUTHBEARER`.
2. If only password/SCRAM credentials exist, prefer `SCRAM-SHA-256`.
3. If `requireAuth` is set, honour it — e.g., `requireAuth=oauth` forces
   OAUTHBEARER even if SCRAM is also advertised.
4. If neither credential type is configured and both mechanisms are offered,
   fail with a message indicating which credentials are missing.

### AuthMethod Enum Changes

```java
public enum AuthMethod {
  NONE, PASSWORD, MD5, GSS, SSPI, SCRAM_SHA_256, OAUTH;

  public static AuthMethod fromString(String method) throws PSQLException {
    switch (method) {
      // ... existing ...
      case "oauth": return OAUTH;
    }
  }
}
```

### AuthenticationRequestType Changes

```java
public enum AuthenticationRequestType {
  CLEARTEXT_PASSWORD,
  GSS,
  MD5_PASSWORD,
  SASL,
  OAUTH,
}
```

## Token Provider Examples

### Static Token (No Plugin)

```
jdbc:postgresql://host/db?user=app&oauthToken=eyJhbGci...
```

### Custom Provider (Client Credentials)

```java
public class MyClientCredentialsProvider implements OAuthTokenProvider {
  @Override
  public String getToken(OAuthTokenRequest info) throws PSQLException {
    // Use any HTTP/OAuth library to perform client_credentials grant
    // against info.getDiscoveryUrl() or a configured IdP endpoint
    return tokenCache.getOrRefresh(info.getScope());
  }
}
```

```
jdbc:postgresql://host/db?user=app&oauthTokenProvider=com.example.MyClientCredentialsProvider&oauthScope=openid%20postgres
```

### AWS IAM / Azure AD Providers

Third-party libraries can implement `OAuthTokenProvider` to fetch tokens from
cloud identity services without the driver needing any cloud SDK dependency.

## Files to Modify / Create

| File | Change |
|------|--------|
| `org.postgresql.plugin.OAuthTokenProvider` | New interface |
| `org.postgresql.plugin.OAuthTokenRequest` | New context class |
| `org.postgresql.core.v3.OAuthBearerAuthenticator` | New SASL handler |
| `org.postgresql.core.v3.ConnectionFactoryImpl` | Branch on OAUTHBEARER in AUTH_REQ_SASL |
| `org.postgresql.core.AuthMethod` | Add `OAUTH` |
| `org.postgresql.plugin.AuthenticationRequestType` | Add `OAUTH` |
| `org.postgresql.PGProperty` | Add `oauthToken`, `oauthTokenProvider`, `oauthIssuerUrl`, `oauthClientId`, `oauthScope` |
| `org.postgresql.core.v3.ScramAuthenticator` | Extract mechanism-list reading into shared utility |

## Open Questions

1. **Should the driver ship a built-in Device Authorization Grant flow?**
   libpq does this for interactive `psql` use. For JDBC (typically server-side
   apps), the plugin interface alone may suffice. A built-in flow could live in
   a separate optional module to avoid pulling in an HTTP client dependency.

2. **Token caching and refresh.** The plugin is called on each new connection.
   Caching is the provider's responsibility. Should the driver provide any
   caching infrastructure, or leave it entirely to implementors?

3. **Discovery retry.** If the first attempt fails and the server returns
   discovery metadata, should the driver automatically retry by calling the
   provider again with the discovery info? Or fail immediately and let the app
   reconnect?

4. **Dependency.** Parsing the server's JSON error response requires a JSON
   parser. Options: minimal hand-rolled parser (it's a flat object), or
   optional dependency on a JSON library already in scope.

5. **SASL mechanism refactoring.** The current code reads the mechanism list
   inside `ScramAuthenticator`. It should be extracted so that mechanism
   selection happens before choosing which authenticator to instantiate.
