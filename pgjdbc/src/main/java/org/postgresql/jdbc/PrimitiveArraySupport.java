/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;
import org.postgresql.core.TypeInfo;
import org.postgresql.util.ByteConverter;

import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.Map;

abstract class PrimitiveArraySupport<A> {

  public abstract int getDefaultArrayTypeOid(TypeInfo tiCache);

  public abstract String toArrayString(char delim, A array);

  public abstract void appendArray(StringBuilder sb, char delim, A array);

  public boolean supportBinaryRepresentation() {
    return true;
  }

  public abstract byte[] toBinaryRepresentation(Connection connection, A array) throws SQLFeatureNotSupportedException;

  private static final PrimitiveArraySupport<long[]> LONG_ARRAY = new PrimitiveArraySupport<long[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultArrayTypeOid(TypeInfo tiCache) {
      return Oid.INT8_ARRAY;
    }

    @Override
    public String toArrayString(char delim, long[] array) {
      final StringBuilder sb = new StringBuilder(Math.max(64, array.length * 8));
      appendArray(sb, delim, array);
      return sb.toString();
    }

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
    public byte[] toBinaryRepresentation(Connection connection, long[] array) {

      int length = 20 + (12 * array.length);
      final byte[] bytes = new byte[length];

      // 1 dimension
      ByteConverter.int4(bytes, 0, 1);
      // no null
      ByteConverter.int4(bytes, 4, 0);
      // oid
      ByteConverter.int4(bytes, 8, Oid.INT8);
      // length
      ByteConverter.int4(bytes, 12, array.length);

      int idx = 20;
      for (int i = 0; i < array.length; ++i) {
        bytes[idx + 3] = 8;
        ByteConverter.int8(bytes, idx + 4, array[i]);
        idx += 12;
      }

      return bytes;
    }
  };

  private static final PrimitiveArraySupport<int[]> INT_ARRAY = new PrimitiveArraySupport<int[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultArrayTypeOid(TypeInfo tiCache) {
      return Oid.INT4_ARRAY;
    }

    @Override
    public String toArrayString(char delim, int[] array) {
      final StringBuilder sb = new StringBuilder(Math.max(32, array.length * 6));
      appendArray(sb, delim, array);
      return sb.toString();
    }

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
    public byte[] toBinaryRepresentation(Connection connection, int[] array) {

      int length = 20 + (8 * array.length);
      final byte[] bytes = new byte[length];

      // 1 dimension
      ByteConverter.int4(bytes, 0, 1);
      // no null
      ByteConverter.int4(bytes, 4, 0);
      // oid
      ByteConverter.int4(bytes, 8, Oid.INT4);
      // length
      ByteConverter.int4(bytes, 12, array.length);

      int idx = 20;
      for (int i = 0; i < array.length; ++i) {
        bytes[idx + 3] = 4;
        ByteConverter.int4(bytes, idx + 4, array[i]);
        idx += 8;
      }

      return bytes;
    }
  };

  private static final PrimitiveArraySupport<short[]> SHORT_ARRAY = new PrimitiveArraySupport<short[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultArrayTypeOid(TypeInfo tiCache) {
      return Oid.INT2_ARRAY;
    }

    @Override
    public String toArrayString(char delim, short[] array) {
      final StringBuilder sb = new StringBuilder(Math.max(32, array.length * 4));
      appendArray(sb, delim, array);
      return sb.toString();
    }

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
    public byte[] toBinaryRepresentation(Connection connection, short[] array) {

      int length = 20 + (6 * array.length);
      final byte[] bytes = new byte[length];

      // 1 dimension
      ByteConverter.int4(bytes, 0, 1);
      // no null
      ByteConverter.int4(bytes, 4, 0);
      // oid
      ByteConverter.int4(bytes, 8, Oid.INT2);
      // length
      ByteConverter.int4(bytes, 12, array.length);

      int idx = 20;
      for (int i = 0; i < array.length; ++i) {
        bytes[idx + 3] = 2;
        ByteConverter.int2(bytes, idx + 4, array[i]);
        idx += 6;
      }

      return bytes;
    }

  };

  private static final PrimitiveArraySupport<double[]> DOUBLE_ARRAY = new PrimitiveArraySupport<double[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultArrayTypeOid(TypeInfo tiCache) {
      return Oid.FLOAT8_ARRAY;
    }

    @Override
    public String toArrayString(char delim, double[] array) {
      final StringBuilder sb = new StringBuilder(Math.max(64, array.length * 8));
      appendArray(sb, delim, array);
      return sb.toString();
    }

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
    public byte[] toBinaryRepresentation(Connection connection, double[] array) {

      int length = 20 + (12 * array.length);
      final byte[] bytes = new byte[length];

      // 1 dimension
      ByteConverter.int4(bytes, 0, 1);
      // no null
      ByteConverter.int4(bytes, 4, 0);
      // oid
      ByteConverter.int4(bytes, 8, Oid.FLOAT8);
      // length
      ByteConverter.int4(bytes, 12, array.length);

      int idx = 20;
      for (int i = 0; i < array.length; ++i) {
        bytes[idx + 3] = 8;
        ByteConverter.float8(bytes, idx + 4, array[i]);
        idx += 12;
      }

      return bytes;
    }

  };

