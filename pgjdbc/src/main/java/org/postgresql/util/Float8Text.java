/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

/**
 * Renders a {@code double} the way PostgreSQL's {@code float8out} does: the shortest decimal string
 * that round-trips to the same value.
 *
 * <p>This differs from {@link Double#toString(double)} only in layout — a whole number renders as
 * {@code 1} rather than {@code 1.0}, and a large magnitude as {@code 1e+100} rather than {@code
 * 1.0E100} — so a value decoded from the binary wire reads back the same as the server's own text
 * form. The shortest significant digits come from {@link Double#toString(double)}; this class only
 * re-lays them out with PostgreSQL's rule: scientific notation when the decimal exponent is below
 * {@code -4} or above {@code 14}, fixed notation otherwise.</p>
 *
 * <p>It is deliberately not wired into the {@code float8}/{@code float4} codecs: their {@code
 * getString} keeps the historical {@link Double#toString(double)} rendering for backward
 * compatibility. Today the geometric codecs are the only consumers, rendering each coordinate so a
 * binary geometric value's {@code getString} agrees with its text transfer.</p>
 */
public final class Float8Text {
  private Float8Text() {
  }

  /**
   * Appends {@code value} in PostgreSQL's {@code float8out} text form.
   *
   * @param sb the buffer to append to
   * @param value the value to append
   * @return {@code sb}, for chaining
   */
  public static StringBuilder append(StringBuilder sb, double value) {
    if (Double.isNaN(value)) {
      return sb.append("NaN");
    }
    if (Double.isInfinite(value)) {
      return sb.append(value > 0 ? "Infinity" : "-Infinity");
    }
    if (value == 0.0) {
      // float8out keeps the sign of a negative zero.
      return sb.append(1.0 / value < 0 ? "-0" : "0");
    }
    if (value < 0) {
      sb.append('-');
      value = -value;
    }

    // Double.toString gives the shortest round-tripping digits; extract them and the decimal exponent
    // E, where value = d.ddd... x 10^E with d the first significant digit.
    String s = Double.toString(value);
    String digits;
    int exp;
    int eIndex = s.indexOf('E');
    if (eIndex >= 0) {
      // "d.ddddE±xx": exactly one digit before the dot, so the exponent is already relative to it.
      String mantissa = s.substring(0, eIndex);
      exp = Integer.parseInt(s.substring(eIndex + 1));
      int dot = mantissa.indexOf('.');
      digits = mantissa.substring(0, dot) + mantissa.substring(dot + 1);
    } else {
      int dot = s.indexOf('.');
      String all = s.substring(0, dot) + s.substring(dot + 1);
      int first = 0;
      while (all.charAt(first) == '0') {
        first++;
      }
      exp = dot - 1 - first;
      digits = all.substring(first);
    }
    // Drop trailing zeros, keeping at least one significant digit.
    int last = digits.length();
    while (last > 1 && digits.charAt(last - 1) == '0') {
      last--;
    }
    return appendDigits(sb, digits, last, exp);
  }

  /**
   * Lays {@code digits[0, count)} out around a decimal point at exponent {@code exp}, choosing the same
   * fixed-versus-scientific form as PostgreSQL's {@code float8out}.
   */
  private static StringBuilder appendDigits(StringBuilder sb, String digits, int count, int exp) {
    if (exp < -4 || exp > 14) {
      sb.append(digits.charAt(0));
      if (count > 1) {
        sb.append('.').append(digits, 1, count);
      }
      sb.append('e');
      if (exp < 0) {
        sb.append('-');
        exp = -exp;
      } else {
        sb.append('+');
      }
      if (exp < 10) {
        sb.append('0');
      }
      return sb.append(exp);
    }
    if (exp >= 0) {
      int intDigits = exp + 1;
      if (count <= intDigits) {
        sb.append(digits, 0, count);
        for (int i = count; i < intDigits; i++) {
          sb.append('0');
        }
      } else {
        sb.append(digits, 0, intDigits).append('.').append(digits, intDigits, count);
      }
      return sb;
    }
    sb.append("0.");
    for (int i = 0; i < -exp - 1; i++) {
      sb.append('0');
    }
    return sb.append(digits, 0, count);
  }
}
