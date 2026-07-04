/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

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
 * The Jazzer-side value generator: draws a value of a coercion scalar's natural class from a
 * {@link FuzzedDataProvider}. This is the Jazzer counterpart of pgjdbc-jqf-test's {@code ValueGenerators}
 * (which draws from jetCheck). Both feed the shared oracle a value of the descriptor's natural class, so
 * the two fuzzers differ only in the front-end that produces the value, not in the oracle that checks it.
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
   * @param naturalClass the coercion scalar's natural class (one of the ten coercion types)
   * @return a value of that class
   * @throws IllegalArgumentException if the class is not a coercion scalar's natural class
   */
  static Object draw(FuzzedDataProvider data, Class<?> naturalClass) {
    if (naturalClass == Integer.class) {
      return data.consumeInt();
    }
    if (naturalClass == Long.class) {
      return data.consumeLong();
    }
    if (naturalClass == BigDecimal.class) {
      return BigDecimal.valueOf(data.consumeLong(), data.consumeInt(0, 12));
    }
    if (naturalClass == Boolean.class) {
      return data.consumeBoolean();
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
}
