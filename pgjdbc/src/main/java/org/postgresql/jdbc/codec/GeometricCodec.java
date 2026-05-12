/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGcircle;
import org.postgresql.geometric.PGline;
import org.postgresql.geometric.PGlseg;
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.GT;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Generic codec for PostgreSQL geometric types.
 *
 * <p>This codec handles encoding/decoding of geometric types (point, line, lseg, box, path, polygon, circle).</p>
 *
 * <p>Note: Only point and box support binary encoding. Others use text format only.</p>
 */
public final class GeometricCodec<T extends PGobject> implements TextCodec {

  // Singletons for each geometric type - binary-capable
  public static final BinaryGeometricCodec<PGpoint> POINT = new BinaryGeometricCodec<>("point", PGpoint.class, PGpoint::new);
  public static final BinaryGeometricCodec<PGbox> BOX = new BinaryGeometricCodec<>("box", PGbox.class, PGbox::new);

  // Singletons for text-only geometric types
  public static final GeometricCodec<PGcircle> CIRCLE = new GeometricCodec<>("circle", PGcircle.class, PGcircle::new);
  public static final GeometricCodec<PGline> LINE = new GeometricCodec<>("line", PGline.class, PGline::new);
  public static final GeometricCodec<PGlseg> LSEG = new GeometricCodec<>("lseg", PGlseg.class, PGlseg::new);
  public static final GeometricCodec<PGpath> PATH = new GeometricCodec<>("path", PGpath.class, PGpath::new);
  public static final GeometricCodec<PGpolygon> POLYGON = new GeometricCodec<>("polygon", PGpolygon.class, PGpolygon::new);

  private final String typeName;
  private final Class<T> javaType;
  private final Supplier<T> constructor;

  private GeometricCodec(String typeName, Class<T> javaType, Supplier<T> constructor) {
    this.typeName = typeName;
    this.javaType = javaType;
    this.constructor = constructor;
  }

  @Override
  public String getTypeName() {
    return typeName;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return javaType;
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null || data.isEmpty()) {
      return null;
    }
    T obj = constructor.get();
    obj.setValue(data);
    return obj;
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (javaType.isInstance(value)) {
      String val = ((PGobject) value).getValue();
      return val != null ? val : "";
    }
    throw new PSQLException(GT.tr("Cannot encode {0} as {1}", value.getClass().getName(), typeName),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <R> @Nullable R decodeTextAs(String data, PgType type, Class<R> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == javaType || targetClass == Object.class || targetClass == PGobject.class) {
      return (R) decodeText(data, type, ctx);
    }
    throw new PSQLException(
        GT.tr("Cannot decode {0} to {1}", typeName, targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert {0} to int", typeName), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert {0} to long", typeName), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert {0} to double", typeName), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert {0} to BigDecimal", typeName), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    return data;
  }

  /**
   * Codec for geometric types that support binary encoding (PGpoint, PGbox).
   */
  public static final class BinaryGeometricCodec<T extends PGobject & PGBinaryObject>
      implements BinaryCodec, TextCodec {

    private final String typeName;
    private final Class<T> javaType;
    private final Supplier<T> constructor;

    private BinaryGeometricCodec(String typeName, Class<T> javaType, Supplier<T> constructor) {
      this.typeName = typeName;
      this.javaType = javaType;
      this.constructor = constructor;
    }

    @Override
    public String getTypeName() {
      return typeName;
    }

    @Override
    public Class<?> getDefaultJavaType() {
      return javaType;
    }

    @Override
    public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
      if (data == null || data.length == 0) {
        return null;
      }
      T obj = constructor.get();
      obj.setByteValue(data, 0);
      return obj;
    }

    @Override
    public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
      if (javaType.isInstance(value)) {
        @SuppressWarnings("unchecked")
        T obj = (T) value;
        byte[] bytes = new byte[obj.lengthInBytes()];
        obj.toBytes(bytes, 0);
        return bytes;
      }
      throw new PSQLException(GT.tr("Cannot encode {0} as {1}", value.getClass().getName(), typeName),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    @Override
    public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
      if (data == null || data.isEmpty()) {
        return null;
      }
      T obj = constructor.get();
      obj.setValue(data);
      return obj;
    }

    @Override
    public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
      if (javaType.isInstance(value)) {
        String val = ((PGobject) value).getValue();
        return val != null ? val : "";
      }
      throw new PSQLException(GT.tr("Cannot encode {0} as {1}", value.getClass().getName(), typeName),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> @Nullable R decodeBinaryAs(byte[] data, PgType type, Class<R> targetClass, CodecContext ctx)
        throws SQLException {
      if (targetClass == javaType || targetClass == Object.class || targetClass == PGobject.class) {
        return (R) decodeBinary(data, type, ctx);
      }
      throw new PSQLException(
          GT.tr("Cannot decode {0} to {1}", typeName, targetClass.getName()),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> @Nullable R decodeTextAs(String data, PgType type, Class<R> targetClass, CodecContext ctx)
        throws SQLException {
      if (targetClass == javaType || targetClass == Object.class || targetClass == PGobject.class) {
        return (R) decodeText(data, type, ctx);
      }
      throw new PSQLException(
          GT.tr("Cannot decode {0} to {1}", typeName, targetClass.getName()),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    @Override
    public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
      throw new PSQLException(GT.tr("Cannot convert {0} to int", typeName), PSQLState.DATA_TYPE_MISMATCH);
    }

    @Override
    public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
      throw new PSQLException(GT.tr("Cannot convert {0} to int", typeName), PSQLState.DATA_TYPE_MISMATCH);
    }

    @Override
    public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
      throw new PSQLException(GT.tr("Cannot convert {0} to long", typeName), PSQLState.DATA_TYPE_MISMATCH);
    }

    @Override
    public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
      throw new PSQLException(GT.tr("Cannot convert {0} to long", typeName), PSQLState.DATA_TYPE_MISMATCH);
    }

    @Override
    public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
      throw new PSQLException(GT.tr("Cannot convert {0} to double", typeName), PSQLState.DATA_TYPE_MISMATCH);
    }

    @Override
    public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
      throw new PSQLException(GT.tr("Cannot convert {0} to double", typeName), PSQLState.DATA_TYPE_MISMATCH);
    }

    @Override
    public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
      throw new PSQLException(GT.tr("Cannot convert {0} to BigDecimal", typeName), PSQLState.DATA_TYPE_MISMATCH);
    }

    @Override
    public @Nullable BigDecimal decodeAsBigDecimal(String data, PgType type, CodecContext ctx) throws SQLException {
      throw new PSQLException(GT.tr("Cannot convert {0} to BigDecimal", typeName), PSQLState.DATA_TYPE_MISMATCH);
    }

    @Override
    public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
      PGobject obj = (PGobject) decodeBinary(data, type, ctx);
      return obj != null ? obj.getValue() : null;
    }

    @Override
    public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
      return data;
    }
  }
}
