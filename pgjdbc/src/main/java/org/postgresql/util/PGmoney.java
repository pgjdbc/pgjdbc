/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.Serializable;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.ParseException;

/**
 * This implements a class that handles the PostgreSQL money and cash types.
 */
public class PGmoney extends PGobject implements Serializable, Cloneable {
  /*
   * The value of the field
   */
  public double val;

  /**
   * @param value of field
   */
  public PGmoney(double value) {
    this();
    val = value;
  }

  public PGmoney(String value) throws SQLException {
    this();
    setValue(value);
  }

  /*
   * Required by the driver
   */
  public PGmoney() {
    setType("money");
  }

  public void setValue(String s) throws SQLException {

    DecimalFormat decimalFormatter = connection.getMonetaryFormatter();

    try {
      val = decimalFormatter.parse(s).doubleValue();
    } catch (NumberFormatException e) {
      throw new PSQLException(GT.tr("Conversion of money failed."),
        PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE, e);
    } catch (ParseException pe) {
      throw new PSQLException(GT.tr("Unable to parse %s as money", s),
      PSQLState.INVALID_PARAMETER_VALUE, pe);
    }
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    long temp;
    temp = Double.doubleToLongBits(val);
    result = prime * result + (int) (temp ^ (temp >>> 32));
    return result;
  }

  public boolean equals(Object obj) {
    if (obj instanceof PGmoney) {
      PGmoney p = (PGmoney) obj;
      return val == p.val;
    }
    return false;
  }

  public String getValue() {
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
