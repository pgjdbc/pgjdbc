/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.postgresql.jdbc.TimestampUtils.createProlepticGregorianCalendar;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.TimeZone;

/**
 * This implements a class that handles the PostgreSQL interval type.
 */
public class PGInterval extends PGobject implements Serializable, Cloneable {

  private static final int MICROS_IN_SECOND = 1000000;

  private int years;
  private int months;
  private int days;
  private int hours;
  private int minutes;
  private int wholeSeconds;
  private int microSeconds;
  private boolean isNull;

  /**
   * required by the driver.
   */
  public PGInterval() {
    type = "interval";
  }

  /**
   * Initialize a interval with a given interval string representation.
   *
   * @param value String represented interval (e.g. '3 years 2 mons')
   * @throws SQLException Is thrown if the string representation has an unknown format
   * @see PGobject#setValue(String)
   */
  @SuppressWarnings("method.invocation")
  public PGInterval(String value) throws SQLException {
    this();
    setValue(value);
  }

  private static int lookAhead(String value, int position, String find) {
    char [] tokens = find.toCharArray();
    int found = -1;

    for (int i = 0; i < tokens.length; i++) {
      found = value.indexOf(tokens[i], position);
      if (found > 0) {
        return found;
      }
    }
    return found;
  }

  private void parseISO8601Format(String value) {
    int number = 0;
    String dateValue;
    String timeValue = null;

    int hasTime = value.indexOf('T');
    if ( hasTime > 0 ) {
      /* skip over the P */
      dateValue = value.substring(1, hasTime);
      timeValue = value.substring(hasTime + 1);
    } else {
      /* skip over the P */
      dateValue = value.substring(1);
    }

    for (int i = 0; i < dateValue.length(); i++) {
      int lookAhead = lookAhead(dateValue, i, "YMD");
      if (lookAhead > 0) {
        number = Integer.parseInt(dateValue.substring(i, lookAhead));
        if (dateValue.charAt(lookAhead) == 'Y') {
          setYears(number);
        } else if (dateValue.charAt(lookAhead) == 'M') {
          setMonths(number);
        } else if (dateValue.charAt(lookAhead) == 'D') {
          setDays(number);
        }
        i = lookAhead;
      }
    }
    if ( timeValue != null ) {
      for (int i = 0; i < timeValue.length(); i++) {
        int lookAhead = lookAhead(timeValue, i, "HMS");
        if (lookAhead > 0) {
          if (timeValue.charAt(lookAhead) == 'H') {
            setHours(Integer.parseInt(timeValue.substring(i, lookAhead)));
          } else if (timeValue.charAt(lookAhead) == 'M') {
            setMinutes(Integer.parseInt(timeValue.substring(i, lookAhead)));
          } else if (timeValue.charAt(lookAhead) == 'S') {
            setSeconds(Double.parseDouble(timeValue.substring(i, lookAhead)));
          }
          i = lookAhead;
        }
      }
    }
  }

  /**
   * Initializes all values of this interval to the specified values.
   *
   * @param years years
   * @param months months
   * @param days days
   * @param hours hours
   * @param minutes minutes
   * @param seconds seconds
   * @see #setValue(int, int, int, int, int, double)
   */
  @SuppressWarnings("method.invocation")
  public PGInterval(int years, int months, int days, int hours, int minutes, double seconds) {
    this();
    setValue(years, months, days, hours, minutes, seconds);
  }

