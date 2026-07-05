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
import org.postgresql.util.NumberParser;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Leaf-level codec for {@code int2[]} arrays.
 *
 * <p>Keeps the per-element loops typed for {@code short[]} and {@code Short[]}
 * while {@link MultiDimArrayBinary} / {@link MultiDimArrayText} own the array
 * header and dimensional walking.</p>
 *
 * <p>The boxed component type is {@code Short} — the type the legacy array
 * decoder returned for {@code int2[]} — even though the scalar {@link Int2Codec}
 * decodes a single {@code int2} value as {@code Integer} for backward
 * compatibility. The array element type follows the array contract, not the
 * scalar one.</p>
 */
final class Int2ArrayLeafCodec implements ArrayLeafCodec {

  static final Int2ArrayLeafCodec INSTANCE = new Int2ArrayLeafCodec();

  private Int2ArrayLeafCodec() {
    // Singleton
  }

  @Override
  public int getElementOid() {
    return Oid.INT2;
  }

  @Override
  public Class<?> getPrimitiveComponentType() {
    return short.class;
  }

  @Override
  public Class<?> getBoxedComponentType() {
    return Short.class;
  }

  @Override
  public boolean writeLeaf(Object leaf, BackpatchingBinarySink out, byte[] scratch,
      CodecContext ctx)
      throws IOException, SQLException {
    byte[] buf = new byte[2];
    if (leaf instanceof short[]) {
      short[] arr = (short[]) leaf;
      for (short v : arr) {
        out.writeInt32(2);
        ByteConverter.int2(buf, 0, v);
        out.write(buf);
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
          out.writeInt32(2);
          ByteConverter.int2(buf, 0, Int2Codec.toShort(element));
          out.write(buf);
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
    if (leaf instanceof short[]) {
      short[] arr = (short[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          throw new PSQLException(
              GT.tr("Cannot decode NULL into primitive short[] leaf"),
              PSQLState.DATA_ERROR);
        }
        validateElementLength(len);
        arr[i] = ByteConverter.int2(data, pos);
        pos += 2;
      }
    } else if (leaf instanceof Short[]) {
      @Nullable Short[] arr = (@Nullable Short[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          arr[i] = null;
        } else {
          validateElementLength(len);
          arr[i] = ByteConverter.int2(data, pos);
          pos += 2;
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
    if (leaf instanceof short[]) {
      short[] arr = (short[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          out.append(delimiter);
        }
        out.append(Short.toString(arr[i]));
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
          out.append(Short.toString(Int2Codec.toShort(arr[i])));
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  @Override
  public void readLeafText(LiteralCursor cur, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException {
    if (leaf instanceof short[]) {
      short[] arr = (short[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          throw new PSQLException(
              GT.tr("Cannot decode NULL into primitive short[] leaf"),
              PSQLState.DATA_ERROR);
        }
        arr[i] = parseShort(cur);
      }
      return;
    }
    if (leaf instanceof Short[]) {
      @Nullable Short[] arr = (@Nullable Short[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          arr[i] = null;
        } else {
          arr[i] = parseShort(cur);
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  private static short parseShort(LiteralCursor cur) throws SQLException {
    char[] chars = cur.tokenChars();
    int off = cur.tokenOffset();
    int len = cur.tokenLength();
    try {
      return (short) NumberParser.getFastLong(chars, off, len, Short.MIN_VALUE, Short.MAX_VALUE);
    } catch (NumberFormatException fast) {
      try {
        return Short.parseShort(new String(chars, off, len));
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Invalid int2 array element: {0}", new String(chars, off, len)),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
      }
    }
  }

  private static void validateElementLength(int length) throws SQLException {
    if (length != 2) {
      throw new PSQLException(
          GT.tr("Invalid int2 array element length: {0}", length),
          PSQLState.DATA_ERROR);
    }
  }
}
