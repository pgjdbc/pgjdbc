package org.postgresql.jdbc;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.junit.Test;
import org.postgresql.core.Oid;

public class PrimitiveArraySupportTest {

  public PrimitiveArraySupport.ArrayToString<long[]> longArrays = PrimitiveArraySupport.getArrayToString(new long[] {});
  public PrimitiveArraySupport.ArrayToString<int[]> intArrays = PrimitiveArraySupport.getArrayToString(new int[] {});
  public PrimitiveArraySupport.ArrayToString<short[]> shortArrays = PrimitiveArraySupport.getArrayToString(new short[] {});
  public PrimitiveArraySupport.ArrayToString<double[]> doubleArrays = PrimitiveArraySupport.getArrayToString(new double[] {});
  public PrimitiveArraySupport.ArrayToString<float[]> floatArrays = PrimitiveArraySupport.getArrayToString(new float[] {});


  @Test
  public void testLongBinary() throws Exception {
    final long[] longs = new long[84];
    for (int i = 0; i < 84; ++i) {
      longs[i] = i - 3;
    }

    final PgArray pgArray = new PgArray(null, Oid.INT8_ARRAY, longArrays.toBinaryRepresentation(longs));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(Long[].class));

    final Long[] actual = (Long[]) arrayObj;

    assertEquals(longs.length, actual.length);

    for (int i = 0; i < longs.length; ++i) {
      assertEquals(Long.valueOf(longs[i]), actual[i]);
    }
  }

  @Test
  public void testLongToString() throws Exception {
    final long[] longs = new long[]{12367890987L, 987664198234L, -2982470923874L};

    final String arrayString = longArrays.toArrayString(',', longs);

    assertEquals("{12367890987,987664198234,-2982470923874}", arrayString);


    final String altArrayString = longArrays.toArrayString(';', longs);

    assertEquals("{12367890987;987664198234;-2982470923874}", altArrayString);
    
  }
  
  @Test
  public void testIntBinary() throws Exception {
    final int[] ints = new int[13];
    for (int i = 0; i < 13; ++i) {
      ints[i] = i - 3;
    }

    final PgArray pgArray = new PgArray(null, Oid.INT4_ARRAY, intArrays.toBinaryRepresentation(ints));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(Integer[].class));

    final Integer[] actual = (Integer[]) arrayObj;

    assertEquals(ints.length, actual.length);

    for (int i = 0; i < ints.length; ++i) {
      assertEquals(Integer.valueOf(ints[i]), actual[i]);
    }
  }

  @Test
  public void testIntToString() throws Exception {
    final int[] ints = new int[]{12367890, 987664198, -298247092};

    final String arrayString = intArrays.toArrayString(',', ints);

    assertEquals("{12367890,987664198,-298247092}", arrayString);


    final String altArrayString = intArrays.toArrayString(';', ints);

    assertEquals("{12367890;987664198;-298247092}", altArrayString);
    
  }
  
  @Test
  public void testShorttBinary() throws Exception {
    final short[] shorts = new short[13];
    for (int i = 0; i < 13; ++i) {
      shorts[i] = (short) (i - 3);
    }

    final PgArray pgArray = new PgArray(null, Oid.INT4_ARRAY, shortArrays.toBinaryRepresentation(shorts));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(Short[].class));

    final Short[] actual = (Short[]) arrayObj;

    assertEquals(shorts.length, actual.length);

    for (int i = 0; i < shorts.length; ++i) {
      assertEquals(Short.valueOf(shorts[i]), actual[i]);
    }
  }

  @Test
  public void testShortToString() throws Exception {
    final short[] shorts = new short[]{123, 34, -57};

    final String arrayString = shortArrays.toArrayString(',', shorts);

    assertEquals("{123,34,-57}", arrayString);


    final String altArrayString = shortArrays.toArrayString(';', shorts);

    assertEquals("{123;34;-57}", altArrayString);
    
  }
  
  @Test
  public void testDoubleBinary() throws Exception {
    final double[] doubles = new double[13];
    for (int i = 0; i < 13; ++i) {
      doubles[i] = i - 3.1;
    }

    final PgArray pgArray = new PgArray(null, Oid.FLOAT8_ARRAY, doubleArrays.toBinaryRepresentation(doubles));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(Double[].class));

    final Double[] actual = (Double[]) arrayObj;

    assertEquals(doubles.length, actual.length);

    for (int i = 0; i < doubles.length; ++i) {
      assertEquals(Double.valueOf(doubles[i]), actual[i]);
    }
  }

  @Test
  public void testdoubleToString() throws Exception {
    final double[] doubles = new double[]{122353.345, 923487.235987, -23.239486};

    final String arrayString = doubleArrays.toArrayString(',', doubles);

    assertEquals("{122353.345,923487.235987,-23.239486}", arrayString);


    final String altArrayString = doubleArrays.toArrayString(';', doubles);

    assertEquals("{122353.345;923487.235987;-23.239486}", altArrayString);
    
  }
  
  @Test
  public void testFloatBinary() throws Exception {
    final float[] floats = new float[13];
    for (int i = 0; i < 13; ++i) {
      floats[i] = (float) (i - 3.1);
    }

    final PgArray pgArray = new PgArray(null, Oid.FLOAT4_ARRAY, floatArrays.toBinaryRepresentation(floats));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(Float[].class));

    final Float[] actual = (Float[]) arrayObj;

    assertEquals(floats.length, actual.length);

    for (int i = 0; i < floats.length; ++i) {
      assertEquals(Float.valueOf(floats[i]), actual[i]);
    }
  }

  @Test
  public void testfloatToString() throws Exception {
    final float[] floats = new float[]{122353.34f, 923487.25f, -23.2394f};

    final String arrayString = floatArrays.toArrayString(',', floats);

    assertEquals("{122353.34,923487.25,-23.2394}", arrayString);


    final String altArrayString = floatArrays.toArrayString(';', floats);

    assertEquals("{122353.34;923487.25;-23.2394}", altArrayString);
    
  }

}
