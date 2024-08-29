/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.core.v3;

import org.postgresql.core.ParameterList;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.index.qual.Positive;
import org.checkerframework.checker.nullness.qual.Nullable;

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

  private int findSubParam(@Positive int index) throws SQLException {
    if (index < 1 || index > total) {
      throw new PSQLException(
          GT.tr("The column index is out of range: {0}, number of columns: {1}.", index, total),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    for (int i = offsets.length - 1; i >= 0; i--) {
      if (offsets[i] < index) {
        return i;
      }
    }

    throw new IllegalArgumentException("I am confused; can't find a subparam for index " + index);
  }

  @Override
  public void registerOutParameter(@Positive int index, int sqlType) {

  }

  public int getDirection(int i) {
    return 0;
  }

  @Override
  public @NonNegative int getParameterCount() {
    return total;
  }

  @Override
  public @NonNegative int getInParameterCount() {
    return total;
  }

  @Override
  public @NonNegative int getOutParameterCount() {
    return 0;
  }

  @Override
  public int[] getTypeOIDs() {
    int[] oids = new int[total];
    for (int i = 0; i < offsets.length; i++) {
      int[] subOids = subparams[i].getTypeOIDs();
      System.arraycopy(subOids, 0, oids, offsets[i], subOids.length);
    }
    return oids;
  }

  @Override
  public void setIntParameter(@Positive int index, int value) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setIntParameter(index - offsets[sub], value);
  }

  @Override
  public void setLiteralParameter(@Positive int index, String value, int oid) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setStringParameter(index - offsets[sub], value, oid);
  }

  @Override
  public void setStringParameter(@Positive int index, String value, int oid) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setStringParameter(index - offsets[sub], value, oid);
  }

  @Override
  public void setBinaryParameter(@Positive int index, byte[] value, int oid) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setBinaryParameter(index - offsets[sub], value, oid);
  }

  @Override
  public void setBytea(@Positive int index, byte[] data, int offset, @NonNegative int length) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setBytea(index - offsets[sub], data, offset, length);
  }

  @Override
  public void setBytea(@Positive int index, InputStream stream, @NonNegative int length) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setBytea(index - offsets[sub], stream, length);
  }

  @Override
  public void setBytea(@Positive int index, InputStream stream) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setBytea(index - offsets[sub], stream);
  }

  @Override
  public void setBytea(@Positive int index, ByteStreamWriter writer) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setBytea(index - offsets[sub], writer);
  }

  @Override
  public void setText(@Positive int index, InputStream stream) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setText(index - offsets[sub], stream);
  }

  @Override
  public void setNull(@Positive int index, int oid) throws SQLException {
    int sub = findSubParam(index);
    subparams[sub].setNull(index - offsets[sub], oid);
  }

  @Override
  public String toString(@Positive int index, boolean standardConformingStrings) {
    return toString(index, SqlSerializationContext.of(standardConformingStrings, true));
  }

  @Override
  public String toString(@Positive int index, SqlSerializationContext context) {
    try {
      int sub = findSubParam(index);
      return subparams[sub].toString(index - offsets[sub], context);
    } catch (SQLException e) {
      throw new IllegalStateException(e.getMessage());
    }
  }

  @Override
  public ParameterList copy() {
    SimpleParameterList[] copySub = new SimpleParameterList[subparams.length];
    for (int sub = 0; sub < subparams.length; sub++) {
      copySub[sub] = (SimpleParameterList) subparams[sub].copy();
    }

    return new CompositeParameterList(copySub, offsets);
  }

  @Override
  public void clear() {
    for (SimpleParameterList subparam : subparams) {
      subparam.clear();
    }
  }

  @Override
  public SimpleParameterList @Nullable [] getSubparams() {
    return subparams;
  }

  @Override
  public void checkAllParametersSet() throws SQLException {
    for (SimpleParameterList subparam : subparams) {
      subparam.checkAllParametersSet();
    }
  }

  @Override
  public byte @Nullable [][] getEncoding() {
    return null; // unsupported
  }

  @Override
  public byte @Nullable [] getFlags() {
    return null; // unsupported
  }

  @Override
  public int @Nullable [] getParamTypes() {
    return null; // unsupported
  }

  @Override
  public @Nullable Object @Nullable [] getValues() {
    return null; // unsupported
  }

  @Override
  public void appendAll(ParameterList list) throws SQLException {
    // no-op, unsupported
  }

  @Override
  public void convertFunctionOutParameters() {
    for (SimpleParameterList subparam : subparams) {
      subparam.convertFunctionOutParameters();
    }
  }

  private final @Positive int total;
  private final SimpleParameterList[] subparams;
  private final int[] offsets;
}
