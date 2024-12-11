/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Struct;
import java.util.Map;
import java.util.regex.Pattern;

public class PgStruct implements Struct {
  // if at least one of \ " ( ) (white space) , is present in the string, we need to quote the attribute value
  private static final Pattern NEEDS_ESCAPE_PATTERN = Pattern.compile("[\\\"()\\s,]");

  private final String sqlTypeName;
  private final PgStructDescriptor descriptor;
  private final @Nullable Object[] attributes;

  private final TimestampUtils timestampUtils;
  private final Charset charset;

  // Record value as string
  private @Nullable String fieldString;

  public PgStruct(PgStructDescriptor descriptor, @Nullable Object[] attributes, BaseConnection connection) {
    this.sqlTypeName = descriptor.sqlTypeName();
    this.descriptor = descriptor;
    this.attributes = attributes;
    this.timestampUtils = connection.getTimestampUtils();
    this.charset = Charset.forName(connection.getQueryExecutor().getEncoding().name());
  }

  @Override
  public String getSQLTypeName() {
    return sqlTypeName;
  }

  @SuppressWarnings("override.return")
  @Override
  public @Nullable Object[] getAttributes() {
    return attributes;
  }

  public PgStructDescriptor getDescriptor() {
    return descriptor;
  }

  @Override
  public Object[] getAttributes(Map<String, Class<?>> map) throws SQLException {
    Object[] newAttributes = new Object[attributes.length];
    for (int i = 0; i < attributes.length; i++) {
      Object attribute = attributes[i];
      if (attribute == null) {
        continue;
      }
      String type = descriptor.pgAttributes()[i].typeName();
      Class<?> javaType = map.get(type);
      if (javaType != null) {
        newAttributes[i] = getAttributeAs(attribute, javaType);
      } else {
        newAttributes[i] = attribute;
      }
    }
    return newAttributes;
  }

  private Object getAttributeAs(Object attribute, Class<?> javaType) throws SQLException {
    if (javaType.isInstance(attribute)) {
      // no need to convert
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
        throw new SQLFeatureNotSupportedException(
            GT.tr("Unsupported conversion to {1}.", javaType.getName()),
            PSQLState.NOT_IMPLEMENTED.getState());
    }
  }

  @Override
  public String toString() {
    if (fieldString != null) {
      return fieldString;
    }
    PgAttribute[] pgAttributes = descriptor.pgAttributes();
    StringBuilder sb = new StringBuilder();
    sb.append("(");
    for (int i = 0; i < pgAttributes.length; i++) {
      Object attribute = attributes[i];
      if (attribute == null || attributes.toString() == null) {
        if (i < pgAttributes.length - 1) {
          sb.append(",");
        }
        continue;
      }

      int oid = pgAttributes[i].oid();

      String s;
      if (oid == Oid.BYTEA && attribute instanceof byte[]) {
        s = new String((byte[]) attribute, charset);
      } else if (oid == Oid.BIT && attribute instanceof Boolean) {
        s = ((Boolean) attribute) ? "1" : "0";
      } else {
        s = attribute.toString();
      }

      boolean needsEscape = NEEDS_ESCAPE_PATTERN.matcher(s).find();
      if (needsEscape) {
        escapeStructAttribute(sb, s);
      } else {
        sb.append(s);
      }

      if (i < pgAttributes.length - 1) {
        sb.append(",");
      }
    }
    sb.append(")");
    fieldString = sb.toString();
    return fieldString;
  }

  public static void escapeStructAttribute(StringBuilder b, String s) {
    b.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\') {
        b.append(c);
      }

      b.append(c);
    }
    b.append('"');
  }
}
