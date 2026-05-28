/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.plugin;

import org.postgresql.util.PSQLException;

/**
 * Provides an OAuth 2.0 token for OAuth bearer authentication.
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
   * @param request holds the information that may be needed to obtain a token.
   *                Individual fields may be null when not yet known; implementers
   *                should ignore fields they do not need.
   * @return the token; must not be null
   * @throws PSQLException if the token cannot be obtained
   */
  char [] getToken(OAuthTokenRequest request) throws PSQLException;

}
