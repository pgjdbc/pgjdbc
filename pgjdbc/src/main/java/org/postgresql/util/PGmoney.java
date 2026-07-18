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

  @SuppressWarnings("method.invocation")
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

  @Override
  public void setValue(@Nullable String s) throws SQLException {
    isNull = s == null;
    if (s == null) {
      return;
    }
    try {
      String t = s.trim();
      boolean negative = false;

      // A negative amount renders either as "($1.00)" or, in most locales, as "-$1.00".
      if (t.length() >= 2 && t.charAt(0) == '(' && t.charAt(t.length() - 1) == ')') {
        negative = true;
        t = t.substring(1, t.length() - 1);
      }

      // Keep only the digits and decimal point; drop the currency symbol, grouping separators and a
      // leading sign. This handles the "-$1.00" form that the previous single-character strip missed.
      StringBuilder digits = new StringBuilder(t.length());
      for (int i = 0; i < t.length(); i++) {
        char c = t.charAt(i);
        if (c >= '0' && c <= '9' || c == '.') {
          digits.append(c);
        } else if (c == '-' && digits.length() == 0) {
          negative = true;
        }
      }

      val = Double.parseDouble(digits.toString());
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

  @Override
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

  @Override
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
