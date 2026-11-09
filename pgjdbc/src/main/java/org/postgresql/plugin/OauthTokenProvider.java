/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.plugin;

import org.postgresql.util.PSQLException;

/**
 * Provides an OAuth 2.0 bearer token for OAUTHBEARER authentication.
 *
 * <p>The driver instantiates the class named by the {@code oauthTokenProviderClassName}
 * connection property, so an implementation must declare either a public constructor taking a
 * single {@link java.util.Properties} argument, which receives the connection properties, or a
 * public no-argument constructor.</p>
 *
 * <p>The driver does not define properties for the provider's own settings, such as the issuer,
 * client id or scope. The {@code Properties} constructor receives every connection property,
 * including names the driver does not know, so a provider can read its settings under names of
 * its own, for example {@code com.example.oauth.clientId}. The driver masks only its own
 * sensitive properties when it logs a URL, so read a client secret from the environment or a
 * file rather than from the URL.</p>
 *
 * <p>The driver creates a new instance and calls {@link #getToken(OauthTokenRequest)} each time
 * a server requests OAUTHBEARER over a connection that passes the transport check: once for each
 * host it tries with a multi-host URL, and not at all when the server selects another
 * authentication method or the {@code oauthToken} connection property is set. Any cache must
 * therefore be static and keyed by everything that affects the token. A provider is best kept a
 * thin bridge to a layer that already caches tokens, such as a cloud SDK credential or a token
 * file that an agent keeps up to date.</p>
 *
 * <p>The call runs on the connect path: it counts against {@code loginTimeout}, and must return
 * within the server's {@code authentication_timeout}, or the server closes the connection.</p>
 *
 * <p>OAUTHBEARER has its own interface rather than {@link AuthenticationPlugin}, because a bearer
 * token travels in a different message than a password, and an existing plugin, which does not
 * check the request type, would hand the driver a password as the token.</p>
 */
public interface OauthTokenProvider {

  /**
   * Returns a token for OAuth authentication.
   *
   * <p>The driver zeroes the returned array once it has sent the token, so each call must return
   * a new array. An implementation that returns the same array again hands the driver a zeroed
   * array, which fails with "The OAuth bearer token contains a character that RFC 6750 section
   * 2.1 does not allow."</p>
   *
   * @param request holds the host, port, user and database of the connection attempt
   * @return the token; neither null nor empty
   * @throws PSQLException if the token cannot be obtained. The driver passes it to the caller
   *     unchanged, and wraps any other exception as "OAuth token provider {0} failed to return a
   *     token". The message and cause reach the caller and the driver's log, so they must not
   *     contain the token or a client secret.
   */
  char [] getToken(OauthTokenRequest request) throws PSQLException;

}
