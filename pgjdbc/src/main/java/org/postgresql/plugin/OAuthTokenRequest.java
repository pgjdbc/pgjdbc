/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.plugin;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Context passed to {@link OAuthTokenProvider#getToken(OAuthTokenRequest)} to
 * provide information about the authentication request.
 */
public class OAuthTokenRequest {

  private final String host;
  private final int port;
  private final String user;
  private final @Nullable String database;
  private final @Nullable String discoveryUrl;
  private final @Nullable String scope;
  private final @Nullable String clientId;

  public OAuthTokenRequest(String host, int port, String user, @Nullable String database,
      @Nullable String discoveryUrl, @Nullable String scope, @Nullable String clientId) {
    this.host = host;
    this.port = port;
    this.user = user;
    this.database = database;
    this.discoveryUrl = discoveryUrl;
    this.scope = scope;
    this.clientId = clientId;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public String getUser() {
    return user;
  }

  public @Nullable String getDatabase() {
    return database;
  }

  /**
   * The OpenID Connect discovery URL, if provided by the server in a prior
   * authentication failure response or configured by the user.
   */
  public @Nullable String getDiscoveryUrl() {
    return discoveryUrl;
  }

  /**
   * The OAuth scope requested by the server or configured by the user.
   */
  public @Nullable String getScope() {
    return scope;
  }

  /**
   * The OAuth client ID configured by the user.
   */
  public @Nullable String getClientId() {
    return clientId;
  }
}
