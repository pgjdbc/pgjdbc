/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.geometric;

import org.postgresql.util.PGobject;
import org.postgresql.util.PGtokenizer;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;

/**
 * This implements the polygon datatype within PostgreSQL.
 */
public class PGpolygon extends PGobject implements Serializable, Cloneable {
  /**
   * The points defining the polygon.
   */
  public PGpoint @Nullable [] points;

  /**
   * Creates a polygon using an array of PGpoints.
   *
   * @param points the points defining the polygon
   */
  public PGpolygon(PGpoint[] points) {
    this();
    this.points = points;
  }

  /**
   * @param s definition of the polygon in PostgreSQL's syntax.
   * @throws SQLException on conversion failure
   */
  @SuppressWarnings("method.invocation.invalid")
  public PGpolygon(String s) throws SQLException {
    this();
    setValue(s);
  }

  /**
   * Required by the driver.
   */
  public PGpolygon() {
    type = "polygon";
  }

  /**
   * @param s Definition of the polygon in PostgreSQL's syntax
   * @throws SQLException on conversion failure
   */
  public void setValue(@Nullable String s) throws SQLException {
    if (s == null) {
      points = null;
      return;
    }
    PGtokenizer t = new PGtokenizer(PGtokenizer.removePara(s), ',');
    int npoints = t.getSize();
    PGpoint[] points = this.points;
    if (points == null || points.length != npoints) {
      this.points = points = new PGpoint[npoints];
    }
    for (int p = 0; p < npoints; p++) {
      points[p] = new PGpoint(t.getToken(p));
    }
  }

  /**
   * @param obj Object to compare with
   * @return true if the two polygons are identical
   */
  public boolean equals(@Nullable Object obj) {
    if (obj instanceof PGpolygon) {
      PGpolygon p = (PGpolygon) obj;

      PGpoint[] points = this.points;
      PGpoint[] pPoints = p.points;
      if (points == null) {
        return pPoints == null;
      } else if (pPoints == null) {
        return false;
      }

      if (pPoints.length != points.length) {
        return false;
      }

      for (int i = 0; i < points.length; i++) {
        if (!points[i].equals(pPoints[i])) {
          return false;
        }
      }

      return true;
    }
    return false;
  }

  public int hashCode() {
    int hash = 0;
    PGpoint[] points = this.points;
    if (points == null) {
      return hash;
    }
    for (int i = 0; i < points.length && i < 5; ++i) {
      hash = hash * 31 + points[i].hashCode();
    }
    return hash;
  }

  public Object clone() throws CloneNotSupportedException {
    PGpolygon newPGpolygon = (PGpolygon) super.clone();
    if (newPGpolygon.points != null) {
      PGpoint[] newPoints = newPGpolygon.points.clone();
      newPGpolygon.points = newPoints;
      for (int i = 0; i < newPGpolygon.points.length; ++i) {
        if (newPGpolygon.points[i] != null) {
          newPoints[i] = (PGpoint) newPGpolygon.points[i].clone();
        }
      }
    }
    return newPGpolygon;
  }

  /**
   * @return the PGpolygon in the syntax expected by org.postgresql
   */
  public @Nullable String getValue() {
    PGpoint[] points = this.points;
    if (points == null) {
      return null;
    }
    StringBuilder b = new StringBuilder();
    b.append("(");
    for (int p = 0; p < points.length; p++) {
      if (p > 0) {
        b.append(",");
      }
      b.append(points[p].toString());
    }
    b.append(")");
    return b.toString();
  }
}
