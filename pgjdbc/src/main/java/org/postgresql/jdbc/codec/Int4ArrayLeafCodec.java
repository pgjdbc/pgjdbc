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
 * Leaf-level codec for {@code int4[]} arrays.
 *
 * <p>This keeps the per-element binary loops typed for {@code int[]} and
 * {@code Integer[]} while {@link MultiDimArrayBinary} owns the array header and
 * dimensional walking.</p>
 */
final class Int4ArrayLeafCodec implements ArrayLeafCodec {

  static final Int4ArrayLeafCodec INSTANCE = new Int4ArrayLeafCodec();

  private Int4ArrayLeafCodec() {
    // Singleton
  }

  @Override
  public int getElementOid() {
    return Oid.INT4;
  }

  @Override
  public Class<?> getPrimitiveComponentType() {
    return int.class;
  }

  @Override
  public Class<?> getBoxedComponentType() {
    return Integer.class;
  }

  @Override
  public boolean writeLeaf(Object leaf, BackpatchingBinarySink out, CodecContext ctx)
      throws IOException, SQLException {
    if (leaf instanceof int[]) {
      int[] arr = (int[]) leaf;
      for (int v : arr) {
        out.writeInt32(4);
        out.writeInt32(v);
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
          out.writeInt32(Int4Codec.toInt(element));
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
    if (leaf instanceof int[]) {
      int[] arr = (int[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          throw new PSQLException(
              GT.tr("Cannot decode NULL into primitive int[] leaf"),
              PSQLState.DATA_ERROR);
        }
        validateElementLength(len);
        arr[i] = ByteConverter.int4(data, pos);
        pos += 4;
      }
    } else if (leaf instanceof Integer[]) {
      @Nullable Integer[] arr = (@Nullable Integer[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          arr[i] = null;
        } else {
          validateElementLength(len);
          arr[i] = ByteConverter.int4(data, pos);
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
    if (leaf instanceof int[]) {
      int[] arr = (int[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          out.append(delimiter);
        }
        out.append(Integer.toString(arr[i]));
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
          out.append(Integer.toString(Int4Codec.toInt(arr[i])));
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  @Override
  public void readLeafText(LiteralCursor cur, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException {
    if (leaf instanceof int[]) {
      int[] arr = (int[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          throw new PSQLException(
              GT.tr("Cannot decode NULL into primitive int[] leaf"),
              PSQLState.DATA_ERROR);
        }
        arr[i] = parseInt(cur);
      }
      return;
    }
    if (leaf instanceof Integer[]) {
      @Nullable Integer[] arr = (@Nullable Integer[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          arr[i] = null;
        } else {
          arr[i] = parseInt(cur);
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  private static int parseInt(LiteralCursor cur) throws SQLException {
    char[] chars = cur.tokenChars();
    int off = cur.tokenOffset();
    int len = cur.tokenLength();
    try {
      return (int) NumberParser.getFastLong(chars, off, len, Integer.MIN_VALUE, Integer.MAX_VALUE);
    } catch (NumberFormatException fast) {
      try {
        return Integer.parseInt(new String(chars, off, len));
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Invalid int4 array element: {0}", new String(chars, off, len)),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
      }
    }
  }

  private static void validateElementLength(int length) throws SQLException {
    if (length != 4) {
      throw new PSQLException(
          GT.tr("Invalid int4 array element length: {0}", length),
          PSQLState.DATA_ERROR);
    }
  }
}
