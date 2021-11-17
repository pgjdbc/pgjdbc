/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Encoding;
import org.postgresql.core.Oid;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.index.qual.Positive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility for using arrays in requests.
 *
 * <p>
 * Binary format:
 * <ul>
 * <li>4 bytes with number of dimensions</li>
 * <li>4 bytes, boolean indicating nulls present or not</li>
 * <li>4 bytes type oid</li>
 * <li>8 bytes describing the length of each dimension (repeated for each dimension)</li>
 * <ul>
 * <li>4 bytes for length</li>
 * <li>4 bytes for lower bound on length to check for overflow (it appears this value can always be 0)</li>
 * </ul>
 * <li>data in depth first element order corresponding number and length of dimensions</li>
 * <ul>
 * <li>4 bytes describing length of element, {@code 0xFFFFFFFF} ({@code -1}) means {@code null}</li>
 * <li>binary representation of element (iff not {@code null}).
 * </ul>
 * </ul>
 * </p>
 *
 * @author Brett Okken
 */
final class ArrayEncoding {

  interface ArrayEncoder<A> {

    /**
     * The default array type oid supported by this instance.
     *
     * @return The default array type oid supported by this instance.
     */
    int getDefaultArrayTypeOid();

    /**
     * Creates {@code String} representation of the <i>array</i>.
     *
     * @param delim
     *          The character to use to delimit between elements.
     * @param array
     *          The array to represent as a {@code String}.
     * @return {@code String} representation of the <i>array</i>.
     */
    String toArrayString(char delim, A array);

    /**
     * Indicates if an array can be encoded in binary form to array <i>oid</i>.
     *
     * @param oid
     *          The array oid to see check for binary support.
     * @return Indication of whether
     *         {@link #toBinaryRepresentation(BaseConnection, Object, int)} is
     *         supported for <i>oid</i>.
     */
    boolean supportBinaryRepresentation(int oid);

    /**
     * Creates binary representation of the <i>array</i>.
     *
     * @param connection
     *          The connection the binary representation will be used on. Attributes
     *          from the connection might impact how values are translated to
     *          binary.
     * @param array
     *          The array to binary encode. Must not be {@code null}, but may
     *          contain {@code null} elements.
     * @param oid
     *          The array type oid to use. Calls to
     *          {@link #supportBinaryRepresentation(int)} must have returned
     *          {@code true}.
     * @return The binary representation of <i>array</i>.
     * @throws SQLFeatureNotSupportedException
     *           If {@link #supportBinaryRepresentation(int)} is false for
     *           <i>oid</i>.
     */
    byte[] toBinaryRepresentation(BaseConnection connection, A array, int oid)
        throws SQLException, SQLFeatureNotSupportedException;

    /**
     * Append {@code String} representation of <i>array</i> to <i>sb</i>.
     *
     * @param sb
     *          The {@link StringBuilder} to append to.
     * @param delim
     *          The delimiter between elements.
     * @param array
     *          The array to represent. Will not be {@code null}, but may contain
     *          {@code null} elements.
     */
    void appendArray(StringBuilder sb, char delim, A array);
  }

  /**
   * Base class to implement {@link ArrayEncoding.ArrayEncoder} and provide
   * multi-dimensional support.
   *
   * @param <A>
   *          Base array type supported.
   */
  private abstract static class AbstractArrayEncoder<A extends Object>
      implements ArrayEncoder<A> {

    private final int oid;

    final int arrayOid;

    /**
     *
     * @param oid
     *          The default/primary base oid type.
     * @param arrayOid
     *          The default/primary array oid type.
     */
    AbstractArrayEncoder(int oid, int arrayOid) {
      this.oid = oid;
      this.arrayOid = arrayOid;
    }

    /**
     *
     * @param arrayOid
     *          The array oid to get base oid type for.
     * @return The base oid type for the given array oid type given to
     *         {@link #toBinaryRepresentation(BaseConnection, Object, int)}.
     */
    int getTypeOID(@SuppressWarnings("unused") int arrayOid) {
      return oid;
    }

    /**
     * By default returns the <i>arrayOid</i> this instance was instantiated with.
     */
    @Override
    public int getDefaultArrayTypeOid() {
      return arrayOid;
    }

    /**
     * Counts the number of {@code null} elements in <i>array</i>.
     *
     * @param array
     *          The array to count {@code null} elements in.
     * @return The number of {@code null} elements in <i>array</i>.
     */
    int countNulls(A array) {
      int nulls = 0;
      final int arrayLength = Array.getLength(array);
      for (int i = 0; i < arrayLength; ++i) {
        if (Array.get(array, i) == null) {
          ++nulls;
        }
      }
      return nulls;
    }

    /**
     * Creates {@code byte[]} of just the raw data (no metadata).
     *
     * @param connection
     *          The connection the binary representation will be used on.
     * @param array
     *          The array to create binary representation of. Will not be
     *          {@code null}, but may contain {@code null} elements.
     * @return {@code byte[]} of just the raw data (no metadata).
     * @throws SQLFeatureNotSupportedException
     *           If {@link #supportBinaryRepresentation(int)} is false for
     *           <i>oid</i>.
     */
    abstract byte[] toSingleDimensionBinaryRepresentation(BaseConnection connection, A array)
        throws SQLException, SQLFeatureNotSupportedException;

    /**
     * {@inheritDoc}
     */
    @Override
    public String toArrayString(char delim, A array) {
      final StringBuilder sb = new StringBuilder(1024);
      appendArray(sb, delim, array);
      return sb.toString();
    }

    /**
     * By default returns {@code true} if <i>oid</i> matches the <i>arrayOid</i>
     * this instance was instantiated with.
     */
    @Override
    public boolean supportBinaryRepresentation(int oid) {
      return oid == arrayOid;
    }
  }

