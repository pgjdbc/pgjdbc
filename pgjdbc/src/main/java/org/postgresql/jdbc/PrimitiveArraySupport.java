package org.postgresql.jdbc;

import org.postgresql.core.Oid;
import org.postgresql.util.ByteConverter;

import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.Map;

final class PrimitiveArraySupport {
  public static interface ArrayToString<A> {

    String getDefaultTypeName();

    String toArrayString(char delim, A array);

    public void appendArray(StringBuilder sb, char delim, A array);
    
    public boolean supportBinaryRepresentation();
    
    public byte[] toBinaryRepresentation(A array) throws SQLFeatureNotSupportedException;
  }

  private static final ArrayToString<long[]> LONG_ARRAY_TOSTRING = new ArrayToString<long[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDefaultTypeName() {
      return "int8[]";
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
    public boolean supportBinaryRepresentation() {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] toBinaryRepresentation(long[] array) {

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

  private static final ArrayToString<int[]> INT_ARRAY_TOSTRING = new ArrayToString<int[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDefaultTypeName() {
      return "int4[]";
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
    public boolean supportBinaryRepresentation() {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] toBinaryRepresentation(int[] array) {

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

  private static final ArrayToString<short[]> SHORT_ARRAY_TOSTRING = new ArrayToString<short[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDefaultTypeName() {
      return "int2[]";
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
    public boolean supportBinaryRepresentation() {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] toBinaryRepresentation(short[] array) {

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

  private static final ArrayToString<double[]> DOUBLE_ARRAY_TOSTRING = new ArrayToString<double[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDefaultTypeName() {
      return "float8[]";
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
        sb.append(array[i]);
      }
      sb.append('}');
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportBinaryRepresentation() {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] toBinaryRepresentation(double[] array) {

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

  private static final ArrayToString<float[]> FLOAT_ARRAY_TOSTRING = new ArrayToString<float[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDefaultTypeName() {
      return "float4[]";
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
        sb.append(array[i]);
      }
      sb.append('}');
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportBinaryRepresentation() {
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] toBinaryRepresentation(float[] array) {

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

  private static final Map<Class, ArrayToString> ARRAY_CLASS_TOSTRING = new HashMap<>(8);

  static {
    ARRAY_CLASS_TOSTRING.put(long[].class, LONG_ARRAY_TOSTRING);
    ARRAY_CLASS_TOSTRING.put(int[].class, INT_ARRAY_TOSTRING);
    ARRAY_CLASS_TOSTRING.put(short[].class, SHORT_ARRAY_TOSTRING);
    ARRAY_CLASS_TOSTRING.put(double[].class, DOUBLE_ARRAY_TOSTRING);
    ARRAY_CLASS_TOSTRING.put(float[].class, FLOAT_ARRAY_TOSTRING);
  }

  public static boolean isSupportedPrimitiveArray(Object obj) {
    return obj != null && ARRAY_CLASS_TOSTRING.containsKey(obj.getClass());
  }

  public static <A> ArrayToString<A> getArrayToString(A array) {
    return ARRAY_CLASS_TOSTRING.get(array.getClass());
  }

  public static <A> String toArrayString(char delim, A array) {
    return ARRAY_CLASS_TOSTRING.get(array.getClass()).toArrayString(delim, array);
  }
}
