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
 * A user-defined data type implemented as a domain over integer on the
 * server-side, demonstrating separation of interface from implementation.
 */
public class PortImpl implements Port, SQLData {

  /**
   * The port value used when undefined.
   */
  private static final int UNDEFINED = Integer.MIN_VALUE;

  /**
   * The port number.
   */
  private int port;

  public PortImpl(int port) {
    if (port == UNDEFINED) {
      throw new IllegalArgumentException();
    }
    this.port = port;
  }

  // Java 8: Move to default method in Port interface
  @Override
  public boolean equals(Object O) {
    if (!(O instanceof Port)) {
      return false;
    }
    Port other = (Port)O;
    return getPort() == other.getPort();
  }

  // Java 8: Move to default method in Port interface
  @Override
  public int hashCode() {
    return getPort();
  }

  // Java 8: Move to default method in Port interface
  @Override
  public int compareTo(Port other) {
    int diff;
    //#if mvn.project.property.postgresql.jdbc.spec < "JDBC4.1"
    if (getPort() < other.getPort()) {
      diff = -1;
    } else if (getPort() > other.getPort()) {
      diff = 1;
    } else {
      diff = 0;
    }
    //#else
    diff = Integer.compare(getPort(), other.getPort());
    //#endif
    return diff;
  }

  // Java 8: Move to default method in Port interface
  @Override
  public String toString() {
    return Integer.toString(getPort());
  }

  @Override
  public int getPort() {
    return port;
  }

  // <editor-fold defaultstate="collapsed" desc="SQLData">
  public static final String SQL_TYPE = "\"Port\"";

  public PortImpl() {
    this.port = UNDEFINED;
  }

  @Override
  public String getSQLTypeName() {
    return SQL_TYPE;
  }

  @Override
  public void writeSQL(SQLOutput stream) throws SQLException {
    stream.writeInt(port);
  }

  @Override
  public void readSQL(SQLInput stream, String typeName) throws SQLException {
    if (port != UNDEFINED) {
      throw new IllegalStateException();
    }
    port = stream.readInt();
    if (stream.wasNull()) {
      throw new SQLException("Null value unexpected");
    }
  }
}
