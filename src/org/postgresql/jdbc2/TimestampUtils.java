/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import java.sql.*;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.SimpleTimeZone;

import org.postgresql.PGStatement;
import org.postgresql.core.Oid;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLState;
import org.postgresql.util.PSQLException;


/**
 * Misc utils for handling time and date values.
 */
public class TimestampUtils {
    /**
     * Number of milliseconds in one day.
     */
    private static final int ONEDAY = 24 * 3600 * 1000;

    private StringBuffer sbuf = new StringBuffer();

    private Calendar defaultCal = new GregorianCalendar();
    private final TimeZone defaultTz = defaultCal.getTimeZone();

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
        if (calCache != null && calCacheZone == rawOffset)
            return calCache;
                
        StringBuffer zoneID = new StringBuffer("GMT");
        zoneID.append(sign < 0 ? '-' : '+');
        if (hr < 10) zoneID.append('0');
        zoneID.append(hr);
        if (min < 10) zoneID.append('0');
        zoneID.append(min);
        if (sec < 10) zoneID.append('0');
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
     * Load date/time information into the provided calendar
     * returning the fractional seconds.
     */
    private ParsedTimestamp loadCalendar(Calendar defaultTz, String str, String type) throws SQLException {
        char []s = str.toCharArray();
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
                if (sep != '-')
                    throw new NumberFormatException("Expected date to be dash-separated, got '" + sep + "'");

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
                if (sep != ':')
                    throw new NumberFormatException("Expected time to be colon-separated, got '" + sep + "'");

                start = end + 1; // Skip ':'

                // minutes

                end = firstNonDigit(s, start);
                result.minute = number(s, start, end);

                sep = charAt(s, end);
                if (sep != ':')
                    throw new NumberFormatException("Expected time to be colon-separated, got '" + sep + "'");

                start = end + 1; // Skip ':'
    
                // seconds

                end = firstNonDigit(s, start);
                result.second = number(s, start, end);
                start = end;
    
                // Fractional seconds.
                if (charAt(s, start) == '.') {
                    end = firstNonDigit(s, start+1); // Skip '.'
                    num = number(s, start+1, end);

                    for (int numlength = (end - (start+1)); numlength < 9; ++numlength)
                        num *= 10;

                    result.nanos = num;
                    start = end;
                }

                start = skipWhitespace(s, start); // Skip trailing whitespace
            }
    
            // Possibly read timezone.
            sep = charAt(s, start);
            if (sep == '-' || sep == '+') {
                int tzsign = (sep == '-') ? -1 : 1;
                int tzhr, tzmin, tzsec;
    
                end = firstNonDigit(s, start+1);    // Skip +/-
                tzhr = number(s, start+1, end);
                start = end;
    
                sep = charAt(s, start);
                if (sep == ':') {
                    end = firstNonDigit(s, start+1);  // Skip ':'
                    tzmin = number(s, start+1, end);
                    start = end;
                } else {
                    tzmin = 0;
                }

                tzsec = 0;
                if (min82) {
                    sep = charAt(s, start);
                    if (sep == ':') {
                        end = firstNonDigit(s, start+1);  // Skip ':'
                        tzsec = number(s, start+1, end);
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
                String eraString = new String(s, start, slen - start) ;
                if (eraString.startsWith("AD")) {
                    result.era = GregorianCalendar.AD;
                    start += 2;
                } else if (eraString.startsWith("BC")) {
                    result.era = GregorianCalendar.BC;
                    start += 2;
                }
            }

            if (start < slen)
                throw new NumberFormatException("Trailing junk on timestamp: '" + new String(s, start, slen - start) + "'");

            if (!result.hasTime && !result.hasDate)
                throw new NumberFormatException("Timestamp has neither date nor time");

        } catch (NumberFormatException nfe) {
            throw new PSQLException(GT.tr("Bad value for type {0} : {1}", new Object[]{type,str}), PSQLState.BAD_DATETIME_FORMAT, nfe);
        }

        return result;
    }

