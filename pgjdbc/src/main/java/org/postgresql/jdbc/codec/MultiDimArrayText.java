/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.CodecContext;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Shared multi-dimensional text array encoder.
 *
 * <p>Owns the PostgreSQL text array literal format ({@code {a,b,{c,d}}}).
 * Outer dimensions are walked via {@link java.lang.reflect.Array}; the leaf
 * level is delegated to a caller-provided {@link LeafTextWriter} that
 * appends one 1-D slice's elements (without surrounding braces). Leaf writers
 * are responsible for array-element escaping when element text can contain
 * array syntax characters; this helper only owns dimensional braces and
 * delimiters.</p>
 */
public final class MultiDimArrayText {

  private MultiDimArrayText() {
    // Utility class
  }

  /**
   * Strategy for appending one leaf-level 1-D array as a comma-delimited
   * sequence of element literals (no surrounding braces — the helper adds
   * them).
   *
   * <p>The {@code out} parameter is an {@link Appendable} so leaf writers
   * that dispatch through a per-element
   * {@link org.postgresql.api.codec.StreamingTextCodec} can wrap it in an
   * {@link EscapingAppendable} and let escape processing happen char-by-char
   * during the write rather than as a second pass over a buffered String.</p>
   */
  public interface LeafTextWriter {
    void appendLeaf(Appendable out, Object leaf, char delim, CodecContext ctx)
        throws SQLException, IOException;
  }

  /**
   * Strategy for reading one leaf-level 1-D slice from a {@link LiteralCursor}
   * into a typed Java array. The read counterpart of {@link LeafTextWriter}.
   */
  public interface LeafTextReader {
    /**
     * Reads {@code Array.getLength(leaf)} elements from {@code cur} into
     * {@code leaf}, consuming the element delimiters between them but not the
     * surrounding braces.
     */
    void readLeafText(LiteralCursor cur, Object leaf, char delim, CodecContext ctx)
        throws SQLException;
  }

  public static String encode(Object javaArray, char delim, CodecContext ctx, LeafTextWriter leaf)
      throws SQLException {
    StringBuilder sb = new StringBuilder(128);
    try {
      encode(javaArray, delim, sb, ctx, leaf);
    } catch (IOException e) {
      throw new AssertionError(e); // StringBuilder never throws
    }
    return sb.toString();
  }

  /**
   * Streaming variant: writes the array literal directly into {@code out}.
   */
  public static void encode(Object javaArray, char delim, Appendable out, CodecContext ctx, LeafTextWriter leaf) throws SQLException, IOException {
    int dimensions = MultiDimArraySupport.computeDimensions(javaArray, leafElementClassOf(leaf));
    if (dimensions == 0) {
      throw Exceptions.requiresJavaArray("MultiDimArrayText.encode", javaArray);
    }
    int[] dimLengths = MultiDimArraySupport.computeDimensionLengths(javaArray, dimensions);
    if (MultiDimArraySupport.isEmpty(dimLengths)) {
      // An empty array renders as {} regardless of its Java dimensionality: PostgreSQL has no
      // multi-dimensional empty array (it rejects a literal such as {{},{}}), and {} decodes back to
      // the same empty shape the binary path produces from its zero-dimension header.
      out.append("{}");
      return;
    }
    walk(out, javaArray, dimensions, delim, ctx, leaf);
  }

  /**
   * The leaf's element Java class when it is an {@link ArrayLeafCodec} (so a
   * {@code byte[]} element of {@code bytea} is counted as a leaf, not a
   * dimension), otherwise {@code null} for a plain {@link LeafTextWriter}.
   */
  private static @Nullable Class<?> leafElementClassOf(LeafTextWriter leaf) {
    return leaf instanceof ArrayLeafCodec ? ((ArrayLeafCodec) leaf).getBoxedComponentType() : null;
  }

  private static void walk(Appendable out, Object array, int depth, char delim,
      CodecContext ctx, LeafTextWriter leaf) throws SQLException, IOException {
    if (depth == 1) {
      out.append('{');
      leaf.appendLeaf(out, array, delim, ctx);
      out.append('}');
      return;
    }
    out.append('{');
    int length = java.lang.reflect.Array.getLength(array);
    for (int i = 0; i < length; i++) {
      if (i > 0) {
        out.append(delim);
      }
      walk(out, java.lang.reflect.Array.get(array, i), depth - 1, delim, ctx, leaf);
    }
    out.append('}');
  }

  // ---------------------------- decode ----------------------------

  /**
   * Decodes a PostgreSQL array text literal into a typed Java array whose leaf
   * component type is {@code leafComponentType} (e.g. {@code int.class},
   * {@code Integer.class}). The element loop is delegated to {@code leaf}, which
   * pulls one value per element from the shared {@link LiteralCursor}.
   *
   * <p>The shape is discovered in a cheap structural pass before allocation:
   * the dimensionality from the leading braces and each dimension's length by
   * counting children (quote-aware) — the text analogue of reading the binary
   * array header. A second pass fills the typed array.</p>
   */
  public static Object decode(String data, Class<?> leafComponentType, char delim,
      CodecContext ctx, ArrayLeafCodec leaf) throws SQLException {
    if (!leaf.supportsTargetComponent(leafComponentType)) {
      throw Exceptions.arrayLeafCodecUnsupported(leaf.getElementOid(), leafComponentType.getName());
    }
    char[] chars = data.toCharArray();

    LiteralCursor measure = new LiteralCursor(chars, 0, chars.length);
    measure.skipDimensionPrefix();
    int dimensions = measure.countLeadingBraces();
    if (dimensions == 0) {
      throw Exceptions.requiresArrayLiteral("MultiDimArrayText.decode", data);
    }
    int[] dimLengths = new int[dimensions];
    measureDim(measure, dimLengths, 0, dimensions, delim);

    Object result = java.lang.reflect.Array.newInstance(leafComponentType, dimLengths);

    LiteralCursor values = new LiteralCursor(chars, 0, chars.length);
    values.skipDimensionPrefix();
    walkAndDecode(values, result, dimensions, delim, ctx, leaf);
    return result;
  }

  private static void measureDim(LiteralCursor cur, int[] dimLengths, int depth, int dimensions,
      char delim) throws SQLException {
    cur.expect('{');
    if (cur.tryConsume('}')) {
      dimLengths[depth] = 0;
      return;
    }
    int count = 0;
    do {
      count++;
      if (depth + 1 < dimensions) {
        if (count == 1) {
          measureDim(cur, dimLengths, depth + 1, dimensions, delim);
        } else {
          cur.skipSubarray();
        }
      } else {
        cur.skipScalar(delim, '}');
      }
    } while (cur.tryConsume(delim));
    cur.expect('}');
    dimLengths[depth] = count;
  }

  private static void walkAndDecode(LiteralCursor cur, Object container, int depth, char delim,
      CodecContext ctx, ArrayLeafCodec leaf) throws SQLException {
    cur.expect('{');
    if (depth == 1) {
      leaf.readLeafText(cur, container, delim, ctx);
    } else {
      int length = java.lang.reflect.Array.getLength(container);
      for (int i = 0; i < length; i++) {
        if (i > 0) {
          cur.expect(delim);
        }
        walkAndDecode(cur, java.lang.reflect.Array.get(container, i), depth - 1, delim, ctx, leaf);
      }
    }
    cur.expect('}');
  }
}
