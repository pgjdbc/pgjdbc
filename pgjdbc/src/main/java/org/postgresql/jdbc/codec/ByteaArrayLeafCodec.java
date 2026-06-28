/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGbytea;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Leaf-level codec for {@code bytea[]} arrays.
 *
 * <p>Unlike the numeric leaves, one {@code bytea} element is itself a Java
 * {@code byte[]}, so the leaf-level array is a {@code byte[][]} and there is no
 * primitive form. {@link MultiDimArraySupport} therefore counts a {@code byte[]}
 * as a leaf rather than an inner dimension (see
 * {@link MultiDimArraySupport#computeDimensions(Object, Class)}), which lets a
 * {@code bytea[]} hold {@code byte[]} elements of differing lengths. The wire
 * forms mirror {@link ByteaCodec}: a length-prefixed byte run in binary and the
 * {@code \x}-hex literal in text.</p>
 */
final class ByteaArrayLeafCodec implements ArrayLeafCodec {

  static final ByteaArrayLeafCodec INSTANCE = new ByteaArrayLeafCodec();

  private ByteaArrayLeafCodec() {
    // Singleton
  }

  @Override
  public int getElementOid() {
    return Oid.BYTEA;
  }

  @Override
  public Class<?> getPrimitiveComponentType() {
    return byte[].class;
  }

  @Override
  public Class<?> getBoxedComponentType() {
    return byte[].class;
  }

  @Override
  public boolean writeLeaf(Object leaf, BackpatchingBinarySink out, byte[] scratch,
      CodecContext ctx)
      throws IOException, SQLException {
    if (leaf instanceof Object[]) {
      Object[] arr = (Object[]) leaf;
      boolean hasNulls = false;
      for (Object element : arr) {
        if (element == null) {
          out.writeInt32(-1);
          hasNulls = true;
        } else {
          byte[] bytes = ByteaCodec.toBytes(element);
          out.writeInt32(bytes.length);
          out.write(bytes);
        }
      }
      return hasNulls;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  @Override
  public void readLeaf(byte[] data, int[] cursor, Object leaf, CodecContext ctx)
      throws SQLException {
    if (leaf instanceof Object[]) {
      @Nullable Object[] arr = (@Nullable Object[]) leaf;
      int pos = cursor[0];
      for (int i = 0; i < arr.length; i++) {
        int len = ByteConverter.int4(data, pos);
        pos += 4;
        if (len == -1) {
          arr[i] = null;
        } else {
          arr[i] = Arrays.copyOfRange(data, pos, pos + len);
          pos += len;
        }
      }
      cursor[0] = pos;
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  @Override
  public void appendLeaf(Appendable out, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException, IOException {
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
          appendEscapedBytea(out, ByteaCodec.toBytes(element));
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  @Override
  public void readLeafText(LiteralCursor cur, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException {
    if (leaf instanceof Object[]) {
      @Nullable Object[] arr = (@Nullable Object[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          cur.expect(delimiter);
        }
        cur.readValue(delimiter, '}');
        if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
          arr[i] = null;
        } else {
          String hex = new String(cur.tokenChars(), cur.tokenOffset(), cur.tokenLength());
          arr[i] = PGbytea.toBytes(hex.getBytes(ctx.getCharset()));
        }
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  /**
   * Appends one element's bytes as a quoted, array-escaped {@code \x}-hex literal.
   * The hex form always contains a backslash, so the value is quoted and its
   * {@code "} / {@code \\} escaped, matching {@code array_out}.
   */
  private static void appendEscapedBytea(Appendable out, byte[] bytes) throws IOException {
    String text = PGbytea.toPGString(bytes);
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' || c == '\\') {
        out.append('\\');
      }
      out.append(c);
    }
    out.append('"');
  }
}
