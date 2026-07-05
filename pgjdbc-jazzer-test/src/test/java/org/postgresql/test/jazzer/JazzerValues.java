/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGcircle;
import org.postgresql.geometric.PGline;
import org.postgresql.geometric.PGlseg;
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.util.PGInterval;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

/**
 * The Jazzer-side value generator: draws a value of a read-populated scalar's natural class from a
 * {@link FuzzedDataProvider}. This is the Jazzer counterpart of pgjdbc-jqf-test's {@code ValueGenerators}
 * (which draws from jetCheck). Both feed the shared oracle a value of the descriptor's natural class, so
 * the two fuzzers differ only in the front-end that produces the value, not in the oracle that checks it.
 *
 * <p>It covers every natural class the reader axis draws: the ten coercion scalars plus the read-only
 * scalars ({@code int2} as {@code Short}, {@code float4} as {@code Float}, {@code float8} as
 * {@code Double}, {@code bytea} as {@code byte[]}, {@code oid} as {@code Long}, and the text types
 * {@code varchar}/{@code bpchar}/{@code name} as {@code String}).
 *
 * <p>The ranges match the jetCheck generators so every drawn value encodes on the canonical wire: the
 * coercion oracle exercises the read side, so the value has to be encodable for the read to be reached.
 */
final class JazzerValues {

  private static final int MIN_DAY = (int) LocalDate.of(1, 1, 1).toEpochDay();
  private static final int MAX_DAY = (int) LocalDate.of(9999, 12, 31).toEpochDay();

  private JazzerValues() {
  }

  /**
   * Draws a value of {@code naturalClass} from the fuzzer input.
   *
   * @param data the fuzzer input
   * @param naturalClass the scalar's natural class (a coercion scalar or a read-only scalar)
   * @return a value of that class
   * @throws IllegalArgumentException if the class is not a read-populated scalar's natural class
   */
  static Object draw(FuzzedDataProvider data, Class<?> naturalClass) {
    if (naturalClass == Integer.class) {
      return data.consumeInt();
    }
    if (naturalClass == Long.class) {
      return data.consumeLong();
    }
    if (naturalClass == Short.class) {
      return data.consumeShort();
    }
    if (naturalClass == Float.class) {
      return data.consumeFloat();
    }
    if (naturalClass == Double.class) {
      return data.consumeDouble();
    }
    if (naturalClass == BigDecimal.class) {
      return BigDecimal.valueOf(data.consumeLong(), data.consumeInt(0, 12));
    }
    if (naturalClass == Boolean.class) {
      return data.consumeBoolean();
    }
    if (naturalClass == byte[].class) {
      // A bounded byte[] for bytea; every byte sequence encodes, so no filtering is needed.
      return data.consumeBytes(16);
    }
    if (naturalClass == String.class) {
      return printableAscii(data);
    }
    if (naturalClass == Date.class) {
      return Date.valueOf(LocalDate.ofEpochDay(data.consumeInt(MIN_DAY, MAX_DAY)));
    }
    if (naturalClass == Time.class) {
      return Time.valueOf(LocalTime.ofSecondOfDay(data.consumeInt(0, 86_399)));
    }
    if (naturalClass == Timestamp.class) {
      return Timestamp.valueOf(localDateTime(data));
    }
    if (naturalClass == OffsetTime.class) {
      return OffsetTime.of(localTimeMicros(data), offset(data));
    }
    if (naturalClass == OffsetDateTime.class) {
      return OffsetDateTime.of(localDateTime(data), offset(data));
    }
    throw new IllegalArgumentException("No JazzerValues generator for " + naturalClass.getName());
  }

  private static LocalDateTime localDateTime(FuzzedDataProvider data) {
    return LocalDateTime.of(LocalDate.ofEpochDay(data.consumeInt(MIN_DAY, MAX_DAY)),
        localTimeMicros(data));
  }

  private static LocalTime localTimeMicros(FuzzedDataProvider data) {
    // Microsecond resolution: PostgreSQL temporal types carry microseconds, so nanoseconds would
    // truncate on the wire and break the round-trip the oracle relies on.
    return LocalTime.ofSecondOfDay(data.consumeInt(0, 86_399))
        .withNano(data.consumeInt(0, 999_999) * 1000);
  }

