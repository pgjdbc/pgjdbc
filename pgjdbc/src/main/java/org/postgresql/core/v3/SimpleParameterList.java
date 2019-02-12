/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.core.v3;

import org.postgresql.core.Oid;
import org.postgresql.core.PGStream;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Utils;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGpoint;
import org.postgresql.jdbc.UUIDArrayAssistant;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.StreamWrapper;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Arrays;


/**
 * Parameter list for a single-statement V3 query.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class SimpleParameterList implements V3ParameterList {

  private static final byte IN = 1;
  private static final byte OUT = 2;
  private static final byte INOUT = IN | OUT;

  private static final byte TEXT = 0;
  private static final byte BINARY = 4;

  SimpleParameterList(int paramCount, TypeTransferModeRegistry transferModeRegistry) {
    this.paramValues = new Object[paramCount];
    this.paramTypes = new int[paramCount];
    this.paramPgTypes = new String[paramCount];
    this.scales = new int[paramCount];
    Arrays.fill(scales, -1);
    this.encoded = new byte[paramCount][];
    this.flags = new byte[paramCount];
    this.transferModeRegistry = transferModeRegistry;
  }

  @Override
  public void registerOutParameter(int index, String pgType, int scale, int sqlType) throws SQLException {
    if (index < 1 || index > paramValues.length) {
      throw new PSQLException(
          GT.tr("The column index is out of range: {0}, number of columns: {1}.",
              index, paramValues.length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    // TODO: Is paramTypes useful here, or always set on the return value from the server?
    // TODO: It is possible that paramPgTypes is already set and not null?  If so, do we ever overwrite it to null?
    if (pgType != null) {
      paramPgTypes[index - 1] = pgType;
    }
    if (scale >= 0) {
      scales[index - 1] = scale;
    }
    flags[index - 1] |= OUT;
  }

  /**
   * @param pgType the type name, if known, or {@code null} to use the default behavior
   * @param scaleOrLength the length or {@code -1} for none
   */
  private void bind(int index, String pgType, int scaleOrLength, Object value, int oid, byte binary) throws SQLException {
    if (index < 1 || index > paramValues.length) {
      throw new PSQLException(
          GT.tr("The column index is out of range: {0}, number of columns: {1}.",
              index, paramValues.length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    --index;

    encoded[index] = null;
    // TODO: Verify scaleOrLength
    paramValues[index] = value;
    flags[index] = (byte) (direction(index) | IN | binary);

    // If we are setting something to an UNSPECIFIED NULL, don't overwrite
    // our existing type for it. We don't need the correct type info to
    // send this value, and we don't want to overwrite and require a
    // reparse.
    if (oid == Oid.UNSPECIFIED && paramTypes[index] != Oid.UNSPECIFIED && value == NULL_OBJECT) {
      return;
    }

    paramTypes[index] = oid;
    if (pgType != null) {
      paramPgTypes[index] = pgType;
    }
    if (scaleOrLength >= 0) {
      scales[index] = scaleOrLength;
    }
    pos = index + 1;
  }

  public int getParameterCount() {
    return paramValues.length;
  }

  public int getOutParameterCount() {
    int count = 0;
    for (int i = 0; i < paramTypes.length; i++) {
      if ((direction(i) & OUT) == OUT) {
        count++;
      }
    }
    // Every function has at least one output.
    if (count == 0) {
      count = 1;
    }
    return count;

  }

  public int getInParameterCount() {
    int count = 0;
    for (int i = 0; i < paramTypes.length; i++) {
      if (direction(i) != OUT) {
        count++;
      }
    }
    return count;
  }

  @Override
  public void setIntParameter(int index, String pgType, int value) throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, value);
    bind(index, pgType, -1, data, Oid.INT4, BINARY);
  }

  @Override
  public void setLiteralParameter(int index, String pgType, int scale, String value, int oid) throws SQLException {
    bind(index, pgType, scale, value, oid, TEXT);
  }

  @Override
  public void setStringParameter(int index, String pgType, int scale, String value, int oid) throws SQLException {
    bind(index, pgType, scale, value, oid, TEXT);
  }

  @Override
  public void setBinaryParameter(int index, String pgType, byte[] value, int oid) throws SQLException {
    bind(index, pgType, -1, value, oid, BINARY);
  }

  @Override
  public void setBytea(int index, String pgType, byte[] data, int offset, int length) throws SQLException {
    bind(index, pgType, -1, new StreamWrapper(data, offset, length), Oid.BYTEA, BINARY);
  }

  @Override
  public void setBytea(int index, String pgType, InputStream stream, int length) throws SQLException {
    bind(index, pgType, length, new StreamWrapper(stream, length), Oid.BYTEA, BINARY);
  }

  @Override
  public void setBytea(int index, String pgType, int scaleOrLength, InputStream stream) throws SQLException {
    bind(index, pgType, scaleOrLength, new StreamWrapper(stream), Oid.BYTEA, BINARY);
  }

  @Override
  public void setText(int index, String pgType, int scaleOrLength, InputStream stream) throws SQLException {
    bind(index, pgType, scaleOrLength, new StreamWrapper(stream), Oid.TEXT, TEXT);
  }

  @Override
  public void setNull(int index, String pgType, int scale, int oid) throws SQLException {

    byte binaryTransfer = TEXT;

    if (transferModeRegistry.useBinaryForReceive(oid)) {
      binaryTransfer = BINARY;
    }
    bind(index, pgType, scale, NULL_OBJECT, oid, binaryTransfer);
  }

  // TODO: Should we be trying to maintain the specific user-defined data type inside simple queries?
  //       We are currently adding an additional ::cast to achieve this.
  @Override
  public String toString(int index, boolean standardConformingStrings) {
    --index;
    Object paramValue = paramValues[index];
    String paramPgType = paramPgTypes[index];
    // TODO: scale any affect here?
    if (paramValue == null) {
      return (paramPgType == null)
          ? "?"
          : ("?::" + paramPgType);
    } else if (paramValue == NULL_OBJECT) {
      return (paramPgType == null)
          ? "NULL"
          : ("NULL::" + paramPgType);
    } else if ((flags[index] & BINARY) == BINARY) {
      // handle some of the numeric types

      switch (paramTypes[index]) {
        case Oid.INT2:
          short s = ByteConverter.int2((byte[]) paramValue, 0);
          return (paramPgType == null)
              ? Short.toString(s)
              : (Short.toString(s) + "::" + paramPgType);

        case Oid.INT4:
          int i = ByteConverter.int4((byte[]) paramValue, 0);
          return (paramPgType == null)
              ? Integer.toString(i)
              : (Integer.toString(i) + "::" + paramPgType);

        case Oid.INT8:
          long l = ByteConverter.int8((byte[]) paramValue, 0);
          return (paramPgType == null)
              ? Long.toString(l)
              : (Long.toString(l) + "::" + paramPgType);

        case Oid.FLOAT4:
          float f = ByteConverter.float4((byte[]) paramValue, 0);
          if (Float.isNaN(f)) {
            return (paramPgType == null)
                ? "'NaN'::real"
                : ("'NaN'::real::" + paramPgType);
          }
          return (paramPgType == null)
              ? Float.toString(f)
              : (Float.toString(f) + "::" + paramPgType);

        case Oid.FLOAT8:
          double d = ByteConverter.float8((byte[]) paramValue, 0);
          if (Double.isNaN(d)) {
            return (paramPgType == null)
                ? "'NaN'::double precision"
                : ("'NaN'::double precision::" + paramPgType);
          }
          return (paramPgType == null)
              ? Double.toString(d)
              : (Double.toString(d) + "::" + paramPgType);

        case Oid.UUID:
          String uuid =
              new UUIDArrayAssistant().buildElement((byte[]) paramValue, 0, 16).toString();
          return (paramPgType == null)
              ? ("'" + uuid + "'::uuid")
              : ("'" + uuid + "'::uuid::" + paramPgType);

        case Oid.POINT:
          PGpoint pgPoint = new PGpoint();
          pgPoint.setByteValue((byte[]) paramValue, 0);
          return (paramPgType == null)
              ? ("'" + pgPoint.toString() + "'::point")
              : ("'" + pgPoint.toString() + "'::point::" + paramPgType);

        case Oid.BOX:
          PGbox pgBox = new PGbox();
          pgBox.setByteValue((byte[]) paramValue, 0);
          return (paramPgType == null)
              ? ("'" + pgBox.toString() + "'::box")
              : ("'" + pgBox.toString() + "'::box::" + paramPgType);
      }
      return (paramPgType == null)
          ? "?"
          : ("?::" + paramPgType);
    } else {
      String param = paramValue.toString();

      // add room for quotes + potential escaping.
      StringBuilder p = new StringBuilder(3 + (param.length() + 10) / 10 * 11);

      // No E'..' here since escapeLiteral escapes all things and it does not use \123 kind of
      // escape codes
      p.append('\'');
      try {
        p = Utils.escapeLiteral(p, param, standardConformingStrings);
      } catch (SQLException sqle) {
        // This should only happen if we have an embedded null
        // and there's not much we can do if we do hit one.
        //
        // The goal of toString isn't to be sent to the server,
        // so we aren't 100% accurate (see StreamWrapper), put
        // the unescaped version of the data.
        //
        p.append(param);
      }
      p.append('\'');
      int paramType = paramTypes[index];
      if (paramType == Oid.TIMESTAMP) {
        p.append("::timestamp");
      } else if (paramType == Oid.TIMESTAMPTZ) {
        p.append("::timestamp with time zone");
      } else if (paramType == Oid.TIME) {
        p.append("::time");
      } else if (paramType == Oid.TIMETZ) {
        p.append("::time with time zone");
      } else if (paramType == Oid.DATE) {
        p.append("::date");
      } else if (paramType == Oid.INTERVAL) {
        p.append("::interval");
      }
      if (paramPgType != null) {
        // It is intentional to possibly double-cast when paramPgType exists.  This will force the parser to the
        // expected type, then cast to the user-defined data type / enum.
        p.append("::").append(paramPgType);
      }
      return p.toString();
    }
  }

  @Override
  public void checkAllParametersSet() throws SQLException {
    for (int i = 0; i < paramTypes.length; ++i) {
      if (direction(i) != OUT && paramValues[i] == null) {
        throw new PSQLException(GT.tr("No value specified for parameter {0}.", i + 1),
            PSQLState.INVALID_PARAMETER_VALUE);
      }
    }
  }

  @Override
  public void convertFunctionOutParameters() {
    for (int i = 0; i < paramTypes.length; ++i) {
      if (direction(i) == OUT) {
        paramTypes[i] = Oid.VOID;
        paramValues[i] = "null";
        // TODO: paramPgType stayes the same, or set to null?
      }
    }
  }

  //
  // bytea helper
  //

  private static void streamBytea(PGStream pgStream, StreamWrapper wrapper) throws IOException {
    byte[] rawData = wrapper.getBytes();
    if (rawData != null) {
      pgStream.send(rawData, wrapper.getOffset(), wrapper.getLength());
      return;
    }

    pgStream.sendStream(wrapper.getStream(), wrapper.getLength());
  }

  // TODO: Redundant with getParamTypes()
  public int[] getTypeOIDs() {
    return paramTypes;
  }

  // TODO: Redundant with getParamPgTypes()
  @Override
  public String[] getPgTypes() {
    return paramPgTypes;
  }

  @Override
  public int[] getScales() {
    return scales;
  }

  //
  // Package-private V3 accessors
  //

  // paramPgTypes needs getter, too?  How is this used?
  int getTypeOID(int index) {
    return paramTypes[index - 1];
  }

  boolean hasUnresolvedTypes() {
    for (int paramType : paramTypes) {
      if (paramType == Oid.UNSPECIFIED) {
        return true;
      }
    }
    return false;
  }

  // TODO: scale might be unnecessary, since it was set while registering the OUT parameter
  void setResolvedType(int index, int oid, String pgType, int scale) {
    // only allow overwriting an unknown value
    if (paramTypes[index - 1] == Oid.UNSPECIFIED) {
      paramTypes[index - 1] = oid;
      // TODO: If paramPgTypes already set, do we overwrite it?
      if (pgType != null) {
        paramPgTypes[index - 1] = pgType;
      }
      if (scale >= 0) {
        scales[index - 1] = scale;
      }
    } else if (paramTypes[index - 1] != oid) {
      throw new IllegalArgumentException("Can't change resolved type for param: " + index + " from "
          + paramTypes[index - 1] + " to " + oid);
    } else {
      // TODO: Allow change paramPgTypes here?  What if was null and now has a value?
      // TODO: Do we allow scale to change?
    }
  }

  boolean isNull(int index) {
    return (paramValues[index - 1] == NULL_OBJECT);
  }

  boolean isBinary(int index) {
    return (flags[index - 1] & BINARY) != 0;
  }

  private byte direction(int index) {
    return (byte) (flags[index] & INOUT);
  }

  int getV3Length(int index) {
    --index;

    // Null?
    if (paramValues[index] == NULL_OBJECT) {
      throw new IllegalArgumentException("can't getV3Length() on a null parameter");
    }

    // Directly encoded?
    if (paramValues[index] instanceof byte[]) {
      return ((byte[]) paramValues[index]).length;
    }

    // Binary-format bytea?
    if (paramValues[index] instanceof StreamWrapper) {
      return ((StreamWrapper) paramValues[index]).getLength();
    }

    // Already encoded?
    if (encoded[index] == null) {
      // Encode value and compute actual length using UTF-8.
      encoded[index] = Utils.encodeUTF8(paramValues[index].toString());
    }

    return encoded[index].length;
  }

  void writeV3Value(int index, PGStream pgStream) throws IOException {
    --index;

    // Null?
    if (paramValues[index] == NULL_OBJECT) {
      throw new IllegalArgumentException("can't writeV3Value() on a null parameter");
    }

    // Directly encoded?
    if (paramValues[index] instanceof byte[]) {
      pgStream.send((byte[]) paramValues[index]);
      return;
    }

    // Binary-format bytea?
    if (paramValues[index] instanceof StreamWrapper) {
      streamBytea(pgStream, (StreamWrapper) paramValues[index]);
      return;
    }

    // Encoded string.
    if (encoded[index] == null) {
      encoded[index] = Utils.encodeUTF8((String) paramValues[index]);
    }
    pgStream.send(encoded[index]);
  }


  @Override
  public ParameterList copy() {
    SimpleParameterList newCopy = new SimpleParameterList(paramValues.length, transferModeRegistry);
    System.arraycopy(paramValues, 0, newCopy.paramValues, 0, paramValues.length);
    System.arraycopy(paramPgTypes, 0, newCopy.paramPgTypes, 0, paramPgTypes.length);
    System.arraycopy(scales, 0, newCopy.scales, 0, scales.length);
    System.arraycopy(paramTypes, 0, newCopy.paramTypes, 0, paramTypes.length);
    System.arraycopy(flags, 0, newCopy.flags, 0, flags.length);
    newCopy.pos = pos;
    return newCopy;
  }

  @Override
  public void clear() {
    Arrays.fill(paramValues, null);
    Arrays.fill(paramTypes, 0);
    Arrays.fill(paramPgTypes, null);
    Arrays.fill(scales, -1);
    Arrays.fill(encoded, null);
    Arrays.fill(flags, (byte) 0);
    pos = 0;
  }

  public SimpleParameterList[] getSubparams() {
    return null;
  }

  public Object[] getValues() {
    return paramValues;
  }

  // TODO: Redundant with getTypeOIDs()
  public int[] getParamTypes() {
    return paramTypes;
  }

  // TODO: Redundant with getPgTypes()
  public String[] getParamPgTypes() {
    return paramPgTypes;
  }

  public byte[] getFlags() {
    return flags;
  }

  public byte[][] getEncoding() {
    return encoded;
  }

  @Override
  public void appendAll(ParameterList list) throws SQLException {
    if (list instanceof org.postgresql.core.v3.SimpleParameterList ) {
      /* only v3.SimpleParameterList is compatible with this type
      we need to create copies of our parameters, otherwise the values can be changed */
      SimpleParameterList spl = (SimpleParameterList) list;
      int inParamCount = spl.getInParameterCount();
      if ((pos + inParamCount) > paramValues.length) {
        throw new PSQLException(
          GT.tr("Added parameters index out of range: {0}, number of columns: {1}.",
              (pos + inParamCount), paramValues.length),
              PSQLState.INVALID_PARAMETER_VALUE);
      }
      System.arraycopy(spl.getValues(), 0, this.paramValues, pos, inParamCount);
      System.arraycopy(spl.getParamTypes(), 0, this.paramTypes, pos, inParamCount);
      System.arraycopy(spl.getParamPgTypes(), 0, this.paramPgTypes, pos, inParamCount);
      System.arraycopy(spl.getScales(), 0, this.scales, pos, inParamCount);
      System.arraycopy(spl.getFlags(), 0, this.flags, pos, inParamCount);
      System.arraycopy(spl.getEncoding(), 0, this.encoded, pos, inParamCount);
      pos += inParamCount;
    }
  }

  /**
   * Useful implementation of toString.
   * @return String representation of the list values
   */
  @Override
  public String toString() {
    StringBuilder ts = new StringBuilder("<[");
    if (paramValues.length > 0) {
      ts.append(toString(1, true));
      for (int c = 2; c <= paramValues.length; c++) {
        ts.append(" ,").append(toString(c, true));
      }
    }
    ts.append("]>");
    return ts.toString();
  }

  // TODO: Move fields to top of class per project standards
  private final Object[] paramValues;
  private final int[] paramTypes;
  private final String[] paramPgTypes;
  private final int[] scales;
  private final byte[] flags;
  private final byte[][] encoded;
  private final TypeTransferModeRegistry transferModeRegistry;

  /**
   * Marker object representing NULL; this distinguishes "parameter never set" from "parameter set
   * to null".
   */
  private static final Object NULL_OBJECT = new Object();

  private int pos = 0;
}