  /**
   * Sets a interval string represented value to this instance. This method only recognize the
   * format, that Postgres returns - not all input formats are supported (e.g. '1 yr 2 m 3 s').
   *
   * @param value String represented interval (e.g. '3 years 2 mons')
   * @throws SQLException Is thrown if the string representation has an unknown format
   */
  @Override
  public void setValue(@Nullable String value) throws SQLException {
    isNull = value == null;
    if (value == null) {
      setValue(0, 0, 0, 0, 0, 0);
      isNull = true;
      return;
    }
    final boolean postgresFormat = !value.startsWith("@");
    if (value.startsWith("P")) {
      parseISO8601Format(value);
      return;
    }
    // Just a simple '0'
    if (!postgresFormat && value.length() == 3 && value.charAt(2) == '0') {
      setValue(0, 0, 0, 0, 0, 0.0);
      return;
    }

    int years = 0;
    int months = 0;
    int days = 0;
    int hours = 0;
    int minutes = 0;
    double seconds = 0;

    try {
      String valueToken = null;

      value = value.replace('+', ' ').replace('@', ' ');
      value = value.toLowerCase(Locale.ROOT);
      final StringTokenizer st = new StringTokenizer(value);
      for (int i = 1; st.hasMoreTokens(); i++) {
        String token = st.nextToken();

        if ((i & 1) == 1) {
          int endHours = token.indexOf(':');
          if (endHours == -1) {
            valueToken = token;
            continue;
          }

          // This handles hours, minutes, seconds and microseconds for
          // ISO intervals
          int offset = token.charAt(0) == '-' ? 1 : 0;

          hours = nullSafeIntGet(token.substring(offset + 0, endHours));
          minutes = nullSafeIntGet(token.substring(endHours + 1, endHours + 3));

          // Pre 7.4 servers do not put second information into the results
          // unless it is non-zero.
          int endMinutes = token.indexOf(':', endHours + 1);
          if (endMinutes != -1) {
            seconds = nullSafeDoubleGet(token.substring(endMinutes + 1));
          }

          if (offset == 1) {
            hours = -hours;
            minutes = -minutes;
            seconds = -seconds;
          }

          valueToken = null;
        } else {
          // This handles years, months, days for both, ISO and
          // Non-ISO intervals. Hours, minutes, seconds and microseconds
          // are handled for Non-ISO intervals here.

          if (token.startsWith("year")) {
            years = nullSafeIntGet(valueToken);
          } else if (token.startsWith("mon")) {
            months = nullSafeIntGet(valueToken);
          } else if (token.startsWith("day")) {
            days = nullSafeIntGet(valueToken);
          } else if (token.startsWith("hour")) {
            hours = nullSafeIntGet(valueToken);
          } else if (token.startsWith("min")) {
            minutes = nullSafeIntGet(valueToken);
          } else if (token.startsWith("sec")) {
            seconds = nullSafeDoubleGet(valueToken);
          }
        }
      }
    } catch (NumberFormatException e) {
      throw new PSQLException(GT.tr("Conversion of interval failed"),
          PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE, e);
    }

    if (!postgresFormat && value.endsWith("ago")) {
      // Inverse the leading sign
      setValue(-years, -months, -days, -hours, -minutes, -seconds);
    } else {
      setValue(years, months, days, hours, minutes, seconds);
    }
  }

  /**
   * Set all values of this interval to the specified values.
   *
   * @param years years
   * @param months months
   * @param days days
   * @param hours hours
   * @param minutes minutes
   * @param seconds seconds
   */
  public void setValue(int years, int months, int days, int hours, int minutes, double seconds) {
    setYears(years);
    setMonths(months);
    setDays(days);
    setHours(hours);
    setMinutes(minutes);
    setSeconds(seconds);
  }

