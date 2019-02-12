/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;

/**
 * A user-defined data type implemented as a domain over text on the
 * server-side.
 */
public class Email implements SQLData {

  /**
   * The email address.
   */
  private String email;

  public Email(String email) {
    if (email == null) {
      throw new IllegalArgumentException();
    }
    this.email = email;
  }

  @Override
  public boolean equals(Object O) {
    if (!(O instanceof Email)) {
      return false;
    }
    Email other = (Email)O;
    // Assuming case-sensitive - not important to these test cases.
    // In reality, the localPart should be consider case-sensitive while the domain would be case-insensitive.
    return email.equals(other.email);
  }

  @Override
  public int hashCode() {
    return email.hashCode();
  }

  @Override
  public String toString() {
    return email;
  }

  // <editor-fold defaultstate="collapsed" desc="SQLData">
  private String sqlTypeName;

  public Email() {
  }

  @Override
  public String getSQLTypeName() {
    return sqlTypeName;
  }

  @Override
  public void writeSQL(SQLOutput stream) throws SQLException {
    stream.writeString(email);
  }

  @Override
  public void readSQL(SQLInput stream, String typeName) throws SQLException {
    if (typeName == null) {
      throw new IllegalArgumentException();
    }
    sqlTypeName = typeName;
    if (email != null) {
      throw new IllegalStateException();
    }
    email = stream.readString();
    if (email == null) {
      throw new SQLException("Null value unexpected");
    }
  }
}
