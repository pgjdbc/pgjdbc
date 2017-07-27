/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import org.postgresql.core.Oid;

import org.junit.Test;

import java.sql.SQLFeatureNotSupportedException;

public class PrimitiveArraySupportTest {

  public PrimitiveArraySupport<long[]> longArrays = PrimitiveArraySupport.getArraySupport(new long[] {});
  public PrimitiveArraySupport<int[]> intArrays = PrimitiveArraySupport.getArraySupport(new int[] {});
  public PrimitiveArraySupport<short[]> shortArrays = PrimitiveArraySupport.getArraySupport(new short[] {});
  public PrimitiveArraySupport<double[]> doubleArrays = PrimitiveArraySupport.getArraySupport(new double[] {});
  public PrimitiveArraySupport<float[]> floatArrays = PrimitiveArraySupport.getArraySupport(new float[] {});
  public PrimitiveArraySupport<boolean[]> booleanArrays = PrimitiveArraySupport.getArraySupport(new boolean[] {});

  @Test
  public void testLongBinary() throws Exception {
    final long[] longs = new long[84];
    for (int i = 0; i < 84; ++i) {
      longs[i] = i - 3;
    }

    final PgArray pgArray = new PgArray(null, Oid.INT8_ARRAY, longArrays.toBinaryRepresentation(null, longs));

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
    final long[] longs = new long[] { 12367890987L, 987664198234L, -2982470923874L };

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

    final PgArray pgArray = new PgArray(null, Oid.INT4_ARRAY, intArrays.toBinaryRepresentation(null, ints));

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
    final int[] ints = new int[] { 12367890, 987664198, -298247092 };

    final String arrayString = intArrays.toArrayString(',', ints);

    assertEquals("{12367890,987664198,-298247092}", arrayString);

    final String altArrayString = intArrays.toArrayString(';', ints);

    assertEquals("{12367890;987664198;-298247092}", altArrayString);

  }

  @Test
  public void testShortToBinary() throws Exception {
    final short[] shorts = new short[13];
    for (int i = 0; i < 13; ++i) {
      shorts[i] = (short) (i - 3);
    }

    final PgArray pgArray = new PgArray(null, Oid.INT4_ARRAY, shortArrays.toBinaryRepresentation(null, shorts));

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
    final short[] shorts = new short[] { 123, 34, -57 };

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

    final PgArray pgArray = new PgArray(null, Oid.FLOAT8_ARRAY, doubleArrays.toBinaryRepresentation(null, doubles));

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
    final double[] doubles = new double[] { 122353.345, 923487.235987, -23.239486 };

    final String arrayString = doubleArrays.toArrayString(',', doubles);

    assertEquals("{\"122353.345\",\"923487.235987\",\"-23.239486\"}", arrayString);

    final String altArrayString = doubleArrays.toArrayString(';', doubles);

    assertEquals("{\"122353.345\";\"923487.235987\";\"-23.239486\"}", altArrayString);

  }

  @Test
  public void testFloatBinary() throws Exception {
    final float[] floats = new float[13];
    for (int i = 0; i < 13; ++i) {
      floats[i] = (float) (i - 3.1);
    }

    final PgArray pgArray = new PgArray(null, Oid.FLOAT4_ARRAY, floatArrays.toBinaryRepresentation(null, floats));

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
    final float[] floats = new float[] { 122353.34f, 923487.25f, -23.2394f };

    final String arrayString = floatArrays.toArrayString(',', floats);

    assertEquals("{\"122353.34\",\"923487.25\",\"-23.2394\"}", arrayString);

    final String altArrayString = floatArrays.toArrayString(';', floats);

    assertEquals("{\"122353.34\";\"923487.25\";\"-23.2394\"}", altArrayString);

  }

  @Test
  public void testBooleanBinary() throws Exception {
    final boolean[] bools = new boolean[] { true, true, false };

    final PgArray pgArray = new PgArray(null, Oid.BIT, booleanArrays.toBinaryRepresentation(null, bools));

    Object arrayObj = pgArray.getArray();

    assertThat(arrayObj, instanceOf(Boolean[].class));

    final Boolean[] actual = (Boolean[]) arrayObj;

    assertEquals(bools.length, actual.length);

    for (int i = 0; i < bools.length; ++i) {
      assertEquals(Boolean.valueOf(bools[i]), actual[i]);
    }
  }

  @Test
  public void testBooleanToString() throws Exception {
    final boolean[] bools = new boolean[] { true, true, false };

    final String arrayString = booleanArrays.toArrayString(',', bools);

    assertEquals("{1,1,0}", arrayString);

    final String altArrayString = booleanArrays.toArrayString(';', bools);

    assertEquals("{1;1;0}", altArrayString);
  }

  @Test
  public void testStringNotSupportBinary() {
    PrimitiveArraySupport<String[]> stringArrays = PrimitiveArraySupport.getArraySupport(new String[] {});
    assertNotNull(stringArrays);
    assertFalse(stringArrays.supportBinaryRepresentation());
    try {
      stringArrays.toBinaryRepresentation(null, new String[] { "1.2" });
      fail("no sql exception thrown");
    } catch (SQLFeatureNotSupportedException e) {

    }
  }
}
