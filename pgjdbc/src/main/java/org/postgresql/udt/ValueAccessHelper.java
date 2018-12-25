/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.PGStatement;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Encoding;
import org.postgresql.core.Utils;
import org.postgresql.jdbc.PgResultSet;
import org.postgresql.util.GT;
import org.postgresql.util.HStoreConverter;
import org.postgresql.util.NumberConverter;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.UUIDConverter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
//#endif
import java.util.Calendar;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Base implementation of {@link ValueAccess}.
 */
public class ValueAccessHelper {

  private ValueAccessHelper() {}

  private static final Logger LOGGER = Logger.getLogger(ValueAccessHelper.class.getName());

  /**
   * Avoid auto-boxing of Double.NaN.  Escape analysis would probably do this, but
   * no harm in being clear here.
   */
  private static final Double DOUBLE_NAN = Double.NaN;

  /**
   * @param connection the current connection
   * @param rsType the current {@link Statement#getResultSetType() result set type}
   * @param access the value access
   * @param sqlType the current {@link Types SQL type}
   * @param pgType the base type
   * @param scale the scale or {@code -1} for default
   * @param allowNaN enables parsing of "NaN" to {@link #DOUBLE_NAN}
   * @return either {@code null}, a {@link BigDecimal}, or {@link #DOUBLE_NAN}.
   * @throws SQLException if something wrong happens
   */
  public static Number getNumeric(BaseConnection connection, int rsType, ValueAccess access, int sqlType, String pgType, int scale, boolean allowNaN) throws SQLException {
    if (access.isBinary()) {
      if (sqlType != Types.NUMERIC && sqlType != Types.DECIMAL) {
        Object obj = internalGetObject(connection, rsType, access, sqlType, pgType, -1);
        if (obj == null) {
          return null;
        }
        if (obj instanceof Long || obj instanceof Integer || obj instanceof Byte) { // TODO: Why not Short, or all Number other than BigDecimal/BigInteger?
          BigDecimal res = BigDecimal.valueOf(((Number) obj).longValue());
          res = NumberConverter.scaleBigDecimal(res, scale);
          return res;
        }
        return NumberConverter.toBigDecimal(NumberConverter.trimMoney(String.valueOf(obj)), scale);
      }
    }

    Encoding encoding = connection.getEncoding();
    if (encoding.hasAsciiNumbers()) {
      try {
        // TODO: access doesn't necessary have getBytes implementation
        byte[] bytes = access.getBytes();
        if (bytes == null) {
          return null;
        }
        BigDecimal res = NumberConverter.getFastBigDecimal(bytes);
        // TODO: Pass scale to getFastBigDecimal instead of doing in two steps?
        res = NumberConverter.scaleBigDecimal(res, scale);
        return res;
      } catch (NumberFormatException ignore) {
        assert ignore == NumberConverter.FAST_NUMBER_FAILED;
      }
    }

    String stringValue = NumberConverter.trimMoney(access.getString());
    if (allowNaN && "NaN".equalsIgnoreCase(stringValue)) {
      return DOUBLE_NAN;
    }
    return NumberConverter.toBigDecimal(stringValue, scale);
  }

