/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.geometric;

import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;
import org.postgresql.util.PGtokenizer;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.awt.Point;
import java.io.Serializable;
import java.sql.SQLException;

/**
 * <p>It maps to the point datatype in org.postgresql.</p>
 *
 * <p>This implements a version of java.awt.Point, except it uses double to represent the coordinates.</p>
 */
public class PGpoint extends PGobject implements PGBinaryObject, Serializable, Cloneable {
  /**
   * The X coordinate of the point.
   */
  public double x;

  /**
   * The Y coordinate of the point.
   */
  public double y;

  /**
   * True if the point represents {@code null::point}.
   */
  public boolean isNull;

  /**
   * @param x coordinate
   * @param y coordinate
   */
  public PGpoint(double x, double y) {
    this();
    this.x = x;
    this.y = y;
  }

  /**
   * This is called mainly from the other geometric types, when a point is embedded within their
   * definition.
   *
   * @param value Definition of this point in PostgreSQL's syntax
   * @throws SQLException if something goes wrong
   */
  @SuppressWarnings("method.invocation.invalid")
  public PGpoint(String value) throws SQLException {
    this();
    setValue(value);
  }

  /**
   * Required by the driver.
   */
  public PGpoint() {
    type = "point";
  }

  /**
   * @param s Definition of this point in PostgreSQL's syntax
   * @throws SQLException on conversion failure
   */
  @Override
  public void setValue(@Nullable String s) throws SQLException {
    isNull = s == null;
    if (s == null) {
      return;
    }
    PGtokenizer t = new PGtokenizer(PGtokenizer.removePara(s), ',');
    try {
      x = Double.parseDouble(t.getToken(0));
      y = Double.parseDouble(t.getToken(1));
    } catch (NumberFormatException e) {
      throw new PSQLException(GT.tr("Conversion to type {0} failed: {1}.", type, s),
          PSQLState.DATA_TYPE_MISMATCH, e);
    }
  }

  /**
   * @param b Definition of this point in PostgreSQL's binary syntax
   */
  public void setByteValue(byte[] b, int offset) {
    this.isNull = false;
    x = ByteConverter.float8(b, offset);
    y = ByteConverter.float8(b, offset + 8);
  }

  /**
   * @param obj Object to compare with
   * @return true if the two points are identical
   */
  public boolean equals(@Nullable Object obj) {
    if (obj instanceof PGpoint) {
      PGpoint p = (PGpoint) obj;
      if (isNull) {
        return p.isNull;
      } else if (p.isNull) {
        return false;
      }
      return x == p.x && y == p.y;
    }
    return false;
  }

  public int hashCode() {
    if (isNull) {
      return 0;
    }
    long v1 = Double.doubleToLongBits(x);
    long v2 = Double.doubleToLongBits(y);
    return (int) (v1 ^ v2 ^ (v1 >>> 32) ^ (v2 >>> 32));
  }

  /**
   * @return the PGpoint in the syntax expected by org.postgresql
   */
  public @Nullable String getValue() {
    return isNull ? null : "(" + x + "," + y + ")";
  }

  public int lengthInBytes() {
    return isNull ? 0 : 16;
  }

  /**
   * Populate the byte array with PGpoint in the binary syntax expected by org.postgresql.
   */
  public void toBytes(byte[] b, int offset) {
    if (isNull) {
      return;
    }
    ByteConverter.float8(b, offset, x);
    ByteConverter.float8(b, offset + 8, y);
  }

  /**
   * Translate the point by the supplied amount.
   *
   * @param x integer amount to add on the x axis
   * @param y integer amount to add on the y axis
   */
  public void translate(int x, int y) {
    translate((double) x, (double) y);
  }

  /**
   * Translate the point by the supplied amount.
   *
   * @param x double amount to add on the x axis
   * @param y double amount to add on the y axis
   */
  public void translate(double x, double y) {
    this.isNull = false;
    this.x += x;
    this.y += y;
  }

  /**
   * Moves the point to the supplied coordinates.
   *
   * @param x integer coordinate
   * @param y integer coordinate
   */
  public void move(int x, int y) {
    setLocation(x, y);
  }

  /**
   * Moves the point to the supplied coordinates.
   *
   * @param x double coordinate
   * @param y double coordinate
   */
  public void move(double x, double y) {
    this.isNull = false;
    this.x = x;
    this.y = y;
  }

  /**
   * Moves the point to the supplied coordinates. refer to java.awt.Point for description of this.
   *
   * @param x integer coordinate
   * @param y integer coordinate
   * @see java.awt.Point
   */
  public void setLocation(int x, int y) {
    move((double) x, (double) y);
  }

  /**
   * Moves the point to the supplied java.awt.Point refer to java.awt.Point for description of this.
   *
   * @param p Point to move to
   * @see java.awt.Point
   */
  public void setLocation(Point p) {
    setLocation(p.x, p.y);
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    // squid:S2157 "Cloneables" should implement "clone
    return super.clone();
  }
}
