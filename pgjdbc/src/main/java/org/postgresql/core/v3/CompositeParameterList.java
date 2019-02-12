/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.core.v3;

import org.postgresql.core.ParameterList;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.InputStream;
import java.sql.SQLException;

/**
 * Parameter list for V3 query strings that contain multiple statements. We delegate to one
 * SimpleParameterList per statement, and translate parameter indexes as needed.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class CompositeParameterList implements V3ParameterList {
  CompositeParameterList(SimpleParameterList[] subparams, int[] offsets) {
    this.subparams = subparams;
    this.offsets = offsets;
    this.total = offsets[offsets.length - 1] + subparams[offsets.length - 1].getInParameterCount();
  }

  private int findSubParam(int index) throws SQLException {
    if (index < 1 || index > total) {
      throw new PSQLException(
          GT.tr("The column index is out of range: {0}, number of columns: {1}.", index, total),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    for (int i = offsets.length - 1; i >= 0; --i) {
      if (offsets[i] < index) {
        return i;
      }
    }

    throw new IllegalArgumentException("I am confused; can't find a subparam for index " + index);
  }

  @Override
  public void registerOutParameter(int index, String pgType, int scale, int sqlType) {

  }

  public int getDirection(int i) {
    return 0;
  }

  public int getParameterCount() {
    return total;
  }

  public int getInParameterCount() {
    return total;
  }

  public int getOutParameterCount() {
    return 0;
  }

  public int[] getTypeOIDs() {
    int[] oids = new int[total];
    for (int i = 0; i < offsets.length; i++) {
      int[] subOids = subparams[i].getTypeOIDs();
      System.arraycopy(subOids, 0, oids, offsets[i], subOids.length);
    }
    return oids;
  }

  @Override
  public String[] getPgTypes() {
    String[] pgTypes = new String[total];
    for (int i = 0; i < offsets.length; i++) {
      String[] subPgTypes = subparams[i].getPgTypes();
      System.arraycopy(subPgTypes, 0, pgTypes, offsets[i], subPgTypes.length);
    }
    return pgTypes;
  }

  @Override
  public int[] getScales() {
    int[] scales = new int[total];
    for (int i = 0; i < offsets.length; i++) {
      int[] subScales = subparams[i].getScales();
      System.arraycopy(subScales, 0, scales, offsets[i], subScales.length);
    }
    return scales;
  }

  @Override
  public void setIntParameter(int index, String pgType, int value) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setIntParameter(index - offsets[sub], pgType, value);
  }

  @Override
  public void setLiteralParameter(int index, String pgType, int scale, String value, int oid) throws SQLException {
    int sub = findSubParam(index);
    // TODO: Should this be a call to setLiteralParameter?
    subparams[sub].setStringParameter(index - offsets[sub], pgType, scale, value, oid);
  }

  @Override
  public void setStringParameter(int index, String pgType, int scale, String value, int oid) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setStringParameter(index - offsets[sub], pgType, scale, value, oid);
  }

  @Override
  public void setBinaryParameter(int index, String pgType, byte[] value, int oid) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setBinaryParameter(index - offsets[sub], pgType, value, oid);
  }

  @Override
  public void setBytea(int index, String pgType, byte[] data, int offset, int length) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setBytea(index - offsets[sub], pgType, data, offset, length);
  }

  @Override
  public void setBytea(int index, String pgType, InputStream stream, int length) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setBytea(index - offsets[sub], pgType, stream, length);
  }

  @Override
  public void setBytea(int index, String pgType, int scaleOrLength, InputStream stream) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setBytea(index - offsets[sub], pgType, scaleOrLength, stream);
  }

  @Override
  public void setText(int index, String pgType, int scaleOrLength, InputStream stream) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setText(index - offsets[sub], pgType, scaleOrLength, stream);
  }

  @Override
  public void setNull(int index, String pgType, int scale, int oid) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setNull(index - offsets[sub], pgType, scale, oid);
  }

  public String toString(int index, boolean standardConformingStrings) {
    try {
      int sub = findSubParam(index);
      return subparams[sub].toString(index - offsets[sub], standardConformingStrings);
    } catch (SQLException e) {
      throw new IllegalStateException(e.getMessage());
    }
  }

  public ParameterList copy() {
    SimpleParameterList[] copySub = new SimpleParameterList[subparams.length];
    for (int sub = 0; sub < subparams.length; ++sub) {
      copySub[sub] = (SimpleParameterList) subparams[sub].copy();
    }

    return new CompositeParameterList(copySub, offsets);
  }

  public void clear() {
    for (SimpleParameterList subparam : subparams) {
      subparam.clear();
    }
  }

  public SimpleParameterList[] getSubparams() {
    return subparams;
  }

  public void checkAllParametersSet() throws SQLException {
    for (SimpleParameterList subparam : subparams) {
      subparam.checkAllParametersSet();
    }
  }

  public byte[][] getEncoding() {
    return null; // unsupported
  }

  public byte[] getFlags() {
    return null; // unsupported
  }

  public int[] getParamTypes() {
    return null; // unsupported
  }

  public Object[] getValues() {
    return null; // unsupported
  }

  public void appendAll(ParameterList list) throws SQLException {
    // no-op, unsupported
  }

  public void convertFunctionOutParameters() {
    for (SimpleParameterList subparam : subparams) {
      subparam.convertFunctionOutParameters();
    }
  }

  private final int total;
  private final SimpleParameterList[] subparams;
  private final int[] offsets;
}
