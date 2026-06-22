/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.BooleanTypeUtil;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Leaf-level codec for {@code bool[]} arrays.
 *
 * <p>Keeps the per-element loops typed for {@code boolean[]} and
 * {@code Boolean[]} while {@link MultiDimArrayBinary} / {@link MultiDimArrayText}
 * own the array header and dimensional walking. The wire forms mirror
 * {@link BoolCodec}: a single byte ({@code 0}/{@code 1}) in binary and
 * {@code t}/{@code f} in text.</p>
 */
final class BoolArrayLeafCodec implements ArrayLeafCodec {

  static final BoolArrayLeafCodec INSTANCE = new BoolArrayLeafCodec();

  private BoolArrayLeafCodec() {
    // Singleton
  }

  @Override
  public int getElementOid() {
    return Oid.BOOL;
  }

  @Override
  public Class<?> getPrimitiveComponentType() {
    return boolean.class;
  }

  @Override
  public Class<?> getBoxedComponentType() {
    return Boolean.class;
  }

  @Override
  public boolean writeLeaf(Object leaf, BackpatchingBinarySink out, byte[] scratch,
      CodecContext ctx)
      throws IOException, SQLException {
    byte[] buf = new byte[1];
    if (leaf instanceof boolean[]) {
      boolean[] arr = (boolean[]) leaf;
      for (boolean v : arr) {
        out.writeInt32(1);
        buf[0] = (byte) (v ? 1 : 0);
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
          out.writeInt32(1);
          buf[0] = (byte) (BoolCodec.toBoolean(element) ? 1 : 0);
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
    if (leaf instanceof boolean[]) {
      boolean[] arr = (boolean[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          throw new PSQLException(
              GT.tr("Cannot decode NULL into primitive boolean[] leaf"),
              PSQLState.DATA_ERROR);
        }
        validateElementLength(len);
        arr[i] = data[pos] == 1;
        pos += 1;
      }
    } else if (leaf instanceof Boolean[]) {
      @Nullable Boolean[] arr = (@Nullable Boolean[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          arr[i] = null;
        } else {
          validateElementLength(len);
          arr[i] = data[pos] == 1;
          pos += 1;
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
    if (leaf instanceof boolean[]) {
      boolean[] arr = (boolean[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          out.append(delimiter);
        }
        out.append(arr[i] ? 't' : 'f');
      }
      return;
    }
    if (leaf instanceof Object[]) {
      Object[] arr = (Object[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          out.append(delimiter);
        }
        Object element = arr[i];
        if (element == null) {
          out.append("NULL");
        } else {
          out.append(BoolCodec.toBoolean(element) ? 't' : 'f');
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  @Override
  public void readLeafText(LiteralCursor cur, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException {
    if (leaf instanceof boolean[]) {
      boolean[] arr = (boolean[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          throw new PSQLException(
              GT.tr("Cannot decode NULL into primitive boolean[] leaf"),
              PSQLState.DATA_ERROR);
        }
        arr[i] = parseBoolean(cur);
      }
      return;
    }
    if (leaf instanceof Boolean[]) {
      @Nullable Boolean[] arr = (@Nullable Boolean[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          arr[i] = null;
        } else {
          arr[i] = parseBoolean(cur);
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  private static boolean parseBoolean(LiteralCursor cur) throws SQLException {
    return BooleanTypeUtil.fromString(
        new String(cur.tokenChars(), cur.tokenOffset(), cur.tokenLength()));
  }

  private static void validateElementLength(int length) throws SQLException {
    if (length != 1) {
      throw new PSQLException(
          GT.tr("Invalid bool array element length: {0}", length),
          PSQLState.DATA_ERROR);
    }
  }
}
