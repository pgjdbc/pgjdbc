/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.codec.CompositeCodec;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;

/**
 * Text format SQLInput implementation.
 *
 * <p>Reads text-encoded composite data. Unlike {@link PgSQLInputBinary}, this reader parses the whole
 * {@code (a,b,"c,d")} literal into its attributes up front and serves each {@code readXxx} from that
 * array. It does not stream field by field, and deliberately so: the binary wire is self-describing
 * (a fixed {@code oid}/{@code length} header per field lets a cursor jump to the next field), whereas
 * the text form has no length prefix — finding a field boundary means scanning for the next
 * comma while tracking quoting and backslash escapes, so the parser has to walk the literal
 * regardless. And a text field is unquoted into a fresh {@code String} whatever the reader does, so a
 * streaming variant would not save the per-field allocation the binary cursor avoids by decoding a
 * slice in place. The parse-then-serve model therefore costs little more than an on-demand one here.
 * For an allocation-sensitive composite read path, prefer the binary format.</p>
 *
 * <p>A field's codec is resolved once, in {@link #advanceIsNull()}, the moment the reader reaches it -
 * and only if it turns out non-null, since a null field is never decoded.</p>
 */
public final class PgSQLInputText extends PgSQLInput {

  /**
   * The parsed attribute values, one per field (null for SQL NULL).
   */
  private final @Nullable String[] values;

  /**
   * The codec for the current field (the one just entered by {@link #advanceIsNull()}). Valid only
   * right after a non-null {@link #advanceIsNull()}.
   */
  private @Nullable TextCodec currentCodec;

  /**
   * Creates a new PgSQLInputText from a composite text string.
   *
   * @param compositeData the text representation "(val1,val2,...)"
   * @param type the composite type
   * @param ctx the codec context
   */
  public PgSQLInputText(String compositeData, PgType type, PgCodecContext ctx)
      throws SQLException {
    this(CompositeCodec.parseCompositeText(compositeData), type, ctx);
  }

  /**
   * Creates a new PgSQLInputText from pre-parsed attribute values.
   *
   * @param attributeValues the parsed attribute values (null for SQL NULL)
   * @param type the composite type
   * @param ctx the codec context
   */
  public PgSQLInputText(@Nullable String[] attributeValues, PgType type, PgCodecContext ctx)
      throws SQLException {
    super(type, ctx);
    this.values = attributeValues;
  }

  @Override
  protected boolean advanceIsNull() throws SQLException {
    int i = fieldIndex - 1;
    // A composite value with fewer attributes than the type declares reads the trailing fields back as
    // NULL, matching the previous behaviour.
    if (i >= values.length || values[i] == null) {
      return true;
    }
    currentCodec = castNonNull(ctx.resolveTextCodec(fields.get(i).getTypeOid()));
    return false;
  }

  /**
   * Gets the codec for the current field.
   */
  private TextCodec getCodec() {
    return castNonNull(currentCodec);
  }

  /**
   * Gets the current field's text. Called only for a non-null field, so the value is never null here.
   */
  private String currentValue() {
    return castNonNull(values[fieldIndex - 1]);
  }

  @Override
  protected int decodeInt() throws SQLException {
    return PrimitiveDecoders.asInt(getCodec(), currentValue(), getCurrentType(), ctx);
  }

  @Override
  protected long decodeLong() throws SQLException {
    return PrimitiveDecoders.asLong(getCodec(), currentValue(), getCurrentType(), ctx);
  }

  @Override
  protected double decodeDouble() throws SQLException {
    return PrimitiveDecoders.asDouble(getCodec(), currentValue(), getCurrentType(), ctx);
  }

  @Override
  protected float decodeFloat() throws SQLException {
    return PrimitiveDecoders.asFloat(getCodec(), currentValue(), getCurrentType(), ctx);
  }

  @Override
  protected boolean decodeBoolean() throws SQLException {
    return PrimitiveDecoders.asBoolean(getCodec(), currentValue(), getCurrentType(), ctx);
  }

  @Override
  protected @Nullable String decodeString() throws SQLException {
    return getCodec().decodeAsString(currentValue(), getCurrentType(), ctx);
  }

  @Override
  protected @Nullable BigDecimal decodeBigDecimal() throws SQLException {
    return PrimitiveDecoders.asBigDecimal(getCodec(), currentValue(), getCurrentType(), ctx);
  }

  @Override
  protected byte @Nullable [] decodeBytes() throws SQLException {
    TypeDescriptor type = getCurrentType();
    Object value = getCodec().decodeText(currentValue(), type, ctx);
    if (value == null) {
      return null;
    }
    if (value instanceof byte[]) {
      return (byte[]) value;
    }
    // Only byte[]-valued types (bytea) yield bytes. Refuse the rest rather than coercing to the
    // value's string bytes, matching the binary adapter and PostgreSQL, which has no cast to bytea.
    throw Codecs.cannotDecode(value, "byte[]");
  }

  @Override
  protected @Nullable Date decodeDate() throws SQLException {
    return getCodec().decodeTextAs(currentValue(), getCurrentType(), Date.class, ctx);
  }

  @Override
  protected @Nullable Time decodeTime() throws SQLException {
    return getCodec().decodeTextAs(currentValue(), getCurrentType(), Time.class, ctx);
  }

  @Override
  protected @Nullable Timestamp decodeTimestamp() throws SQLException {
    return getCodec().decodeTextAs(currentValue(), getCurrentType(), Timestamp.class, ctx);
  }

  @Override
  protected @Nullable Object decodeObject() throws SQLException {
    // Honor only the explicit JDBC typeMap here. If no explicit mapping is
    // present, return the codec's default Java type so SPI-provided codecs can
    // surface their own Java objects instead of being forced through the
    // legacy PGobject registry.
    TypeDescriptor currentType = getCurrentType();
    Class<?> mapped = ctx.getTypeMap().get(currentType.getFullName());
    if (mapped == null) {
      mapped = ctx.getTypeMap().get(currentType.getTypeName().getName());
    }
    if (mapped != null) {
      return getCodec().decodeTextAs(currentValue(), currentType, mapped, ctx);
    }
    return getCodec().decodeText(currentValue(), currentType, ctx);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected <T> @Nullable T decodeObjectAs(Class<T> type) throws SQLException {
    return getCodec().decodeTextAs(currentValue(), getCurrentType(), type, ctx);
  }

  @Override
  protected Array decodeArray() throws SQLException {
    // A nested array materializes a connection-bound PgArray; offline reports a clear limitation.
    return new PgArray(ctx.requireConnection(getCurrentType()), getCurrentType().getOid(), currentValue());
  }
}