  private static final PrimitiveArraySupport<float[]> FLOAT_ARRAY = new PrimitiveArraySupport<float[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultArrayTypeOid(TypeInfo tiCache) {
      return Oid.FLOAT4_ARRAY;
    }

    @Override
    public String toArrayString(char delim, float[] array) {
      final StringBuilder sb = new StringBuilder(Math.max(64, array.length * 8));
      appendArray(sb, delim, array);
      return sb.toString();
    }

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
    public byte[] toBinaryRepresentation(Connection connection, float[] array) {

      int length = 20 + (8 * array.length);
      final byte[] bytes = new byte[length];

      // 1 dimension
      ByteConverter.int4(bytes, 0, 1);
      // no null
      ByteConverter.int4(bytes, 4, 0);
      // oid
      ByteConverter.int4(bytes, 8, Oid.FLOAT4);
      // length
      ByteConverter.int4(bytes, 12, array.length);

      int idx = 20;
      for (int i = 0; i < array.length; ++i) {
        bytes[idx + 3] = 4;
        ByteConverter.float4(bytes, idx + 4, array[i]);
        idx += 8;
      }

      return bytes;
    }

  };

  private static final PrimitiveArraySupport<boolean[]> BOOLEAN_ARRAY = new PrimitiveArraySupport<boolean[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultArrayTypeOid(TypeInfo tiCache) {
      return Oid.BOOL_ARRAY;
    }

    @Override
    public String toArrayString(char delim, boolean[] array) {
      final StringBuilder sb = new StringBuilder(Math.max(64, array.length * 8));
      appendArray(sb, delim, array);
      return sb.toString();
    }

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
     *
     * @throws SQLFeatureNotSupportedException
     *           Because this feature is not supported.
     */
    @Override
    public byte[] toBinaryRepresentation(Connection connection, boolean[] array) throws SQLFeatureNotSupportedException {
      int length = 20 + (5 * array.length);
      final byte[] bytes = new byte[length];

      // 1 dimension
      ByteConverter.int4(bytes, 0, 1);
      // no null
      ByteConverter.int4(bytes, 4, 0);
      // oid
      ByteConverter.int4(bytes, 8, Oid.BOOL);
      // length
      ByteConverter.int4(bytes, 12, array.length);

      int idx = 20;
      for (int i = 0; i < array.length; ++i) {
        bytes[idx + 3] = 1;
        ByteConverter.bool(bytes, idx + 4, array[i]);
        idx += 5;
      }

      return bytes;
    }

  };

  private static final PrimitiveArraySupport<String[]> STRING_ARRAY = new PrimitiveArraySupport<String[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public int getDefaultArrayTypeOid(TypeInfo tiCache) {
      return Oid.VARCHAR_ARRAY;
    }

    @Override
    public String toArrayString(char delim, String[] array) {
      final StringBuilder sb = new StringBuilder(Math.max(64, array.length * 8));
      appendArray(sb, delim, array);
      return sb.toString();
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
    public boolean supportBinaryRepresentation() {
      return false;
    }

    /**
     * {@inheritDoc}
     *
     * @throws SQLFeatureNotSupportedException
     *           Because this feature is not supported.
     */
    @Override
    public byte[] toBinaryRepresentation(Connection connection, String[] array) throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

  };

  private static final Map<Class, PrimitiveArraySupport> ARRAY_CLASS_TO_SUPPORT = new HashMap<Class, PrimitiveArraySupport>((int) (7 / .75) + 1);

  static {
    ARRAY_CLASS_TO_SUPPORT.put(long[].class, LONG_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(int[].class, INT_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(short[].class, SHORT_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(double[].class, DOUBLE_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(float[].class, FLOAT_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(boolean[].class, BOOLEAN_ARRAY);
    ARRAY_CLASS_TO_SUPPORT.put(String[].class, STRING_ARRAY);
  }

  public static boolean isSupportedPrimitiveArray(Object obj) {
    return obj != null && ARRAY_CLASS_TO_SUPPORT.containsKey(obj.getClass());
  }

  public static <A> PrimitiveArraySupport<A> getArraySupport(A array) {
    return ARRAY_CLASS_TO_SUPPORT.get(array.getClass());
  }
}
