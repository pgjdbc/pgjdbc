/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.plugin;

import org.postgresql.util.PSQLException;

/**
 * Plugin interface for providing OAuth bearer tokens during OAUTHBEARER SASL
 * authentication.
 *
 * <p>Implementations acquire a bearer token through whatever mechanism is
 * appropriate: reading from a cache, performing a client credentials grant,
 * initiating a device authorization flow, delegating to a cloud identity
 * service, etc.</p>
 *
 * <p>The driver calls {@link #getToken(OAuthTokenRequest)} when the server
 * requests OAUTHBEARER authentication. Implementations must return a valid,
 * non-empty bearer token string.</p>
 */
public interface OAuthTokenProvider {

  /**
   * Called when the server requests OAUTHBEARER authentication.
   *
   * @param request context about the authentication request including server
   *     host, port, user, and any discovery metadata
   * @return a valid bearer token, never null or empty
   * @throws PSQLException if a token cannot be obtained
   */
  String getToken(OAuthTokenRequest request) throws PSQLException;
}
