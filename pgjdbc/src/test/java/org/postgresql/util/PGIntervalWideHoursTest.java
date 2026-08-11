/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Unit tests for PGInterval hours wider than 2^31 (GitHub issue #4301).
 * These do not require a live PostgreSQL server.
 */
class PGIntervalWideHoursTest {

  /** PostgreSQL's documented maximum interval time component (hours:mins:secs.micros). */
  private static final long PG_MAX_INTERVAL_HOURS = 2562047788L;

  /**
   * Serialized by postgresql-42.7.7 for {@code new PGInterval(1, 2, 3, 4, 5, 6.5)}.
   * Used to prove we still read the historical stream shape.
   */
  private static final String HISTORICAL_SERIALIZED_1_2_3_4_5_6_5 =
      "rO0ABXNyAB5vcmcucG9zdGdyZXNxbC51dGlsLlBHSW50ZXJ2YWyIK+QOLL1ZuwIACEkABGRheXNJ"
          + "AAVob3Vyc1oABmlzTnVsbEkADG1pY3JvU2Vjb25kc0kAB21pbnV0ZXNJAAZtb250aHNJAAx3aG9s"
          + "ZVNlY29uZHNJAAV5ZWFyc3hyABxvcmcucG9zdGdyZXNxbC51dGlsLlBHb2JqZWN0S/iVyqxvZEQC"
          + "AAJMAAR0eXBldAASTGphdmEvbGFuZy9TdHJpbmc7TAAFdmFsdWVxAH4AAnhwdAAIaW50ZXJ2YWxw"
          + "AAAAAwAAAAQAAAehIAAAAAUAAAACAAAABgAAAAE=";

  @Test
  void holdsHoursBeyondIntegerMax() throws SQLException {
    PGInterval interval = new PGInterval(0, 0, 0, Integer.MAX_VALUE + 1L, 0, 0);
    assertEquals(Integer.MAX_VALUE + 1L, interval.getHoursLong());
    assertThrows(ArithmeticException.class, interval::getHours);
    assertEquals((Integer.MAX_VALUE + 1L) + " hours", interval.getValue());
  }

  @Test
  void parsesPostgresMaxIntervalTime() throws SQLException {
    // Same literal as in issue #4301 reproduce steps
    PGInterval interval = new PGInterval("2562047788:00:54.775807");
    assertEquals(PG_MAX_INTERVAL_HOURS, interval.getHoursLong());
    assertThrows(ArithmeticException.class, interval::getHours);
    assertEquals(0, interval.getMinutes());
    assertEquals(54.775807, interval.getSeconds(), 0.0000001);
  }

  @Test
  void parsesVerboseHoursBeyondIntegerMax() throws SQLException {
    PGInterval interval = new PGInterval("2562047788 hours");
    assertEquals(PG_MAX_INTERVAL_HOURS, interval.getHoursLong());
    assertEquals(PG_MAX_INTERVAL_HOURS + " hours", interval.getValue());
  }

  @Test
  void parsesIntegerMinHoursOnTextPath() throws SQLException {
    // Previously failed on text path: sign stripped then magnitude 2147483648 overflowed int
    PGInterval interval = new PGInterval("-2147483648:00:00");
    assertEquals(Integer.MIN_VALUE, interval.getHours());
    assertEquals(Integer.MIN_VALUE, interval.getHoursLong());
    assertEquals(0, interval.getMinutes());
    assertEquals(0.0, interval.getSeconds(), 0.0);
  }

  @Test
  void parsesNegativeHoursBeyondIntegerMinMagnitude() throws SQLException {
    PGInterval interval = new PGInterval("-2147483649:00:00");
    assertEquals(-2147483649L, interval.getHoursLong());
    assertThrows(ArithmeticException.class, interval::getHours);
  }

  @Test
  void roundTripsWideHoursViaGetValue() throws SQLException {
    long[] samples = {
        Integer.MAX_VALUE + 1L,
        Integer.MIN_VALUE - 1L,
        PG_MAX_INTERVAL_HOURS,
        -PG_MAX_INTERVAL_HOURS,
        2147483648L,
        -2147483648L,
    };
    for (long hours : samples) {
      PGInterval original = new PGInterval(0, 0, 0, hours, 0, 0);
      PGInterval copy = new PGInterval(original.getValue());
      assertEquals(original, copy, () -> "hours=" + hours);
      assertEquals(hours, copy.getHoursLong());
    }
  }

  @ParameterizedTest
  @CsvSource({
      "2147483647:00:00, 2147483647",
      "2147483648:00:00, 2147483648",
      "-2147483647:00:00, -2147483647",
      "-2147483648:00:00, -2147483648",
      "2562047788:00:00, 2562047788",
      "-2562047788:00:00, -2562047788",
  })
  void parsesColonHoursLiterals(String literal, long expectedHours) throws SQLException {
    PGInterval interval = new PGInterval(literal);
    assertEquals(expectedHours, interval.getHoursLong());
    if (expectedHours >= Integer.MIN_VALUE && expectedHours <= Integer.MAX_VALUE) {
      assertEquals((int) expectedHours, interval.getHours());
    } else {
      assertThrows(ArithmeticException.class, interval::getHours);
    }
  }

  @Test
  void parsesIso8601TimeHoursBeyondIntegerMax() throws SQLException {
    PGInterval interval = new PGInterval("PT2562047788H");
    assertEquals(PG_MAX_INTERVAL_HOURS, interval.getHoursLong());
  }

  @Test
  void setHoursAcceptsLongAndIntOverloads() {
    PGInterval interval = new PGInterval();
    interval.setHours(PG_MAX_INTERVAL_HOURS);
    assertEquals(PG_MAX_INTERVAL_HOURS, interval.getHoursLong());
    interval.setHours(-PG_MAX_INTERVAL_HOURS);
    assertEquals(-PG_MAX_INTERVAL_HOURS, interval.getHoursLong());
    interval.setHours(42);
    assertEquals(42, interval.getHours());
    assertEquals(42L, interval.getHoursLong());
  }

  @Test
  void equalsAndHashCodeUseLongHours() throws SQLException {
    PGInterval a = new PGInterval(0, 0, 0, PG_MAX_INTERVAL_HOURS, 0, 0);
    PGInterval b = new PGInterval(PG_MAX_INTERVAL_HOURS + " hours");
    PGInterval c = new PGInterval(0, 0, 0, PG_MAX_INTERVAL_HOURS - 1, 0, 0);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, c);
  }

  @Test
  void inRangeHoursPreserveHistoricalHashCode() {
    // Pinned against postgresql-42.7.7 (int hours storage)
    PGInterval sample = new PGInterval(1, 2, 3, 4, 5, 6.5);
    assertEquals(-1144933301, sample.hashCode());

    PGInterval negativeHours = new PGInterval(0, 0, 0, -7, 0, 0);
    assertEquals(-1581198463, negativeHours.hashCode());

    PGInterval zero = new PGInterval();
    zero.setValue(0, 0, 0, 0, 0, 0);
    assertEquals(-1574733816, zero.hashCode());
  }

  @Test
  void addCombinesWideHours() {
    PGInterval base = new PGInterval();
    base.setHours(Integer.MAX_VALUE);
    PGInterval delta = new PGInterval();
    delta.setHours(10);
    delta.add(base);
    assertEquals(Integer.MAX_VALUE + 10L, base.getHoursLong());
    assertThrows(ArithmeticException.class, base::getHours);
  }

  @Test
  void scaleWideHoursRequiresLong() {
    // 2_000_000_000 * 2 = 4_000_000_000, which does not fit in int
    PGInterval interval = new PGInterval();
    interval.setHours(2_000_000_000L);
    interval.scale(2);
    assertEquals(4_000_000_000L, interval.getHoursLong());
    assertThrows(ArithmeticException.class, interval::getHours);
  }

  @Test
  void addCalendarWithInRangeHours() {
    Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
    cal.clear();
    cal.set(2000, Calendar.JANUARY, 1, 0, 0, 0);
    cal.set(Calendar.MILLISECOND, 0);

    PGInterval interval = new PGInterval();
    interval.setHours(5);
    interval.add(cal);

    assertEquals(5, cal.get(Calendar.HOUR_OF_DAY));
  }

  @Test
  void addCalendarWithWideHoursThrowsRatherThanHang() {
    Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
    cal.clear();
    cal.set(2000, Calendar.JANUARY, 1, 0, 0, 0);

    PGInterval interval = new PGInterval();
    interval.setHours(Integer.MAX_VALUE + 1L);
    assertThrows(ArithmeticException.class, () -> interval.add(cal));

    interval.setHours(Long.MAX_VALUE);
    assertThrows(ArithmeticException.class, () -> interval.add(cal));
  }

  @Test
  void hibernateStyleBinaryCompatibleDescriptorsStillResolve() throws Exception {
    // Hibernate 6/7 look up the int-hours constructor and int getHours reflectively
    Constructor<PGInterval> ctor = PGInterval.class.getConstructor(
        int.class, int.class, int.class, int.class, int.class, double.class);
    PGInterval interval = ctor.newInstance(0, 0, 1, 5, 30, 0.0);

    Method getHours = PGInterval.class.getMethod("getHours");
    assertEquals(int.class, getHours.getReturnType());
    assertEquals(5, getHours.invoke(interval));

    Method setHoursInt = PGInterval.class.getMethod("setHours", int.class);
    setHoursInt.invoke(interval, 7);
    assertEquals(7, getHours.invoke(interval));

    // New long overloads exist alongside the legacy descriptors
    assertEquals(long.class, PGInterval.class.getMethod("getHoursLong").getReturnType());
    PGInterval.class.getConstructor(
        int.class, int.class, int.class, long.class, int.class, double.class);
    PGInterval.class.getMethod("setHours", long.class);
    PGInterval.class.getMethod("setValue",
        int.class, int.class, int.class, int.class, int.class, double.class);
    PGInterval.class.getMethod("setValue",
        int.class, int.class, int.class, long.class, int.class, double.class);
  }

  @Test
  void readsHistoricalSerializedForm() throws Exception {
    byte[] bytes = Base64.getDecoder().decode(HISTORICAL_SERIALIZED_1_2_3_4_5_6_5);
    try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
      PGInterval interval = (PGInterval) ois.readObject();
      assertEquals(1, interval.getYears());
      assertEquals(2, interval.getMonths());
      assertEquals(3, interval.getDays());
      assertEquals(4, interval.getHours());
      assertEquals(4L, interval.getHoursLong());
      assertEquals(5, interval.getMinutes());
      assertEquals(6.5, interval.getSeconds(), 0.0);
    }
  }

  @Test
  void serializesInRangeHoursRoundTrip() throws Exception {
    PGInterval original = new PGInterval(1, 2, 3, 4, 5, 6.5);
    PGInterval copy = roundTrip(original);
    assertEquals(original, copy);
    assertEquals(4, copy.getHours());
    assertEquals(original.hashCode(), copy.hashCode());
  }

  @Test
  void serializesWideHoursRoundTrip() throws Exception {
    PGInterval original = new PGInterval(0, 0, 0, PG_MAX_INTERVAL_HOURS, 0, 0);
    PGInterval copy = roundTrip(original);
    assertEquals(original, copy);
    assertEquals(PG_MAX_INTERVAL_HOURS, copy.getHoursLong());
  }

  @Test
  void intConstructorAndSetValueStillWork() {
    PGInterval a = new PGInterval(1, 2, 3, 4, 5, 6.0);
    assertEquals(4, a.getHours());
    a.setValue(0, 0, 0, 9, 0, 0.0);
    assertEquals(9, a.getHours());
  }

  private static PGInterval roundTrip(PGInterval original) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
      oos.writeObject(original);
    }
    try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
      return (PGInterval) ois.readObject();
    }
  }
}