  private static ZoneOffset offset(FuzzedDataProvider data) {
    return ZoneOffset.ofTotalSeconds(data.consumeInt(-1080, 1080) * 60);
  }

  private static String printableAscii(FuzzedDataProvider data) {
    // The jetCheck generator draws printable ASCII; strip control characters (including NUL, which
    // PostgreSQL text cannot carry) so the value always encodes.
    return data.consumeAsciiString(16).replaceAll("[\\x00-\\x1F\\x7F]", "");
  }

  // --- PGobject / PGInterval scalars (U7a) ---------------------------------------------------

  private static final String[] JSON_KEYWORDS = {"null", "true", "false"};

  /**
   * Draws a valid, non-empty JSON literal (Jazzer counterpart of the jetCheck {@code JSON_LITERAL}
   * generator). The json / jsonb codecs pass the value through verbatim, so any non-empty literal
   * round-trips; the shape is one of the keywords, a number, a quoted string, a small array, or a
   * small object, so the literal carries JSON structure without ever being empty.
   *
   * @param data the fuzzer input
   * @return a non-empty JSON literal
   */
  static String jsonLiteral(FuzzedDataProvider data) {
    switch (data.consumeInt(0, 4)) {
      case 0:
        return JSON_KEYWORDS[data.consumeInt(0, JSON_KEYWORDS.length - 1)];
      case 1:
        return String.valueOf(data.consumeInt());
      case 2:
        return jsonString(data);
      case 3:
        return jsonArray(data);
      default:
        return jsonObject(data);
    }
  }

  private static String jsonString(FuzzedDataProvider data) {
    // Only ASCII letters and digits, so the quoted string never needs JSON escaping.
    return '"' + data.consumeAsciiString(12).replaceAll("[^A-Za-z0-9]", "") + '"';
  }

