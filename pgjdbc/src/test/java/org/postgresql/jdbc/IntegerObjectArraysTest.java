package org.postgresql.jdbc;

public class IntegerObjectArraysTest extends AbstractArraysTest<Integer[]> {

  private static final Integer[][][] ints = new Integer[][][] {
      { { 1, 2, 3, 4 }, { 5, null, 7, 8 }, { 9, 10, 11, 12 } },
      { { 13, 14, 15, 16 }, { 17, 18, 19, 20 }, { 21, 22, 23, 24 } } };

  public IntegerObjectArraysTest() {
    super(ints, true);
  }
}
