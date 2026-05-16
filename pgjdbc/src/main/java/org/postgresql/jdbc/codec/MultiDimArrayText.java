/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.jdbc.CodecContext;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

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
    int dimensions = MultiDimArraySupport.computeDimensions(javaArray);
    if (dimensions == 0) {
      throw new PSQLException(
          GT.tr("MultiDimArrayText.encode requires a Java array, got {0}",
              javaArray.getClass().getName()),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    MultiDimArraySupport.computeDimensionLengths(javaArray, dimensions);
    walk(out, javaArray, dimensions, delim, ctx, leaf);
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
}
