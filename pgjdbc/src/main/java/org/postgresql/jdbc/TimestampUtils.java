/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.jdbc;

import org.postgresql.PGStatement;
import org.postgresql.core.Oid;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.SimpleTimeZone;
import java.util.TimeZone;


/**
 * Misc utils for handling time and date values.
 */
public class TimestampUtils {
  /**
   * Number of milliseconds in one day.
   */
  private static final int ONEDAY = 24 * 3600 * 1000;
  private static final char[] ZEROS = {'0', '0', '0', '0', '0', '0', '0', '0', '0'};
  private static final char[][] NUMBERS;

  static {
    // The expected maximum value is 60 (seconds), so 64 is used "just in case"
    NUMBERS = new char[64][];
    for (int i = 0; i < NUMBERS.length; i++) {
      NUMBERS[i] = Integer.toString(i).toCharArray();
    }
  }

  private final StringBuilder sbuf = new StringBuilder();

  // This calendar is used when user provides calendar in setX(, Calendar) method.
  // It ensures calendar is Gregorian.
  private final Calendar calendarWithUserTz = new GregorianCalendar();
  private final TimeZone utcTz = TimeZone.getTimeZone("UTC");

  private Calendar calCache;
  private int calCacheZone;

  private final boolean min74;
  private final boolean min82;

  /**
   * True if the backend uses doubles for time values. False if long is used.
   */
  private final boolean usesDouble;

  TimestampUtils(boolean min74, boolean min82, boolean usesDouble) {
    this.min74 = min74;
    this.min82 = min82;
    this.usesDouble = usesDouble;
  }

  private Calendar getCalendar(int sign, int hr, int min, int sec) {
    int rawOffset = sign * (((hr * 60 + min) * 60 + sec) * 1000);
    if (calCache != null && calCacheZone == rawOffset) {
      return calCache;
    }

    StringBuilder zoneID = new StringBuilder("GMT");
    zoneID.append(sign < 0 ? '-' : '+');
    if (hr < 10) {
      zoneID.append('0');
    }
    zoneID.append(hr);
    if (min < 10) {
      zoneID.append('0');
    }
    zoneID.append(min);
    if (sec < 10) {
      zoneID.append('0');
    }
    zoneID.append(sec);

    TimeZone syntheticTZ = new SimpleTimeZone(rawOffset, zoneID.toString());
    calCache = new GregorianCalendar(syntheticTZ);
    calCacheZone = rawOffset;
    return calCache;
  }

  private static class ParsedTimestamp {
    boolean hasDate = false;
    int era = GregorianCalendar.AD;
    int year = 1970;
    int month = 1;

    boolean hasTime = false;
    int day = 1;
    int hour = 0;
    int minute = 0;
    int second = 0;
    int nanos = 0;

    Calendar tz = null;
  }

