/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;

/**
 * This implements a class that handles the PostgreSQL money and cash types.
 */
public class PGmoney extends PGobject implements Serializable, Cloneable {
  /*
   * The value of the field
   */
  public double val;

  /**
   * If the object represents {@code null::money}
   */
  public boolean isNull;

  /**
   * @param value of field
   */
  public PGmoney(double value) {
    this();
    val = value;
  }

  @SuppressWarnings("method.invocation.invalid")
  public PGmoney(String value) throws SQLException {
    this();
    setValue(value);
  }

  /*
   * Required by the driver
   */
  public PGmoney() {
    type = "money";
  }

  public void setValue(@Nullable String s) throws SQLException {
    isNull = s == null;
    if (s == null) {
      return;
    }
    try {
      String s1;
      boolean negative;

      negative = (s.charAt(0) == '(');

      // Remove any () (for negative) & currency symbol
      s1 = PGtokenizer.removePara(s).substring(1);

      // Strip out any , in currency
      int pos = s1.indexOf(',');
      while (pos != -1) {
        s1 = s1.substring(0, pos) + s1.substring(pos + 1);
        pos = s1.indexOf(',');
      }

      val = Double.parseDouble(s1);
      val = negative ? -val : val;

    } catch (NumberFormatException e) {
      throw new PSQLException(GT.tr("Conversion of money failed."),
          PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE, e);
    }
  }

  @Override
  public int hashCode() {
    if (isNull) {
      return 0;
    }
    final int prime = 31;
    int result = super.hashCode();
    long temp;
    temp = Double.doubleToLongBits(val);
    result = prime * result + (int) (temp ^ (temp >>> 32));
    return result;
  }

  public boolean equals(@Nullable Object obj) {
    if (obj instanceof PGmoney) {
      PGmoney p = (PGmoney) obj;
      if (isNull) {
        return p.isNull;
      } else if (p.isNull) {
        return false;
      }
      return val == p.val;
    }
    return false;
  }

  public @Nullable String getValue() {
    if (isNull) {
      return null;
    }
    if (val < 0) {
      return "-$" + (-val);
    } else {
      return "$" + val;
    }
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    // squid:S2157 "Cloneables" should implement "clone
    return super.clone();
  }
}
