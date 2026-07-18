/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link Float8Text} must render a {@code double} exactly as PostgreSQL's {@code float8out} does, so a
 * binary geometric value's {@code getString} agrees with its text transfer. The expected strings below
 * are the live server's own {@code float8out} (PostgreSQL 16, {@code extra_float_digits = 1}, the
 * default since PostgreSQL 12); the cross-format geometric checks in {@code GeometricTest} keep them
 * pinned to the server.
 *
 * <p>The cases concentrate on the fixed-versus-scientific switch, which PostgreSQL makes on the
 * decimal exponent of the leading digit: fixed notation for an exponent in {@code [-4, 14]},
 * scientific outside it. The values here have a single unambiguous shortest decimal, so the expected
 * strings hold on every JVM regardless of {@link Double#toString} quirks; the awkward-value coverage
 * (where a JVM's shortest digits could differ) is the round-trip property test and the live
 * differential in {@code GeometricTest}.</p>
 */
class Float8TextTest {
  private static String render(double value) {
    return Float8Text.append(new StringBuilder(), value).toString();
  }

  private static void assertRender(String expected, double value) {
    assertEquals(expected, render(value), () -> "float8out of " + value);
  }

  @Test
  void positiveFixedToScientificBoundary() {
    // The switch is on the leading digit's exponent: 1e14 is the last fixed value, 1e15 the first
    // scientific one. float8out: 1e14 -> 100000000000000, 1e15 -> 1e+15.
    assertRender("10000000000000", 1e13);
    assertRender("100000000000000", 1e14);
    assertRender("1e+15", 1e15);
    assertRender("1e+16", 1e16);
    // A non-power-of-ten straddling the boundary keeps its significant digits on each side.
    assertRender("999000000000000", 9.99e14);
    assertRender("1.5e+15", 1.5e15);
    assertRender("1.234567890123456e+15", 1234567890123456d);
  }

  @Test
  void mantissaFromScientificDoubleStringRendersFixed() {
    // Double.toString switches to scientific notation at 1e7 ("1.0E7"), where float8out is still fixed;
    // the layout must follow the exponent, not the source string's form.
    assertRender("10000000", 1e7);
    assertRender("100000000", 1e8);
    assertRender("12345.6789", 12345.6789d);
    assertRender("123.456", 123.456d);
  }

  @Test
  void negativeFixedToScientificBoundary() {
    // 1e-4 is the smallest fixed magnitude; 1e-5 is the first scientific one, its exponent padded to
    // two digits: 1e-05, not 1e-5.
    assertRender("0.001", 1e-3);
    assertRender("0.0001", 1e-4);
    assertRender("1e-05", 1e-5);
    assertRender("1.5e-05", 1.5e-5);
    assertRender("0.5", 0.5);
    assertRender("1.5", 1.5);
  }

  @Test
  void exponentPadding() {
    // Two-digit minimum, no over-padding for a wide exponent, and the sign is always present.
    assertRender("1e+100", 1e100);
    assertRender("1e-100", 1e-100);
    assertRender("1e-05", 1e-5);
    assertRender("1e+15", 1e15);
  }

  @Test
  void wholeNumbersDropTheFractionalZero() {
    // 1, not Java's 1.0; 100, not 100.0.
    assertRender("1", 1.0);
    assertRender("100", 100.0);
    assertRender("0", 0.0);
  }

  @Test
  void signsAndZeros() {
    assertRender("-1.5", -1.5);
    assertRender("-1e+15", -1e15);
    assertRender("-0.0001", -1e-4);
    assertRender("-100", -100.0);
    // float8out keeps the sign of a negative zero: float8 '-0' renders as "-0".
    assertRender("-0", -0.0);
    assertRender("0", 0.0);
  }

  @Test
  void nonFinite() {
    assertRender("NaN", Double.NaN);
    assertRender("Infinity", Double.POSITIVE_INFINITY);
    assertRender("-Infinity", Double.NEGATIVE_INFINITY);
  }

  /**
   * Whatever the layout, the rendering must round-trip: {@code Double.parseDouble(render(x)) == x} for
   * every finite {@code x}. This holds on every JVM (a non-shortest {@link Double#toString} still
   * round-trips, and {@link Float8Text} only re-lays-out its digits), so it covers the awkward values
   * the exact-string pins above deliberately avoid.
   */
  @Test
  void renderingRoundTrips() {
    double[] values = {
        0.0, -0.0, 1.0, -1.0, 0.1, -0.1, Math.PI, Math.E,
        1e-4, 1e-5, 1e14, 1e15, 1e16, 9.99e14, 1.5e15, 1234567890123456d,
        2e23, 1.9999999999999998e23, 1e100, 1e-100, 1e300, 1e-300,
        Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_NORMAL,
        123456.789, -987654.321, 0.000123456789, 6.022e23, 1.602e-19,
    };
    for (double value : values) {
      String rendered = render(value);
      assertEquals(value, Double.parseDouble(rendered),
          () -> "round-trip of " + value + " rendered as " + rendered);
    }
  }
}
