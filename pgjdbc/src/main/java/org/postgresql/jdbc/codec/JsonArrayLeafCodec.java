/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Leaf-level codec for {@code json[]} / {@code jsonb[]} arrays.
 *
 * <p>{@code getArray().getArray()} on a json array returns {@code String[]} (the raw JSON text of
 * each element), matching the legacy decoder, even though a scalar {@code json}/{@code jsonb} column
 * decodes to {@link org.postgresql.util.PGobject}. This leaf reproduces that: each element decodes to
 * a {@link String} via the element codec's {@code decodeAsString} (which strips the {@code jsonb}
 * version byte in binary), while encoding delegates to the element codec, so a {@code String[]} or
 * {@code PGobject[]} binds correctly.</p>
 */
final class JsonArrayLeafCodec implements ArrayLeafCodec {

  private final int elementOid;
  private final BinaryCodec binaryCodec;
  private final TextCodec textCodec;

  <C extends BinaryCodec & TextCodec> JsonArrayLeafCodec(int elementOid, C codec) {
    this.elementOid = elementOid;
    this.binaryCodec = codec;
    this.textCodec = codec;
  }

  @Override
  public int getElementOid() {
    return elementOid;
  }

  @Override
  public Class<?> getPrimitiveComponentType() {
    return String.class;
  }

  @Override
  public Class<?> getBoxedComponentType() {
    return String.class;
  }

  private PgType elementType(CodecContext ctx) throws SQLException {
    return ctx.getTypeInfo().getPgTypeByOid(elementOid);
  }

  @Override
  public boolean writeLeaf(Object leaf, BackpatchingBinarySink out, byte[] scratch, CodecContext ctx)
      throws IOException, SQLException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    PgType elementType = elementType(ctx);
    boolean hasNulls = false;
    for (Object element : (Object[]) leaf) {
      if (element == null) {
        out.writeInt32(-1);
        hasNulls = true;
      } else {
        byte[] encoded = binaryCodec.encodeBinary(element, elementType, ctx);
        out.writeInt32(encoded.length);
        out.write(encoded);
      }
    }
    return hasNulls;
  }

  @Override
  public void readLeaf(byte[] data, int[] cursor, Object leaf, CodecContext ctx) throws SQLException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    PgType elementType = elementType(ctx);
    @Nullable Object[] arr = (@Nullable Object[]) leaf;
    int pos = cursor[0];
    for (int i = 0; i < arr.length; i++) {
      int len = ByteConverter.int4(data, pos);
      pos += 4;
      if (len == -1) {
        arr[i] = null;
      } else {
        arr[i] = binaryCodec.decodeAsString(Arrays.copyOfRange(data, pos, pos + len), elementType, ctx);
        pos += len;
      }
    }
    cursor[0] = pos;
  }

  @Override
  public void appendLeaf(Appendable out, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException, IOException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    PgType elementType = elementType(ctx);
    Object[] arr = (Object[]) leaf;
    for (int i = 0; i < arr.length; i++) {
      if (i > 0) {
        out.append(delimiter);
      }
      Object element = arr[i];
      if (element == null) {
        out.append("NULL");
      } else {
        appendQuotedEscaped(out, textCodec.encodeText(element, elementType, ctx));
      }
    }
  }

  @Override
  public void readLeafText(LiteralCursor cur, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    @Nullable Object[] arr = (@Nullable Object[]) leaf;
    for (int i = 0; i < arr.length; i++) {
      if (i > 0) {
        cur.expect(delimiter);
      }
      cur.readValue(delimiter, '}');
      if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
        arr[i] = null;
      } else {
        arr[i] = new String(cur.tokenChars(), cur.tokenOffset(), cur.tokenLength());
      }
    }
  }

  private static void appendQuotedEscaped(Appendable out, String value) throws IOException {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        out.append('\\');
      }
      out.append(c);
    }
    out.append('"');
  }
}
