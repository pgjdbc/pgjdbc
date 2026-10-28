/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.plugin;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Context passed to {@link OauthTokenProvider#getToken(OauthTokenRequest)}.
 */
public final class OauthTokenRequest {

  private final @Nullable String host;
  private final int port;
  private final @Nullable String user;
  private final @Nullable String database;

  private OauthTokenRequest(Builder builder) {
    this.host = builder.host;
    this.port = builder.port;
    this.user = builder.user;
    this.database = builder.database;
  }

  /**
   * Returns a builder with every field unset.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the host the driver is connecting to. With a multi-host URL, this is the host of the
   * current attempt.
   *
   * @return the host name or address, or null if not set
   */
  public @Nullable String getHost() {
    return host;
  }

  /**
   * Returns the port the driver is connecting to, on {@link #getHost()}.
   *
   * @return the port, or 0 if not set
   */
  public int getPort() {
    return port;
  }

  /**
   * Returns the database user the connection authenticates as.
   *
   * @return the user name, or null if not set
   */
  public @Nullable String getUser() {
    return user;
  }

  /**
   * Returns the name of the database the connection opens.
   *
   * @return the database name, or null if not set
   */
  public @Nullable String getDatabase() {
    return database;
  }

  /**
   * Builds an {@link OauthTokenRequest}. Every field is optional.
   */
  public static final class Builder {
    private @Nullable String host;
    private int port;
    private @Nullable String user;
    private @Nullable String database;

    private Builder() {
    }

    /**
     * Sets the host the driver is connecting to.
     *
     * @param host the host name or address
     * @return this builder
     */
    public Builder host(@Nullable String host) {
      this.host = host;
      return this;
    }

    /**
     * Sets the port the driver is connecting to.
     *
     * @param port the port
     * @return this builder
     */
    public Builder port(int port) {
      this.port = port;
      return this;
    }

    /**
     * Sets the database user.
     *
     * @param user the user name
     * @return this builder
     */
    public Builder user(@Nullable String user) {
      this.user = user;
      return this;
    }

    /**
     * Sets the database name.
     *
     * @param database the database name
     * @return this builder
     */
    public Builder database(@Nullable String database) {
      this.database = database;
      return this;
    }

    /**
     * Creates the request.
     *
     * @return a request holding the values set on this builder
     */
    public OauthTokenRequest build() {
      return new OauthTokenRequest(this);
    }
  }
}