  /**
   * Returns the stored interval information as a string.
   *
   * @return String represented interval
   */
  @Override
  public @Nullable String getValue() {
    if (isNull) {
      return null;
    }

    // See https://github.com/pgjdbc/pgjdbc/pull/3866 for the justification
    // It looks like any attempt to estimate the buffer size causes noticeable slowdown
    StringBuilder sb = new StringBuilder(64);
    appendUnit(sb, years, " years");
    appendUnit(sb, months, " mons");
    appendUnit(sb, days, " days");
    appendUnit(sb, hours, " hours");
    appendUnit(sb, minutes, " mins");

    if (sb.length() == 0 || wholeSeconds != 0 || microSeconds != 0) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      if (wholeSeconds < 0 || microSeconds < 0) {
        // E.g. -0.73 has wholeSeconds==0, so we need to check micros as well
        sb.append('-');
      }
      sb.append(Math.abs(wholeSeconds));

      if (microSeconds != 0) {
        sb.append('.');
        int microsStart = sb.length(); // including
        // Add microseconds
        sb.append(Math.abs(microSeconds));
        int microsEnd = sb.length(); // excluding
        int prefixZeros = 6 - (microsEnd - microsStart);
        // Remove trailing zeros
        while (sb.charAt(microsEnd - 1) == '0' && microsEnd > microsStart) {
          microsEnd--;
        }
        sb.setLength(microsEnd);
        // Add missing leading zeros
        sb.insert(microsStart, "000000", 0, prefixZeros);
      }

      sb.append(" secs");
    }
    return sb.toString();
  }

  private static void appendUnit(StringBuilder sb, int value, String unit) {
    if (value == 0) {
      return;
    }
    if (sb.length() > 0) {
      sb.append(' ');
    }
    sb.append(value).append(unit);
  }

  /**
   * Returns the years represented by this interval.
   *
   * @return years represented by this interval
   */
  public int getYears() {
    return years;
  }

  /**
   * Set the years of this interval to the specified value.
   *
   * @param years years to set
   */
  public void setYears(int years) {
    isNull = false;
    this.years = years;
  }

  /**
   * Returns the months represented by this interval.
   *
   * @return months represented by this interval
   */
  public int getMonths() {
    return months;
  }

  /**
   * Set the months of this interval to the specified value.
   *
   * @param months months to set
   */
  public void setMonths(int months) {
    isNull = false;
    this.months = months;
  }

  /**
   * Returns the days represented by this interval.
   *
   * @return days represented by this interval
   */
  public int getDays() {
    return days;
  }

  /**
   * Set the days of this interval to the specified value.
   *
   * @param days days to set
   */
  public void setDays(int days) {
    isNull = false;
    this.days = days;
  }

  /**
   * Returns the hours represented by this interval.
   *
   * @return hours represented by this interval
   */
  public int getHours() {
    return hours;
  }

  /**
   * Set the hours of this interval to the specified value.
   *
   * @param hours hours to set
   */
  public void setHours(int hours) {
    isNull = false;
    this.hours = hours;
  }

  /**
   * Returns the minutes represented by this interval.
   *
   * @return minutes represented by this interval
   */
  public int getMinutes() {
    return minutes;
  }

  /**
   * Set the minutes of this interval to the specified value.
   *
   * @param minutes minutes to set
   */
  public void setMinutes(int minutes) {
    isNull = false;
    this.minutes = minutes;
  }

  /**
   * Returns the seconds represented by this interval.
   *
   * @return seconds represented by this interval
   */
  public double getSeconds() {
    return wholeSeconds + (double) microSeconds / MICROS_IN_SECOND;
  }

  public int getWholeSeconds() {
    return wholeSeconds;
  }

  public int getMicroSeconds() {
    return microSeconds;
  }

  /**
   * Set the seconds of this interval to the specified value.
   *
   * @param seconds seconds to set
   */
  public void setSeconds(double seconds) {
    isNull = false;

    double micros = seconds * MICROS_IN_SECOND;
    if (micros > Long.MAX_VALUE || micros < Long.MIN_VALUE) {
      throw new IllegalArgumentException("Number of seconds should be within Long.MIN_VALUE/1000000...Long.MAX_VALUE/1000000");
    }
    long totalMicros = Math.round(micros);
    wholeSeconds = (int) (totalMicros / MICROS_IN_SECOND);
    microSeconds = (int) (totalMicros % MICROS_IN_SECOND);
  }

  /**
   * Rolls this interval on a given calendar.
   *
   * @param cal Calendar instance to add to
   */
  public void add(Calendar cal) {
    if (isNull) {
      return;
    }

    final int milliseconds = (microSeconds + (microSeconds < 0 ? -500 : 500)) / 1000 + wholeSeconds * 1000;

    cal.add(Calendar.MILLISECOND, milliseconds);
    cal.add(Calendar.MINUTE, getMinutes());
    cal.add(Calendar.HOUR, getHours());
    cal.add(Calendar.DAY_OF_MONTH, getDays());
    cal.add(Calendar.MONTH, getMonths());
    cal.add(Calendar.YEAR, getYears());
  }

  /**
   * Rolls this interval on a given date.
   *
   * @param date Date instance to add to
   */
  @SuppressWarnings("JavaUtilDate")
  public void add(Date date) {
    if (isNull) {
      return;
    }
    final Calendar cal = createProlepticGregorianCalendar(TimeZone.getDefault());
    cal.setTime(date);
    add(cal);
    date.setTime(cal.getTime().getTime());
  }

  /**
   * Add this interval's value to the passed interval. This is backwards to what I would expect, but
   * this makes it match the other existing add methods.
   *
   * @param interval intval to add
   */
  public void add(PGInterval interval) {
    if (isNull || interval.isNull) {
      return;
    }
    interval.setYears(interval.getYears() + getYears());
    interval.setMonths(interval.getMonths() + getMonths());
    interval.setDays(interval.getDays() + getDays());
    interval.setHours(interval.getHours() + getHours());
    interval.setMinutes(interval.getMinutes() + getMinutes());
    interval.setSeconds(interval.getSeconds() + getSeconds());
  }

  /**
   * Scale this interval by an integer factor. The server can scale by arbitrary factors, but that
   * would require adjusting the call signatures for all the existing methods like getDays() or
   * providing our own justification of fractional intervals. Neither of these seem like a good idea
   * without a strong use case.
   *
   * @param factor scale factor
   */
  public void scale(int factor) {
    if (isNull) {
      return;
    }
    setYears(factor * getYears());
    setMonths(factor * getMonths());
    setDays(factor * getDays());
    setHours(factor * getHours());
    setMinutes(factor * getMinutes());
    setSeconds(factor * getSeconds());
  }

  /**
   * Returns integer value of value or 0 if value is null.
   *
   * @param value integer as string value
   * @return integer parsed from string value
   * @throws NumberFormatException if the string contains invalid chars
   */
  private static int nullSafeIntGet(@Nullable String value) throws NumberFormatException {
    return value == null ? 0 : Integer.parseInt(value);
  }

  /**
   * Returns double value of value or 0 if value is null.
   *
   * @param value double as string value
   * @return double parsed from string value
   * @throws NumberFormatException if the string contains invalid chars
   */
  private static double nullSafeDoubleGet(@Nullable String value) throws NumberFormatException {
    return value == null ? 0 : Double.parseDouble(value);
  }

  /**
   * Returns whether an object is equal to this one or not.
   *
   * @param obj Object to compare with
   * @return true if the two intervals are identical
   */
  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj == null) {
      return false;
    }

    if (obj == this) {
      return true;
    }

    if (!(obj instanceof PGInterval)) {
      return false;
    }

    final PGInterval pgi = (PGInterval) obj;
    if (isNull) {
      return pgi.isNull;
    } else if (pgi.isNull) {
      return false;
    }

    return pgi.years == years
        && pgi.months == months
        && pgi.days == days
        && pgi.hours == hours
        && pgi.minutes == minutes
        && pgi.wholeSeconds == wholeSeconds
        && pgi.microSeconds == microSeconds;
  }

  /**
   * Returns a hashCode for this object.
   *
   * @return hashCode
   */
  @Override
  public int hashCode() {
    if (isNull) {
      return 0;
    }
    return (((((((8 * 31 + microSeconds) * 31 + wholeSeconds) * 31 + minutes) * 31 + hours) * 31
        + days) * 31 + months) * 31 + years) * 31;
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    // squid:S2157 "Cloneables" should implement "clone
    return super.clone();
  }
}
