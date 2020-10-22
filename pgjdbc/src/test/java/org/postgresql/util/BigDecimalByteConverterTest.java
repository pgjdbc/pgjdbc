/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;

/**
 *
 * @author Brett Okken
 */
@RunWith(Parameterized.class)
public class BigDecimalByteConverterTest {

  @Parameter
  public BigDecimal number;

  @Parameterized.Parameters(name = "number = {0,number,#,###.##################################################}")
  public static Iterable<Object[]> data() {
    final Collection<Object[]> numbers = new ArrayList<Object[]>();
    numbers.add(new Object[] {new BigDecimal("1.0")});
    numbers.add(new Object[] {new BigDecimal("0.000000000000000000000000000000000000000000000000000")});
    numbers.add(new Object[] {new BigDecimal("0.100000000000000000000000000000000000000000000009900")});
    numbers.add(new Object[] {new BigDecimal("-1.0")});
    numbers.add(new Object[] {new BigDecimal("-1")});
    numbers.add(new Object[] {new BigDecimal("1.2")});
    numbers.add(new Object[] {new BigDecimal("-2.05")});
    numbers.add(new Object[] {new BigDecimal("0.000000000000000000000000000990")});
    numbers.add(new Object[] {new BigDecimal("-0.000000000000000000000000000990")});
    numbers.add(new Object[] {new BigDecimal("10.0000000000099")});
    numbers.add(new Object[] {new BigDecimal(".10000000000000")});
    numbers.add(new Object[] {new BigDecimal("1.10000000000000")});
    numbers.add(new Object[] {new BigDecimal("99999.2")});
    numbers.add(new Object[] {new BigDecimal("99999")});
    numbers.add(new Object[] {new BigDecimal("-99999.2")});
    numbers.add(new Object[] {new BigDecimal("-99999")});
    numbers.add(new Object[] {new BigDecimal("2147483647")});
    numbers.add(new Object[] {new BigDecimal("-2147483648")});
    numbers.add(new Object[] {new BigDecimal("2147483648")});
    numbers.add(new Object[] {new BigDecimal("-2147483649")});
    numbers.add(new Object[] {new BigDecimal("9223372036854775807")});
    numbers.add(new Object[] {new BigDecimal("-9223372036854775808")});
    numbers.add(new Object[] {new BigDecimal("9223372036854775808")});
    numbers.add(new Object[] {new BigDecimal("-9223372036854775809")});
    numbers.add(new Object[] {new BigDecimal("10223372036850000000")});
    numbers.add(new Object[] {new BigDecimal("19223372036854775807")});
    numbers.add(new Object[] {new BigDecimal("19223372036854775807.300")});
    numbers.add(new Object[] {new BigDecimal("-19223372036854775807.300")});
    numbers.add(new Object[] {new BigDecimal(BigInteger.valueOf(1234567890987654321L), -1)});
    numbers.add(new Object[] {new BigDecimal(BigInteger.valueOf(1234567890987654321L), -5)});
    numbers.add(new Object[] {new BigDecimal(BigInteger.valueOf(-1234567890987654321L), -3)});
    numbers.add(new Object[] {new BigDecimal(BigInteger.valueOf(6), -8)});
    return numbers;
  }

  @Test
  public void testBinary() {
    final byte[] bytes = ByteConverter.numeric(number);
    final BigDecimal actual = (BigDecimal) ByteConverter.numeric(bytes);
    if (number.scale() >= 0) {
      assertEquals(number, actual);
    } else {
      assertEquals(number.toPlainString(), actual.toPlainString());
    }
  }
}