    //
    // Debugging hooks, not normally used unless developing -- uncomment the
    // bodies for stderr debug spam.
    //

    private static void showParse(String type, String what, Calendar cal, java.util.Date result, Calendar resultCal) {
//         java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd G HH:mm:ss Z");
//         sdf.setTimeZone(resultCal.getTimeZone());

//         StringBuffer sb = new StringBuffer("Parsed ");
//         sb.append(type);
//         sb.append(" '");
//         sb.append(what);
//         sb.append("' in zone ");
//         sb.append(cal.getTimeZone().getID());
//         sb.append(" as ");
//         sb.append(sdf.format(result));
//         sb.append(" (millis=");
//         sb.append(result.getTime());
//         sb.append(")");
        
//         System.err.println(sb.toString());
    }
    
    private static void showString(String type, Calendar cal, java.util.Date value, String result) {
//         java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd G HH:mm:ss Z");
//         sdf.setTimeZone(cal.getTimeZone());

//         StringBuffer sb = new StringBuffer("Stringized ");
//         sb.append(type);
//         sb.append(" ");
//         sb.append(sdf.format(value));
//         sb.append(" (millis=");
//         sb.append(value.getTime());
//         sb.append(") as '");
//         sb.append(result);
//         sb.append("'");
        
//         System.err.println(sb.toString());
    }
    
    /**
    * Parse a string and return a timestamp representing its value.
    *
    * @param s      The ISO formated date string to parse.
    *
    * @return null if s is null or a timestamp of the parsed string s.
    *
    * @throws SQLException if there is a problem parsing s.
    **/
    public synchronized Timestamp toTimestamp(Calendar cal, String s) throws SQLException
    {
        if (s == null)
            return null;

        int slen = s.length();

        // convert postgres's infinity values to internal infinity magic value
        if (slen == 8 && s.equals("infinity")) {
            return new Timestamp(PGStatement.DATE_POSITIVE_INFINITY);
        }

        if (slen == 9 && s.equals("-infinity")) {
            return new Timestamp(PGStatement.DATE_NEGATIVE_INFINITY);
        }

        if (cal == null)
            cal = defaultCal;

        ParsedTimestamp ts = loadCalendar(cal, s, "timestamp");
        Calendar useCal = (ts.tz == null ? cal : ts.tz);
        useCal.set(Calendar.ERA,          ts.era);
        useCal.set(Calendar.YEAR,         ts.year);
        useCal.set(Calendar.MONTH,        ts.month-1);
        useCal.set(Calendar.DAY_OF_MONTH, ts.day);
        useCal.set(Calendar.HOUR_OF_DAY,  ts.hour);
        useCal.set(Calendar.MINUTE,       ts.minute);
        useCal.set(Calendar.SECOND,       ts.second);
        useCal.set(Calendar.MILLISECOND,  0);
        
        Timestamp result = new Timestamp(useCal.getTime().getTime());
        result.setNanos(ts.nanos);
        showParse("timestamp", s, cal, result, useCal);
        return result;
    }

    public synchronized Time toTime(Calendar cal, String s) throws SQLException
    {
        if (s == null)
            return null;

        int slen = s.length();

        // infinity cannot be represented as Time
        // so there's not much we can do here.
        if ((slen == 8 && s.equals("infinity")) || (slen == 9 && s.equals("-infinity"))) {
            throw new PSQLException(GT.tr("Infinite value found for timestamp/date. This cannot be represented as time."),
                                    PSQLState.DATETIME_OVERFLOW);
        }

        if (cal == null)
            cal = defaultCal;

        ParsedTimestamp ts = loadCalendar(cal, s, "time");
        
        Calendar useCal = (ts.tz == null ? cal : ts.tz);
        useCal.set(Calendar.HOUR_OF_DAY,  ts.hour);
        useCal.set(Calendar.MINUTE,       ts.minute);
        useCal.set(Calendar.SECOND,       ts.second);
        useCal.set(Calendar.MILLISECOND,  (ts.nanos + 500000) / 1000000);
        
        if (ts.hasDate) {
            // Rotate it into the requested timezone before we zero out the date
            useCal.set(Calendar.ERA,          ts.era);
            useCal.set(Calendar.YEAR,         ts.year);
            useCal.set(Calendar.MONTH,        ts.month-1);
            useCal.set(Calendar.DAY_OF_MONTH, ts.day);
            cal.setTime(new Date(useCal.getTime().getTime()));
            useCal = cal;
        }
        
        useCal.set(Calendar.ERA,          GregorianCalendar.AD);
        useCal.set(Calendar.YEAR,         1970);
        useCal.set(Calendar.MONTH,        0);
        useCal.set(Calendar.DAY_OF_MONTH, 1);                
        
        Time result = new Time(useCal.getTime().getTime());
        showParse("time", s, cal, result, useCal);
        return result;
    }

