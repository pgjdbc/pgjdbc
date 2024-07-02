/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public class PgStruct implements Struct {
  // if at least one of \ " ( ) (white space) , is present in the string, we need to quote the attribute value
  private static final Pattern needQuotesPattern = Pattern.compile("[\\\"()\\s,]");

  private final String sqlTypeName;
  private Object[] attributes = new Object[0];

  private final PgStructField[] structFields;
  private final TimestampUtils timestampUtils;
  private final Charset charset;

  public PgStruct(String type, PgStructField[] structFields, BaseConnection connection) {
    this(type, structFields, connection.getTimestampUtils(), Charset.forName(connection.getQueryExecutor().getEncoding().name()));
  }

  private PgStruct(String type, PgStructField[] structFields, TimestampUtils timestampUtils, Charset charset) {
    this.sqlTypeName = type;
    this.structFields = structFields;
    this.timestampUtils = timestampUtils;
    this.charset = charset;
  }

  public Struct withAttributes(Object[] attributes) throws SQLException {
    PgStruct struct = new PgStruct(sqlTypeName, structFields, timestampUtils, charset);
    for (int i = 0; i < attributes.length; i++) {
      if (attributes[i] == null) {
        continue;
      }
      int oid = structFields[i].getOID();
      if (oid == Oid.DATE) {
        attributes[i] = timestampUtils.toDate(timestampUtils.getSharedCalendar(null), attributes[i].toString());
      } else if (oid == Oid.TIME) {
        attributes[i] = timestampUtils.toTime(timestampUtils.getSharedCalendar(null), attributes[i].toString());
      } else if (oid == Oid.TIMESTAMP || oid == Oid.TIMESTAMPTZ) {
        attributes[i] = timestampUtils.toTimestamp(timestampUtils.getSharedCalendar(null), attributes[i].toString());
      } else {
        attributes[i] = JavaObjectResolver.tryResolveObject(attributes[i], oid);
      }
    }
    struct.attributes = attributes;
    return struct;
  }

  @Override
  public String getSQLTypeName() {
    return sqlTypeName;
  }

  @Override
  public Object[] getAttributes() {
    return attributes;
  }

  public PgStructField[] getFields() {
    return structFields;
  }

  @Override
  public Object[] getAttributes(Map<String, Class<?>> map) throws SQLException {
    Object[] newAttributes = new Object[attributes.length];
    for (int i = 0; i < attributes.length; i++) {
      String type = structFields[i].getTypeName();
      Class<?> javaType = map.get(type);
      if (javaType == null) {
        // I guess if no type is found in the user mapping, we just return the attribute as is?
        newAttributes[i] = attributes[i];
        continue;
      }
      newAttributes[i] = getAttributeAs(attributes[i], javaType);
    }
    return newAttributes;
  }

  private @Nullable Object getAttributeAs(@Nullable Object attribute, Class<?> javaType) throws SQLException {
    if (attribute == null || javaType.isInstance(attribute)) {
      return attribute;
    }

    String value = String.valueOf(attribute);
    switch (javaType.getName()) {
      case "java.lang.Integer":
        return PgResultSet.toInt(value);
      case "java.lang.Long":
        return PgResultSet.toLong(value);
      case "java.lang.Double":
        return PgResultSet.toDouble(value);
      case "java.lang.BigDecimal":
        return PgResultSet.toBigDecimal(value);
      case "java.lang.String":
        return value;
      case "java.lang.Boolean":
        return BooleanTypeUtil.castToBoolean(value);
      case "java.util.Date":
        return timestampUtils.toTimestamp(null, value);
      default:
        throw new PSQLException(
            GT.tr("Unsupported conversion to {1}.", javaType.getName()),
            null);
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("(");
    for (int i = 0; i < structFields.length; i++) {
      if (attributes[i] == null || attributes[i].toString() == null) {
        if (i < structFields.length - 1) {
          sb.append(",");
        }
        continue;
      }

      int oid = structFields[i].getOID();
      String s;
      if (oid == Oid.BYTEA && attributes[i] instanceof byte[]) {
        s = new String((byte[]) attributes[i], charset);
      } else {
        s = attributes[i].toString();
      }

      if (attributes[i] instanceof PgStruct) {
        // escape chars
        s = s.replace("\"", "\"\"");
        s = s.replace("\\", "\\\\");
      } else if (oid == Oid.JSON) {
        s = s.replace("\"", "\\\"");
      } else if (oid == Oid.BIT && attributes[i] instanceof Boolean) {
        s = ((Boolean) attributes[i]) ? "1" : "0";
      }

      boolean needQuotes = needQuotesPattern.matcher(s).find();

      if (needQuotes) {
        sb.append("\"");
      }
      sb.append(s);
      if (needQuotes) {
        sb.append("\"");
      }

      if (i < structFields.length - 1) {
        sb.append(",");
      }
    }
    sb.append(")");
    return sb.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PgStruct pgStruct = (PgStruct) o;
    return Objects.equals(sqlTypeName, pgStruct.sqlTypeName) && Objects.deepEquals(attributes, pgStruct.attributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sqlTypeName, Arrays.hashCode(attributes));
  }
}