  /**
   * Base class to provide support for {@code Number} based arrays.
   *
   * @param <N>
   *          The base type of array.
   */
  private abstract static class NumberArrayEncoder<N extends Number> extends AbstractArrayEncoder<N[]> {

    private final int fieldSize;

    /**
     *
     * @param fieldSize
     *          The fixed size to represent each value in binary.
     * @param oid
     *          The base type oid.
     * @param arrayOid
     *          The array type oid.
     */
    NumberArrayEncoder(int fieldSize, int oid, int arrayOid) {
      super(oid, arrayOid);
      this.fieldSize = fieldSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    final int countNulls(N[] array) {
      int count = 0;
      for (int i = 0; i < array.length; ++i) {
        if (array[i] == null) {
          ++count;
        }
      }
      return count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final byte[] toBinaryRepresentation(BaseConnection connection, N[] array, int oid)
        throws SQLException, SQLFeatureNotSupportedException {
      assert oid == this.arrayOid;

      final int nullCount = countNulls(array);

      final byte[] bytes = writeBytes(array, nullCount, 20);

      // 1 dimension
      ByteConverter.int4(bytes, 0, 1);
      // no null
      ByteConverter.int4(bytes, 4, nullCount == 0 ? 0 : 1);
      // oid
      ByteConverter.int4(bytes, 8, getTypeOID(oid));
      // length
      ByteConverter.int4(bytes, 12, array.length);
      // postgresql uses 1 base by default
      ByteConverter.int4(bytes, 16, 1);

      return bytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    final byte[] toSingleDimensionBinaryRepresentation(BaseConnection connection, N[] array)
        throws SQLException, SQLFeatureNotSupportedException {

      final int nullCount = countNulls(array);

      return writeBytes(array, nullCount, 0);
    }

    private byte[] writeBytes(final N[] array, final int nullCount, final int offset) {
      final int length = offset + (4 * array.length) + (fieldSize * (array.length - nullCount));
      final byte[] bytes = new byte[length];

      int idx = offset;
      for (int i = 0; i < array.length; ++i) {
        if (array[i] == null) {
          ByteConverter.int4(bytes, idx, -1);
          idx += 4;
        } else {
          ByteConverter.int4(bytes, idx, fieldSize);
          idx += 4;
          write(array[i], bytes, idx);
          idx += fieldSize;
        }
      }

      return bytes;
    }

    /**
     * Write single value (<i>number</i>) to <i>bytes</i> beginning at
     * <i>offset</i>.
     *
     * @param number
     *          The value to write to <i>bytes</i>. This will never be {@code null}.
     * @param bytes
     *          The {@code byte[]} to write to.
     * @param offset
     *          The offset into <i>bytes</i> to write the <i>number</i> value.
     */
    protected abstract void write(N number, byte[] bytes, int offset);

    /**
     * {@inheritDoc}
     */
    @Override
    public final void appendArray(StringBuilder sb, char delim, N[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i != 0) {
          sb.append(delim);
        }
        if (array[i] == null) {
          sb.append('N').append('U').append('L').append('L');
        } else {
          sb.append('"');
          sb.append(array[i].toString());
          sb.append('"');
        }
      }
      sb.append('}');
    }
  }

  /**
   * Base support for primitive arrays.
   *
   * @param <A>
   *          The primitive array to support.
   */
  private abstract static class FixedSizePrimitiveArrayEncoder<A extends Object>
      extends AbstractArrayEncoder<A> {

    private final int fieldSize;

    FixedSizePrimitiveArrayEncoder(int fieldSize, int oid, int arrayOid) {
      super(oid, arrayOid);
      this.fieldSize = fieldSize;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Always returns {@code 0}.
     * </p>
     */
    @Override
    final int countNulls(A array) {
      return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final byte[] toBinaryRepresentation(BaseConnection connection, A array, int oid)
        throws SQLException, SQLFeatureNotSupportedException {
      assert oid == arrayOid;

      final int arrayLength = Array.getLength(array);
      final int length = 20 + ((fieldSize + 4) * arrayLength);
      final byte[] bytes = new byte[length];

      // 1 dimension
      ByteConverter.int4(bytes, 0, 1);
      // no null
      ByteConverter.int4(bytes, 4, 0);
      // oid
      ByteConverter.int4(bytes, 8, getTypeOID(oid));
      // length
      ByteConverter.int4(bytes, 12, arrayLength);
      // postgresql uses 1 base by default
      ByteConverter.int4(bytes, 16, 1);

      write(array, bytes, 20);

      return bytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    final byte[] toSingleDimensionBinaryRepresentation(BaseConnection connection, A array)
        throws SQLException, SQLFeatureNotSupportedException {
      final int length = ((fieldSize + 4) * Array.getLength(array));
      final byte[] bytes = new byte[length];

      write(array, bytes, 0);
      return bytes;
    }

    /**
     * Write the entire contents of <i>array</i> to <i>bytes</i> starting at
     * <i>offset</i> without metadata describing type or length.
     *
     * @param array
     *          The array to write.
     * @param bytes
     *          The {@code byte[]} to write to.
     * @param offset
     *          The offset into <i>bytes</i> to start writing.
     */
    protected abstract void write(A array, byte[] bytes, int offset);
  }

  private static final AbstractArrayEncoder<long[]> LONG_ARRAY = new FixedSizePrimitiveArrayEncoder<long[]>(8, Oid.INT8,
      Oid.INT8_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    public void appendArray(StringBuilder sb, char delim, long[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        sb.append(array[i]);
      }
      sb.append('}');
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(long[] array, byte[] bytes, int offset) {
      int idx = offset;
      for (int i = 0; i < array.length; ++i) {
        bytes[idx + 3] = 8;
        ByteConverter.int8(bytes, idx + 4, array[i]);
        idx += 12;
      }
    }
  };

  private static final AbstractArrayEncoder<Long[]> LONG_OBJ_ARRAY = new NumberArrayEncoder<Long>(8, Oid.INT8,
      Oid.INT8_ARRAY) {

    @Override
    protected void write(Long number, byte[] bytes, int offset) {
      ByteConverter.int8(bytes, offset, number.longValue());
    }
  };

  private static final AbstractArrayEncoder<int[]> INT_ARRAY = new FixedSizePrimitiveArrayEncoder<int[]>(4, Oid.INT4,
      Oid.INT4_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    public void appendArray(StringBuilder sb, char delim, int[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        sb.append(array[i]);
      }
      sb.append('}');
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(int[] array, byte[] bytes, int offset) {
      int idx = offset;
      for (int i = 0; i < array.length; ++i) {
        bytes[idx + 3] = 4;
        ByteConverter.int4(bytes, idx + 4, array[i]);
        idx += 8;
      }
    }
  };

  private static final AbstractArrayEncoder<Integer[]> INT_OBJ_ARRAY = new NumberArrayEncoder<Integer>(4, Oid.INT4,
      Oid.INT4_ARRAY) {

    @Override
    protected void write(Integer number, byte[] bytes, int offset) {
      ByteConverter.int4(bytes, offset, number.intValue());
    }
  };

  private static final AbstractArrayEncoder<short[]> SHORT_ARRAY = new FixedSizePrimitiveArrayEncoder<short[]>(2,
      Oid.INT2, Oid.INT2_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    public void appendArray(StringBuilder sb, char delim, short[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        sb.append(array[i]);
      }
      sb.append('}');
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(short[] array, byte[] bytes, int offset) {
      int idx = offset;
      for (int i = 0; i < array.length; ++i) {
        bytes[idx + 3] = 2;
        ByteConverter.int2(bytes, idx + 4, array[i]);
        idx += 6;
      }
    }
  };

  private static final AbstractArrayEncoder<Short[]> SHORT_OBJ_ARRAY = new NumberArrayEncoder<Short>(2, Oid.INT2,
      Oid.INT2_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(Short number, byte[] bytes, int offset) {
      ByteConverter.int2(bytes, offset, number.shortValue());
    }
  };

  private static final AbstractArrayEncoder<double[]> DOUBLE_ARRAY = new FixedSizePrimitiveArrayEncoder<double[]>(8,
      Oid.FLOAT8, Oid.FLOAT8_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    public void appendArray(StringBuilder sb, char delim, double[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        // use quotes to account for any issues with scientific notation
        sb.append('"');
        sb.append(array[i]);
        sb.append('"');
      }
      sb.append('}');
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(double[] array, byte[] bytes, int offset) {
      int idx = offset;
      for (int i = 0; i < array.length; ++i) {
        bytes[idx + 3] = 8;
        ByteConverter.float8(bytes, idx + 4, array[i]);
        idx += 12;
      }
    }
  };

  private static final AbstractArrayEncoder<Double[]> DOUBLE_OBJ_ARRAY = new NumberArrayEncoder<Double>(8, Oid.FLOAT8,
      Oid.FLOAT8_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(Double number, byte[] bytes, int offset) {
      ByteConverter.float8(bytes, offset, number.doubleValue());
    }
  };

  private static final AbstractArrayEncoder<float[]> FLOAT_ARRAY = new FixedSizePrimitiveArrayEncoder<float[]>(4,
      Oid.FLOAT4, Oid.FLOAT4_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    public void appendArray(StringBuilder sb, char delim, float[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        // use quotes to account for any issues with scientific notation
        sb.append('"');
        sb.append(array[i]);
        sb.append('"');
      }
      sb.append('}');
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(float[] array, byte[] bytes, int offset) {
      int idx = offset;
      for (int i = 0; i < array.length; ++i) {
        bytes[idx + 3] = 4;
        ByteConverter.float4(bytes, idx + 4, array[i]);
        idx += 8;
      }
    }
  };

  private static final AbstractArrayEncoder<Float[]> FLOAT_OBJ_ARRAY = new NumberArrayEncoder<Float>(4, Oid.FLOAT4,
      Oid.FLOAT4_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(Float number, byte[] bytes, int offset) {
      ByteConverter.float4(bytes, offset, number.floatValue());
    }
  };

  private static final AbstractArrayEncoder<boolean[]> BOOLEAN_ARRAY = new FixedSizePrimitiveArrayEncoder<boolean[]>(1,
      Oid.BOOL, Oid.BOOL_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    public void appendArray(StringBuilder sb, char delim, boolean[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        sb.append(array[i] ? '1' : '0');
      }
      sb.append('}');
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(boolean[] array, byte[] bytes, int offset) {
      int idx = offset;
      for (int i = 0; i < array.length; ++i) {
        bytes[idx + 3] = 1;
        ByteConverter.bool(bytes, idx + 4, array[i]);
        idx += 5;
      }
    }
  };

  private static final AbstractArrayEncoder<Boolean[]> BOOLEAN_OBJ_ARRAY = new AbstractArrayEncoder<Boolean[]>(Oid.BOOL,
      Oid.BOOL_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] toBinaryRepresentation(BaseConnection connection, Boolean[] array, int oid)
        throws SQLException, SQLFeatureNotSupportedException {
      assert oid == arrayOid;

      final int nullCount = countNulls(array);

      final byte[] bytes = writeBytes(array, nullCount, 20);

      // 1 dimension
      ByteConverter.int4(bytes, 0, 1);
      // no null
      ByteConverter.int4(bytes, 4, nullCount == 0 ? 0 : 1);
      // oid
      ByteConverter.int4(bytes, 8, getTypeOID(oid));
      // length
      ByteConverter.int4(bytes, 12, array.length);
      // postgresql uses 1 base by default
      ByteConverter.int4(bytes, 16, 1);

      return bytes;
    }

    private byte[] writeBytes(final Boolean[] array, final int nullCount, final int offset) {
      final int length = offset + (4 * array.length) + (array.length - nullCount);
      final byte[] bytes = new byte[length];

      int idx = offset;
      for (int i = 0; i < array.length; ++i) {
        if (array[i] == null) {
          ByteConverter.int4(bytes, idx, -1);
          idx += 4;
        } else {
          ByteConverter.int4(bytes, idx, 1);
          idx += 4;
          write(array[i], bytes, idx);
          ++idx;
        }
      }

      return bytes;
    }

    private void write(Boolean bool, byte[] bytes, int idx) {
      ByteConverter.bool(bytes, idx, bool.booleanValue());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    byte[] toSingleDimensionBinaryRepresentation(BaseConnection connection, Boolean[] array)
        throws SQLException, SQLFeatureNotSupportedException {
      final int nullCount = countNulls(array);
      return writeBytes(array, nullCount, 0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void appendArray(StringBuilder sb, char delim, Boolean[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i != 0) {
          sb.append(delim);
        }
        if (array[i] == null) {
          sb.append('N').append('U').append('L').append('L');
        } else {
          sb.append(array[i].booleanValue() ? '1' : '0');
        }
      }
      sb.append('}');
    }
  };

  private static final AbstractArrayEncoder<String[]> STRING_ARRAY = new AbstractArrayEncoder<String[]>(Oid.VARCHAR,
      Oid.VARCHAR_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    int countNulls(String[] array) {
      int count = 0;
      for (int i = 0; i < array.length; ++i) {
        if (array[i] == null) {
          ++count;
        }
      }
      return count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportBinaryRepresentation(int oid) {
      return oid == Oid.VARCHAR_ARRAY || oid == Oid.TEXT_ARRAY;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    int getTypeOID(int arrayOid) {
      if (arrayOid == Oid.VARCHAR_ARRAY) {
        return Oid.VARCHAR;
      }

      if (arrayOid == Oid.TEXT_ARRAY) {
        return Oid.TEXT;
      }

      // this should not be possible based on supportBinaryRepresentation returning
      // false for all other types
      throw new IllegalStateException("Invalid array oid: " + arrayOid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void appendArray(StringBuilder sb, char delim, String[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        if (array[i] == null) {
          sb.append('N').append('U').append('L').append('L');
        } else {
          PgArray.escapeArrayElement(sb, array[i]);
        }
      }
      sb.append('}');
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] toBinaryRepresentation(BaseConnection connection, String[] array, int oid) throws SQLException {
      final ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(1024, (array.length * 32) + 20));

      assert supportBinaryRepresentation(oid);

      final byte[] buffer = new byte[4];

      try {
        // 1 dimension
        ByteConverter.int4(buffer, 0, 1);
        baos.write(buffer);
        // null
        ByteConverter.int4(buffer, 0, countNulls(array) > 0 ? 1 : 0);
        baos.write(buffer);
        // oid
        ByteConverter.int4(buffer, 0, getTypeOID(oid));
        baos.write(buffer);
        // length
        ByteConverter.int4(buffer, 0, array.length);
        baos.write(buffer);

        // postgresql uses 1 base by default
        ByteConverter.int4(buffer, 0, 1);
        baos.write(buffer);

        final Encoding encoding = connection.getEncoding();
        for (int i = 0; i < array.length; ++i) {
          final String string = array[i];
          if (string != null) {
            final byte[] encoded;
            try {
              encoded = encoding.encode(string);
            } catch (IOException e) {
              throw new PSQLException(GT.tr("Unable to translate data into the desired encoding."),
                  PSQLState.DATA_ERROR, e);
            }
            ByteConverter.int4(buffer, 0, encoded.length);
            baos.write(buffer);
            baos.write(encoded);
          } else {
            ByteConverter.int4(buffer, 0, -1);
            baos.write(buffer);
          }
        }

        return baos.toByteArray();
      } catch (IOException e) {
        // this IO exception is from writing to baos, which will never throw an
        // IOException
        throw new java.lang.AssertionError(e);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    byte[] toSingleDimensionBinaryRepresentation(BaseConnection connection, String[] array)
        throws SQLException, SQLFeatureNotSupportedException {
      try {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(1024, (array.length * 32) + 20));
        final byte[] buffer = new byte[4];
        final Encoding encoding = connection.getEncoding();
        for (int i = 0; i < array.length; ++i) {
          final String string = array[i];
          if (string != null) {
            final byte[] encoded;
            try {
              encoded = encoding.encode(string);
            } catch (IOException e) {
              throw new PSQLException(GT.tr("Unable to translate data into the desired encoding."),
                  PSQLState.DATA_ERROR, e);
            }
            ByteConverter.int4(buffer, 0, encoded.length);
            baos.write(buffer);
            baos.write(encoded);
          } else {
            ByteConverter.int4(buffer, 0, -1);
            baos.write(buffer);
          }
        }

        return baos.toByteArray();
      } catch (IOException e) {
        // this IO exception is from writing to baos, which will never throw an
        // IOException
        throw new java.lang.AssertionError(e);
      }
    }
  };

  private static final AbstractArrayEncoder<byte[][]> BYTEA_ARRAY = new AbstractArrayEncoder<byte[][]>(Oid.BYTEA,
      Oid.BYTEA_ARRAY) {

    /**
     * The possible characters to use for representing hex binary data.
     */
    private final char[] hexDigits = new char[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd',
        'e', 'f' };

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] toBinaryRepresentation(BaseConnection connection, byte[][] array, int oid)
        throws SQLException, SQLFeatureNotSupportedException {

      assert oid == arrayOid;

      int length = 20;
      for (int i = 0; i < array.length; ++i) {
        length += 4;
        if (array[i] != null) {
          length += array[i].length;
        }
      }
      final byte[] bytes = new byte[length];

      // 1 dimension
      ByteConverter.int4(bytes, 0, 1);
      // no null
      ByteConverter.int4(bytes, 4, 0);
      // oid
      ByteConverter.int4(bytes, 8, getTypeOID(oid));
      // length
      ByteConverter.int4(bytes, 12, array.length);
      // postgresql uses 1 base by default
      ByteConverter.int4(bytes, 16, 1);

      write(array, bytes, 20);

      return bytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    byte[] toSingleDimensionBinaryRepresentation(BaseConnection connection, byte[][] array)
        throws SQLException, SQLFeatureNotSupportedException {
      int length = 0;
      for (int i = 0; i < array.length; ++i) {
        length += 4;
        if (array[i] != null) {
          length += array[i].length;
        }
      }
      final byte[] bytes = new byte[length];

      write(array, bytes, 0);
      return bytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    int countNulls(byte[][] array) {
      int nulls = 0;
      for (int i = 0; i < array.length; ++i) {
        if (array[i] == null) {
          ++nulls;
        }
      }
      return nulls;
    }

    private void write(byte[][] array, byte[] bytes, int offset) {
      int idx = offset;
      for (int i = 0; i < array.length; ++i) {
        if (array[i] != null) {
          ByteConverter.int4(bytes, idx, array[i].length);
          idx += 4;
          System.arraycopy(array[i], 0, bytes, idx, array[i].length);
          idx += array[i].length;
        } else {
          ByteConverter.int4(bytes, idx, -1);
          idx += 4;
        }
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void appendArray(StringBuilder sb, char delim, byte[][] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }

        if (array[i] != null) {
          sb.append("\"\\\\x");
          for (int j = 0; j < array[i].length; ++j) {
            byte b = array[i][j];

            // get the value for the left 4 bits (drop sign)
            sb.append(hexDigits[(b & 0xF0) >>> 4]);
            // get the value for the right 4 bits
            sb.append(hexDigits[b & 0x0F]);
          }
          sb.append('"');
        } else {
          sb.append("NULL");
        }
      }
      sb.append('}');
    }
  };

  private static final AbstractArrayEncoder<Object[]> OBJECT_ARRAY = new AbstractArrayEncoder<Object[]>(0, 0) {

    @Override
    public int getDefaultArrayTypeOid() {
      return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportBinaryRepresentation(int oid) {
      return false;
    }

    @Override
    public byte[] toBinaryRepresentation(BaseConnection connection, Object[] array, int oid)
        throws SQLException, SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    byte[] toSingleDimensionBinaryRepresentation(BaseConnection connection, Object[] array)
        throws SQLException, SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void appendArray(StringBuilder sb, char delim, Object[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        if (array[i] == null) {
          sb.append('N').append('U').append('L').append('L');
        } else if (array[i].getClass().isArray()) {
          if (array[i] instanceof byte[]) {
            throw new UnsupportedOperationException("byte[] nested inside Object[]");
          }
          try {
            getArrayEncoder(array[i]).appendArray(sb, delim, array[i]);
          } catch (PSQLException e) {
            // this should never happen
            throw new IllegalStateException(e);
          }
        } else {
          PgArray.escapeArrayElement(sb, array[i].toString());
        }
      }
      sb.append('}');
    }
  };

  @SuppressWarnings("rawtypes")
  private static final Map<Class, AbstractArrayEncoder> ARRAY_CLASS_TO_ENCODER = new HashMap<Class, AbstractArrayEncoder>(
      (int) (14 / .75) + 1);

  static {
    ARRAY_CLASS_TO_ENCODER.put(long.class, LONG_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(Long.class, LONG_OBJ_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(int.class, INT_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(Integer.class, INT_OBJ_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(short.class, SHORT_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(Short.class, SHORT_OBJ_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(double.class, DOUBLE_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(Double.class, DOUBLE_OBJ_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(float.class, FLOAT_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(Float.class, FLOAT_OBJ_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(boolean.class, BOOLEAN_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(Boolean.class, BOOLEAN_OBJ_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(byte[].class, BYTEA_ARRAY);
    ARRAY_CLASS_TO_ENCODER.put(String.class, STRING_ARRAY);
  }

  /**
   * Returns support for encoding <i>array</i>.
   *
   * @param array
   *          The array to encode. Must not be {@code null}.
   * @return An instance capable of encoding <i>array</i> as a {@code String} at
   *         minimum. Some types may support binary encoding.
   * @throws PSQLException
   *           if <i>array</i> is not a supported type.
   * @see ArrayEncoding.ArrayEncoder#supportBinaryRepresentation(int)
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public static <A extends Object> ArrayEncoder<A> getArrayEncoder(A array) throws PSQLException {
    final Class<?> arrayClazz = array.getClass();
    Class<?> subClazz = arrayClazz.getComponentType();
    if (subClazz == null) {
      throw new PSQLException(GT.tr("Invalid elements {0}", array), PSQLState.INVALID_PARAMETER_TYPE);
    }
    AbstractArrayEncoder<A> support = ARRAY_CLASS_TO_ENCODER.get(subClazz);
    if (support != null) {
      return support;
    }
    Class<?> subSubClazz = subClazz.getComponentType();
    if (subSubClazz == null) {
      if (Object.class.isAssignableFrom(subClazz)) {
        return (ArrayEncoder<A>) OBJECT_ARRAY;
      }
      throw new PSQLException(GT.tr("Invalid elements {0}", array), PSQLState.INVALID_PARAMETER_TYPE);
    }

    subClazz = subSubClazz;
    int dimensions = 2;
    while (subClazz != null) {
      support = ARRAY_CLASS_TO_ENCODER.get(subClazz);
      if (support != null) {
        if (dimensions == 2) {
          return new TwoDimensionPrimitiveArrayEncoder(support);
        }
        return new RecursiveArrayEncoder(support, dimensions);
      }
      subSubClazz = subClazz.getComponentType();
      if (subSubClazz == null) {
        if (Object.class.isAssignableFrom(subClazz)) {
          if (dimensions == 2) {
            return new TwoDimensionPrimitiveArrayEncoder(OBJECT_ARRAY);
          }
          return new RecursiveArrayEncoder(OBJECT_ARRAY, dimensions);
        }
      }
      ++dimensions;
      subClazz = subSubClazz;
    }

    throw new PSQLException(GT.tr("Invalid elements {0}", array), PSQLState.INVALID_PARAMETER_TYPE);
  }

  /**
   * Wraps an {@link AbstractArrayEncoder} implementation and provides optimized
   * support for 2 dimensions.
   */
  private static final class TwoDimensionPrimitiveArrayEncoder<A extends Object> implements ArrayEncoder<A[]> {
    private final AbstractArrayEncoder<A> support;

    /**
     * @param support
     *          The instance providing support for the base array type.
     */
    TwoDimensionPrimitiveArrayEncoder(AbstractArrayEncoder<A> support) {
      super();
      this.support = support;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultArrayTypeOid() {
      return support.getDefaultArrayTypeOid();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toArrayString(char delim, A[] array) {
      final StringBuilder sb = new StringBuilder(1024);
      appendArray(sb, delim, array);
      return sb.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void appendArray(StringBuilder sb, char delim, A[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        support.appendArray(sb, delim, array[i]);
      }
      sb.append('}');
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportBinaryRepresentation(int oid) {
      return support.supportBinaryRepresentation(oid);
    }

    /**
     * {@inheritDoc} 4 bytes - dimension 4 bytes - oid 4 bytes - ? 8*d bytes -
     * dimension length
     */
    @Override
    public byte[] toBinaryRepresentation(BaseConnection connection, A[] array, int oid)
        throws SQLException, SQLFeatureNotSupportedException {
      final ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(1024, (array.length * 32) + 20));
      final byte[] buffer = new byte[4];

      boolean hasNulls = false;
      for (int i = 0; !hasNulls && i < array.length; ++i) {
        if (support.countNulls(array[i]) > 0) {
          hasNulls = true;
        }
      }

      try {
        // 2 dimension
        ByteConverter.int4(buffer, 0, 2);
        baos.write(buffer);
        // nulls
        ByteConverter.int4(buffer, 0, hasNulls ? 1 : 0);
        baos.write(buffer);
        // oid
        ByteConverter.int4(buffer, 0, support.getTypeOID(oid));
        baos.write(buffer);

        // length
        ByteConverter.int4(buffer, 0, array.length);
        baos.write(buffer);
        // postgres defaults to 1 based lower bound
        ByteConverter.int4(buffer, 0, 1);
        baos.write(buffer);

        ByteConverter.int4(buffer, 0, array.length > 0 ? Array.getLength(array[0]) : 0);
        baos.write(buffer);
        // postgresql uses 1 base by default
        ByteConverter.int4(buffer, 0, 1);
        baos.write(buffer);

        for (int i = 0; i < array.length; ++i) {
          baos.write(support.toSingleDimensionBinaryRepresentation(connection, array[i]));
        }

        return baos.toByteArray();

      } catch (IOException e) {
        // this IO exception is from writing to baos, which will never throw an
        // IOException
        throw new java.lang.AssertionError(e);
      }
    }
  }

  /**
   * Wraps an {@link AbstractArrayEncoder} implementation and provides support for
   * 2 or more dimensions using recursion.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static final class RecursiveArrayEncoder implements ArrayEncoder {

    private final AbstractArrayEncoder support;
    private final @Positive int dimensions;

    /**
     * @param support
     *          The instance providing support for the base array type.
     */
    RecursiveArrayEncoder(AbstractArrayEncoder support, @Positive int dimensions) {
      super();
      this.support = support;
      this.dimensions = dimensions;
      assert dimensions >= 2;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultArrayTypeOid() {
      return support.getDefaultArrayTypeOid();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toArrayString(char delim, Object array) {
      final StringBuilder sb = new StringBuilder(2048);
      arrayString(sb, array, delim, dimensions);
      return sb.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void appendArray(StringBuilder sb, char delim, Object array) {
      arrayString(sb, array, delim, dimensions);
    }

    private void arrayString(StringBuilder sb, Object array, char delim, int depth) {

      if (depth > 1) {
        sb.append('{');
        for (int i = 0, j = Array.getLength(array); i < j; ++i) {
          if (i > 0) {
            sb.append(delim);
          }
          arrayString(sb, Array.get(array, i), delim, depth - 1);
        }
        sb.append('}');
      } else {
        support.appendArray(sb, delim, array);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportBinaryRepresentation(int oid) {
      return support.supportBinaryRepresentation(oid);
    }

    private boolean hasNulls(Object array, int depth) {
      if (depth > 1) {
        for (int i = 0, j = Array.getLength(array); i < j; ++i) {
          if (hasNulls(Array.get(array, i), depth - 1)) {
            return true;
          }
        }
        return false;
      }

      return support.countNulls(array) > 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] toBinaryRepresentation(BaseConnection connection, Object array, int oid)
        throws SQLException, SQLFeatureNotSupportedException {

      final boolean hasNulls = hasNulls(array, dimensions);

      final ByteArrayOutputStream baos = new ByteArrayOutputStream(1024 * dimensions);
      final byte[] buffer = new byte[4];

      try {
        // dimensions
        ByteConverter.int4(buffer, 0, dimensions);
        baos.write(buffer);
        // nulls
        ByteConverter.int4(buffer, 0, hasNulls ? 1 : 0);
        baos.write(buffer);
        // oid
        ByteConverter.int4(buffer, 0, support.getTypeOID(oid));
        baos.write(buffer);

        // length
        ByteConverter.int4(buffer, 0, Array.getLength(array));
        baos.write(buffer);
        // postgresql uses 1 base by default
        ByteConverter.int4(buffer, 0, 1);
        baos.write(buffer);

        writeArray(connection, buffer, baos, array, dimensions, true);

        return baos.toByteArray();

      } catch (IOException e) {
        // this IO exception is from writing to baos, which will never throw an
        // IOException
        throw new java.lang.AssertionError(e);
      }
    }

    private void writeArray(BaseConnection connection, byte[] buffer, ByteArrayOutputStream baos,
        Object array, int depth, boolean first) throws IOException, SQLException {
      final int length = Array.getLength(array);

      if (first) {
        ByteConverter.int4(buffer, 0, length > 0 ? Array.getLength(Array.get(array, 0)) : 0);
        baos.write(buffer);
        // postgresql uses 1 base by default
        ByteConverter.int4(buffer, 0, 1);
        baos.write(buffer);
      }

      for (int i = 0; i < length; ++i) {
        final Object subArray = Array.get(array, i);
        if (depth > 2) {
          writeArray(connection, buffer, baos, subArray, depth - 1, i == 0);
        } else {
          baos.write(support.toSingleDimensionBinaryRepresentation(connection, subArray));
        }
      }
    }

  }
}
