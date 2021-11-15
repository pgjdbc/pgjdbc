/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.geometric;

import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PGtokenizer;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;

/**
 * This represents org.postgresql's circle datatype, consisting of a point and a radius.
 */
public class PGcircle extends PGobject implements Serializable, Cloneable {
  /**
   * This is the center point.
   */
  public @Nullable PGpoint center;

  /**
   * This is the radius.
   */
  public double radius;

  /**
   * @param x coordinate of center
   * @param y coordinate of center
   * @param r radius of circle
   */
  public PGcircle(double x, double y, double r) {
    this(new PGpoint(x, y), r);
  }

  /**
   * @param c PGpoint describing the circle's center
   * @param r radius of circle
   */
  public PGcircle(PGpoint c, double r) {
    this();
    this.center = c;
    this.radius = r;
  }

  /**
   * @param s definition of the circle in PostgreSQL's syntax.
   * @throws SQLException on conversion failure
   */
  @SuppressWarnings("method.invocation.invalid")
  public PGcircle(String s) throws SQLException {
    this();
    setValue(s);
  }

  /**
   * This constructor is used by the driver.
   */
  public PGcircle() {
    type = "circle";
  }

  /**
   * @param s definition of the circle in PostgreSQL's syntax.
   * @throws SQLException on conversion failure
   */
  @Override
  public void setValue(@Nullable String s) throws SQLException {
    if (s == null) {
      center = null;
      return;
    }
    PGtokenizer t = new PGtokenizer(PGtokenizer.removeAngle(s), ',');
    if (t.getSize() != 2) {
      throw new PSQLException(GT.tr("Conversion to type {0} failed: {1}.", type, s),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    try {
      center = new PGpoint(t.getToken(0));
      radius = Double.parseDouble(t.getToken(1));
    } catch (NumberFormatException e) {
      throw new PSQLException(GT.tr("Conversion to type {0} failed: {1}.", type, s),
          PSQLState.DATA_TYPE_MISMATCH, e);
    }
  }

  /**
   * @param obj Object to compare with
   * @return true if the two circles are identical
   */
  public boolean equals(@Nullable Object obj) {
    if (obj instanceof PGcircle) {
      PGcircle p = (PGcircle) obj;
      PGpoint center = this.center;
      PGpoint pCenter = p.center;
      if (center == null) {
        return pCenter == null;
      } else if (pCenter == null) {
        return false;
      }

      return p.radius == radius && equals(pCenter, center);
    }
    return false;
  }

  public int hashCode() {
    if (center == null) {
      return 0;
    }
    long bits = Double.doubleToLongBits(radius);
    int v = (int) (bits ^ (bits >>> 32));
    v = v * 31 + center.hashCode();
    return v;
  }

  public Object clone() throws CloneNotSupportedException {
    PGcircle newPGcircle = (PGcircle) super.clone();
    if (newPGcircle.center != null) {
      newPGcircle.center = (PGpoint) newPGcircle.center.clone();
    }
    return newPGcircle;
  }

  /**
   * @return the PGcircle in the syntax expected by org.postgresql
   */
  public @Nullable String getValue() {
    return center == null ? null : "<" + center + "," + radius + ">";
  }
}