  private static String jsonArray(FuzzedDataProvider data) {
    int n = data.consumeInt(0, 4);
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < n; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(data.consumeInt());
    }
    return sb.append(']').toString();
  }

  private static String jsonObject(FuzzedDataProvider data) {
    int n = data.consumeInt(0, 4);
    StringBuilder sb = new StringBuilder("{");
    for (int i = 0; i < n; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(jsonString(data)).append(':').append(data.consumeInt());
    }
    return sb.append('}').toString();
  }

  /**
   * Draws a non-empty bit string (a run of {@code '0'}/{@code '1'}) for {@code bit} / {@code varbit}.
   * The length is at least one so it decodes to a value rather than SQL NULL, and it round-trips
   * exactly through both the text and the packed binary form.
   *
   * @param data the fuzzer input
   * @return a bit string of length one or more
   */
  static String bitString(FuzzedDataProvider data) {
    int length = data.consumeInt(1, 32);
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(data.consumeBoolean() ? '1' : '0');
    }
    return sb.toString();
  }

  /**
   * Draws a {@link PGInterval} whose components are a fixed point of the codec's binary re-split, so it
   * round-trips by {@code equals} in both formats (Jazzer counterpart of the jetCheck {@code INTERVAL}
   * generator). The year/month pair comes from splitting one total month count the way the decoder does
   * ({@code years = total/12}, {@code months = total%12}), so both share a sign; the time components
   * stay non-negative with minutes in {@code [0,59]} and seconds in {@code [0,60)}. The bounds keep the
   * encoder's {@code hours*3600e6} and {@code years*12} clear of overflow.
   *
   * @param data the fuzzer input
   * @return a normalised interval
   */
  static PGInterval interval(FuzzedDataProvider data) {
    int totalMonths = data.consumeInt(-1_200_000, 1_200_000);
    int years = totalMonths / 12;
    int months = totalMonths % 12;
    int days = data.consumeInt(-1_000_000, 1_000_000);
    // Run well past 596_523 hours: IntervalCodec.decodeBinary's re-split (hours * 3600) once overflowed
    // int past that and corrupted the minutes; the decoder now splits in long arithmetic, so the wider
    // range exercises that fix rather than dodging it.
    int hours = data.consumeInt(0, 2_000_000);
    int minutes = data.consumeInt(0, 59);
    int wholeSeconds = data.consumeInt(0, 59);
    int micros = data.consumeInt(0, 999_999);
    return new PGInterval(years, months, days, hours, minutes, wholeSeconds + micros / 1_000_000.0);
  }

  // --- Geometric types (U7b) -----------------------------------------------------------------

  /**
   * Draws a finite double coordinate the way the jetCheck {@code FINITE_DOUBLE} generator does --
   * {@code unscaled * 10^-scale} with {@code scale} in {@code [0,6]}. The value is always finite, so it
   * never trips the {@code x == x} equality the geometric classes use, and {@code Double.toString}
   * recovers it exactly through the text codec.
   *
   * @param data the fuzzer input
   * @return a finite double
   */
  private static double finiteDouble(FuzzedDataProvider data) {
    int unscaled = data.consumeInt();
    int scale = data.consumeInt(0, 6);
    return unscaled / Math.pow(10, scale);
  }

  private static PGpoint point(FuzzedDataProvider data) {
    return new PGpoint(finiteDouble(data), finiteDouble(data));
  }

  /**
   * Draws one to eight {@link PGpoint}s for the {@code path} and {@code polygon} generators. The list is
   * never empty: an empty {@code path} decodes to SQL NULL and an empty {@code polygon} re-parses to a
   * one-point polygon, so neither round-trips.
   */
  private static PGpoint[] pointList(FuzzedDataProvider data) {
    int n = data.consumeInt(1, 8);
    PGpoint[] points = new PGpoint[n];
    for (int i = 0; i < n; i++) {
      points[i] = point(data);
    }
    return points;
  }

  /**
   * Draws a {@link PGpoint} (Jazzer counterpart of the jetCheck {@code POINT} generator).
   *
   * @param data the fuzzer input
   * @return a point with finite coordinates
   */
  static PGpoint pointValue(FuzzedDataProvider data) {
    return point(data);
  }

  /**
   * Draws a {@link PGline} from three finite coefficients {@code a}, {@code b}, {@code c} (Jazzer
   * counterpart of the jetCheck {@code LINE} generator).
   *
   * @param data the fuzzer input
   * @return a line {@code {a,b,c}}
   */
  static PGline lineValue(FuzzedDataProvider data) {
    return new PGline(finiteDouble(data), finiteDouble(data), finiteDouble(data));
  }

  /**
   * Draws a {@link PGlseg} from two points (Jazzer counterpart of the jetCheck {@code LSEG} generator).
   *
   * @param data the fuzzer input
   * @return a line segment
   */
  static PGlseg lsegValue(FuzzedDataProvider data) {
    return new PGlseg(point(data), point(data));
  }

  /**
   * Draws a {@link PGbox} from two points (Jazzer counterpart of the jetCheck {@code BOX} generator).
   *
   * @param data the fuzzer input
   * @return a box
   */
  static PGbox boxValue(FuzzedDataProvider data) {
    return new PGbox(point(data), point(data));
  }

  /**
   * Draws a {@link PGpath} from one to eight points and an open/closed flag (Jazzer counterpart of the
   * jetCheck {@code PATH} generator).
   *
   * @param data the fuzzer input
   * @return an open or closed path
   */
  static PGpath pathValue(FuzzedDataProvider data) {
    return new PGpath(pointList(data), data.consumeBoolean());
  }

  /**
   * Draws a {@link PGpolygon} from one to eight points (Jazzer counterpart of the jetCheck
   * {@code POLYGON} generator).
   *
   * @param data the fuzzer input
   * @return a polygon
   */
  static PGpolygon polygonValue(FuzzedDataProvider data) {
    return new PGpolygon(pointList(data));
  }

  /**
   * Draws a {@link PGcircle} from a centre point and a non-negative radius (Jazzer counterpart of the
   * jetCheck {@code CIRCLE} generator).
   *
   * @param data the fuzzer input
   * @return a circle
   */
  static PGcircle circleValue(FuzzedDataProvider data) {
    return new PGcircle(point(data), Math.abs(finiteDouble(data)));
  }
}
