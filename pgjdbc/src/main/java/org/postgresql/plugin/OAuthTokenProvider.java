/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.plugin;

import org.postgresql.util.PSQLException;

/**
 * Provides an OAuth 2.0 token for OAuth bearer authentication.
 *
 * <p>The driver instantiates the class named by the {@code oauthTokenProviderClassName}
 * connection property, so an implementation must declare either a constructor taking a single
 * {@link java.util.Properties} argument, which receives the connection properties, or a
 * no-argument constructor.</p>
 *
 * <p>{@link #getToken(OAuthTokenRequest)} is invoked once per connection attempt and therefore
 * runs on the connect path. It is not invoked at all when the {@code oauthToken} connection
 * property is set, as that token is used directly.</p>
 */
public interface OAuthTokenProvider {

  /**
   * Returns a token for OAuth authentication.
   *
   * <p>For security reasons, the driver will wipe the contents of the array returned
   * by this method after it has been used for authentication.</p>
   *
   * <p><b>Implementers must provide a new array each time this method is invoked as
   * the previous contents will have been wiped.</b></p>
   *
   * @param request holds the information that may be needed to obtain a token. A field is null
   *                when the matching connection property is not set; implementers should ignore
   *                fields they do not need.
   * @return the token; neither null nor empty
   * @throws PSQLException if the token cannot be obtained
   */
  char [] getToken(OAuthTokenRequest request) throws PSQLException;

}
