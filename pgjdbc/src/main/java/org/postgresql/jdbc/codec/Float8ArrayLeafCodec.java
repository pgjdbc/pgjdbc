/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Leaf-level codec for {@code float8[]} arrays.
 *
 * <p>Keeps the per-element loops typed for {@code double[]} and {@code Double[]}
 * while {@link MultiDimArrayBinary} / {@link MultiDimArrayText} own the array
 * header and dimensional walking. The text form mirrors {@link Float8Codec}:
 * {@link Double#toString} / {@link Double#parseDouble}, whose {@code NaN},
 * {@code Infinity} and {@code -Infinity} spellings match PostgreSQL's.</p>
 */
final class Float8ArrayLeafCodec implements ArrayLeafCodec {

  static final Float8ArrayLeafCodec INSTANCE = new Float8ArrayLeafCodec();

  private Float8ArrayLeafCodec() {
    // Singleton
  }

  @Override
  public int getElementOid() {
    return Oid.FLOAT8;
  }

  @Override
  public Class<?> getPrimitiveComponentType() {
    return double.class;
  }

  @Override
  public Class<?> getBoxedComponentType() {
    return Double.class;
  }

  @Override
  public boolean writeLeaf(Object leaf, BackpatchingBinarySink out, CodecContext ctx)
      throws IOException, SQLException {
    if (leaf instanceof double[]) {
      double[] arr = (double[]) leaf;
      for (double v : arr) {
        out.writeInt32(8);
        out.writeDouble(v);
      }
      return false;
    }
    if (leaf instanceof Object[]) {
      Object[] arr = (Object[]) leaf;
      boolean hasNulls = false;
      for (Object element : arr) {
        if (element == null) {
          out.writeInt32(-1);
          hasNulls = true;
        } else {
          out.writeInt32(8);
          out.writeDouble(Float8Codec.toDouble(element));
        }
      }
      return hasNulls;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  @Override
  public void readLeaf(byte[] data, int[] cursor, Object leaf, CodecContext ctx)
      throws SQLException {
    int pos = cursor[0];
    if (leaf instanceof double[]) {
      double[] arr = (double[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          throw new PSQLException(
              GT.tr("Cannot decode NULL into primitive double[] leaf"),
              PSQLState.DATA_ERROR);
        }
        validateElementLength(len);
        arr[i] = ByteConverter.float8(data, pos);
        pos += 8;
      }
    } else if (leaf instanceof Double[]) {
      @Nullable Double[] arr = (@Nullable Double[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          arr[i] = null;
        } else {
          validateElementLength(len);
          arr[i] = ByteConverter.float8(data, pos);
          pos += 8;
        }
      }
    } else {
      throw unsupportedLeaf(leaf, ctx);
    }
    cursor[0] = pos;
  }

  @Override
  public void appendLeaf(Appendable out, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException, IOException {
    if (leaf instanceof double[]) {
      double[] arr = (double[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          out.append(delimiter);
        }
        out.append(Double.toString(arr[i]));
      }
      return;
    }
    if (leaf instanceof Object[]) {
      Object[] arr = (Object[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          out.append(delimiter);
        }
        if (arr[i] == null) {
          out.append("NULL");
        } else {
          out.append(Double.toString(Float8Codec.toDouble(arr[i])));
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  @Override
  public void readLeafText(LiteralCursor cur, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException {
    if (leaf instanceof double[]) {
      double[] arr = (double[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          throw new PSQLException(
              GT.tr("Cannot decode NULL into primitive double[] leaf"),
              PSQLState.DATA_ERROR);
        }
        arr[i] = parseDouble(cur);
      }
      return;
    }
    if (leaf instanceof Double[]) {
      @Nullable Double[] arr = (@Nullable Double[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          arr[i] = null;
        } else {
          arr[i] = parseDouble(cur);
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  private static double parseDouble(LiteralCursor cur) throws SQLException {
    String s = new String(cur.tokenChars(), cur.tokenOffset(), cur.tokenLength());
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Invalid float8 array element: {0}", s),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  private static void validateElementLength(int length) throws SQLException {
    if (length != 8) {
      throw new PSQLException(
          GT.tr("Invalid float8 array element length: {0}", length),
          PSQLState.DATA_ERROR);
    }
  }
}
