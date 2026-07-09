/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.sql.SQLException;

/**
 * Parses and formats a PostgreSQL {@code point} literal, in both its text ({@code (x,y)}) and
 * binary (two big-endian {@code float8}) wire forms.
 *
 * <p>A point is the primitive every other geometric type is built from: box, lseg, path, and
 * polygon each nest one or more of them, and circle nests one as its center. {@link
 * org.postgresql.geometric.PGpoint} and the geometric codecs in {@code org.postgresql.jdbc.codec}
 * both share this class rather than each parsing and formatting a point on their own.</p>
 */
public final class PGpointFormat {
  private PGpointFormat() {
  }

  /**
   * Parses a {@code (x,y)} point literal.
   *
   * @param literal the point literal, including its parentheses
   * @return a two-element array, {@code {x, y}}
   * @throws SQLException if {@code literal} is not a well-formed point
   */
  public static double[] parseText(String literal) throws SQLException {
    PGtokenizer t = new PGtokenizer(PGtokenizer.removePara(literal), ',');
    try {
      if (t.getSize() != 2) {
        throw new NumberFormatException("expected 2 coordinates, got " + t.getSize());
      }
      return new double[] {Double.parseDouble(t.getToken(0)), Double.parseDouble(t.getToken(1))};
    } catch (NumberFormatException e) {
      throw new PSQLException(GT.tr("Conversion to type {0} failed: {1}.", "point", literal),
          PSQLState.DATA_TYPE_MISMATCH, e);
    }
  }

  /**
   * Appends a point in {@code (x,y)} form.
   *
   * @param sb the buffer to append to
   * @param x the point's x coordinate
   * @param y the point's y coordinate
   * @return {@code sb}, for chaining
   */
  public static StringBuilder appendText(StringBuilder sb, double x, double y) {
    return sb.append('(').append(x).append(',').append(y).append(')');
  }

  /**
   * Reads a point from its binary wire form: two big-endian {@code float8} values.
   *
   * @param data the backing buffer
   * @param offset start of the point's 16 bytes within {@code data}
   * @return a two-element array, {@code {x, y}}
   */
  public static double[] parseBinary(byte[] data, int offset) {
    return new double[] {ByteConverter.float8(data, offset), ByteConverter.float8(data, offset + 8)};
  }

  /**
   * Writes a point in its binary wire form: two big-endian {@code float8} values.
   *
   * @param target the backing buffer
   * @param offset start of the point's 16 bytes within {@code target}
   * @param x the point's x coordinate
   * @param y the point's y coordinate
   */
  public static void appendBinary(byte[] target, int offset, double x, double y) {
    ByteConverter.float8(target, offset, x);
    ByteConverter.float8(target, offset + 8, y);
  }
}
