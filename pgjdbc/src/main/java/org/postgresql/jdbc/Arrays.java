
package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Encoding;
import org.postgresql.core.Oid;
import org.postgresql.core.TypeInfo;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility for using arrays in requests.
 */
final class Arrays {

  public interface ArraySupport<A> {

    /**
     * The default array type oid supported by this instance.
     * 
     * @param tiCache
     * @return The default array type oid supported by this instance.
     */
    public abstract int getDefaultArrayTypeOid(TypeInfo tiCache);

    /**
     * Creates {@code String} representation of the <i>array</i>.
     * 
     * @param delim
     *          The character to use to delimit between elements.
     * @param array
     *          The array to represent as a {@code String}.
     * @return {@code String} representation of the <i>array</i>.
     */
    public abstract String toArrayString(char delim, A array);

    /**
     * Indicates if an array can be encoded in binary form to array <i>oid</i>.
     * 
     * @param oid
     *          The array oid to see check for binary support.
     * @return Indication of whether
     *         {@link #toBinaryRepresentation(BaseConnection, Object, int)} is
     *         supported for <i>oid</i>.
     */
    public boolean supportBinaryRepresentation(int oid);

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
     * @throws SQLException
     * @throws SQLFeatureNotSupportedException
     */
    public abstract byte[] toBinaryRepresentation(BaseConnection connection, A array, int oid)
        throws SQLException, SQLFeatureNotSupportedException;
  }

  /**
   * Base class to implement {@link Arrays.ArraySupport} and provide
   * multi-dimensional support.
   * 
   * @param <A>
   *          Base array type supported.
   */
  private static abstract class AbstractArraySupport<A> implements ArraySupport<A> {

    private final int oid;

    final int arrayOid;

    /**
     * 
     * @param oid
     *          The default/primary base oid type.
     * @param arrayOid
     *          The default/primary array oid type.
     */
    AbstractArraySupport(int oid, int arrayOid) {
      this.oid = oid;
      this.arrayOid = arrayOid;
    }

    /**
     * 
     * @param arrayOid
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
    public int getDefaultArrayTypeOid(TypeInfo tiCache) {
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
     * @param array
     *          The array to create binary representation of. Will not be
     *          {@code null}, but may contain {@code null} elements.
     * @return {@code byte[]} of just the raw data (no metadata).
     * @throws SQLException
     * @throws SQLFeatureNotSupportedException
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
    abstract void appendArray(StringBuilder sb, char delim, A array);

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
  private static abstract class NumberArraySupport<N extends Number> extends AbstractArraySupport<N[]> {

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
    NumberArraySupport(int fieldSize, int oid, int arrayOid) {
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

    private final byte[] writeBytes(final N[] array, final int nullCount, final int offset) {
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
    final void appendArray(StringBuilder sb, char delim, N[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i != 0) {
          sb.append(delim);
        }
        if (array[i] == null) {
          sb.append('N');
          sb.append('U');
          sb.append('L');
          sb.append('L');
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
  private static abstract class FixedSizePrimitiveArraySupport<A> extends AbstractArraySupport<A> {

    private final int fieldSize;

    FixedSizePrimitiveArraySupport(int fieldSize, int oid, int arrayOid) {
      super(oid, arrayOid);
      this.fieldSize = fieldSize;
    }

    /**
     * {@inheritDoc}
     * 
     * Always returns {@code 0}.
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

  private static final AbstractArraySupport<long[]> LONG_ARRAY = new FixedSizePrimitiveArraySupport<long[]>(8, Oid.INT8,
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

  private static final AbstractArraySupport<Long[]> LONG_OBJ_ARRAY = new NumberArraySupport<Long>(8, Oid.INT8,
      Oid.INT8_ARRAY) {

    @Override
    protected void write(Long number, byte[] bytes, int offset) {
      ByteConverter.int8(bytes, offset, number.longValue());
    }
  };

  private static final AbstractArraySupport<int[]> INT_ARRAY = new FixedSizePrimitiveArraySupport<int[]>(4, Oid.INT4,
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

  private static final AbstractArraySupport<Integer[]> INT_OBJ_ARRAY = new NumberArraySupport<Integer>(4, Oid.INT4,
      Oid.INT4_ARRAY) {

    @Override
    protected void write(Integer number, byte[] bytes, int offset) {
      ByteConverter.int4(bytes, offset, number.intValue());
    }
  };

  private static final AbstractArraySupport<short[]> SHORT_ARRAY = new FixedSizePrimitiveArraySupport<short[]>(2,
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

  private static final AbstractArraySupport<Short[]> SHORT_OBJ_ARRAY = new NumberArraySupport<Short>(2, Oid.INT2,
      Oid.INT2_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(Short number, byte[] bytes, int offset) {
      ByteConverter.int2(bytes, offset, number.shortValue());
    }
  };

  private static final AbstractArraySupport<double[]> DOUBLE_ARRAY = new FixedSizePrimitiveArraySupport<double[]>(8,
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

  private static final AbstractArraySupport<Double[]> DOUBLE_OBJ_ARRAY = new NumberArraySupport<Double>(8, Oid.FLOAT8,
      Oid.FLOAT8_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(Double number, byte[] bytes, int offset) {
      ByteConverter.float8(bytes, offset, number.doubleValue());
    }
  };

  private static final AbstractArraySupport<float[]> FLOAT_ARRAY = new FixedSizePrimitiveArraySupport<float[]>(4,
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

  private static final AbstractArraySupport<Float[]> FLOAT_OBJ_ARRAY = new NumberArraySupport<Float>(4, Oid.FLOAT4,
      Oid.FLOAT4_ARRAY) {

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(Float number, byte[] bytes, int offset) {
      ByteConverter.float4(bytes, offset, number.floatValue());
    }
  };

  private static final AbstractArraySupport<boolean[]> BOOLEAN_ARRAY = new FixedSizePrimitiveArraySupport<boolean[]>(1,
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

  private static final AbstractArraySupport<Boolean[]> BOOLEAN_OBJ_ARRAY = new AbstractArraySupport<Boolean[]>(Oid.BOOL,
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

      return bytes;
    }

    private final byte[] writeBytes(final Boolean[] array, final int nullCount, final int offset) {
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
    void appendArray(StringBuilder sb, char delim, Boolean[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i != 0) {
          sb.append(delim);
        }
        if (array[i] == null) {
          sb.append('N');
          sb.append('U');
          sb.append('L');
          sb.append('L');
        } else {
          sb.append(array[i].booleanValue() ? '1' : '0');
        }
      }
      sb.append('}');
    }
  };

  private static final AbstractArraySupport<String[]> STRING_ARRAY = new AbstractArraySupport<String[]>(Oid.VARCHAR,
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
          sb.append('N');
          sb.append('U');
          sb.append('L');
          sb.append('L');
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

        // write 4 empty bytes
        java.util.Arrays.fill(buffer, (byte) 0);
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

  private static final AbstractArraySupport<byte[][]> BYTEA_ARRAY = new AbstractArraySupport<byte[][]>(Oid.BYTEA,
      Oid.BYTEA_ARRAY) {

    /**
     * Bit Mask for first 4 bits.
     */
    private final int BITS_1111_0000 = 0xF0;

