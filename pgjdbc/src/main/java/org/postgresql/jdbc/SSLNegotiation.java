package org.postgresql.jdbc;

public enum SSLNegotiation {
  POSTGRES("postgres"),
  DIRECT("direct");

  private final String value;

  SSLNegotiation(String value) {
    this.value = value;
  }

  public static SSLNegotiation of(String mode) {
    for (SSLNegotiation sslNegotiation : values()) {
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
