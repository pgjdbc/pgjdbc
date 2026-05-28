/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.plugin;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Context passed to {@link OAuthTokenProvider#getToken(OAuthTokenRequest)}.
 *
 * <p>Any field may be null or empty when the corresponding information is not yet known.
 */
public final class OAuthTokenRequest {

  private final @Nullable String username;
  private final @Nullable String issuer;
  private final @Nullable String scope;
  private final @Nullable String clientId;
  private final @Nullable String clientSecret;

  public OAuthTokenRequest(
      @Nullable String username,
      @Nullable String issuer,
      @Nullable String clientId,
      @Nullable String clientSecret,
      @Nullable String scope) {
    this.username = username;
    this.issuer = issuer;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.scope = scope;
  }

  /**
   * @return the OAuth username.
   */
  public @Nullable String getUsername() {
    return username;
  }

  /**
   * @return the OAuth issuer URL.
   */
  public @Nullable String getIssuer() {
    return issuer;
  }

  /**
   * @return the OAuth client id.
   */
  public @Nullable String getClientId() {
    return clientId;
  }

  /**
   * @return the OAuth client secret.
   */
  public @Nullable String getClientSecret() {
    return clientSecret;
  }

  /**
   * @return the OAuth token scope.
   */
  public @Nullable String getScope() {
    return scope;
  }
}
