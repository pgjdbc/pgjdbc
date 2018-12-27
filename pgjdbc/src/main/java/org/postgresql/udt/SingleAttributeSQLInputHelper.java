/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.EnumMode;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;


/**
 * Helper for implementing {@link SingleAttributeSQLInput}.
 */
// TODO: Rename SQLInputHelper?  Combine with ValueAccessHelper, and rename it to ObjectConverter?
public class SingleAttributeSQLInputHelper {

  private SingleAttributeSQLInputHelper() {}

  /**
   * Instantiates a {@link SQLData} and reads it from the given {@link SingleAttributeSQLInput}.
   * At this time, only single attribute {@link SQLData} is supported.
   *
   * @param udtMap the current user-defined data types
   * @param type the backend typename
   * @param sqlDataType the class of the custom type to be returned
   * @param in the field source for the custom type
   * @return an appropriate object; never null.
   * @throws SQLException if something goes wrong
   */
  // TODO: udtMap is unused
  public static SQLData readSQLData(UdtMap udtMap, String type, Class<? extends SQLData> sqlDataType, SingleAttributeSQLInput in) throws SQLException {
    // An extremely simple implementation for scalar values only (not composite types)
    // This is useful for DOMAIN (and possibly ENUM?) values mapping onto SQLData
    try {
      SQLData sqlData = sqlDataType.newInstance();
      sqlData.readSQL(in, type);
      if (!in.getReadDone()) {
        throw new PSQLException(GT.tr(
            "No attributes read by SQLData instance of {0}",
            sqlDataType.getName()), PSQLState.DATA_ERROR);
      }
      return sqlData;
    } catch (InstantiationException e) {
      // Copying SYSTEM_ERROR used for IllegalAccessException in Parser.java
      throw new PSQLException(e.getMessage(), PSQLState.SYSTEM_ERROR, e);
    } catch (IllegalAccessException e) {
      // Copying SYSTEM_ERROR used for IllegalAccessException in Parser.java
      throw new PSQLException(e.getMessage(), PSQLState.SYSTEM_ERROR, e);
    }
  }

  /**
   * Get an {@link Enum} from the given {@link ValueAccess}
   * via a single call to {@link ValueAccess#getString()}.
   *
   * @param <T> The enum type whose constant is to be returned
   * @param enumType the {@code Class} object of the enum type from which
   *      to return a constant
   * @param access the value access
   * @return an appropriate object; possibly null.
   * @throws SQLException if something goes wrong
   *
   * @see Enum#valueOf(java.lang.Class, java.lang.String)
   */
  public static <T extends Enum<T>> T getEnum(Class<T> enumType, ValueAccess access) throws SQLException {
    // Read enum value through single call to readString()
    String value = access.getString();
    if (value == null) {
      return null;
    } else {
      try {
        return Enum.valueOf(enumType, value);
      } catch (IllegalArgumentException e) {
        throw new PSQLException(GT.tr("Enum value not found for type {0}: {1}", enumType.getName(), value),
            PSQLState.DATA_ERROR, e); // TODO: Review: Is this the most appropriate PSQLState?
      }
    }
  }

  /**
   * Reads an {@link Enum} from the given {@link SQLInput}
   * via a single call to {@link SQLInput#readString()}.
   *
   * @param <T> The enum type whose constant is to be returned
   * @param enumType the {@code Class} object of the enum type from which
   *      to return a constant
   * @param in the field source for the custom type
   * @return an appropriate object; possibly null.
   * @throws SQLException if something goes wrong
   *
   * @see Enum#valueOf(java.lang.Class, java.lang.String)
   */
  public static <T extends Enum<T>> T readEnum(Class<T> enumType, SQLInput in) throws SQLException {
    // Read enum value through single call to readString()
    String value = in.readString();
    if (value == null) {
      return null;
    } else {
      try {
        return Enum.valueOf(enumType, value);
      } catch (IllegalArgumentException e) {
        throw new PSQLException(GT.tr("Enum value not found for type {0}: {1}", enumType.getName(), value),
            PSQLState.DATA_ERROR, e); // TODO: Review: Is this the most appropriate PSQLState?
      }
    }
  }

  /**
   * Implementation of {@link BaseConnection#getObject(java.lang.String, java.lang.String, byte[])} for custom types
   * once the custom type is known.
   *
   * <p>
   * This is present for type inference implemented by {@code org.postgresql.jdbc.PgResultSet.getObject(..., Class<T> type)}, which is
   * a consequence of the server sending base types for domains.
   * </p>
   *
   * @param <T> the custom type to be returned
   * @param udtMap the current user-defined data types
   * @param type the backend typename
   * @param enumMode the Enum mode
   * @param customType the class of the custom type to be returned
   * @param in the field source for the custom type
   * @return an appropriate object; possibly null.
   * @throws SQLException if something goes wrong
   *
   * @see  BaseConnection#getObject(java.lang.String, java.lang.String, byte[])
   * @see  org.postgresql.jdbc.PgResultSet#getObject(java.lang.String, java.lang.Class)
   * @see  org.postgresql.jdbc.PgResultSet#getObject(int, java.lang.Class)
   *
   * @see  #readEnum(java.lang.Class, java.sql.SQLInput)
   * @see  #readSQLData(org.postgresql.udt.UdtMap, java.lang.String, java.lang.Class, org.postgresql.udt.SingleAttributeSQLInput)
   */
  public static <T> T getObjectCustomType(EnumMode enumMode, UdtMap udtMap, String type, Class<? extends T> customType, SingleAttributeSQLInput in) throws SQLException {
    if ((enumMode == EnumMode.TYPEMAP || enumMode == EnumMode.ALWAYS) && Enum.class.isAssignableFrom(customType)) {
      return (T)readEnum(customType.asSubclass(Enum.class), in);
    }
    if (SQLData.class.isAssignableFrom(customType)) {
      Class<? extends SQLData> sqlDataType = customType.asSubclass(SQLData.class);
      return customType.cast(SingleAttributeSQLInputHelper.readSQLData(udtMap, type, sqlDataType, in));
    }
    // TODO: Support Struct, too
    // Unexected custom type
    throw new PSQLException(GT.tr("Custom type does not implement SQLData: {0}", customType.getName()),
        PSQLState.NOT_IMPLEMENTED);
  }
}
