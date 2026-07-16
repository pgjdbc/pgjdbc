/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.IntervalStyle;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGInterval;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL interval type.
 *
 * <p>Returns {@link PGInterval} for getObject().</p>
 *
 * <p>Binary format is: 8 bytes (microseconds), 4 bytes (days), 4 bytes (months).</p>
 */
public final class IntervalCodec implements StreamingBinaryCodec, TextCodec {

  public static final IntervalCodec INSTANCE = new IntervalCodec();

  private IntervalCodec() {
  }

  @Override
  public String getPrimaryTypeName() {
    return "interval";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGInterval.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 16) {
      throw Exceptions.invalidBinaryLength("interval", length);
    }
    return toPgInterval(normalize(data, offset));
  }

  /**
   * Splits a 16-byte binary interval into its rendering fields. The hour count is kept as a
   * {@code long}: near the microsecond field's int64 limit it reaches about 2562047788 hours, past
   * what an {@code int} holds, and the whole-microsecond split must stay in long arithmetic anyway so
   * the {@code hours * 3600} term does not overflow (it does past ~596_523 hours, corrupting the
   * minutes).
   */
  private static Parts normalize(byte[] data, int offset) {
    // Binary format: 8 bytes microseconds, 4 bytes days, 4 bytes months.
    long microseconds = ByteConverter.int8(data, offset);
    int days = ByteConverter.int4(data, offset + 8);
    int wireMonths = ByteConverter.int4(data, offset + 12);

    int years = wireMonths / 12;
    int months = wireMonths % 12;

    long totalSeconds = microseconds / 1_000_000L;
    int microSeconds = (int) (microseconds % 1_000_000L);
    long hours = totalSeconds / 3600;
    int minutes = (int) (totalSeconds % 3600 / 60);
    int wholeSeconds = (int) (totalSeconds % 60);

    return new Parts(years, months, days, hours, minutes, wholeSeconds, microSeconds);
  }

  /**
   * Builds the {@link PGInterval} that {@code getObject} returns. PGInterval stores hours in an
   * {@code int}, so a max-range microsecond value that overflows it is refused with a checked
   * {@link SQLException} rather than silently wrapping into a corrupt interval.
   */
  private static PGInterval toPgInterval(Parts p) throws SQLException {
    if (p.hours < Integer.MIN_VALUE || p.hours > Integer.MAX_VALUE) {
      throw Exceptions.intervalHoursOutOfRange(p.hours);
    }
    // wholeSeconds and microSeconds are already bounded (|s| < 60, |us| < 1_000_000), so folding them
    // back into a double stays exact and cannot overflow PGInterval.setSeconds.
    double seconds = p.wholeSeconds + p.microSeconds / 1_000_000.0;
    return new PGInterval(p.years, p.months, p.days, (int) p.hours, p.minutes, seconds);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    BackpatchByteArrayOutputStream out = new BackpatchByteArrayOutputStream(16);
    try {
      encodeBinary(value, type, ctx, out);
    } catch (IOException e) {
      throw new AssertionError(e); // BackpatchByteArrayOutputStream never throws
    }
    return out.toByteArray();
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    PGInterval interval = toInterval(value);

    // Convert to binary format. Keep the hours and minutes terms in long arithmetic (they are exact),
    // and round the fractional seconds to the nearest microsecond rather than truncating: the seconds
    // are a double, so (long) (seconds * 1e6) drops the last microsecond on most values (0.999999 s is
    // 999998.999... as a double and truncates to 999998).
    int months = interval.getYears() * 12 + interval.getMonths();
    int days = interval.getDays();
    long microseconds = interval.getHours() * 3600_000_000L
        + interval.getMinutes() * 60_000_000L
        + Math.round(interval.getSeconds() * 1_000_000.0);

    // Wire order: 8 bytes microseconds, 4 bytes days, 4 bytes months.
    out.writeInt64(microseconds);
    out.writeInt32(days);
    out.writeInt32(months);
  }

  private static PGInterval toInterval(Object value) throws SQLException {
    if (value instanceof PGInterval) {
      return (PGInterval) value;
    }
    if (value instanceof String) {
      return new PGInterval((String) value);
    }
    throw Exceptions.cannotEncode(value, "interval");
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null || data.isEmpty()) {
      return null;
    }
    return new PGInterval(data);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof PGInterval) {
      String text = ((PGInterval) value).getValue();
      return text != null ? text : "";
    }
    return value.toString();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 16) {
      throw Exceptions.invalidBinaryLength("interval", length);
    }
    // Render straight from the wire fields, never via PGInterval: a max-range interval renders here
    // (getString) even though it overflows PGInterval's int hours (getObject).
    return render(normalize(data, offset), ctx);
  }

  /**
   * Renders an interval the way the server's text form does, so that a value read in binary produces
   * the same string {@code getString} would see in text mode &mdash; independent of the wire format.
   * The output follows the connection's {@code IntervalStyle} (a GUC_REPORT parameter, read from
   * {@link CodecContext#getIntervalStyle()}); {@link IntervalStyle#POSTGRES} is used when no context
   * is supplied. Each branch mirrors the backend's {@code EncodeInterval} for the corresponding style.
   */
  private static String render(Parts interval, @Nullable CodecContext ctx) {
    IntervalStyle style = ctx == null ? IntervalStyle.POSTGRES : ctx.getIntervalStyle();
    switch (style) {
      case POSTGRES_VERBOSE:
        return toPostgresVerbose(interval);
      case SQL_STANDARD:
        return toSqlStandard(interval);
      case ISO_8601:
        return toIso8601(interval);
      case POSTGRES:
      default:
        return toPostgres(interval);
    }
  }

  /**
   * {@code IntervalStyle=postgres} (the default): {@code 1 year 2 mons 3 days 04:05:06}. The sign of a
   * field is carried into the next one, so {@code -1 day 02:03:04} renders as {@code -1 days +02:03:04}.
   */
  private static String toPostgres(Parts itv) {
    StringBuilder sb = new StringBuilder(32);
    boolean[] state = {true, false}; // {isZero, isBefore}
    appendPostgresDatePart(sb, itv.years, "year", state);
    appendPostgresDatePart(sb, itv.months, "mon", state);
    appendPostgresDatePart(sb, itv.days, "day", state);

    long hours = itv.hours;
    int minutes = itv.minutes;
    int sec = itv.wholeSeconds;
    int fsec = itv.microSeconds;
    boolean isZero = state[0];
    if (isZero || hours != 0 || minutes != 0 || sec != 0 || fsec != 0) {
      boolean negative = hours < 0 || minutes < 0 || sec < 0 || fsec < 0;
      if (!isZero) {
        sb.append(' ');
      }
      sb.append(negative ? "-" : (state[1] ? "+" : ""));
      appendZeroPadded2(sb, Math.abs(hours));
      sb.append(':');
      appendZeroPadded2(sb, Math.abs(minutes));
      sb.append(':');
      appendSeconds(sb, sec, fsec);
    }
    return sb.toString();
  }

  private static void appendPostgresDatePart(StringBuilder sb, int value, String unit, boolean[] state) {
    if (value == 0) {
      return;
    }
    if (!state[0]) {
      sb.append(' ');
    }
    if (state[1] && value > 0) {
      sb.append('+');
    }
    sb.append(value).append(' ').append(unit);
    if (value != 1) {
      sb.append('s');
    }
    state[0] = false; // isZero
    state[1] = value < 0; // isBefore: carry this field's sign to the next one
  }

  /**
   * {@code IntervalStyle=postgres_verbose}: {@code @ 1 year 2 mons 3 days 4 hours 5 mins 6 secs}. The
   * first non-zero field fixes the overall sign; later fields are negated relative to it (so a field
   * with the same sign prints positive) and a negative interval gets a trailing {@code ago}.
   */
  private static String toPostgresVerbose(Parts itv) {
    int year = itv.years;
    int mon = itv.months;
    int day = itv.days;
    long hour = itv.hours;
    int min = itv.minutes;
    int sec = itv.wholeSeconds;
    int fsec = itv.microSeconds;
    boolean before = firstNonZeroNegative(year, mon, day, hour, min, sec, fsec);

    StringBuilder sb = new StringBuilder(32);
    sb.append('@');
    appendVerbosePart(sb, before ? -year : year, "year");
    appendVerbosePart(sb, before ? -mon : mon, "mon");
    appendVerbosePart(sb, before ? -day : day, "day");
    appendVerbosePart(sb, before ? -hour : hour, "hour");
    appendVerbosePart(sb, before ? -min : min, "min");
    if (sec != 0 || fsec != 0) {
      int ts = before ? -sec : sec;
      int tf = before ? -fsec : fsec;
      sb.append(' ');
      if (ts < 0 || tf < 0) {
        sb.append('-');
      }
      sb.append(Math.abs(ts));
      appendMicros(sb, Math.abs(tf));
      // PostgreSQL singularises the unit only for exactly one whole second: "@ 1 sec" but "@ 2 secs"
      // and "@ 1.000001 secs" (any fraction forces the plural).
      sb.append(Math.abs(ts) == 1 && tf == 0 ? " sec" : " secs");
    }
    if (year == 0 && mon == 0 && day == 0 && hour == 0 && min == 0 && sec == 0 && fsec == 0) {
      sb.append(" 0");
    }
    if (before) {
      sb.append(" ago");
    }
    return sb.toString();
  }

  private static void appendVerbosePart(StringBuilder sb, long value, String unit) {
    if (value == 0) {
      return;
    }
    sb.append(' ').append(value).append(' ').append(unit);
    if (value != 1) {
      sb.append('s');
    }
  }

  /**
   * {@code IntervalStyle=sql_standard}: a year-month literal ({@code 1-2}), a day-time literal
   * ({@code 1 2:03:04}), or, when signs or classes are mixed, the fully-signed form
   * ({@code +1-2 +3 +4:05:06}). A pure single-signed value carries one leading {@code -}.
   */
  private static String toSqlStandard(Parts itv) {
    int year = itv.years;
    int mon = itv.months;
    int day = itv.days;
    long hour = itv.hours;
    int min = itv.minutes;
    int sec = itv.wholeSeconds;
    int fsec = itv.microSeconds;

    boolean hasNegative = year < 0 || mon < 0 || day < 0 || hour < 0 || min < 0 || sec < 0 || fsec < 0;
    boolean hasPositive = year > 0 || mon > 0 || day > 0 || hour > 0 || min > 0 || sec > 0 || fsec > 0;
    boolean hasYearMonth = year != 0 || mon != 0;
    boolean hasDayTime = day != 0 || hour != 0 || min != 0 || sec != 0 || fsec != 0;
    // A well-formed SQL-standard literal is single-signed and either year-month or day-time, not both.
    boolean sqlStandardValue = !(hasNegative && hasPositive) && !(hasYearMonth && hasDayTime);

    StringBuilder sb = new StringBuilder(24);
    if (sqlStandardValue && hasNegative) {
      // One leading sign for the whole value; render the magnitude.
      sb.append('-');
      year = -year;
      mon = -mon;
      day = -day;
      hour = -hour;
      min = -min;
      sec = -sec;
      fsec = -fsec;
    }

    if (!hasYearMonth && !hasDayTime) {
      sb.append('0');
    } else if (!sqlStandardValue) {
      // Mixed signs or mixed classes: force per-group signs to stay unambiguous.
      char yearSign = year < 0 || mon < 0 ? '-' : '+';
      char daySign = day < 0 ? '-' : '+';
      char secSign = hour < 0 || min < 0 || sec < 0 || fsec < 0 ? '-' : '+';
      sb.append(yearSign).append(Math.abs(year)).append('-').append(Math.abs(mon));
      sb.append(' ').append(daySign).append(Math.abs(day));
      sb.append(' ').append(secSign).append(Math.abs(hour)).append(':');
      appendZeroPadded2(sb, Math.abs(min));
      sb.append(':');
      appendSeconds(sb, sec, fsec);
    } else if (hasYearMonth) {
      sb.append(year).append('-').append(mon);
    } else {
      if (day != 0) {
        sb.append(day).append(' ');
      }
      sb.append(hour).append(':');
      appendZeroPadded2(sb, min);
      sb.append(':');
      appendSeconds(sb, sec, fsec);
    }
    return sb.toString();
  }

  /**
   * {@code IntervalStyle=iso_8601}: an ISO 8601 duration, {@code P1Y2M3DT4H5M6S}. Fields are printed
   * with their own sign and omitted when zero; the all-zero interval is {@code PT0S}.
   */
  private static String toIso8601(Parts itv) {
    int year = itv.years;
    int mon = itv.months;
    int day = itv.days;
    long hour = itv.hours;
    int min = itv.minutes;
    int sec = itv.wholeSeconds;
    int fsec = itv.microSeconds;

    if (year == 0 && mon == 0 && day == 0 && hour == 0 && min == 0 && sec == 0 && fsec == 0) {
      return "PT0S";
    }
    StringBuilder sb = new StringBuilder(24);
    sb.append('P');
    appendIsoPart(sb, year, 'Y');
    appendIsoPart(sb, mon, 'M');
    appendIsoPart(sb, day, 'D');
    if (hour != 0 || min != 0 || sec != 0 || fsec != 0) {
      sb.append('T');
      appendIsoPart(sb, hour, 'H');
      appendIsoPart(sb, min, 'M');
      if (sec != 0 || fsec != 0) {
        if (sec < 0 || fsec < 0) {
          sb.append('-');
        }
        sb.append(Math.abs(sec));
        appendMicros(sb, Math.abs(fsec));
        sb.append('S');
      }
    }
    return sb.toString();
  }

  private static void appendIsoPart(StringBuilder sb, long value, char unit) {
    if (value != 0) {
      sb.append(value).append(unit);
    }
  }

  /** Returns whether the first non-zero field (scanned largest to smallest) is negative. */
  private static boolean firstNonZeroNegative(int year, int mon, int day, long hour, int min,
      int sec, int fsec) {
    if (year != 0) {
      return year < 0;
    }
    if (mon != 0) {
      return mon < 0;
    }
    if (day != 0) {
      return day < 0;
    }
    if (hour != 0) {
      return hour < 0;
    }
    if (min != 0) {
      return min < 0;
    }
    if (sec != 0) {
      return sec < 0;
    }
    return fsec < 0;
  }

  /** Appends {@code SS[.ffffff]}: two-digit zero-padded whole seconds plus trimmed microseconds. */
  private static void appendSeconds(StringBuilder sb, int sec, int fsec) {
    appendZeroPadded2(sb, Math.abs(sec));
    appendMicros(sb, Math.abs(fsec));
  }

  private static void appendZeroPadded2(StringBuilder sb, int value) {
    if (value < 10) {
      sb.append('0');
    }
    sb.append(value);
  }

  /**
   * The hours variant: an interval's hour field can exceed {@code int} (up to ~2562047788), so it is
   * printed from a {@code long}. Still pads to two digits, and prints wider values in full.
   */
  private static void appendZeroPadded2(StringBuilder sb, long value) {
    if (value < 10) {
      sb.append('0');
    }
    sb.append(value);
  }

  private static void appendMicros(StringBuilder sb, int micros) {
    if (micros == 0) {
      return;
    }
    sb.append('.');
    int start = sb.length();
    // Left-pad to six digits, then drop the trailing zeros the server omits.
    for (int scale = 100_000; scale > 1 && micros < scale; scale /= 10) {
      sb.append('0');
    }
    sb.append(micros);
    int end = sb.length();
    while (sb.charAt(end - 1) == '0' && end > start) {
      end--;
    }
    sb.setLength(end);
  }

  @Override
  public String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (length == 0) {
      return null;
    }
    if (length != 16) {
      throw Exceptions.invalidBinaryLength("interval", length);
    }
    Parts parts = normalize(data, offset);
    if (targetClass == String.class) {
      // Match decodeAsString/text mode: the server text form, not PGInterval.getValue()'s verbose one.
      // Rendered straight from the wire fields so a max-range interval still yields its string, where
      // the PGInterval path below refuses it (its hours field is an int).
      return (T) render(parts, ctx);
    }
    if (targetClass == PGInterval.class || targetClass == Object.class) {
      return (T) toPgInterval(parts);
    }
    throw Exceptions.cannotDecode("interval", targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (data == null || data.isEmpty()) {
      return null;
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    PGInterval interval = new PGInterval(data);
    if (targetClass == PGInterval.class || targetClass == Object.class) {
      return (T) interval;
    }
    throw Exceptions.cannotDecode("interval", targetClass.getName());
  }

  /**
   * A binary interval split into rendering fields. Hours are a {@code long}: near the wire
   * microsecond field's int64 limit the hour count reaches about 2562047788, past what
   * {@link PGInterval} stores in its {@code int} field. The remaining fields are already bounded
   * ({@code |minutes| < 60}, {@code |wholeSeconds| < 60}, {@code |microSeconds| < 1_000_000}), so they
   * stay {@code int}.
   */
  private static final class Parts {
    final int years;
    final int months;
    final int days;
    final long hours;
    final int minutes;
    final int wholeSeconds;
    final int microSeconds;

    Parts(int years, int months, int days, long hours, int minutes, int wholeSeconds,
        int microSeconds) {
      this.years = years;
      this.months = months;
      this.days = days;
      this.hours = hours;
      this.minutes = minutes;
      this.wholeSeconds = wholeSeconds;
      this.microSeconds = microSeconds;
    }
  }
}
