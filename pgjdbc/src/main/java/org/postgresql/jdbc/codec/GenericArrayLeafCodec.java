/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.lang.reflect.Array;
import java.sql.SQLException;

/**
 * Generic array leaf adapter that delegates each non-null element to the
 * scalar codec registered for the array's element type.
 *
 * <p>This is the fallback counterpart to specialized leaf codecs such as
 * {@link Int4ArrayLeafCodec}: it handles arbitrary object leaves, including
 * composites and custom types, while specialized codecs keep primitive-array
 * fast paths for hot built-in types.</p>
 *
 * <p>When constructed with a {@code decodeTargetComponent}, each element decodes
 * to that exact Java type through the element codec's {@code decodeBinaryAs}/
 * {@code decodeTextAs} (so a {@code CustomDto[]} or {@code LocalDate[]} target is
 * honoured), instead of the codec's default type. The encode path never uses a
 * target and is unaffected.</p>
 */
final class GenericArrayLeafCodec implements ArrayLeafCodec {

  private final TypeDescriptor elementType;
  private final @Nullable BinaryCodec binaryCodec;
  private final @Nullable TextCodec textCodec;
  // When non-null, decode each element to this exact component type (decode-only path).
  private final @Nullable Class<?> decodeTargetComponent;

  GenericArrayLeafCodec(TypeDescriptor elementType, Codec elementCodec) {
    this(elementType, elementCodec, null);
  }

  GenericArrayLeafCodec(TypeDescriptor elementType, Codec elementCodec,
      @Nullable Class<?> decodeTargetComponent) {
    this.elementType = elementType;
    this.binaryCodec = elementCodec instanceof BinaryCodec ? (BinaryCodec) elementCodec : null;
    this.textCodec = elementCodec instanceof TextCodec ? (TextCodec) elementCodec : null;
    this.decodeTargetComponent = decodeTargetComponent;
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
    Class<?> target = decodeTargetComponent;
    return target != null ? target : getDefaultJavaType();
  }

  @Override
  public boolean supportsTargetComponent(Class<?> componentType) {
    return componentType == Object.class || componentType.isAssignableFrom(getBoxedComponentType());
  }

  @Override
  public boolean writeLeaf(Object leaf, BackpatchingBinarySink out, CodecContext ctx)
      throws IOException, SQLException {
    BinaryCodec codec = binaryCodec;
    if (codec == null) {
      throw noBinaryCodec();
    }
    if (leaf instanceof Object[]) {
      boolean hasNulls = false;
      for (Object element : (Object[]) leaf) {
        if (element == null) {
          out.writeInt32(-1);
          hasNulls = true;
        } else {
          BinaryCodec.writeElement(out, element, codec, elementType, ctx);
        }
      }
      return hasNulls;
    }
    if (leaf.getClass().isArray()) {
      // Primitive leaf array (e.g. int[]/double[] bound to numeric[]): box each element and
      // dispatch to the element codec. Primitive arrays never contain nulls.
      int len = Array.getLength(leaf);
      for (int i = 0; i < len; i++) {
        Object element = Array.get(leaf, i);
        BinaryCodec.writeElement(out, element, codec, elementType, ctx);
      }
      return false;
    }
    throw unsupportedLeaf(leaf, ctx);
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
    Class<?> target = decodeTargetComponent;
    int pos = cursor[0];
    for (int i = 0; i < arr.length; i++) {
      int length = ByteConverter.int4(data, pos);
      pos += 4;
      if (length == -1) {
        arr[i] = null;
      } else {
        arr[i] = target != null
            ? codec.decodeBinaryAs(data, pos, length, elementType, target,
                ctx)
            : codec.decodeBinary(data, pos, length, elementType, ctx);
        pos += length;
      }
    }
    cursor[0] = pos;
  }

  @Override
  public void appendLeaf(Appendable out, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException, IOException {
    TextCodec codec = textCodec;
    if (codec == null) {
      throw noTextCodec();
    }
    if (leaf instanceof Object[]) {
      Object[] arr = (Object[]) leaf;
      for (int i = 0; i < arr.length; i++) {
        if (i > 0) {
          out.append(delimiter);
        }
        appendElement(codec, arr[i], out, ctx);
      }
      return;
    }
    if (leaf.getClass().isArray()) {
      // Primitive leaf array (e.g. int[]/double[] bound to numeric[]): box each element. Primitive
      // arrays never contain nulls.
      int len = Array.getLength(leaf);
      for (int i = 0; i < len; i++) {
        if (i > 0) {
          out.append(delimiter);
        }
        appendElement(codec, Array.get(leaf, i), out, ctx);
      }
      return;
    }
    throw unsupportedLeaf(leaf, ctx);
  }

  private void appendElement(TextCodec codec, @Nullable Object element, Appendable out,
      CodecContext ctx) throws SQLException, IOException {
    if (element == null) {
      out.append("NULL");
    } else if (codec instanceof StreamingTextCodec) {
      StreamingTextCodec streamingCodec = (StreamingTextCodec) codec;
      if (!codec.mayRequireQuoting()) {
        streamingCodec.encodeText(element, elementType, ctx, out);
      } else {
        out.append('"');
        streamingCodec.encodeText(element, elementType, ctx, new EscapingAppendable(out));
        out.append('"');
      }
    } else {
      appendQuotedArrayElement(out, codec.encodeText(element, elementType, ctx));
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
    Class<?> target = decodeTargetComponent;
    for (int i = 0; i < arr.length; i++) {
      if (i > 0) {
        cur.expect(delimiter);
      }
      cur.readValue(delimiter, '}');
      if (!cur.tokenWasQuoted() && cur.tokenEquals("NULL")) {
        arr[i] = null;
      } else if (target != null) {
        arr[i] = codec.decodeTextAs(
            new String(cur.tokenChars(), cur.tokenOffset(), cur.tokenLength()), elementType, target,
            ctx);
      } else {
        arr[i] = codec.decodeText(cur.tokenChars(), cur.tokenOffset(), cur.tokenLength(),
            elementType, ctx);
      }
    }
  }

  private static void appendQuotedArrayElement(Appendable out, String value) throws IOException {
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
