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
 * This implements a lseg (line segment) consisting of two points.
 */
public class PGlseg extends PGobject implements Serializable, Cloneable {
  /**
   * These are the two points.
   */
  public PGpoint @Nullable [] point;

  /**
   * @param x1 coordinate for first point
   * @param y1 coordinate for first point
   * @param x2 coordinate for second point
   * @param y2 coordinate for second point
   */
  public PGlseg(double x1, double y1, double x2, double y2) {
    this(new PGpoint(x1, y1), new PGpoint(x2, y2));
  }

  /**
   * @param p1 first point
   * @param p2 second point
   */
  public PGlseg(PGpoint p1, PGpoint p2) {
    this();
    point = new PGpoint[]{p1, p2};
  }

  /**
   * @param s definition of the line segment in PostgreSQL's syntax.
   * @throws SQLException on conversion failure
   */
  @SuppressWarnings("method.invocation.invalid")
  public PGlseg(String s) throws SQLException {
    this();
    setValue(s);
  }

  /**
   * required by the driver.
   */
  public PGlseg() {
    type = "lseg";
  }

  /**
   * @param s Definition of the line segment in PostgreSQL's syntax
   * @throws SQLException on conversion failure
   */
  @Override
  public void setValue(@Nullable String s) throws SQLException {
    if (s == null) {
      point = null;
      return;
    }
    PGtokenizer t = new PGtokenizer(PGtokenizer.removeBox(s), ',');
    if (t.getSize() != 2) {
      throw new PSQLException(GT.tr("Conversion to type {0} failed: {1}.", type, s),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    PGpoint[] point = this.point;
    if (point == null) {
      this.point = point = new PGpoint[2];
    }
    point[0] = new PGpoint(t.getToken(0));
    point[1] = new PGpoint(t.getToken(1));
  }

  /**
   * @param obj Object to compare with
   * @return true if the two line segments are identical
   */
  public boolean equals(@Nullable Object obj) {
    if (obj instanceof PGlseg) {
      PGlseg p = (PGlseg) obj;
      PGpoint[] point = this.point;
      PGpoint[] pPoint = p.point;
      if (point == null) {
        return pPoint == null;
      } else if (pPoint == null) {
        return false;
      }
      return (pPoint[0].equals(point[0]) && pPoint[1].equals(point[1]))
          || (pPoint[0].equals(point[1]) && pPoint[1].equals(point[0]));
    }
    return false;
  }

  public int hashCode() {
    PGpoint[] point = this.point;
    if (point == null) {
      return 0;
    }
    return point[0].hashCode() ^ point[1].hashCode();
  }

  public Object clone() throws CloneNotSupportedException {
    PGlseg newPGlseg = (PGlseg) super.clone();
    if (newPGlseg.point != null) {
      newPGlseg.point = (PGpoint[]) newPGlseg.point.clone();
      for (int i = 0; i < newPGlseg.point.length; ++i) {
        if (newPGlseg.point[i] != null) {
          newPGlseg.point[i] = (PGpoint) newPGlseg.point[i].clone();
        }
      }
    }
    return newPGlseg;
  }

  /**
   * @return the PGlseg in the syntax expected by org.postgresql
   */
  public @Nullable String getValue() {
    PGpoint[] point = this.point;
    if (point == null) {
      return null;
    }
    return "[" + point[0] + "," + point[1] + "]";
  }
}