    public synchronized Date toDate(Calendar cal, String s) throws SQLException
    {
        if (s == null)
            return null;

        int slen = s.length();

        // convert postgres's infinity values to internal infinity magic value
        if (slen == 8 && s.equals("infinity")) {
            return new Date(PGStatement.DATE_POSITIVE_INFINITY);
        }

        if (slen == 9 && s.equals("-infinity")) {
            return new Date(PGStatement.DATE_NEGATIVE_INFINITY);
        }

        if (cal == null)
            cal = defaultCal;

        ParsedTimestamp ts = loadCalendar(cal, s, "date");
        Calendar useCal = (ts.tz == null ? cal : ts.tz);
        
        useCal.set(Calendar.ERA,          ts.era);
        useCal.set(Calendar.YEAR,         ts.year);
        useCal.set(Calendar.MONTH,        ts.month-1);
        useCal.set(Calendar.DAY_OF_MONTH, ts.day);
        
        if (ts.hasTime) {
            // Rotate it into the requested timezone before we zero out the time
            useCal.set(Calendar.HOUR_OF_DAY,  ts.hour);
            useCal.set(Calendar.MINUTE,       ts.minute);
            useCal.set(Calendar.SECOND,       ts.second);
            useCal.set(Calendar.MILLISECOND,  (ts.nanos + 500000) / 1000000);
            cal.setTime(new Date(useCal.getTime().getTime()));
            useCal = cal;
        }
        
        useCal.set(Calendar.HOUR_OF_DAY,  0);
        useCal.set(Calendar.MINUTE,       0);
        useCal.set(Calendar.SECOND,       0);
        useCal.set(Calendar.MILLISECOND,  0);
        
        Date result = new Date(useCal.getTime().getTime());
        showParse("date", s, cal, result, useCal);
        return result;
    }

    public synchronized String toString(Calendar cal, Timestamp x) {
        if (cal == null)
            cal = defaultCal;

        cal.setTime(x);
        sbuf.setLength(0);
        
        if (x.getTime() == PGStatement.DATE_POSITIVE_INFINITY) {
            sbuf.append("infinity");
        } else if (x.getTime() == PGStatement.DATE_NEGATIVE_INFINITY) {
            sbuf.append("-infinity");
        } else {
            appendDate(sbuf, cal);
            sbuf.append(' ');
            appendTime(sbuf, cal, x.getNanos());
            appendTimeZone(sbuf, cal);
            appendEra(sbuf, cal);
        }
        
        showString("timestamp", cal, x, sbuf.toString());        
        return sbuf.toString();
    }

    public synchronized String toString(Calendar cal, Date x) {
        if (cal == null)
            cal = defaultCal;

        cal.setTime(x);
        sbuf.setLength(0);
        
        if (x.getTime() == PGStatement.DATE_POSITIVE_INFINITY) {
            sbuf.append("infinity");
        } else if (x.getTime() == PGStatement.DATE_NEGATIVE_INFINITY) {
            sbuf.append("-infinity");
        } else {
            appendDate(sbuf, cal);
            appendEra(sbuf, cal);
            appendTimeZone(sbuf, cal);
        }
        
        showString("date", cal, x, sbuf.toString());
        
        return sbuf.toString();
    }

