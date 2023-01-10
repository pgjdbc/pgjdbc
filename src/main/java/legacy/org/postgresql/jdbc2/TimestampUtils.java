/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import java.sql.*;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.SimpleTimeZone;

import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PSQLException;
import legacy.org.postgresql.util.PSQLState;
import legacy.org.postgresql.PGStatement;


/**
 * Misc utils for handling time and date values.
 */
public class TimestampUtils {
    private StringBuffer sbuf = new StringBuffer();

    private Calendar defaultCal = new GregorianCalendar();

    private Calendar calCache;
    private int calCacheZone;

    private final boolean min74;
    private final boolean min82;

    TimestampUtils(boolean min74, boolean min82) {
        this.min74 = min74;
        this.min82 = min82;
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

}
