package org.postgresql.jdbc;

import java.util.HashMap;
import java.util.Map;

final class PrimitiveArraySupport {
  public static interface ArrayToString<A> {

    String getDefaultTypeName();

    String toArrayString(char delim, A array);
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
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        sb.append(array[i]);
      }
      return sb.toString();
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
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        sb.append(array[i]);
      }
      return sb.toString();
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
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        sb.append(array[i]);
      }
      return sb.toString();
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
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        sb.append(array[i]);
      }
      return sb.toString();
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
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        sb.append(array[i]);
      }
      return sb.toString();
    }

  };

  private static final ArrayToString<boolean[]> BOOLEAN_ARRAY_TOSTRING = new ArrayToString<boolean[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDefaultTypeName() {
      return "bool[]";
    }

    @Override
    public String toArrayString(char delim, boolean[] array) {
      final StringBuilder sb = new StringBuilder(Math.max(32, array.length * 6));
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        sb.append(array[i]);
      }
      return sb.toString();
    }

  };

  private static final ArrayToString<char[]> CHAR_ARRAY_TOSTRING = new ArrayToString<char[]>() {

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDefaultTypeName() {
      return "char[]";
    }

    @Override
    public String toArrayString(char delim, char[] array) {
      final StringBuilder sb = new StringBuilder(Math.max(16, array.length * 2) + 2);
      sb.append('{');
      for (int i = 0; i < array.length; ++i) {
        if (i > 0) {
          sb.append(delim);
        }
        sb.append(array[i]);
      }
      return sb.toString();
    }

  };

  private static final Map<Class, ArrayToString> ARRAY_CLASS_TOSTRING = new HashMap<>(11);

  static {
    ARRAY_CLASS_TOSTRING.put(long[].class, LONG_ARRAY_TOSTRING);
    ARRAY_CLASS_TOSTRING.put(int[].class, INT_ARRAY_TOSTRING);
    ARRAY_CLASS_TOSTRING.put(short[].class, SHORT_ARRAY_TOSTRING);
    ARRAY_CLASS_TOSTRING.put(double[].class, DOUBLE_ARRAY_TOSTRING);
    ARRAY_CLASS_TOSTRING.put(float[].class, FLOAT_ARRAY_TOSTRING);
    ARRAY_CLASS_TOSTRING.put(boolean[].class, BOOLEAN_ARRAY_TOSTRING);
    ARRAY_CLASS_TOSTRING.put(char[].class, CHAR_ARRAY_TOSTRING);
  }

  public static boolean isSupportedPrimitiveArray(Object obj) {
    return obj != null && ARRAY_CLASS_TOSTRING.containsKey(obj.getClass());
  }

  public static <A> ArrayToString<A> getArrayToString(A array) {
    return ARRAY_CLASS_TOSTRING.get(array.getClass());
  }
}
