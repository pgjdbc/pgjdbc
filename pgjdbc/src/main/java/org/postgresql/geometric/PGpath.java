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
 * This implements a path (a multiple segmented line, which may be closed).
 */
public class PGpath extends PGobject implements Serializable, Cloneable {
  /**
   * True if the path is open, false if closed.
   */
  public boolean open;

  /**
   * The points defining this path.
   */
  public PGpoint @Nullable [] points;

  /**
   * @param points the PGpoints that define the path
   * @param open True if the path is open, false if closed
   */
  public PGpath(PGpoint @Nullable [] points, boolean open) {
    this();
    this.points = points;
    this.open = open;
  }

  /**
   * Required by the driver.
   */
  public PGpath() {
    type = "path";
  }

  /**
   * @param s definition of the path in PostgreSQL's syntax.
   * @throws SQLException on conversion failure
   */
  @SuppressWarnings("method.invocation.invalid")
  public PGpath(String s) throws SQLException {
    this();
    setValue(s);
  }

  /**
   * @param s Definition of the path in PostgreSQL's syntax
   * @throws SQLException on conversion failure
   */
  public void setValue(@Nullable String s) throws SQLException {
    if (s == null) {
      points = null;
      return;
    }
    // First test to see if were open
    if (s.startsWith("[") && s.endsWith("]")) {
      open = true;
      s = PGtokenizer.removeBox(s);
    } else if (s.startsWith("(") && s.endsWith(")")) {
      open = false;
      s = PGtokenizer.removePara(s);
    } else {
      throw new PSQLException(GT.tr("Cannot tell if path is open or closed: {0}.", s),
          PSQLState.DATA_TYPE_MISMATCH);
    }

    PGtokenizer t = new PGtokenizer(s, ',');
    int npoints = t.getSize();
    PGpoint[] points = new PGpoint[npoints];
    this.points = points;
    for (int p = 0; p < npoints; p++) {
      points[p] = new PGpoint(t.getToken(p));
    }
  }

  /**
   * @param obj Object to compare with
   * @return true if the two paths are identical
   */
  public boolean equals(@Nullable Object obj) {
    if (obj instanceof PGpath) {
      PGpath p = (PGpath) obj;

      PGpoint[] points = this.points;
      PGpoint[] pPoints = p.points;
      if (points == null) {
        return pPoints == null;
      } else if (pPoints == null) {
        return false;
      }

      if (p.open != open) {
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
    PGpoint[] points = this.points;
    if (points == null) {
      return 0;
    }
    // XXX not very good..
    int hash = open ? 1231 : 1237;
    for (int i = 0; i < points.length && i < 5; ++i) {
      hash = hash * 31 + points[i].hashCode();
    }
    return hash;
  }

  public Object clone() throws CloneNotSupportedException {
    PGpath newPGpath = (PGpath) super.clone();
    if (newPGpath.points != null) {
      PGpoint[] newPoints = newPGpath.points.clone();
      newPGpath.points = newPoints;
      for (int i = 0; i < newPGpath.points.length; ++i) {
        newPoints[i] = (PGpoint) newPGpath.points[i].clone();
      }
    }
    return newPGpath;
  }

  /**
   * This returns the path in the syntax expected by org.postgresql.
   * @return the value of this object
   */
  public @Nullable String getValue() {
    PGpoint[] points = this.points;
    if (points == null) {
      return null;
    }
    StringBuilder b = new StringBuilder(open ? "[" : "(");

    for (int p = 0; p < points.length; p++) {
      if (p > 0) {
        b.append(",");
      }
      b.append(points[p].toString());
    }
    b.append(open ? "]" : ")");

    return b.toString();
  }

  public boolean isOpen() {
    return open && points != null;
  }

  public boolean isClosed() {
    return !open && points != null;
  }

  public void closePath() {
    open = false;
  }

  public void openPath() {
    open = true;
  }
}
