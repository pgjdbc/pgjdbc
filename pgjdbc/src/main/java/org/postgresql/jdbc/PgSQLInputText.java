/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.codec.TextCodec;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Text format SQLInput implementation.
 *
 * <p>Reads text-encoded composite data using the codec infrastructure.</p>
 *
 * <p>Codecs are pre-cached at construction time for performance - each field's
 * codec is looked up once rather than on every read operation.</p>
 */
public final class PgSQLInputText extends PgSQLInput<String> {

  /**
   * Pre-cached codecs for each field, indexed by field position.
   */
  private final TextCodec[] cachedCodecs;

  /**
   * Pre-cached PgTypes for each field, indexed by field position.
   */
  private final PgType[] cachedTypes;

  /**
   * Creates a new PgSQLInputText from a composite text string.
   *
   * @param compositeData the text representation "(val1,val2,...)"
   * @param type the composite type
   * @param ctx the codec context
   */
  public PgSQLInputText(String compositeData, PgType type, CodecContext ctx)
      throws SQLException {
    super(parseCompositeString(compositeData), type, ctx);
    this.cachedCodecs = new TextCodec[fields.size()];
    this.cachedTypes = new PgType[fields.size()];
    cacheCodecs();
  }

  /**
   * Creates a new PgSQLInputText from pre-parsed attribute values.
   *
   * @param attributeValues the parsed attribute values (null for SQL NULL)
   * @param type the composite type
   * @param ctx the codec context
   */
  public PgSQLInputText(String @Nullable [] attributeValues, PgType type, CodecContext ctx)
      throws SQLException {
    super(attributeValues, type, ctx);
    this.cachedCodecs = new TextCodec[fields.size()];
    this.cachedTypes = new PgType[fields.size()];
    cacheCodecs();
  }

  /**
   * Pre-caches codecs and types for all fields.
   */
  private void cacheCodecs() throws SQLException {
    for (int i = 0; i < fields.size(); i++) {
      PgField field = fields.get(i);
      int oid = field.getTypeOid();
      cachedTypes[i] = ctx.getTypeInfo().getPgTypeByOid(oid);
      cachedCodecs[i] = ctx.getCodecs().getTextCodec(oid);
    }
  }

  /**
   * Parses composite text format "(val1,val2,...)" into individual field values.
   */
  private static String @Nullable [] parseCompositeString(String text) {
    List<@Nullable String> values = new ArrayList<>();

    if (text.startsWith("(") && text.endsWith(")")) {
      text = text.substring(1, text.length() - 1);
    }

    int i = 0;
    int len = text.length();

    while (i <= len) {
      if (i == len) {
        // Handle trailing empty value
        if (!values.isEmpty() || len == 0) {
          values.add(null);
        }
        break;
      }

      char c = text.charAt(i);
      if (c == '"') {
        // Quoted value
        StringBuilder sb = new StringBuilder();
        i++; // skip opening quote
        while (i < len) {
          char ch = text.charAt(i);
          if (ch == '"') {
            if (i + 1 < len && text.charAt(i + 1) == '"') {
              sb.append('"');
              i += 2;
            } else {
              i++; // skip closing quote
              break;
            }
          } else if (ch == '\\' && i + 1 < len) {
            sb.append(text.charAt(i + 1));
            i += 2;
          } else {
            sb.append(ch);
            i++;
          }
        }
        values.add(sb.toString());
        if (i < len && text.charAt(i) == ',') {
          i++;
        }
      } else if (c == ',') {
        values.add(null);
        i++;
      } else {
        // Unquoted value
        int start = i;
        while (i < len && text.charAt(i) != ',') {
          i++;
        }
        values.add(text.substring(start, i));
        if (i < len && text.charAt(i) == ',') {
          i++;
        }
      }
    }

    return values.toArray(new String[0]);
  }

  /**
   * Gets the codec for the current field (the one just read by nextValue()).
   */
  private TextCodec getCodec() {
    return cachedCodecs[fieldIndex - 1];
  }

  /**
   * Gets the type for the current field.
   */
  private PgType getCurrentType() {
    return cachedTypes[fieldIndex - 1];
  }

  @Override
  protected int decodeInt(String data, PgType fieldType) throws SQLException {
    return getCodec().decodeAsInt(data, getCurrentType(), ctx);
  }

  @Override
  protected long decodeLong(String data, PgType fieldType) throws SQLException {
    return getCodec().decodeAsLong(data, getCurrentType(), ctx);
  }

  @Override
  protected double decodeDouble(String data, PgType fieldType) throws SQLException {
    return getCodec().decodeAsDouble(data, getCurrentType(), ctx);
  }

  @Override
  protected float decodeFloat(String data, PgType fieldType) throws SQLException {
    return getCodec().decodeAsFloat(data, getCurrentType(), ctx);
  }

  @Override
  protected boolean decodeBoolean(String data, PgType fieldType) throws SQLException {
    return getCodec().decodeAsBoolean(data, getCurrentType(), ctx);
  }

  @Override
  protected @Nullable String decodeString(String data, PgType fieldType) throws SQLException {
    return getCodec().decodeAsString(data, getCurrentType(), ctx);
  }

  @Override
  protected @Nullable BigDecimal decodeBigDecimal(String data, PgType fieldType)
      throws SQLException {
    return getCodec().decodeAsBigDecimal(data, getCurrentType(), ctx);
  }

  @Override
  protected byte @Nullable [] decodeBytes(String data, PgType fieldType) throws SQLException {
    PgType type = getCurrentType();
    Object value = getCodec().decodeText(data, type, ctx);
    if (value instanceof byte[]) {
      return (byte[]) value;
    }
    if (value instanceof String) {
      return ((String) value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    if (value != null) {
      return value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    return null;
  }

  @Override
  protected @Nullable Date decodeDate(String data, PgType fieldType) throws SQLException {
    PgType type = getCurrentType();
    Object value = getCodec().decodeText(data, type, ctx);
    if (value instanceof Date) {
      return (Date) value;
    }
    if (value != null) {
      return Date.valueOf(value.toString());
    }
    return null;
  }

  @Override
  protected @Nullable Time decodeTime(String data, PgType fieldType) throws SQLException {
    PgType type = getCurrentType();
    Object value = getCodec().decodeText(data, type, ctx);
    if (value instanceof Time) {
      return (Time) value;
    }
    if (value != null) {
      return Time.valueOf(value.toString());
    }
    return null;
  }

  @Override
  protected @Nullable Timestamp decodeTimestamp(String data, PgType fieldType)
      throws SQLException {
    PgType type = getCurrentType();
    Object value = getCodec().decodeText(data, type, ctx);
    if (value instanceof Timestamp) {
      return (Timestamp) value;
    }
    if (value != null) {
      return Timestamp.valueOf(value.toString());
    }
    return null;
  }

  @Override
  protected @Nullable Object decodeObject(String data, PgType fieldType) throws SQLException {
    return getCodec().decodeText(data, getCurrentType(), ctx);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected <T> @Nullable T decodeObjectAs(String data, PgType fieldType, Class<T> type)
      throws SQLException {
    return getCodec().decodeTextAs(data, getCurrentType(), type, ctx);
  }
}
