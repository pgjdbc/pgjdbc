/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/TimestampUtils.java,v 1.13 2005/02/15 08:31:47 jurka Exp $
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
import org.postgresql.util.GT;
import org.postgresql.util.PSQLState;
import org.postgresql.util.PSQLException;


/**
 * Misc utils for handling time and date values.
 */
public class TimestampUtils {
    private StringBuffer sbuf = new StringBuffer();

    private Calendar defaultCal = new GregorianCalendar();

    private Calendar calCache;
    private int calCacheZone;

    private boolean min74;

    TimestampUtils(boolean min74) {
        this.min74 = min74;
    }

    private Calendar getCalendar(int sign, int hr, int min) {
        int unified = sign * (hr * 100 + min);
        if (calCache != null && calCacheZone == unified)
            return calCache;
                
        StringBuffer zoneID = new StringBuffer("GMT");
        zoneID.append(sign < 0 ? '-' : '+');
        if (hr < 10) zoneID.append('0');
        zoneID.append(hr);
        zoneID.append(':');
        if (min < 10) zoneID.append('0');
        zoneID.append(min);
        
        TimeZone syntheticTZ = TimeZone.getTimeZone(zoneID.toString());
        calCache = new GregorianCalendar(syntheticTZ);
        calCacheZone = unified;
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
    private ParsedTimestamp loadCalendar(Calendar defaultTz, String s, String type) throws SQLException {
        int slen = s.length();

        // This is pretty gross..
        ParsedTimestamp result = new ParsedTimestamp();

        try {
    
            int start = 0;
            int end = firstNonDigit(s, start);
    
            int num = number(s, start, end);
            char sep = charAt(s, end);
    
            // Read date
            if (sep == '-') {
                result.hasDate = true;
                result.year = num;
                start = end + 1;
    
                // read month
                end = firstNonDigit(s, start);
                result.month = number(s, start, end);
                start = end + 1;
    
                // read date
                end = firstNonDigit(s, start);
                result.day = number(s, start, end);
                start = end + 1;
    
                while (charAt(s, start) == ' ') {
                    start++;
                }
    
                end = firstNonDigit(s, start);
                try {
                    num = number(s, start, end);
                } catch(NumberFormatException nfe) {
                    // Ignore this, we don't know if a time, an
                    // era, or nothing is coming next, but we
                    // must be prepared for a time by parsing
                    // this as a number.
                }
                sep = charAt(s, end);
            }
    
            // Read time
            if (sep == ':') {
                // we've already read in num.
                result.hasTime = true;
                result.hour = num;
                start = end + 1;
    
                // minutes
                end = firstNonDigit(s, start);
                result.minute = number(s, start, end);
                start = end + 1;
    
                // seconds
                end = firstNonDigit(s, start);
                result.second = number(s, start, end);
                start = end + 1;
    
                sep = charAt(s, end);
    
                // Fractional seconds.
                if (sep == '.') {
                    end = firstNonDigit(s, start);
                    num = number(s, start, end);
                    int numlength = 9 - s.substring(start, end).length();
                    for (int i=0; i<numlength; i++) {
                        num *= 10;
                    }
                    result.nanos = num;
                    start = end + 1;
                    sep = charAt(s, end);
                }
            }
    
            // Timezone
            if (sep == '-' || sep == '+') {
                int tzsign = (sep == '-') ? -1 : 1;
    
                end = firstNonDigit(s, start);
                int tzhr = number(s, start, end);
                start = end + 1;
    
                end = firstNonDigit(s, start);
                sep = charAt(s, end);
    
                int tzmin = 0;
                if (sep == ':') {
                    tzmin = number(s, start, end);
                    start = end + 1;
                }
               
                // Setting offset does not seem to work correctly in all
                // cases.. So get a fresh calendar for a synthetic timezone
                // instead
                result.tz = getCalendar(tzsign, tzhr, tzmin);
                while (charAt(s, start) == ' ') {
                    start++;
                }
            }
    
            if (start < slen) {
                String eraString = s.substring(start);
                if (eraString.equals("AD")) {
                    result.era = GregorianCalendar.AD;
                } else if (eraString.equals("BC")) {
                    result.era = GregorianCalendar.BC;
                }
            }
        } catch (NumberFormatException nfe) {
            throw new PSQLException(GT.tr("Bad value for type {0} : {1}", new Object[]{type,s}), PSQLState.BAD_DATETIME_FORMAT, nfe);
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
        
        Timestamp result = new Timestamp(useCal.getTimeInMillis());
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
            cal.setTimeInMillis(useCal.getTimeInMillis());
            useCal = cal;
        }
        
        useCal.set(Calendar.ERA,          GregorianCalendar.AD);
        useCal.set(Calendar.YEAR,         1970);
        useCal.set(Calendar.MONTH,        0);
        useCal.set(Calendar.DAY_OF_MONTH, 1);                
        
        Time result = new Time(useCal.getTimeInMillis());
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
            cal.setTimeInMillis(useCal.getTimeInMillis());
            useCal = cal;
        }
        
        useCal.set(Calendar.HOUR_OF_DAY,  0);
        useCal.set(Calendar.MINUTE,       0);
        useCal.set(Calendar.SECOND,       0);
        useCal.set(Calendar.MILLISECOND,  0);
        
        Date result = new Date(useCal.getTimeInMillis());
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

    private static void appendTimeZone(StringBuffer sb, java.util.Calendar cal)
    {
        int offset = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 1000 / 60;

        int absoff = Math.abs(offset);
        int hours = absoff / 60;
        int mins = absoff - hours * 60;

        sb.append((offset >= 0) ? " +" : " -");

        if (hours < 10)
            sb.append('0');
        sb.append(hours);

        if (mins < 10)
            sb.append('0');
        sb.append(mins);
    }

    private static void appendEra(StringBuffer sb, Calendar cal)
    {
        if (cal.get(Calendar.ERA) == GregorianCalendar.BC) {
            sb.append(" BC");
        }
    }

    private static int firstNonDigit(String s, int start)
    {
        int slen = s.length();
        for (int i=start; i<slen; i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return i;
            }
        }
        return slen; 
    }

    private static int number(String s, int start, int end) {
        if (start >= end) {
            throw new NumberFormatException();
        }
        String num = s.substring(start, end);
        return Integer.parseInt(num);
    }

    private static char charAt(String s, int pos) {
        if (pos >= 0 && pos < s.length()) {
            return s.charAt(pos);
        }
        return '\0';
    }

}
