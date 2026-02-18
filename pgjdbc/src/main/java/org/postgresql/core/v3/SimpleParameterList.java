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
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.GT;
import org.postgresql.util.PGbytea;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.StreamWrapper;

import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.index.qual.Positive;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

  SimpleParameterList(int paramCount, @Nullable TypeTransferModeRegistry transferModeRegistry) {
    this.paramValues = new Object[paramCount];
    this.paramTypes = new int[paramCount];
    this.encoded = new byte[paramCount][];
    this.flags = new byte[paramCount];
    this.transferModeRegistry = transferModeRegistry;
  }

  @Override
  public void registerOutParameter(int index, int sqlType) throws SQLException {
    if (index < 1 || index > paramValues.length) {
      throw new PSQLException(
          GT.tr("The column index is out of range: {0}, number of columns: {1}.",
              index, paramValues.length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    flags[index - 1] |= OUT;
  }

  private void bind(int index, Object value, int oid, byte binary) throws SQLException {
    if (index < 1 || index > paramValues.length) {
      throw new PSQLException(
          GT.tr("The column index is out of range: {0}, number of columns: {1}.",
              index, paramValues.length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    --index;

    encoded[index] = null;
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
    pos = index + 1;
  }

  @Override
  public @NonNegative int getParameterCount() {
    return paramValues.length;
  }

  @Override
  public @NonNegative int getOutParameterCount() {
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

  @Override
  public @NonNegative int getInParameterCount() {
    int count = 0;
    for (int i = 0; i < paramTypes.length; i++) {
      if (direction(i) != OUT) {
        count++;
      }
    }
    return count;
  }

  @Override
  public void setIntParameter(@Positive int index, int value) throws SQLException {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, value);
    bind(index, data, Oid.INT4, BINARY);
  }

  @Override
  public void setLiteralParameter(@Positive int index, String value, int oid) throws SQLException {
    bind(index, value, oid, TEXT);
  }

  @Override
  public void setStringParameter(@Positive int index, String value, int oid) throws SQLException {
    bind(index, value, oid, TEXT);
  }

  @Override
  public void setBinaryParameter(@Positive int index, byte[] value, int oid) throws SQLException {
    bind(index, value, oid, BINARY);
  }

  @Override
  public void setBytea(@Positive int index, byte[] data, int offset, @NonNegative int length) throws SQLException {
    bind(index, new StreamWrapper(data, offset, length), Oid.BYTEA, BINARY);
  }

  @Override
  public void setBytea(@Positive int index, InputStream stream, @NonNegative int length) throws SQLException {
    bind(index, new StreamWrapper(stream, length), Oid.BYTEA, BINARY);
  }

  @Override
  public void setBytea(@Positive int index, InputStream stream) throws SQLException {
    bind(index, new StreamWrapper(stream), Oid.BYTEA, BINARY);
  }

  @Override
  public void setBytea(@Positive int index, ByteStreamWriter writer) throws SQLException {
    bind(index, writer, Oid.BYTEA, BINARY);
  }

  @Override
  public void setText(@Positive int index, InputStream stream) throws SQLException {
    bind(index, new StreamWrapper(stream), Oid.TEXT, TEXT);
  }

  @Override
  public void setNull(@Positive int index, int oid) throws SQLException {

    byte binaryTransfer = TEXT;

    if (transferModeRegistry != null && transferModeRegistry.useBinaryForReceive(oid)) {
      binaryTransfer = BINARY;
    }
    bind(index, NULL_OBJECT, oid, binaryTransfer);
  }

  /**
   * Escapes a given text value as a literal, wraps it in single quotes, casts it to the
   * to the given data type, and finally wraps the whole thing in parentheses.
   *
   * <p>For example, "123" and "int4" becomes "('123'::int)"</p>
   *
   * <p>The additional parentheses is added to ensure that the surrounding text of where the
   * parameter value is entered does modify the interpretation of the value.</p>
   *
   * <p>For example if our input SQL is: <code>SELECT ?b</code></p>
   *
   * <p>Using a parameter value of '{}' and type of json we'd get:</p>
   *
   * <pre>
   * test=# SELECT ('{}'::json)b;
   *  b
   * ----
   *  {}
   * </pre>
   *
   * <p>But without the parentheses the result changes:</p>
   *
   * <pre>
   * test=# SELECT '{}'::jsonb;
   * jsonb
   * -------
   * {}
   * </pre>
   **/
  private static String quoteAndCast(String text, @Nullable String type, boolean standardConformingStrings) {
    StringBuilder sb = new StringBuilder((text.length() + 10) / 10 * 11); // Add 10% for escaping.
    sb.append("('");
    try {
      Utils.escapeLiteral(sb, text, standardConformingStrings);
    } catch (SQLException e) {
      // This should only happen if we have an embedded null
      // and there's not much we can do if we do hit one.
      //
      // To force a server side failure, we deliberately include
      // a zero byte character in the literal to force the server
      // to reject the command.
      sb.append('\u0000');
    }
    sb.append("'");
    if (type != null) {
      sb.append("::");
      sb.append(type);
    }
    sb.append(")");
    return sb.toString();
  }

  private static <E extends Throwable> RuntimeException sneakyThrow(Throwable e) throws E {
    throw (E) e;
  }

  @Override
  public String toString(@Positive int index, boolean standardConformingStrings) {
    return toString(index, SqlSerializationContext.of(standardConformingStrings, true));
  }

  @Override
  public String toString(@Positive int index, SqlSerializationContext context) {
    --index;
    Object paramValue = paramValues[index];
    if (paramValue == null) {
      return "?";
    } else if (paramValue == NULL_OBJECT) {
      switch (paramTypes[index]) {
        case Oid.INT2:
          return "(NULL::int2)";
        case Oid.INT4:
          return "(NULL::int4)";
        case Oid.INT8:
          return "(NULL::int8)";
        case Oid.FLOAT4:
          return "(NULL::real)";
        case Oid.FLOAT8:
          return "(NULL::double precision)";
        case Oid.DATE:
          return "(NULL::date)";
        case Oid.NUMERIC:
          return "(NULL::numeric)";
        case Oid.BOOL:
          return "(NULL::boolean)";
        default:
          return "(NULL)";
      }
    }
    String textValue;
    String type;
    if (paramTypes[index] == Oid.BYTEA) {
      try {
        return PGbytea.toPGLiteral(paramValue, context);
      } catch (Throwable e) {
        Throwable cause = e;
        if (!(cause instanceof IOException)) {
          // This is for compatibilty with the similar handling in QueryExecutorImpl
          cause = new IOException("Error writing bytes to stream", e);
        }
        throw sneakyThrow(
            new PSQLException(
                GT.tr("Unable to convert bytea parameter at position {0} to literal",
                    index),
                PSQLState.INVALID_PARAMETER_VALUE,
                cause));
      }
    }
    if ((flags[index] & BINARY) == BINARY) {
      // handle some of the numeric types
      switch (paramTypes[index]) {
        case Oid.INT2:
          short s = ByteConverter.int2((byte[]) paramValue, 0);
          textValue = Short.toString(s);
          type = "int2";
          break;

        case Oid.INT4:
          int i = ByteConverter.int4((byte[]) paramValue, 0);
          textValue = Integer.toString(i);
          type = "int4";
          break;

        case Oid.INT8:
          long l = ByteConverter.int8((byte[]) paramValue, 0);
          textValue = Long.toString(l);
          type = "int8";
          break;

        case Oid.FLOAT4:
          float f = ByteConverter.float4((byte[]) paramValue, 0);
          if (Float.isNaN(f)) {
            return "('NaN'::real)";
          }
          textValue = Float.toString(f);
          type = "real";
          break;

        case Oid.FLOAT8:
          double d = ByteConverter.float8((byte[]) paramValue, 0);
          if (Double.isNaN(d)) {
            return "('NaN'::double precision)";
          }
          textValue = Double.toString(d);
          type = "double precision";
          break;

        case Oid.NUMERIC:
          Number n = ByteConverter.numeric((byte[]) paramValue);
          if (n instanceof Double) {
            assert ((Double) n).isNaN();
            return "('NaN'::numeric)";
          }
          textValue = n.toString();
          type = "numeric";
          break;

        case Oid.UUID:
          textValue =
              new UUIDArrayAssistant().buildElement((byte[]) paramValue, 0, 16).toString();
          type = "uuid";
          break;

        case Oid.POINT:
          PGpoint pgPoint = new PGpoint();
          pgPoint.setByteValue((byte[]) paramValue, 0);
          textValue = pgPoint.toString();
          type = "point";
          break;

        case Oid.BOX:
          PGbox pgBox = new PGbox();
          pgBox.setByteValue((byte[]) paramValue, 0);
          textValue = pgBox.toString();
          type = "box";
          break;

        default:
          return "?";
      }
    } else {
      textValue = paramValue.toString();
      switch (paramTypes[index]) {
        case Oid.INT2:
          type = "int2";
          break;
        case Oid.INT4:
          type = "int4";
          break;
        case Oid.INT8:
          type = "int8";
          break;
        case Oid.FLOAT4:
          type = "real";
          break;
        case Oid.FLOAT8:
          type = "double precision";
          break;
        case Oid.TIMESTAMP:
          type = "timestamp";
          break;
        case Oid.TIMESTAMPTZ:
          type = "timestamp with time zone";
          break;
        case Oid.TIME:
          type = "time";
          break;
        case Oid.TIMETZ:
          type = "time with time zone";
          break;
        case Oid.DATE:
          type = "date";
          break;
        case Oid.INTERVAL:
          type = "interval";
          break;
        case Oid.NUMERIC:
          type = "numeric";
          break;
        case Oid.UUID:
          type = "uuid";
          break;
        case Oid.BOOL:
          type = "boolean";
          break;
        case Oid.BOX:
          type = "box";
          break;
        case Oid.POINT:
          type = "point";
          break;
        default:
          type = null;
      }
    }
    return quoteAndCast(textValue, type, context.getStandardConformingStrings());
  }

  @Override
  public void checkAllParametersSet() throws SQLException {
    for (int i = 0; i < paramTypes.length; i++) {
      if (direction(i) != OUT && paramValues[i] == null) {
        throw new PSQLException(GT.tr("No value specified for parameter {0}.", i + 1),
            PSQLState.INVALID_PARAMETER_VALUE);
      }
    }
  }

  @Override
  public void convertFunctionOutParameters() {
    for (int i = 0; i < paramTypes.length; i++) {
      if (direction(i) == OUT) {
        paramTypes[i] = Oid.VOID;
        paramValues[i] = NULL_OBJECT;
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

  //
  // byte stream writer support
  //

  private static void streamBytea(PGStream pgStream, ByteStreamWriter writer) throws IOException {
    pgStream.send(writer);
  }

  @Override
  public int[] getTypeOIDs() {
    return paramTypes;
  }

  //
  // Package-private V3 accessors
  //

  int getTypeOID(@Positive int index) {
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

  void setResolvedType(@Positive int index, int oid) {
    // only allow overwriting an unknown value or VOID value
    if (paramTypes[index - 1] == Oid.UNSPECIFIED || paramTypes[index - 1] == Oid.VOID) {
      paramTypes[index - 1] = oid;
    } else if (paramTypes[index - 1] != oid) {
      throw new IllegalArgumentException("Can't change resolved type for param: " + index + " from "
          + paramTypes[index - 1] + " to " + oid);
    }
  }

  boolean isNull(@Positive int index) {
    return paramValues[index - 1] == NULL_OBJECT;
  }

  boolean isBinary(@Positive int index) {
    return (flags[index - 1] & BINARY) != 0;
  }

  private byte direction(@Positive int index) {
    return (byte) (flags[index] & INOUT);
  }

  int getV3Length(@Positive int index) {
    --index;

    // Null?
    Object value = paramValues[index];
    if (value == null || value == NULL_OBJECT) {
      throw new IllegalArgumentException("can't getV3Length() on a null parameter");
    }

    // Directly encoded?
    if (value instanceof byte[]) {
      return ((byte[]) value).length;
    }

    // Binary-format bytea?
    if (value instanceof StreamWrapper) {
      return ((StreamWrapper) value).getLength();
    }

    // Binary-format bytea?
    if (value instanceof ByteStreamWriter) {
      return ((ByteStreamWriter) value).getLength();
    }

    // Already encoded?
    byte[] encoded = this.encoded[index];
    if (encoded == null) {
      // Encode value and compute actual length using UTF-8.
      this.encoded[index] = encoded = value.toString().getBytes(StandardCharsets.UTF_8);
    }

    return encoded.length;
  }

  void writeV3Value(@Positive int index, PGStream pgStream) throws IOException {
    --index;

    // Null?
    Object paramValue = paramValues[index];
    if (paramValue == null || paramValue == NULL_OBJECT) {
      throw new IllegalArgumentException("can't writeV3Value() on a null parameter");
    }

    // Directly encoded?
    if (paramValue instanceof byte[]) {
      pgStream.send((byte[]) paramValue);
      return;
    }

    // Binary-format bytea?
    if (paramValue instanceof StreamWrapper) {
      try (StreamWrapper streamWrapper = (StreamWrapper) paramValue) {
        streamBytea(pgStream, streamWrapper);
      }
      return;
    }

    // Streamed bytea?
    if (paramValue instanceof ByteStreamWriter) {
      streamBytea(pgStream, (ByteStreamWriter) paramValue);
      return;
    }

    // Encoded string.
    if (encoded[index] == null) {
      encoded[index] = ((String) paramValue).getBytes(StandardCharsets.UTF_8);
    }
    pgStream.send(encoded[index]);
  }

  @Override
  public ParameterList copy() {
    SimpleParameterList newCopy = new SimpleParameterList(paramValues.length, transferModeRegistry);
    System.arraycopy(paramValues, 0, newCopy.paramValues, 0, paramValues.length);
    System.arraycopy(paramTypes, 0, newCopy.paramTypes, 0, paramTypes.length);
    System.arraycopy(flags, 0, newCopy.flags, 0, flags.length);
    newCopy.pos = pos;
    return newCopy;
  }

  @Override
  public void clear() {
    Arrays.fill(paramValues, null);
    Arrays.fill(paramTypes, 0);
    Arrays.fill(encoded, null);
    Arrays.fill(flags, (byte) 0);
    pos = 0;
  }

  @Override
  public SimpleParameterList @Nullable [] getSubparams() {
    return null;
  }

  @Override
  public @Nullable Object[] getValues() {
    return paramValues;
  }

  @Override
  public int[] getParamTypes() {
    return paramTypes;
  }

  @Override
  public byte[] getFlags() {
    return flags;
  }

  @Override
  public byte[] @Nullable [] getEncoding() {
    return encoded;
  }

  @Override
  public void appendAll(ParameterList list) throws SQLException {
    if (list instanceof SimpleParameterList ) {
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

  private final @Nullable Object[] paramValues;
  private final int[] paramTypes;
  private final byte[] flags;
  private final byte[] @Nullable [] encoded;
  private final @Nullable TypeTransferModeRegistry transferModeRegistry;

  /**
   * Marker object representing NULL; this distinguishes "parameter never set" from "parameter set
   * to null".
   */
  private static final Object NULL_OBJECT = new Object();

  private int pos;
}
