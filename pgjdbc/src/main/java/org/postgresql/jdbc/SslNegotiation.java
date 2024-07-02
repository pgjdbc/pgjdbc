/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

public enum SslNegotiation {
  POSTGRES("postgres"),
  DIRECT("direct");

  private final String value;

  SslNegotiation(String value) {
    this.value = value;
  }

  public static SslNegotiation of(String mode) {
    for (SslNegotiation sslNegotiation : values()) {
      if (sslNegotiation.value.equals(mode)) {
        return sslNegotiation;
      }
    }
    return POSTGRES;
  }

  public String value() {
    return value;
  }
}