  /**
   * Load date/time information into the provided calendar returning the fractional seconds.
   */
  private ParsedTimestamp parseBackendTimestamp(String str) throws SQLException {
    char[] s = str.toCharArray();
    int slen = s.length;

    // This is pretty gross..
    ParsedTimestamp result = new ParsedTimestamp();

    // We try to parse these fields in order; all are optional
    // (but some combinations don't make sense, e.g. if you have
    //  both date and time then they must be whitespace-separated).
    // At least one of date and time must be present.

    //   leading whitespace
    //   yyyy-mm-dd
    //   whitespace
    //   hh:mm:ss
    //   whitespace
    //   timezone in one of the formats:  +hh, -hh, +hh:mm, -hh:mm
    //   whitespace
    //   if date is present, an era specifier: AD or BC
    //   trailing whitespace

    try {
      int start = skipWhitespace(s, 0);   // Skip leading whitespace
      int end = firstNonDigit(s, start);
      int num;
      char sep;

      // Possibly read date.
      if (charAt(s, end) == '-') {
        //
        // Date
        //
        result.hasDate = true;

        // year
        result.year = number(s, start, end);
        start = end + 1; // Skip '-'

        // month
        end = firstNonDigit(s, start);
        result.month = number(s, start, end);

        sep = charAt(s, end);
        if (sep != '-') {
          throw new NumberFormatException("Expected date to be dash-separated, got '" + sep + "'");
        }

        start = end + 1; // Skip '-'

        // day of month
        end = firstNonDigit(s, start);
        result.day = number(s, start, end);

        start = skipWhitespace(s, end); // Skip trailing whitespace
      }

      // Possibly read time.
      if (Character.isDigit(charAt(s, start))) {
        //
        // Time.
        //

        result.hasTime = true;

        // Hours

        end = firstNonDigit(s, start);
        result.hour = number(s, start, end);

        sep = charAt(s, end);
        if (sep != ':') {
          throw new NumberFormatException("Expected time to be colon-separated, got '" + sep + "'");
        }

        start = end + 1; // Skip ':'

        // minutes

        end = firstNonDigit(s, start);
        result.minute = number(s, start, end);

        sep = charAt(s, end);
        if (sep != ':') {
          throw new NumberFormatException("Expected time to be colon-separated, got '" + sep + "'");
        }

        start = end + 1; // Skip ':'

        // seconds

        end = firstNonDigit(s, start);
        result.second = number(s, start, end);
        start = end;

        // Fractional seconds.
        if (charAt(s, start) == '.') {
          end = firstNonDigit(s, start + 1); // Skip '.'
          num = number(s, start + 1, end);

          for (int numlength = (end - (start + 1)); numlength < 9; ++numlength) {
            num *= 10;
          }

          result.nanos = num;
          start = end;
        }

        start = skipWhitespace(s, start); // Skip trailing whitespace
      }

      // Possibly read timezone.
      sep = charAt(s, start);
      if (sep == '-' || sep == '+') {
        int tzsign = (sep == '-') ? -1 : 1;
        int tzhr;
        int tzmin;
        int tzsec;

        end = firstNonDigit(s, start + 1);    // Skip +/-
        tzhr = number(s, start + 1, end);
        start = end;

        sep = charAt(s, start);
        if (sep == ':') {
          end = firstNonDigit(s, start + 1);  // Skip ':'
          tzmin = number(s, start + 1, end);
          start = end;
        } else {
          tzmin = 0;
        }

        tzsec = 0;
        if (min82) {
          sep = charAt(s, start);
          if (sep == ':') {
            end = firstNonDigit(s, start + 1);  // Skip ':'
            tzsec = number(s, start + 1, end);
            start = end;
          }
        }

        // Setting offset does not seem to work correctly in all
        // cases.. So get a fresh calendar for a synthetic timezone
        // instead
        result.tz = getCalendar(tzsign, tzhr, tzmin, tzsec);

        start = skipWhitespace(s, start);  // Skip trailing whitespace
      }

      if (result.hasDate && start < slen) {
        String eraString = new String(s, start, slen - start);
        if (eraString.startsWith("AD")) {
          result.era = GregorianCalendar.AD;
          start += 2;
        } else if (eraString.startsWith("BC")) {
          result.era = GregorianCalendar.BC;
          start += 2;
        }
      }

      if (start < slen) {
        throw new NumberFormatException(
            "Trailing junk on timestamp: '" + new String(s, start, slen - start) + "'");
      }

      if (!result.hasTime && !result.hasDate) {
        throw new NumberFormatException("Timestamp has neither date nor time");
      }

    } catch (NumberFormatException nfe) {
      throw new PSQLException(
          GT.tr("Bad value for type timestamp/date/time: {1}", new Object[]{str}),
          PSQLState.BAD_DATETIME_FORMAT, nfe);
    }

    return result;
  }

  /**
   * Parse a string and return a timestamp representing its value.
   *
   * @param cal calendar to be used to parse the input string
   * @param s   The ISO formated date string to parse.
   * @return null if s is null or a timestamp of the parsed string s.
   * @throws SQLException if there is a problem parsing s.
   */
  public synchronized Timestamp toTimestamp(Calendar cal, String s) throws SQLException {
    if (s == null) {
      return null;
    }

    int slen = s.length();

    // convert postgres's infinity values to internal infinity magic value
    if (slen == 8 && s.equals("infinity")) {
      return new Timestamp(PGStatement.DATE_POSITIVE_INFINITY);
    }

    if (slen == 9 && s.equals("-infinity")) {
      return new Timestamp(PGStatement.DATE_NEGATIVE_INFINITY);
    }

    ParsedTimestamp ts = parseBackendTimestamp(s);
    Calendar useCal = ts.tz != null ? ts.tz : setupCalendar(cal);
    useCal.set(Calendar.ERA, ts.era);
    useCal.set(Calendar.YEAR, ts.year);
    useCal.set(Calendar.MONTH, ts.month - 1);
    useCal.set(Calendar.DAY_OF_MONTH, ts.day);
    useCal.set(Calendar.HOUR_OF_DAY, ts.hour);
    useCal.set(Calendar.MINUTE, ts.minute);
    useCal.set(Calendar.SECOND, ts.second);
    useCal.set(Calendar.MILLISECOND, 0);

    Timestamp result = new Timestamp(useCal.getTimeInMillis());
    result.setNanos(ts.nanos);
    return result;
  }

