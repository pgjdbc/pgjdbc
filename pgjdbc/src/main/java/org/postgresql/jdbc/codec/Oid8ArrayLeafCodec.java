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
 * Leaf-level codec for {@code oid8[]} arrays.
 *
 * <p>Keeps the per-element loops typed for {@code long[]} and {@code Long[]} while
 * {@link MultiDimArrayBinary} / {@link MultiDimArrayText} own the array header and dimensional
 * walking. Each element is the raw 8-byte {@code oid8} bit pattern (see {@link Oid8Codec}); text
 * elements are formatted and parsed as unsigned decimal via {@link Long#toUnsignedString(long)}
 * and {@link Long#parseUnsignedLong(String)}.</p>
 */
final class Oid8ArrayLeafCodec implements ArrayLeafCodec {

  static final Oid8ArrayLeafCodec INSTANCE = new Oid8ArrayLeafCodec();

  private Oid8ArrayLeafCodec() {
    // Singleton
  }

  @Override
  public int getElementOid() {
    return Oid.OID8;
  }

  @Override
  public Class<?> getPrimitiveComponentType() {
    return long.class;
  }

  @Override
  public Class<?> getBoxedComponentType() {
    return Long.class;
  }

  @Override
  public boolean writeLeaf(Object leaf, BackpatchingBinarySink out, CodecContext ctx)
      throws IOException, SQLException {
    if (leaf instanceof long[]) {
      long[] arr = (long[]) leaf;
      for (long v : arr) {
        out.writeInt32(8);
        out.writeInt64(v);
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
          out.writeInt64(Oid8Codec.toLong(element));
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
    if (leaf instanceof long[]) {
      long[] arr = (long[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          throw new PSQLException(
              GT.tr("Cannot decode NULL into primitive long[] leaf"),
              PSQLState.DATA_ERROR);
        }
        validateElementLength(len);
        arr[i] = ByteConverter.int8(data, pos);
        pos += 8;
      }
    } else if (leaf instanceof Long[]) {
      @Nullable Long[] arr = (@Nullable Long[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          arr[i] = null;
        } else {
          validateElementLength(len);
          arr[i] = ByteConverter.int8(data, pos);
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
    if (leaf instanceof long[]) {
      long[] arr = (long[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          out.append(delimiter);
        }
        out.append(Long.toUnsignedString(arr[i]));
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
          out.append(Long.toUnsignedString(Oid8Codec.toLong(arr[i])));
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  @Override
  public void readLeafText(LiteralCursor cur, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException {
    if (leaf instanceof long[]) {
      long[] arr = (long[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          throw new PSQLException(
              GT.tr("Cannot decode NULL into primitive long[] leaf"),
              PSQLState.DATA_ERROR);
        }
        arr[i] = parseUnsignedLong(cur);
      }
      return;
    }
    if (leaf instanceof Long[]) {
      @Nullable Long[] arr = (@Nullable Long[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          arr[i] = null;
        } else {
          arr[i] = parseUnsignedLong(cur);
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  private static long parseUnsignedLong(LiteralCursor cur) throws SQLException {
    char[] chars = cur.tokenChars();
    int off = cur.tokenOffset();
    int len = cur.tokenLength();
    try {
      return Long.parseUnsignedLong(new String(chars, off, len));
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Invalid oid8 array element: {0}", new String(chars, off, len)),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  private static void validateElementLength(int length) throws SQLException {
    if (length != 8) {
      throw new PSQLException(
          GT.tr("Invalid oid8 array element length: {0}", length),
          PSQLState.DATA_ERROR);
    }
  }
}
