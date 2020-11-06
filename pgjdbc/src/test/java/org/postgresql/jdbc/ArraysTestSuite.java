/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
    BigDecimalObjectArraysTest.class,
    BooleanArraysTest.class,
    BooleanObjectArraysTest.class,
    ByteaArraysTest.class,
    DoubleArraysTest.class,
    DoubleObjectArraysTest.class,
    FloatArraysTest.class,
    FloatObjectArraysTest.class,
    IntArraysTest.class,
    IntegerObjectArraysTest.class,
    LongArraysTest.class,
    LongObjectArraysTest.class,
    ShortArraysTest.class,
    ShortObjectArraysTest.class,
    StringArraysTest.class
})
public class ArraysTestSuite {
}