  public synchronized Time toTime(Calendar cal, String s) throws SQLException {
    // 1) Parse backend string
    Timestamp timestamp = toTimestamp(cal, s);

    if (timestamp == null) {
      return null;
    }

    long millis = timestamp.getTime();
    // infinity cannot be represented as Time
    // so there's not much we can do here.
    if (millis <= PGStatement.DATE_NEGATIVE_INFINITY
        || millis >= PGStatement.DATE_POSITIVE_INFINITY) {
      throw new PSQLException(
          GT.tr("Infinite value found for timestamp/date. This cannot be represented as time."),
          PSQLState.DATETIME_OVERFLOW);
    }


    // 2) Truncate date part so in given time zone the date would be formatted as 00:00
    return convertToTime(timestamp.getTime(), cal == null ? null : cal.getTimeZone());
  }

  public synchronized Date toDate(Calendar cal, String s) throws SQLException {
    // 1) Parse backend string
    Timestamp timestamp = toTimestamp(cal, s);

    if (timestamp == null) {
      return null;
    }

    //    Note: infinite dates are handled in convertToDate
    // 2) Truncate date part so in given time zone the date would be formatted as 00:00
    return convertToDate(timestamp.getTime(), cal == null ? null : cal.getTimeZone());
  }

  private Calendar setupCalendar(Calendar cal) {
    Calendar tmp = calendarWithUserTz;
    tmp.setTimeZone(cal == null ? getDefaultTz() : cal.getTimeZone());
    return tmp;
  }

  public synchronized String toString(Calendar cal, Timestamp x) {
    if (x.getTime() == PGStatement.DATE_POSITIVE_INFINITY) {
      return "infinity";
    } else if (x.getTime() == PGStatement.DATE_NEGATIVE_INFINITY) {
      return "-infinity";
    }

    cal = setupCalendar(cal);
    cal.setTime(x);

    sbuf.setLength(0);

    appendDate(sbuf, cal);
    sbuf.append(' ');
    appendTime(sbuf, cal, x.getNanos());
    appendTimeZone(sbuf, cal);
    appendEra(sbuf, cal);

    return sbuf.toString();
  }

  public synchronized String toString(Calendar cal, Date x) {
    if (x.getTime() == PGStatement.DATE_POSITIVE_INFINITY) {
      sbuf.append("infinity");
    } else if (x.getTime() == PGStatement.DATE_NEGATIVE_INFINITY) {
      sbuf.append("-infinity");
    }

    cal = setupCalendar(cal);
    cal.setTime(x);

    sbuf.setLength(0);

    appendDate(sbuf, cal);
    appendEra(sbuf, cal);
    appendTimeZone(sbuf, cal);

    return sbuf.toString();
  }

  public synchronized String toString(Calendar cal, Time x) {
    cal = setupCalendar(cal);
    cal.setTime(x);

    sbuf.setLength(0);

    appendTime(sbuf, cal, cal.get(Calendar.MILLISECOND) * 1000000);

    // The 'time' parser for <= 7.3 doesn't like timezones.
    if (min74) {
      appendTimeZone(sbuf, cal);
    }

    return sbuf.toString();
  }

  private static void appendDate(StringBuilder sb, Calendar cal) {
    int l_year = cal.get(Calendar.YEAR);
    // always use at least four digits for the year so very
    // early years, like 2, don't get misinterpreted
    //
    int prevLength = sb.length();
    sb.append(l_year);
    int leadingZerosForYear = 4 - (sb.length() - prevLength);
    if (leadingZerosForYear > 0) {
      sb.insert(prevLength, ZEROS, 0, leadingZerosForYear);
    }

    sb.append('-');
    int l_month = cal.get(Calendar.MONTH) + 1;
    sb.append(NUMBERS[l_month]);
    sb.append('-');
    int l_day = cal.get(Calendar.DAY_OF_MONTH);
    sb.append(NUMBERS[l_day]);
  }

