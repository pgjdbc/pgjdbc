/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.sql.SQLException;
import java.util.UUID;


public class UUIDConverter {

  private UUIDConverter() {}

  public static Object getUUID(String data) throws SQLException {
    UUID uuid;
    try {
      uuid = UUID.fromString(data);
    } catch (IllegalArgumentException iae) {
      throw new PSQLException(GT.tr("Invalid UUID data."), PSQLState.INVALID_PARAMETER_VALUE, iae);
    }

    return uuid;
  }

  public static Object getUUID(byte[] data) {
    return new UUID(ByteConverter.int8(data, 0), ByteConverter.int8(data, 8));
  }

  public static Object getUUID(byte[] data, int pos) {
    return new UUID(ByteConverter.int8(data, pos + 0), ByteConverter.int8(data, pos + 8));
  }
}