    /**
     * Bit Mask for last 4 bits.
     */
    private final int BITS_0000_1111 = 0x0F;

    /**
     * The possible characters to use for representing hex binary data.
     */
    private final char[] HEX_DIGITS = new char[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd',
        'e', 'f' };

    /**
     * {@inheritDoc}
     */
    @Override
    public final byte[] toBinaryRepresentation(BaseConnection connection, byte[][] array, int oid)
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

    private final void write(byte[][] array, byte[] bytes, int offset) {
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
    void appendArray(StringBuilder sb, char delim, byte[][] array) {
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
            sb.append(HEX_DIGITS[(b & BITS_1111_0000) >>> 4]);
            // get the value for the right 4 bits
            sb.append(HEX_DIGITS[b & BITS_0000_1111]);
          }
          sb.append('"');
        } else {
          sb.append("NULL");
        }
      }
      sb.append('}');
    }
  };

  private static final AbstractArraySupport<Object[]> OBJECT_ARRAY = new AbstractArraySupport<Object[]>(0, 0) {

    @Override
    public int getDefaultArrayTypeOid(TypeInfo tiCache) {
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
    void appendArray(StringBuilder sb, char delim, Object[] array) {
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        if (array[i] == null) {
          sb.append('N');
          sb.append('U');
          sb.append('L');
          sb.append('L');
        } else {
          PgArray.escapeArrayElement(sb, array[i].toString());
        }
      }
      sb.append('}');
    }
  };

  @SuppressWarnings("rawtypes")
  private static final Map<Class, AbstractArraySupport> ARRAY_CLASS_TO_SUPPORT = new HashMap<Class, AbstractArraySupport>(
      (int) (14 / .75) + 1);

  static {
    ARRAY_CLASS_TO_SUPPORT.put(long.class, LONG_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(Long.class, LONG_OBJ_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(int.class, INT_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(Integer.class, INT_OBJ_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(short.class, SHORT_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(Short.class, SHORT_OBJ_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(double.class, DOUBLE_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(Double.class, DOUBLE_OBJ_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(float.class, FLOAT_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(Float.class, FLOAT_OBJ_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(boolean.class, BOOLEAN_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(Boolean.class, BOOLEAN_OBJ_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(byte[].class, BYTEA_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(String.class, STRING_ARRAY);
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
   * @see ArraySupport#supportBinaryRepresentation(int)
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public static <A> ArraySupport<A> getArraySupport(A array) throws PSQLException {
    final Class<? extends Object> arrayClazz = array.getClass();
    Class<?> subClazz = arrayClazz.getComponentType();
    AbstractArraySupport<A> support = ARRAY_CLASS_TO_SUPPORT.get(subClazz);
    if (support != null) {
      return support;
    }
    if (subClazz == null) {
      throw new PSQLException(GT.tr("Invalid elements {0}", array), PSQLState.INVALID_PARAMETER_TYPE);
    }
    Class<?> subSubClazz = subClazz.getComponentType();
    if (subSubClazz == null) {
      if (Object.class.isAssignableFrom(subClazz)) {
        return (ArraySupport<A>) OBJECT_ARRAY;
      }
      throw new PSQLException(GT.tr("Invalid elements {0}", array), PSQLState.INVALID_PARAMETER_TYPE);
    }

    subClazz = subSubClazz;
    int dimensions = 2;
    while (subClazz != null) {
      support = ARRAY_CLASS_TO_SUPPORT.get(subClazz);
      if (support != null) {
        return dimensions == 2 ? new TwoDimensionPrimitiveArraySupport(support)
            : new RecursiveArraySupport(support, dimensions);
      }
      subSubClazz = subClazz.getComponentType();
      if (subSubClazz == null) {
        if (Object.class.isAssignableFrom(subClazz)) {
          return dimensions == 2 ? new TwoDimensionPrimitiveArraySupport(OBJECT_ARRAY)
              : new RecursiveArraySupport(OBJECT_ARRAY, dimensions);
        }
      }
      ++dimensions;
      subClazz = subSubClazz;
    }

    throw new PSQLException(GT.tr("Invalid elements {0}", array), PSQLState.INVALID_PARAMETER_TYPE);
  }

  /**
   * Wraps an {@link AbstractArraySupport} implementation and provides optimized
   * support for 2 dimensions.
   */
  private static final class TwoDimensionPrimitiveArraySupport<A> implements ArraySupport<A[]> {
    private final AbstractArraySupport<A> support;

    /**
     * @param support
     */
    TwoDimensionPrimitiveArraySupport(AbstractArraySupport<A> support) {
      super();
      this.support = support;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultArrayTypeOid(TypeInfo tiCache) {
      return support.getDefaultArrayTypeOid(tiCache);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toArrayString(char delim, A[] array) {
      final StringBuilder sb = new StringBuilder(1024);
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        support.appendArray(sb, delim, array[i]);
      }
      sb.append('}');
      return sb.toString();
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
        // write 4 empty bytes
        java.util.Arrays.fill(buffer, (byte) 0);
        baos.write(buffer);

        ByteConverter.int4(buffer, 0, array.length > 0 ? Array.getLength(array[0]) : 0);
        baos.write(buffer);
        // write 4 empty bytes
        java.util.Arrays.fill(buffer, (byte) 0);
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
   * Wraps an {@link AbstractArraySupport} implementation and provides support for
   * 2 or more dimensions using recursion.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static final class RecursiveArraySupport implements ArraySupport {

    private final AbstractArraySupport support;
    private final int dimensions;

    /**
     * @param support
     */
    RecursiveArraySupport(AbstractArraySupport support, int dimensions) {
      super();
      this.support = support;
      this.dimensions = dimensions;
      assert dimensions >= 2;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultArrayTypeOid(TypeInfo tiCache) {
      return support.getDefaultArrayTypeOid(tiCache);
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

    final void arrayString(StringBuilder sb, Object array, char delim, int depth) {

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

    private final boolean hasNulls(Object array, int depth) {
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
        // write 4 empty bytes
        java.util.Arrays.fill(buffer, (byte) 0);
        baos.write(buffer);

        writeArray(connection, buffer, baos, array, dimensions, true);

        return baos.toByteArray();

      } catch (IOException e) {
        // this IO exception is from writing to baos, which will never throw an
        // IOException
        throw new java.lang.AssertionError(e);
      }
    }

    private final void writeArray(BaseConnection connection, byte[] buffer, ByteArrayOutputStream baos, Object array,
        int depth, boolean first) throws IOException, SQLException {
      final int length = Array.getLength(array);

      if (first) {
        ByteConverter.int4(buffer, 0, length > 0 ? Array.getLength(Array.get(array, 0)) : 0);
        baos.write(buffer);
        // write 4 empty bytes
        java.util.Arrays.fill(buffer, (byte) 0);
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