  private static void appendTime(StringBuilder sb, Calendar cal, int nanos) {
    int hours = cal.get(Calendar.HOUR_OF_DAY);
    sb.append(NUMBERS[hours]);

    sb.append(':');
    int minutes = cal.get(Calendar.MINUTE);
    sb.append(NUMBERS[minutes]);

    sb.append(':');
    int seconds = cal.get(Calendar.SECOND);
    sb.append(NUMBERS[seconds]);

    // Add nanoseconds.
    // This won't work for server versions < 7.2 which only want
    // a two digit fractional second, but we don't need to support 7.1
    // anymore and getting the version number here is difficult.
    //
    sb.append('.');
    int len = sb.length();
    sb.append(nanos / 1000); // append microseconds
    int needZeros = 6 - (sb.length() - len);
    if (needZeros > 0) {
      sb.insert(len, ZEROS, 0, needZeros);
    }
  }

  private void appendTimeZone(StringBuilder sb, java.util.Calendar cal) {
    int offset = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 1000;

    int absoff = Math.abs(offset);
    int hours = absoff / 60 / 60;
    int mins = (absoff - hours * 60 * 60) / 60;
    int secs = absoff - hours * 60 * 60 - mins * 60;

    sb.append((offset >= 0) ? " +" : " -");

    sb.append(NUMBERS[hours]);

    sb.append(':');

    sb.append(NUMBERS[mins]);

    if (min82) {
      sb.append(':');
      sb.append(NUMBERS[secs]);
    }
  }

  private static void appendEra(StringBuilder sb, Calendar cal) {
    if (cal.get(Calendar.ERA) == GregorianCalendar.BC) {
      sb.append(" BC");
    }
  }

  private static int skipWhitespace(char[] s, int start) {
    int slen = s.length;
    for (int i = start; i < slen; i++) {
      if (!Character.isSpace(s[i])) {
        return i;
      }
    }
    return slen;
  }

  private static int firstNonDigit(char[] s, int start) {
    int slen = s.length;
    for (int i = start; i < slen; i++) {
      if (!Character.isDigit(s[i])) {
        return i;
      }
    }
    return slen;
  }

  private static int number(char[] s, int start, int end) {
    if (start >= end) {
      throw new NumberFormatException();
    }
    int n = 0;
    for (int i = start; i < end; i++) {
      n = 10 * n + (s[i] - '0');
    }
    return n;
  }

  private static char charAt(char[] s, int pos) {
    if (pos >= 0 && pos < s.length) {
      return s[pos];
    }
    return '\0';
  }

