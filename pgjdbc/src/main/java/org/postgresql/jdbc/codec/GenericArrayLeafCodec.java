/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Generic array leaf adapter that delegates each non-null element to the
 * scalar codec registered for the array's element type.
 *
 * <p>This is the fallback counterpart to specialized leaf codecs such as
 * {@link Int4ArrayLeafCodec}: it handles arbitrary object leaves, including
 * composites and custom types, while specialized codecs keep primitive-array
 * fast paths for hot built-in types.</p>
 */
final class GenericArrayLeafCodec implements ArrayLeafCodec {

  private final PgType elementType;
  private final @Nullable BinaryCodec binaryCodec;
  private final @Nullable TextCodec textCodec;

  GenericArrayLeafCodec(PgType elementType, Codec elementCodec) {
    this.elementType = elementType;
    this.binaryCodec = elementCodec instanceof BinaryCodec ? (BinaryCodec) elementCodec : null;
    this.textCodec = elementCodec instanceof TextCodec ? (TextCodec) elementCodec : null;
  }

  @Override
  public int getElementOid() {
    return elementType.getOid();
  }

  @Override
  public Class<?> getPrimitiveComponentType() {
    return Object.class;
  }

  @Override
  public Class<?> getBoxedComponentType() {
    return getDefaultJavaType();
  }

  @Override
  public boolean supportsTargetComponent(Class<?> componentType) {
    return componentType == Object.class || componentType.isAssignableFrom(getBoxedComponentType());
  }

  @Override
  public boolean writeLeaf(Object leaf, BackpatchingBinarySink out, byte[] scratch,
      CodecContext ctx) throws IOException, SQLException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    BinaryCodec codec = binaryCodec;
    if (codec == null) {
      throw noBinaryCodec();
    }
    Object[] arr = (Object[]) leaf;
    boolean hasNulls = false;
    for (Object element : arr) {
      if (element == null) {
        out.writeInt32(-1);
        hasNulls = true;
      } else if (codec instanceof StreamingBinaryCodec) {
        int lengthSlot = out.reserveInt32();
        int startPos = out.position();
        ((StreamingBinaryCodec) codec).encodeBinary(element, elementType, ctx, out.asOutputStream());
        out.setInt32At(lengthSlot, out.position() - startPos);
      } else {
        byte[] encoded = codec.encodeBinary(element, elementType, ctx);
        out.writeInt32(encoded.length);
        out.write(encoded);
      }
    }
    return hasNulls;
  }

  @Override
  public void readLeaf(byte[] data, int[] cursor, Object leaf, CodecContext ctx)
      throws SQLException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    BinaryCodec codec = binaryCodec;
    if (codec == null) {
      throw noBinaryCodec();
    }
    @Nullable Object[] arr = (@Nullable Object[]) leaf;
    int pos = cursor[0];
    for (int i = 0; i < arr.length; i++) {
      int length = ByteConverter.int4(data, pos);
      pos += 4;
      if (length == -1) {
        arr[i] = null;
      } else {
        arr[i] = codec.decodeBinary(data, pos, length, elementType, ctx);
        pos += length;
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
    TextCodec codec = textCodec;
    if (codec == null) {
      throw noTextCodec();
    }
    Object[] arr = (Object[]) leaf;
    for (int i = 0; i < arr.length; i++) {
      if (i > 0) {
        out.append(delimiter);
      }
      Object element = arr[i];
      if (element == null) {
        out.append("NULL");
      } else if (codec instanceof StreamingTextCodec) {
        out.append('"');
        ((StreamingTextCodec) codec).encodeText(element, elementType, ctx,
            new EscapingAppendable(out));
        out.append('"');
      } else {
        appendEscapedArrayElement(out, codec.encodeText(element, elementType, ctx));
      }
    }
  }

  @Override
  public void readLeafText(LiteralCursor cur, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException {
    if (!(leaf instanceof Object[])) {
      throw unsupportedLeaf(leaf, ctx);
    }
    TextCodec codec = textCodec;
    if (codec == null) {
      throw noTextCodec();
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
        arr[i] = codec.decodeText(cur.tokenChars(), cur.tokenOffset(), cur.tokenLength(),
            elementType, ctx);
      }
    }
  }

  private static void appendEscapedArrayElement(Appendable out, String value) throws IOException {
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

  private PSQLException noBinaryCodec() {
    return new PSQLException(
        GT.tr("No binary codec registered for array element type {0}", elementType.getFullName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  private PSQLException noTextCodec() {
    return new PSQLException(
        GT.tr("No text codec registered for array element type {0}", elementType.getFullName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  private Class<?> getDefaultJavaType() {
    Codec codec = binaryCodec != null ? binaryCodec : textCodec;
    return codec == null ? Object.class : codec.getDefaultJavaType();
  }
}
