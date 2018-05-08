package org.postgresql.jdbc;

public class LongArraysTest extends AbstractArraysTest<long[]> {

  private static final long[][][] longs = new long[][][] { { { 1, 2, 3, 4 }, { 5, 6, 7, 8 }, { 9, 10, 11, 12 } },
      { { 13, 14, 15, 16 }, { 17, 18, 19, 20 }, { 21, 22, 23, 24 } } };

  public LongArraysTest() {
    super(longs, true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getExpectedString(long[] expected, char delim) {
    final StringBuilder sb = new StringBuilder(1024);
    sb.append('{');
    for (int i = 0; i < expected.length; ++i) {
      if (i != 0) {
        sb.append(delim);
      }
      sb.append(Long.toString(expected[i]));
    }
    sb.append('}');

    return sb.toString();
  }
}