  /**
   * Returns the SQL Date object matching the given bytes with {@link Oid#DATE}.
   *
   * @param tz    The timezone used.
   * @param bytes The binary encoded date value.
   * @return The parsed date object.
   * @throws PSQLException If binary format could not be parsed.
   */
  public Date toDateBin(TimeZone tz, byte[] bytes) throws PSQLException {
    if (bytes.length != 4) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.",
          "date"), PSQLState.BAD_DATETIME_FORMAT);
    }
    int days = ByteConverter.int4(bytes, 0);
    if (tz == null) {
      tz = getDefaultTz();
    }
    long secs = toJavaSecs(days * 86400L);
    long millis = secs * 1000L;
    int offset = tz.getOffset(millis);
    if (millis <= PGStatement.DATE_NEGATIVE_SMALLER_INFINITY) {
      millis = PGStatement.DATE_NEGATIVE_INFINITY;
      offset = 0;
    } else if (millis >= PGStatement.DATE_POSITIVE_SMALLER_INFINITY) {
      millis = PGStatement.DATE_POSITIVE_INFINITY;
      offset = 0;
    }
    return new Date(millis - offset);
  }

  private static TimeZone getDefaultTz() {
    return TimeZone.getDefault();
  }

  /**
   * Returns the SQL Time object matching the given bytes with {@link Oid#TIME} or {@link
   * Oid#TIMETZ}.
   *
   * @param tz    The timezone used when received data is {@link Oid#TIME}, ignored if data already
   *              contains {@link Oid#TIMETZ}.
   * @param bytes The binary encoded time value.
   * @return The parsed time object.
   * @throws PSQLException If binary format could not be parsed.
   */
  public Time toTimeBin(TimeZone tz, byte[] bytes) throws PSQLException {
    if ((bytes.length != 8 && bytes.length != 12)) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.",
          "time"), PSQLState.BAD_DATETIME_FORMAT);
    }

    long millis;
    int timeOffset;

    if (usesDouble) {
      double time = ByteConverter.float8(bytes, 0);

      millis = (long) (time * 1000);
    } else {
      long time = ByteConverter.int8(bytes, 0);

      millis = time / 1000;
    }

    if (bytes.length == 12) {
      timeOffset = ByteConverter.int4(bytes, 8);
      timeOffset *= -1000;
    } else {
      if (tz == null) {
        tz = getDefaultTz();
      }

      timeOffset = tz.getOffset(millis);
    }

    millis -= timeOffset;
    return convertToTime(millis, tz); // Ensure date part is 1970-01-01
  }

  /**
   * Returns the SQL Timestamp object matching the given bytes with {@link Oid#TIMESTAMP} or {@link
   * Oid#TIMESTAMPTZ}.
   *
   * @param tz          The timezone used when received data is {@link Oid#TIMESTAMP}, ignored if
   *                    data already contains {@link Oid#TIMESTAMPTZ}.
   * @param bytes       The binary encoded timestamp value.
   * @param timestamptz True if the binary is in GMT.
   * @return The parsed timestamp object.
   * @throws PSQLException If binary format could not be parsed.
   */
  public Timestamp toTimestampBin(TimeZone tz, byte[] bytes, boolean timestamptz)
      throws PSQLException {

    if (bytes.length != 8) {
      throw new PSQLException(GT.tr("Unsupported binary encoding of {0}.",
          "timestamp"), PSQLState.BAD_DATETIME_FORMAT);
    }

    long secs;
    int nanos;

    if (usesDouble) {
      double time = ByteConverter.float8(bytes, 0);
      if (time == Double.POSITIVE_INFINITY) {
        return new Timestamp(PGStatement.DATE_POSITIVE_INFINITY);
      } else if (time == Double.NEGATIVE_INFINITY) {
        return new Timestamp(PGStatement.DATE_NEGATIVE_INFINITY);
      }

      secs = (long) time;
      nanos = (int) ((time - secs) * 1000000);
    } else {
      long time = ByteConverter.int8(bytes, 0);

      // compatibility with text based receiving, not strictly necessary
      // and can actually be confusing because there are timestamps
      // that are larger than infinite
      if (time == Long.MAX_VALUE) {
        return new Timestamp(PGStatement.DATE_POSITIVE_INFINITY);
      } else if (time == Long.MIN_VALUE) {
        return new Timestamp(PGStatement.DATE_NEGATIVE_INFINITY);
      }

      secs = time / 1000000;
      nanos = (int) (time - secs * 1000000);
    }
    if (nanos < 0) {
      secs--;
      nanos += 1000000;
    }
    nanos *= 1000;

    secs = toJavaSecs(secs);
    long millis = secs * 1000L;
    if (!timestamptz) {
      // Here be dragons: backend did not provide us the timezone, so we guess the actual point in time
      millis = guessTimestamp(millis, tz);
    }

    Timestamp ts = new Timestamp(millis);
    ts.setNanos(nanos);
    return ts;
  }

  /**
   * Given a UTC timestamp {@code millis} finds another point in time that is rendered in given time
   * zone {@code tz} exactly as "millis in UTC".
   *
   * For instance, given 7 Jan 16:00 UTC and tz=GMT+02:00 it returns 7 Jan 14:00 UTC == 7 Jan 16:00
   * GMT+02:00 Note that is not trivial for timestamps near DST change. For such cases, we rely on
   * {@link Calendar} to figure out the proper timestamp.
   *
   * @param millis source timestamp
   * @param tz     desired time zone
   * @return timestamp that would be rendered in {@code tz} like {@code millis} in UTC
   */
  private long guessTimestamp(long millis, TimeZone tz) {
    if (tz == null) {
      // If client did not provide us with time zone, we use system default time zone
      tz = getDefaultTz();
    }
    // The story here:
    //   Backend provided us with something like '2015-10-04 13:40' and it did NOT provide us with a time zone.
    //   On top of that, user asked us to treat the timestamp as if it were in GMT+02:00.
    //
    // The code below creates such a timestamp that is rendered as '2015-10-04 13:40 GMT+02:00'
    // In other words, its UTC value should be 11:40 UTC == 13:40 GMT+02:00.
    // It is not sufficient to just subtract offset as you might cross DST change as you subtract.
    //
    // For instance, on 2000-03-26 02:00:00 Moscow went to DST, thus local time became 03:00:00
    // Suppose we deal with 2000-03-26 02:00:01
    // If you subtract offset from the timestamp, the time will be "a hour behind" since
    // "just a couple of hours ago the OFFSET was different"
    //
    // To make a long story short: we have UTC timestamp that looks like "2000-03-26 02:00:01" when rendered in UTC tz.
    // We want to know another timestamp that will look like "2000-03-26 02:00:01" in Europe/Moscow time zone.

    if (isSimpleTimeZone(tz.getID())) {
      // For well-known non-DST time zones, just subtract offset
      return millis - tz.getRawOffset();
    }
    // For all the other time zones, enjoy debugging Calendar API
    // Here we do a straight-forward implementation that splits original timestamp into pieces and composes it back.
    // Note: cal.setTimeZone alone is not sufficient as it would alter hour (it will try to keep the same time instant value)
    Calendar cal = calendarWithUserTz;
    cal.setTimeZone(utcTz);
    cal.setTimeInMillis(millis);
    int era = cal.get(Calendar.ERA);
    int year = cal.get(Calendar.YEAR);
    int month = cal.get(Calendar.MONTH);
    int day = cal.get(Calendar.DAY_OF_MONTH);
    int hour = cal.get(Calendar.HOUR_OF_DAY);
    int min = cal.get(Calendar.MINUTE);
    int sec = cal.get(Calendar.SECOND);
    int ms = cal.get(Calendar.MILLISECOND);
    cal.setTimeZone(tz);
    cal.set(Calendar.ERA, era);
    cal.set(Calendar.YEAR, year);
    cal.set(Calendar.MONTH, month);
    cal.set(Calendar.DAY_OF_MONTH, day);
    cal.set(Calendar.HOUR_OF_DAY, hour);
    cal.set(Calendar.MINUTE, min);
    cal.set(Calendar.SECOND, sec);
    cal.set(Calendar.MILLISECOND, ms);
    return cal.getTimeInMillis();
  }

  private static boolean isSimpleTimeZone(String id) {
    return id.startsWith("GMT") || id.startsWith("UTC");
  }

  /**
   * Extracts the date part from a timestamp.
   *
   * @param millis The timestamp from which to extract the date.
   * @param tz     The time zone of the date.
   * @return The extracted date.
   */
  public Date convertToDate(long millis, TimeZone tz) {

    // no adjustments for the inifity hack values
    if (millis <= PGStatement.DATE_NEGATIVE_INFINITY
        || millis >= PGStatement.DATE_POSITIVE_INFINITY) {
      return new Date(millis);
    }
    if (tz == null) {
      tz = getDefaultTz();
    }
    if (isSimpleTimeZone(tz.getID())) {
      // Truncate to 00:00 of the day.
      // Suppose the input date is 7 Jan 15:40 GMT+02:00 (that is 13:40 UTC)
      // We want it to become 7 Jan 00:00 GMT+02:00
      // 1) Make sure millis becomes 15:40 in UTC, so add offset
      int offset = tz.getRawOffset();
      millis += offset;
      // 2) Truncate hours, minutes, etc. Day is always 86400 seconds, no matter what leap seconds are
      millis = millis / ONEDAY * ONEDAY;
      // 2) Now millis is 7 Jan 00:00 UTC, however we need that in GMT+02:00, so subtract some offset
      millis -= offset;
      // Now we have brand-new 7 Jan 00:00 GMT+02:00
      return new Date(millis);
    }
    Calendar cal = calendarWithUserTz;
    cal.setTimeZone(tz);
    cal.setTimeInMillis(millis);
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    return new Date(cal.getTimeInMillis());
  }

  /**
   * Extracts the time part from a timestamp. This method ensures the date part of output timestamp
   * looks like 1970-01-01 in given timezone.
   *
   * @param millis The timestamp from which to extract the time.
   * @param tz     timezone to use.
   * @return The extracted time.
   */
  public Time convertToTime(long millis, TimeZone tz) {
    if (tz == null) {
      tz = getDefaultTz();
    }
    if (isSimpleTimeZone(tz.getID())) {
      // Leave just time part of the day.
      // Suppose the input date is 2015 7 Jan 15:40 GMT+02:00 (that is 13:40 UTC)
      // We want it to become 1970 1 Jan 15:40 GMT+02:00
      // 1) Make sure millis becomes 15:40 in UTC, so add offset
      int offset = tz.getRawOffset();
      millis += offset;
      // 2) Truncate year, month, day. Day is always 86400 seconds, no matter what leap seconds are
      millis = millis % ONEDAY;
      // 2) Now millis is 1970 1 Jan 15:40 UTC, however we need that in GMT+02:00, so subtract some offset
      millis -= offset;
      // Now we have brand-new 1970 1 Jan 15:40 GMT+02:00
      return new Time(millis);
    }
    Calendar cal = calendarWithUserTz;
    cal.setTimeZone(tz);
    cal.setTimeInMillis(millis);
    cal.set(Calendar.ERA, GregorianCalendar.AD);
    cal.set(Calendar.YEAR, 1970);
    cal.set(Calendar.MONTH, 0);
    cal.set(Calendar.DAY_OF_MONTH, 1);

    return new Time(cal.getTimeInMillis());
  }

  /**
   * Returns the given time value as String matching what the current postgresql server would send
   * in text mode.
   *
   * @param time time value
   * @return given time value as String
   */
  public String timeToString(java.util.Date time) {
    long millis = time.getTime();
    if (millis <= PGStatement.DATE_NEGATIVE_INFINITY) {
      return "-infinity";
    }
    if (millis >= PGStatement.DATE_POSITIVE_INFINITY) {
      return "infinity";
    }
    return time.toString();
  }

  /**
   * Converts the given postgresql seconds to java seconds. Reverse engineered by inserting varying
   * dates to postgresql and tuning the formula until the java dates matched. See {@link #toPgSecs}
   * for the reverse operation.
   *
   * @param secs Postgresql seconds.
   * @return Java seconds.
   */
  private static long toJavaSecs(long secs) {
    // postgres epoc to java epoc
    secs += 946684800L;

    // Julian/Gregorian calendar cutoff point
    if (secs < -12219292800L) { // October 4, 1582 -> October 15, 1582
      secs += 86400 * 10;
      if (secs < -14825808000L) { // 1500-02-28 -> 1500-03-01
        int extraLeaps = (int) ((secs + 14825808000L) / 3155760000L);
        extraLeaps--;
        extraLeaps -= extraLeaps / 4;
        secs += extraLeaps * 86400L;
      }
    }
    return secs;
  }

  /**
   * Converts the given java seconds to postgresql seconds. See {@link #toJavaSecs} for the reverse
   * operation. The conversion is valid for any year 100 BC onwards.
   *
   * @param secs Postgresql seconds.
   * @return Java seconds.
   */
  private static long toPgSecs(long secs) {
    // java epoc to postgres epoc
    secs -= 946684800L;

    // Julian/Greagorian calendar cutoff point
    if (secs < -13165977600L) { // October 15, 1582 -> October 4, 1582
      secs -= 86400 * 10;
      if (secs < -15773356800L) { // 1500-03-01 -> 1500-02-28
        int years = (int) ((secs + 15773356800L) / -3155823050L);
        years++;
        years -= years / 4;
        secs += years * 86400;
      }
    }

    return secs;
  }

  /**
   * Converts the SQL Date to binary representation for {@link Oid#DATE}.
   *
   * @param tz    The timezone used.
   * @param bytes The binary encoded date value.
   * @param value value
   * @throws PSQLException If binary format could not be parsed.
   */
  public void toBinDate(TimeZone tz, byte[] bytes, Date value) throws PSQLException {
    long millis = value.getTime();

    if (tz == null) {
      tz = getDefaultTz();
    }
    // It "getOffset" is UNTESTED
    // See org.postgresql.jdbc.AbstractJdbc2Statement.setDate(int, java.sql.Date, java.util.Calendar)
    // The problem is we typically do not know for sure what is the exact required date/timestamp type
    // Thus pgjdbc sticks to text transfer.
    millis += tz.getOffset(millis);

    long secs = toPgSecs(millis / 1000);
    ByteConverter.int4(bytes, 0, (int) (secs / 86400));
  }

}
