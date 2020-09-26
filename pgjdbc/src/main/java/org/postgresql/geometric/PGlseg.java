/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.geometric;

import org.postgresql.exception.PgSqlState;
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PGtokenizer;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;

/**
 * This implements a lseg (line segment) consisting of two points.
 */
public class PGlseg extends PGobject implements Serializable, Cloneable {
  /**
   * These are the two points.
   */
  public PGpoint[] point = new PGpoint[2];

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
    this.point[0] = p1;
    this.point[1] = p2;
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
  public void setValue(String s) throws SQLException {
    PGtokenizer t = new PGtokenizer(PGtokenizer.removeBox(s), ',');
    if (t.getSize() != 2) {
      throw new SQLSyntaxErrorException(GT.tr("Conversion to type {0} failed: {1}.", type, s),
          PgSqlState.DATATYPE_MISMATCH);
    }

    point[0] = new PGpoint(t.getToken(0));
    point[1] = new PGpoint(t.getToken(1));
  }

  /**
   * @param obj Object to compare with
   * @return true if the two line segments are identical
   */
  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj instanceof PGlseg) {
      PGlseg p = (PGlseg) obj;
      return (p.point[0].equals(point[0]) && p.point[1].equals(point[1]))
          || (p.point[0].equals(point[1]) && p.point[1].equals(point[0]));
    }
    return false;
  }

  @Override
  public int hashCode() {
    return point[0].hashCode() ^ point[1].hashCode();
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    PGlseg newPGlseg = (PGlseg) super.clone();
    if (newPGlseg.point != null) {
      newPGlseg.point = newPGlseg.point.clone();
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
  @Override
  public String getValue() {
    return "[" + point[0] + "," + point[1] + "]";
  }
}
