package org.postgresql.jdbc;

public class BooleanArraysTest extends AbstractArraysTest<boolean[]> {
  private static final boolean[][][] booleans = new boolean[][][] {
      { { true, false, false, true }, { false, false, true, true }, { true, true, false, false } },
      { { false, true, true, false }, { true, false, true, false }, { false, true, false, true } } };

  public BooleanArraysTest() {
    super(booleans, true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getExpectedString(boolean[] expected, char delim) {
    final StringBuilder sb = new StringBuilder();
    sb.append('{');
    for (int i = 0; i < expected.length; ++i) {
      if (i != 0) {
        sb.append(delim);
      }
      sb.append(expected[i] ? '1' : '0');
    }
    sb.append('}');
    return sb.toString();
  }
}