  public static Object internalGetObject(BaseConnection connection, int rsType, ValueAccess access, int sqlType, String pgType, int scale) throws SQLException {
    switch (sqlType) {
      case Types.BOOLEAN:
      case Types.BIT:
        return access.getBoolean();
      case Types.SQLXML:
        return access.getSQLXML();
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
        return access.getInt();
      case Types.BIGINT:
        return access.getLong();
      case Types.NUMERIC:
      case Types.DECIMAL:
        return getNumeric(connection, rsType, access, sqlType, pgType,
            scale, true);
      case Types.REAL:
        return access.getFloat();
      case Types.FLOAT:
      case Types.DOUBLE:
        return access.getDouble();
      case Types.CHAR:
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
        return access.getString();
      case Types.DATE:
        return access.getDate();
      case Types.TIME:
        return access.getTime();
      case Types.TIMESTAMP:
        return access.getTimestamp();
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        // TODO: access doesn't necessary have getBytes implementation
        return access.getBytes();
      case Types.ARRAY:
        return access.getArray();
      case Types.CLOB:
        return access.getClob();
      case Types.BLOB:
        return access.getBlob();

      default:

        // if the backend doesn't know the type then coerce to String
        if (pgType.equals("unknown")) {
          return access.getString();
        }

        if (pgType.equals("uuid")) {
          if (access.isBinary()) {
            // TODO: A getUUID method on ValueAccess?
            return UUIDConverter.getUUID(access.getBytes());
          }
          return UUIDConverter.getUUID(access.getString());
        }

        // Specialized support for ref cursors is neater.
        if (pgType.equals("refcursor")) {
          // Fetch all results.
          String cursorName = access.getString();

          StringBuilder sb = new StringBuilder("FETCH ALL IN ");
          Utils.escapeIdentifier(sb, cursorName);

          // nb: no BEGIN triggered here. This is fine. If someone
          // committed, and the cursor was not holdable (closing the
          // cursor), we avoid starting a new xact and promptly causing
          // it to fail. If the cursor *was* holdable, we don't want a
          // new xact anyway since holdable cursor state isn't affected
          // by xact boundaries. If our caller didn't commit at all, or
          // autocommit was on, then we wouldn't issue a BEGIN anyway.
          //
          // We take the scrollability from the statement, but until
          // we have updatable cursors it must be readonly.
          ResultSet rs =
              connection.execSQLQuery(sb.toString(), rsType, ResultSet.CONCUR_READ_ONLY);
          //
          // In long running transactions these backend cursors take up memory space
          // we could close in rs.close(), but if the transaction is closed before the result set,
          // then
          // the cursor no longer exists

          sb.setLength(0);
          sb.append("CLOSE ");
          Utils.escapeIdentifier(sb, cursorName);
          connection.execSQLUpdate(sb.toString());
          ((PgResultSet) rs).setRefCursor(cursorName);
          return rs;
        }
        if ("hstore".equals(pgType)) {
          if (access.isBinary()) {
            // TODO: A getHstore method on ValueAccess?
            return HStoreConverter.fromBytes(access.getBytes(), connection.getEncoding());
          }
          return HStoreConverter.fromString(access.getString());
        }

        // Caller determines what to do (JDBC3 overrides in this case)
        return null;
    }
  }

  // TODO: Should this accept a scale when ultimately coming from a CallableStatement?
  public static Object getObject(BaseConnection connection, int rsType, ValueAccess access, int sqlType, String pgType, UdtMap udtMap, PSQLState conversionNotSupported) throws SQLException {
    Map<String, Class<?>> map = udtMap.getTypeMap();
    LOGGER.log(Level.FINEST, "  map: {0}", map);
    if (!map.isEmpty()) {
      Class<?> customType = map.get(pgType);
      if (customType != null) {
        if (LOGGER.isLoggable(Level.FINER)) {
          LOGGER.log(Level.FINER, "  Found custom type: {0} -> {1}", new Object[] {pgType, customType.getName()});
        }
        return SingleAttributeSQLInputHelper.getObjectCustomType(
            udtMap, pgType, customType, access.getSQLInput(udtMap));
      }
    }

    Object result = internalGetObject(connection, rsType, access, sqlType, pgType, -1);
    if (result != null) {
      return result;
    }

    return access.getPGobject(pgType);
  }

