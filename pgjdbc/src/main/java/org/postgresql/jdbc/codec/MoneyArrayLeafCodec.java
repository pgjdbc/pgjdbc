/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGmoney;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Leaf-level codec for {@code money[]} arrays.
 *
 * <p>{@code getArray().getArray()} on a money array returns {@code Double[]}, matching the legacy
 * decoder's component type (the legacy element parse was itself broken — it ran {@code "$1.50"}
 * through {@code Double.parseDouble} and read the binary {@code int8} as an IEEE double — so this
 * leaf simply produces correct values). Text elements parse through {@link PGmoney} (handling the
 * currency symbol, parentheses and grouping separators); binary elements are the {@code int8}
 * smallest-unit value, decoded as {@code value / 100.0}.</p>
 */
final class MoneyArrayLeafCodec implements ArrayLeafCodec {

  static final MoneyArrayLeafCodec INSTANCE = new MoneyArrayLeafCodec();

  /**
   * PostgreSQL {@code cash} stores the amount as an {@code int8} scaled by the locale's fraction
   * digits ({@code lc_monetary}; 2 for most locales). The binary scale therefore depends on the
   * server locale, which is <em>not</em> a {@code GUC_REPORT} parameter (and {@code frac_digits} is a
   * C-locale property, not even a GUC), so the driver cannot reliably learn it from the protocol —
   * {@code money}/{@code money[]} are kept text-only (not in {@code SUPPORTED_BINARY_OIDS}). These
   * binary methods assume the default scale of {@code 2} and exist only for the rare case where a
   * caller has explicitly opted {@code money} into binary transfer.
   */
  private static final double SCALE = 100.0;

  private MoneyArrayLeafCodec() {
  }

  @Override
  public int getElementOid() {
    return Oid.MONEY;
  }

  @Override
  public Class<?> getPrimitiveComponentType() {
    return Double.class;
  }

  @Override
  public Class<?> getBoxedComponentType() {
    return Double.class;
  }

  @Override
  public boolean writeLeaf(Object leaf, BackpatchingBinarySink out, byte[] scratch, CodecContext ctx)
      throws IOException, SQLException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    boolean hasNulls = false;
    for (Object element : (Object[]) leaf) {
      if (element == null) {
        out.writeInt32(-1);
        hasNulls = true;
      } else {
        out.writeInt32(8);
        byte[] buf = new byte[8];
        ByteConverter.int8(buf, 0, Math.round(toDouble(element) * SCALE));
        out.write(buf);
      }
    }
    return hasNulls;
  }

  @Override
  public void readLeaf(byte[] data, int[] cursor, Object leaf, CodecContext ctx) throws SQLException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    @Nullable Object[] arr = (@Nullable Object[]) leaf;
    int pos = cursor[0];
    for (int i = 0; i < arr.length; i++) {
      int len = ByteConverter.int4(data, pos);
      pos += 4;
      if (len == -1) {
        arr[i] = null;
      } else {
        arr[i] = ByteConverter.int8(data, pos) / SCALE;
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
    Object[] arr = (Object[]) leaf;
    for (int i = 0; i < arr.length; i++) {
      if (i > 0) {
        out.append(delimiter);
      }
      Object element = arr[i];
      if (element == null) {
        out.append("NULL");
      } else {
        // A bare numeric literal is accepted by the server's money input regardless of lc_monetary.
        out.append(Double.toString(toDouble(element)));
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
        String token = new String(cur.tokenChars(), cur.tokenOffset(), cur.tokenLength());
        arr[i] = new PGmoney(token).val;
      }
    }
  }

  private static double toDouble(Object value) throws SQLException {
    if (value instanceof PGmoney) {
      return ((PGmoney) value).val;
    }
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    if (value instanceof String) {
      return new PGmoney((String) value).val;
    }
    throw Codec.cannotEncode(value, "money");
  }
}