    public synchronized String toString(Calendar cal, Time x) {
        if (cal == null)
            cal = defaultCal;

        cal.setTime(x);
        sbuf.setLength(0);
        
        appendTime(sbuf, cal, cal.get(Calendar.MILLISECOND) * 1000000);

        // The 'time' parser for <= 7.3 doesn't like timezones.
        if (min74)
            appendTimeZone(sbuf, cal);
        
        showString("time", cal, x, sbuf.toString());
        
        return sbuf.toString();
    }

    private static void appendDate(StringBuffer sb, Calendar cal)
    {
        int l_year = cal.get(Calendar.YEAR);
        // always use at least four digits for the year so very
        // early years, like 2, don't get misinterpreted
        //
        int l_yearlen = String.valueOf(l_year).length();
        for (int i = 4; i > l_yearlen; i--)
        {
            sb.append("0");
        }

        sb.append(l_year);
        sb.append('-');
        int l_month = cal.get(Calendar.MONTH) + 1;
        if (l_month < 10)
            sb.append('0');
        sb.append(l_month);
        sb.append('-');
        int l_day = cal.get(Calendar.DAY_OF_MONTH);
        if (l_day < 10)
            sb.append('0');
        sb.append(l_day);
    }

    private static void appendTime(StringBuffer sb, Calendar cal, int nanos)
    {
        int hours = cal.get(Calendar.HOUR_OF_DAY);
        if (hours < 10)
            sb.append('0');
        sb.append(hours);

        sb.append(':');
        int minutes = cal.get(Calendar.MINUTE);
        if (minutes < 10)
            sb.append('0');
        sb.append(minutes);

        sb.append(':');
        int seconds = cal.get(Calendar.SECOND);
        if (seconds < 10)
            sb.append('0');
        sb.append(seconds);

        // Add nanoseconds.
        // This won't work for server versions < 7.2 which only want
        // a two digit fractional second, but we don't need to support 7.1
        // anymore and getting the version number here is difficult.
        //
        char[] decimalStr = {'0', '0', '0', '0', '0', '0', '0', '0', '0'};
        char[] nanoStr = Integer.toString(nanos).toCharArray();
        System.arraycopy(nanoStr, 0, decimalStr, decimalStr.length - nanoStr.length, nanoStr.length);
        sb.append('.');
        sb.append(decimalStr, 0, 6);
    }

    private void appendTimeZone(StringBuffer sb, java.util.Calendar cal)
    {
        int offset = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 1000;

        int absoff = Math.abs(offset);
        int hours = absoff / 60 / 60;
        int mins = (absoff - hours * 60 * 60) / 60;
        int secs = absoff - hours * 60 * 60 - mins * 60;

        sb.append((offset >= 0) ? " +" : " -");

        if (hours < 10)
            sb.append('0');
        sb.append(hours);

        sb.append(':');

        if (mins < 10)
            sb.append('0');
        sb.append(mins);

        if (min82) {
            sb.append(':');
            if (secs < 10)
                sb.append('0');
            sb.append(secs);
        }
    }

    private static void appendEra(StringBuffer sb, Calendar cal)
    {
        if (cal.get(Calendar.ERA) == GregorianCalendar.BC) {
            sb.append(" BC");
        }
    }

    private static int skipWhitespace(char []s, int start)
    {
        int slen = s.length;
        for (int i=start; i<slen; i++) {
            if (!Character.isSpace(s[i]))
                return i;
        }
        return slen;
    }

    private static int firstNonDigit(char []s, int start)
    {
        int slen = s.length;
        for (int i=start; i<slen; i++) {
            if (!Character.isDigit(s[i])) {
                return i;
            }
        }
        return slen; 
    }

    private static int number(char []s, int start, int end) {
        if (start >= end) {
            throw new NumberFormatException();
        }
        int n=0;
        for ( int i=start; i < end; i++)
        {
            n = 10 * n + (s[i]-'0'); 
        }
        return n;
    }

    private static char charAt(char []s, int pos) {
        if (pos >= 0 && pos < s.length) {
            return s[pos];
        }
        return '\0';
    }

