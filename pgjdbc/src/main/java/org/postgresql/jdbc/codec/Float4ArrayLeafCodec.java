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
 * Leaf-level codec for {@code float4[]} arrays.
 *
 * <p>Keeps the per-element loops typed for {@code float[]} and {@code Float[]}
 * while {@link MultiDimArrayBinary} / {@link MultiDimArrayText} own the array
 * header and dimensional walking. The text form mirrors {@link Float4Codec}:
 * {@link Float#toString} / {@link Float#parseFloat}, whose {@code NaN},
 * {@code Infinity} and {@code -Infinity} spellings match PostgreSQL's.</p>
 */
final class Float4ArrayLeafCodec implements ArrayLeafCodec {

  static final Float4ArrayLeafCodec INSTANCE = new Float4ArrayLeafCodec();

  private Float4ArrayLeafCodec() {
    // Singleton
  }

  @Override
  public int getElementOid() {
    return Oid.FLOAT4;
  }

  @Override
  public Class<?> getPrimitiveComponentType() {
    return float.class;
  }

  @Override
  public Class<?> getBoxedComponentType() {
    return Float.class;
  }

  @Override
  public boolean writeLeaf(Object leaf, BackpatchingBinarySink out, CodecContext ctx)
      throws IOException, SQLException {
    if (leaf instanceof float[]) {
      float[] arr = (float[]) leaf;
      for (float v : arr) {
        out.writeInt32(4);
        out.writeFloat(v);
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
          out.writeInt32(4);
          out.writeFloat(Float4Codec.toFloat(element));
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
    if (leaf instanceof float[]) {
      float[] arr = (float[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          throw new PSQLException(
              GT.tr("Cannot decode NULL into primitive float[] leaf"),
              PSQLState.DATA_ERROR);
        }
        validateElementLength(len);
        arr[i] = ByteConverter.float4(data, pos);
        pos += 4;
      }
    } else if (leaf instanceof Float[]) {
      @Nullable Float[] arr = (@Nullable Float[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          arr[i] = null;
        } else {
          validateElementLength(len);
          arr[i] = ByteConverter.float4(data, pos);
          pos += 4;
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
    if (leaf instanceof float[]) {
      float[] arr = (float[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          out.append(delimiter);
        }
        out.append(Float.toString(arr[i]));
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
          out.append(Float.toString(Float4Codec.toFloat(arr[i])));
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  @Override
  public void readLeafText(LiteralCursor cur, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException {
    if (leaf instanceof float[]) {
      float[] arr = (float[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          throw new PSQLException(
              GT.tr("Cannot decode NULL into primitive float[] leaf"),
              PSQLState.DATA_ERROR);
        }
        arr[i] = parseFloat(cur);
      }
      return;
    }
    if (leaf instanceof Float[]) {
      @Nullable Float[] arr = (@Nullable Float[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          arr[i] = null;
        } else {
          arr[i] = parseFloat(cur);
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  private static float parseFloat(LiteralCursor cur) throws SQLException {
    String s = new String(cur.tokenChars(), cur.tokenOffset(), cur.tokenLength());
    try {
      return Float.parseFloat(s);
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Invalid float4 array element: {0}", s),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  private static void validateElementLength(int length) throws SQLException {
    if (length != 4) {
      throw new PSQLException(
          GT.tr("Invalid float4 array element length: {0}", length),
          PSQLState.DATA_ERROR);
    }
  }
}
