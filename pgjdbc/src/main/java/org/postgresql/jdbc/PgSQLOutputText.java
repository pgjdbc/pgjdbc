/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.codec.CompositeCodec;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;

/**
 * Text format SQLOutput implementation.
 *
 * <p>Encodes values to text format using the codec infrastructure.</p>
 *
 * <p>Codecs are pre-cached at construction time for performance.</p>
 */
public final class PgSQLOutputText extends PgSQLOutput<String> {

  /**
   * Pre-cached codecs for each field.
   */
  private final TextCodec[] cachedCodecs;

  /**
   * Pre-cached field types.
   */
  private final TypeDescriptor[] cachedTypes;

  /**
   * Creates a new PgSQLOutputText.
   *
   * @param type the composite type
   * @param ctx the codec context
   */
  public PgSQLOutputText(PgType type, PgCodecContext ctx) throws SQLException {
    super(type, ctx);
    this.cachedCodecs = new TextCodec[fields.size()];
    this.cachedTypes = new TypeDescriptor[fields.size()];
    cacheCodecs();
  }

  private void cacheCodecs() throws SQLException {
    for (int i = 0; i < fields.size(); i++) {
      PgField field = fields.get(i);
      int oid = field.getTypeOid();
      // resolveType/resolveTextCodec dispatch composite/array/domain/range/enum types by
      // typtype/typcategory when the OID is not explicitly registered (dynamic OIDs for
      // user-defined types).
      cachedTypes[i] = ctx.resolveType(oid);
      cachedCodecs[i] = castNonNull(ctx.resolveTextCodec(oid));
    }
  }

  private TextCodec getCodec() {
    return cachedCodecs[fieldIndex - 1];
  }

  private TypeDescriptor getCurrentType() {
    return cachedTypes[fieldIndex - 1];
  }

  @Override
  protected String encodeInt(int value, PgType fieldType) throws SQLException {
    return getCodec().encodeText(value, getCurrentType(), ctx);
  }

  @Override
  protected String encodeLong(long value, PgType fieldType) throws SQLException {
    return getCodec().encodeText(value, getCurrentType(), ctx);
  }

  @Override
  protected String encodeDouble(double value, PgType fieldType) throws SQLException {
    return getCodec().encodeText(value, getCurrentType(), ctx);
  }

  @Override
  protected String encodeFloat(float value, PgType fieldType) throws SQLException {
    return getCodec().encodeText(value, getCurrentType(), ctx);
  }

  @Override
  protected String encodeBoolean(boolean value, PgType fieldType) throws SQLException {
    return getCodec().encodeText(value, getCurrentType(), ctx);
  }

  @Override
  protected String encodeString(String value, PgType fieldType) throws SQLException {
    return getCodec().encodeText(value, getCurrentType(), ctx);
  }

  @Override
  protected String encodeBigDecimal(BigDecimal value, PgType fieldType) throws SQLException {
    return getCodec().encodeText(value, getCurrentType(), ctx);
  }

  @Override
  protected String encodeBytes(byte[] value, PgType fieldType) throws SQLException {
    return getCodec().encodeText(value, getCurrentType(), ctx);
  }

  @Override
  protected String encodeDate(Date value, PgType fieldType) throws SQLException {
    return getCodec().encodeText(value, getCurrentType(), ctx);
  }

  @Override
  protected String encodeTime(Time value, PgType fieldType) throws SQLException {
    return getCodec().encodeText(value, getCurrentType(), ctx);
  }

  @Override
  protected String encodeTimestamp(Timestamp value, PgType fieldType) throws SQLException {
    return getCodec().encodeText(value, getCurrentType(), ctx);
  }

  @Override
  protected String encodeObject(Object value, PgType fieldType) throws SQLException {
    return getCodec().encodeText(value, getCurrentType(), ctx);
  }

  /**
   * Serializes the collected values to PostgreSQL text composite format "(val1,val2,...)".
   *
   * @return the composite text representation
   */
  public String toCompositeString() {
    List<@Nullable String> values = getAttributeValues();
    StringBuilder sb = new StringBuilder();
    sb.append('(');
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      String val = values.get(i);
      if (val != null) {
        if (CompositeCodec.needsQuoting(val)) {
          sb.append('"');
          sb.append(val.replace("\\", "\\\\").replace("\"", "\\\""));
          sb.append('"');
        } else {
          sb.append(val);
        }
      }
    }
    sb.append(')');
    return sb.toString();
  }
}