  // TODO: Support enums value Enum.valueOf?
  public static <T> T getObject(ValueAccess access, int sqlType, String pgType, Class<T> type, UdtMap udtMap, PSQLState conversionNotSupported) throws SQLException {
    if (type == null) {
      throw new SQLException("type is null");
    }
    // TODO: Should we check for custom types first, in case they override a base type?
    //       This could then check is assignable to the requested type, throwing can't coerce otherwise
    //       Then can use this implementation to get the actual objects.  Create a failing test case
    //       first should we make this change.
    if (type == BigDecimal.class) {
      if (sqlType == Types.NUMERIC || sqlType == Types.DECIMAL) {
        return type.cast(access.getBigDecimal());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == String.class) {
      if (sqlType == Types.CHAR || sqlType == Types.VARCHAR) {
        return type.cast(access.getString());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Boolean.class) {
      if (sqlType == Types.BOOLEAN || sqlType == Types.BIT) {
        boolean booleanValue = access.getBoolean();
        if (access.wasNull()) {
          return null;
        }
        return type.cast(booleanValue);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Short.class) {
      if (sqlType == Types.SMALLINT) {
        short shortValue = access.getShort();
        if (access.wasNull()) {
          return null;
        }
        return type.cast(shortValue);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Integer.class) {
      if (sqlType == Types.INTEGER || sqlType == Types.SMALLINT) {
        int intValue = access.getInt();
        if (access.wasNull()) {
          return null;
        }
        return type.cast(intValue);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Long.class) {
      if (sqlType == Types.BIGINT) {
        long longValue = access.getLong();
        if (access.wasNull()) {
          return null;
        }
        return type.cast(longValue);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == BigInteger.class) {
      if (sqlType == Types.BIGINT) {
        long longValue = access.getLong();
        if (access.wasNull()) {
          return null;
        }
        return type.cast(BigInteger.valueOf(longValue));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Float.class) {
      if (sqlType == Types.REAL) {
        float floatValue = access.getFloat();
        if (access.wasNull()) {
          return null;
        }
        return type.cast(floatValue);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Double.class) {
      if (sqlType == Types.FLOAT || sqlType == Types.DOUBLE) {
        double doubleValue = access.getDouble();
        if (access.wasNull()) {
          return null;
        }
        return type.cast(doubleValue);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Date.class) {
      if (sqlType == Types.DATE) {
        return type.cast(access.getDate());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Time.class) {
      if (sqlType == Types.TIME) {
        return type.cast(access.getTime());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Timestamp.class) {
      if (sqlType == Types.TIMESTAMP
              //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
              || sqlType == Types.TIMESTAMP_WITH_TIMEZONE
      //#endif
      ) {
        return type.cast(access.getTimestamp());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Calendar.class) {
      if (sqlType == Types.TIMESTAMP
              //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
              || sqlType == Types.TIMESTAMP_WITH_TIMEZONE
      //#endif
      ) {
        return type.cast(access.getCalendar());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Blob.class) {
      if (sqlType == Types.BLOB || sqlType == Types.BINARY || sqlType == Types.BIGINT) {
        return type.cast(access.getBlob());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Clob.class) {
      if (sqlType == Types.CLOB || sqlType == Types.BIGINT) {
        return type.cast(access.getClob());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == java.util.Date.class) {
      if (sqlType == Types.TIMESTAMP) {
        Timestamp timestamp = access.getTimestamp();
        if (access.wasNull()) {
          return null;
        }
        return type.cast(new java.util.Date(timestamp.getTime()));
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == Array.class) {
      if (sqlType == Types.ARRAY) {
        return type.cast(access.getArray());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == SQLXML.class) {
      if (sqlType == Types.SQLXML) {
        return type.cast(access.getSQLXML());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == UUID.class) {
      return type.cast(access.getObject(udtMap));
    } else if (type == InetAddress.class) {
      Object addressString = access.getObject(udtMap);
      if (addressString == null) {
        return null;
      }
      try {
        return type.cast(InetAddress.getByName(((PGobject) addressString).getValue()));
      } catch (UnknownHostException e) {
        throw new SQLException("could not create inet address from string '" + addressString + "'");
      }
      // JSR-310 support
      //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
    } else if (type == LocalDate.class) {
      if (sqlType == Types.DATE) {
        Date dateValue = access.getDate();
        if (access.wasNull()) {
          return null;
        }
        long time = dateValue.getTime();
        if (time == PGStatement.DATE_POSITIVE_INFINITY) {
          return type.cast(LocalDate.MAX);
        }
        if (time == PGStatement.DATE_NEGATIVE_INFINITY) {
          return type.cast(LocalDate.MIN);
        }
        return type.cast(dateValue.toLocalDate());
      } else if (sqlType == Types.TIMESTAMP) {
        LocalDateTime localDateTimeValue = access.getLocalDateTime();
        if (access.wasNull()) {
          return null;
        }
        return type.cast(localDateTimeValue.toLocalDate());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == LocalTime.class) {
      if (sqlType == Types.TIME) {
        return type.cast(access.getLocalTime());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == LocalDateTime.class) {
      if (sqlType == Types.TIMESTAMP) {
        return type.cast(access.getLocalDateTime());
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
    } else if (type == OffsetDateTime.class) {
      if (sqlType == Types.TIMESTAMP_WITH_TIMEZONE || sqlType == Types.TIMESTAMP) {
        Timestamp timestampValue = access.getTimestamp();
        if (access.wasNull()) {
          return null;
        }
        long time = timestampValue.getTime();
        if (time == PGStatement.DATE_POSITIVE_INFINITY) {
          return type.cast(OffsetDateTime.MAX);
        }
        if (time == PGStatement.DATE_NEGATIVE_INFINITY) {
          return type.cast(OffsetDateTime.MIN);
        }
        // Postgres stores everything in UTC and does not keep original time zone
        OffsetDateTime offsetDateTime = OffsetDateTime.ofInstant(timestampValue.toInstant(), ZoneOffset.UTC);
        return type.cast(offsetDateTime);
      } else {
        throw new PSQLException(GT.tr("conversion to {0} from {1} not supported", type, sqlType),
                conversionNotSupported);
      }
      //#endif
    } else if (PGobject.class.isAssignableFrom(type)) {
      return type.cast(access.getPGobject(type.asSubclass(PGobject.class)));
    } else {
      // In the wacky case an object both extends PGobject and implements SQLData, the PGobject implementation
      // takes precedence for backwards compatibility.
      Map<String, Class<?>> typemap = udtMap.getTypeMap();
      LOGGER.log(Level.FINEST, "  typemap: {0}", typemap);
      if (!typemap.isEmpty()) {
        Class<?> customType = typemap.get(pgType);
        if (customType != null) {
          if (LOGGER.isLoggable(Level.FINER)) {
            LOGGER.log(Level.FINER, "  Found custom type without needing inference: {0} -> {1}", new Object[] {pgType, customType.getName()});
          }
          // Direct match, as expected - no fancy workarounds required
          if (type.isAssignableFrom(customType)) {
            return type.cast(
                SingleAttributeSQLInputHelper.getObjectCustomType(
                    udtMap, pgType, customType, access.getSQLInput(udtMap)));
          } else {
            throw new PSQLException(GT.tr("Customized type from map {0} -> {1} is not assignable to requested type {2}", pgType, customType.getName(), type.getName()),
                    conversionNotSupported);
          }
        }
        // It is an issue that a DOMAIN type is sent from the backend with its oid of non-domain type, which makes this pgType not match
        // what is expected.  For example, our "Email" DOMAIN is currently oid 4015336, but it is coming back as 25 "text".
        // This is tested on PostgreSQL 9.4.
        //
        // Digging deeper, this seems to be central to the PostgreSQL protocol, including mentions in other client implementations:
        // http://php.net/manual/en/function.pg-field-type-oid.php
        //
        // To work around this issue, we are performing some rudimentary type inference by first finding
        // all type mappings to the requested class, then any type mappings to any subclass or,
        // in the case of an interface, implementations.
        //
        // See https://github.com/pgjdbc/pgjdbc/issues/641 for more details

        String inferredPgType;
        Class<? extends T> inferredClass;
        // First check for direct inference (exact match to type map)
        Set<String> directTypes = udtMap.getInvertedDirect(type);
        int size = directTypes.size();
        if (size == 1) {
          inferredPgType = directTypes.iterator().next();
          inferredClass = type;
          if (LOGGER.isLoggable(Level.FINER)) {
            LOGGER.log(Level.FINER, "  Found single match in direct inverted map, using as inferred type: {0} -> {1}", new Object[] {inferredPgType, inferredClass.getName()});
          }
        } else if (size > 1) {
          // Sort types for easier reading
          Set<String> sortedTypes = new TreeSet<String>(directTypes);
          LOGGER.log(Level.FINE, "  sortedTypes: {0}", sortedTypes);
          throw new PSQLException(GT.tr("Unable to infer type: more than one type directly maps to {0}: {1}", type, sortedTypes.toString()),
                  conversionNotSupported);
        } else {
          // Now check for inherited inference (matches the type or any subclass/implementation of it)
          Set<String> inheritedTypes = udtMap.getInvertedInherited(type);
          size = inheritedTypes.size();
          if (size == 1) {
            inferredPgType = inheritedTypes.iterator().next();
            // We've worked backward to a mapped pgType, now lookup which specific
            // class this pgType is mapped to:
            Class<?> inferredClassUnbounded = typemap.get(inferredPgType);
            // There is a slight race condition: inferred type might have been just
            // added and was not known when this method retrieved the typemap.
            if (inferredClassUnbounded == null) {
              LOGGER.log(Level.FINE, "  Found single match in inherited inverted map, but missing in typemap (type just added by concurrent thread?), ignoring: {0}", inferredPgType);
              inferredPgType = null;
              inferredClass = null;
            } else {
              inferredClass = inferredClassUnbounded.asSubclass(type);
              if (LOGGER.isLoggable(Level.FINER)) {
                LOGGER.log(Level.FINER, "  Found single match in inherited inverted map, using as inferred type: {0} -> {1}", new Object[] {inferredPgType, inferredClass.getName()});
              }
            }
          } else if (size > 1) {
            // Sort types for easier reading
            Set<String> sortedTypes = new TreeSet<String>(inheritedTypes);
            LOGGER.log(Level.FINE, "  sortedTypes: {0}", sortedTypes);
            throw new PSQLException(GT.tr("Unable to infer type: more than one type maps to {0}: {1}", type, sortedTypes.toString()),
                    conversionNotSupported);
          } else {
            inferredPgType = null;
            inferredClass = null;
          }
        }
        if (inferredPgType != null) {
          return SingleAttributeSQLInputHelper.getObjectCustomType(
              udtMap, inferredPgType, inferredClass, access.getSQLInput(udtMap));
        }
      }
    }
    throw new PSQLException(GT.tr("conversion to {0} from {1} ({2}) not supported", type, sqlType, pgType),
            conversionNotSupported);
  }
}