    /**
     * Returns the SQL Date object matching the given bytes with
     * {@link Oid#DATE}.
     * 
     * @param tz The timezone used.
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
            tz = defaultTz;
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

    /**
     * Returns the SQL Time object matching the given bytes with
     * {@link Oid#TIME} or {@link Oid#TIMETZ}.
     * 
     * @param tz The timezone used when received data is {@link Oid#TIME},
     * ignored if data already contains {@link Oid#TIMETZ}.
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
                tz = defaultTz;
            }
            
            timeOffset = tz.getOffset(millis);
        }
        
        millis -= timeOffset;
        return new Time(millis);
    }

    /**
     * Returns the SQL Timestamp object matching the given bytes with
     * {@link Oid#TIMESTAMP} or {@link Oid#TIMESTAMPTZ}.
     * 
     * @param tz The timezone used when received data is {@link Oid#TIMESTAMP},
     * ignored if data already contains {@link Oid#TIMESTAMPTZ}.
     * @param bytes The binary encoded timestamp value.
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
            if (tz == null) {
                tz = defaultTz;
            }
            millis -= tz.getOffset(millis);
        }

        Timestamp ts = new Timestamp(millis);
        ts.setNanos(nanos);
        return ts;
    }
    
    /**
     * Extracts the date part from a timestamp.
     * 
     * @param timestamp The timestamp from which to extract the date.
     * @param tz The time zone of the date.
     * @return The extracted date.
     */
    public Date convertToDate(Timestamp timestamp, TimeZone tz) {
        long millis = timestamp.getTime();
        // no adjustments for the inifity hack values
        if (millis <= PGStatement.DATE_NEGATIVE_INFINITY ||
            millis >= PGStatement.DATE_POSITIVE_INFINITY) {
            return new Date(millis);
        }
        if (tz == null) {
            tz = defaultTz;
        }
        int offset = tz.getOffset(millis);
        long timePart = millis % ONEDAY;
        if (timePart + offset >= ONEDAY) {
            millis += ONEDAY;
        }
        millis -= timePart;
        millis -= offset;

        return new Date(millis);
    }

    /**
     * Extracts the time part from a timestamp.
     * 
     * @param timestamp The timestamp from which to extract the time.
     * @param tz The time zone of the time.
     * @return The extracted time.
     */
    public Time convertToTime(Timestamp timestamp, TimeZone tz) {
        long millis = timestamp.getTime();
        if (tz == null) {
            tz = defaultTz;
        }
        int offset = tz.getOffset(millis);
        long low = - tz.getOffset(millis);
        long high = low + ONEDAY;
        if (millis < low) {
            do { millis += ONEDAY; } while (millis < low);
        } else if (millis >= high) {
            do { millis -= ONEDAY; } while (millis > high);
        }

        return new Time(millis);
    }
    
    /**
     * Returns the given time value as String matching what the
     * current postgresql server would send in text mode.
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
     * Converts the given postgresql seconds to java seconds.
     * Reverse engineered by inserting varying dates to postgresql
     * and tuning the formula until the java dates matched.
     * See {@link #toPgSecs} for the reverse operation.
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
     * Converts the given java seconds to postgresql seconds.
     * See {@link #toJavaSecs} for the reverse operation.
     * The conversion is valid for any year 100 BC onwards.
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
                years -= years/4;
                secs += years * 86400;
            }
        }
        
        return secs;
    }

    /**
     * Converts the SQL Date to binary representation for {@link Oid#DATE}.
     * 
     * @param tz The timezone used.
     * @param bytes The binary encoded date value.
     * @throws PSQLException If binary format could not be parsed.
     */
    public void toBinDate(TimeZone tz, byte[] bytes, Date value) throws PSQLException {
        long millis = value.getTime();
        
        if (tz == null) {
            tz = defaultTz;
        }
        millis += tz.getOffset(millis);
        
        long secs = toPgSecs(millis / 1000);
        ByteConverter.int4(bytes, 0, (int) (secs / 86400));
    }

}
