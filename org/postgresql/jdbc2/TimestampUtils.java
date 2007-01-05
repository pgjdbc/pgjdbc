/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/TimestampUtils.java,v 1.12.2.1 2005/02/15 08:32:16 jurka Exp $
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

    /**
     * Load date/time information into the provided calendar
     * returning the fractional seconds.
     */
    private static int loadCalendar(GregorianCalendar cal, String s, String type) throws SQLException {
        int slen = s.length();

        // Zero out all the fields.
        cal.setTime(new java.util.Date(0));

        int nanos = 0;

        try {
    
            int start = 0;
            int end = firstNonDigit(s, start);
    
            int num = number(s, start, end);
            char sep = charAt(s, end);
    
            // Read date
            if (sep == '-') {
                cal.set(Calendar.YEAR, num);
                start = end + 1;
    
                // read month
                end = firstNonDigit(s, start);
                num = number(s, start, end);
                cal.set(Calendar.MONTH, num-1);
                start = end + 1;
    
                // read date
                end = firstNonDigit(s, start);
                num = number(s, start, end);
                cal.set(Calendar.DAY_OF_MONTH, num);
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
                cal.set(Calendar.HOUR_OF_DAY, num);
                start = end + 1;
    
                // minutes
                end = firstNonDigit(s, start);
                num = number(s, start, end);
                cal.set(Calendar.MINUTE, num);
                start = end + 1;
    
                // seconds
                end = firstNonDigit(s, start);
                num = number(s, start, end);
                cal.set(Calendar.SECOND, num);
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
                    nanos = num;
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
    
                int tzoffset = tzsign * tzhr * 60 + tzmin;
                // offset is in milliseconds.
                tzoffset *= 60 * 1000;
    
                cal.set(Calendar.ZONE_OFFSET, tzoffset);
                cal.set(Calendar.DST_OFFSET, 0);
    
                while (charAt(s, start) == ' ') {
                    start++;
                }
            }
    
            if (start < slen) {
                String era = s.substring(start);
                if (era.equals("AD")) {
                    cal.set(Calendar.ERA, GregorianCalendar.AD);
                } else if (era.equals("BC")) {
                    cal.set(Calendar.ERA, GregorianCalendar.BC);
                }
            }
        } catch (NumberFormatException nfe) {
            throw new PSQLException(GT.tr("Bad value for type {0} : {1}", new Object[]{type,s}), PSQLState.BAD_DATETIME_FORMAT, nfe);
        }
    
        return nanos;
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
    public static Timestamp toTimestamp(GregorianCalendar cal, String s) throws SQLException
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

        synchronized(cal) {
            cal.set(Calendar.ZONE_OFFSET, 0);
            cal.set(Calendar.DST_OFFSET, 0);
            int nanos = loadCalendar(cal, s, "timestamp");

            Timestamp result = new Timestamp(cal.getTime().getTime());
            result.setNanos(nanos);
            return result;
        }
    }

    public static Time toTime(GregorianCalendar cal, String s) throws SQLException
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

        synchronized(cal) {
            cal.set(Calendar.ZONE_OFFSET, 0);
            cal.set(Calendar.DST_OFFSET, 0);
            int nanos = loadCalendar(cal, s, "time");

            cal.set(Calendar.YEAR, 1970);
            cal.set(Calendar.MONTH, 0);
            cal.set(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.ERA, GregorianCalendar.AD);
            cal.set(Calendar.MILLISECOND, (nanos + 500000) / 1000000);
            Time result = new Time(cal.getTime().getTime());
            return result;
        }
    }

    public static Date toDate(GregorianCalendar cal, String s) throws SQLException
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

        synchronized(cal) {
            cal.set(Calendar.ZONE_OFFSET, 0);
            cal.set(Calendar.DST_OFFSET, 0);
            loadCalendar(cal, s, "date");

            // zero out non-date things.
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            Date result = new Date(cal.getTime().getTime());
            return result;
        }
    }

    public static String toString(StringBuffer sbuf, GregorianCalendar cal, Timestamp x) {
        synchronized(sbuf) {
            synchronized(cal) {
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
                    appendTimeZone(sbuf, x);
                    appendEra(sbuf, cal);
                }
                return sbuf.toString();
            }
        }
    }

    public static String toString(StringBuffer sbuf, GregorianCalendar cal, Date x) {
        synchronized(sbuf) {
            synchronized(cal) {
                cal.setTime(x);
                sbuf.setLength(0);

                if (x.getTime() == PGStatement.DATE_POSITIVE_INFINITY) {
                    sbuf.append("infinity");
                } else if (x.getTime() == PGStatement.DATE_NEGATIVE_INFINITY) {
                    sbuf.append("-infinity");
                } else {
                    appendDate(sbuf, cal);
                    appendEra(sbuf, cal);
                }
                return sbuf.toString();
            }
        }
    }

    public static String toString(StringBuffer sbuf, GregorianCalendar cal, Time x) {
        synchronized(sbuf) {
            synchronized(cal) {
                cal.setTime(x);
                sbuf.setLength(0);

                appendTime(sbuf, cal, cal.get(Calendar.MILLISECOND) * 1000000);
                // Doesn't work on <= 7.3 for time, only for timetz
                //appendTimeZone(sbuf, x);
                return sbuf.toString();
            }
        }
    }

    private static void appendDate(StringBuffer sbuf, Calendar cal)
    {
        int l_year = cal.get(Calendar.YEAR);
        // always use at least four digits for the year so very
        // early years, like 2, don't get misinterpreted
        //
        int l_yearlen = String.valueOf(l_year).length();
        for (int i = 4; i > l_yearlen; i--)
        {
            sbuf.append("0");
        }

        sbuf.append(l_year);
        sbuf.append('-');
        int l_month = cal.get(Calendar.MONTH) + 1;
        if (l_month < 10)
            sbuf.append('0');
        sbuf.append(l_month);
        sbuf.append('-');
        int l_day = cal.get(Calendar.DAY_OF_MONTH);
        if (l_day < 10)
            sbuf.append('0');
        sbuf.append(l_day);
    }

    private static void appendTime(StringBuffer sbuf, Calendar cal, int nanos)
    {
        int hours = cal.get(Calendar.HOUR_OF_DAY);
        if (hours < 10)
            sbuf.append('0');
        sbuf.append(hours);

        sbuf.append(':');
        int minutes = cal.get(Calendar.MINUTE);
        if (minutes < 10)
            sbuf.append('0');
        sbuf.append(minutes);

        sbuf.append(':');
        int seconds = cal.get(Calendar.SECOND);
        if (seconds < 10)
            sbuf.append('0');
        sbuf.append(seconds);

        // Add nanoseconds.
        // This won't work for server versions < 7.2 which only want
        // a two digit fractional second, but we don't need to support 7.1
        // anymore and getting the version number here is difficult.
        //
        char[] decimalStr = {'0', '0', '0', '0', '0', '0', '0', '0', '0'};
        char[] nanoStr = Integer.toString(nanos).toCharArray();
        System.arraycopy(nanoStr, 0, decimalStr, decimalStr.length - nanoStr.length, nanoStr.length);
        sbuf.append('.');
        sbuf.append(decimalStr, 0, 6);
    }

    private static void appendTimeZone(StringBuffer sbuf, java.util.Date x)
    {
        //add timezone offset
        int offset = -(x.getTimezoneOffset());
        int absoff = Math.abs(offset);
        int hours = absoff / 60;
        int mins = absoff - hours * 60;

        sbuf.append((offset >= 0) ? "+" : "-");

        if (hours < 10)
            sbuf.append('0');
        sbuf.append(hours);

        sbuf.append(':');

        if (mins < 10)
            sbuf.append('0');
        sbuf.append(mins);
    }

    private static void appendEra(StringBuffer sbuf, Calendar cal)
    {
        if (cal.get(Calendar.ERA) == GregorianCalendar.BC) {
            sbuf.append(" BC");
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

    private static String testData[] = {
        "2004-12-10 22:58:49.964907-09",
        "0027-12-10 22:59:29-09 BC",
        "0027-12-10 BC",
        "14034-12-10",
        "23:01:05.372441-09",
        "23:01:05",
        "23:01:05.123",
        "00:00:00.01"
    };

    private static int testTypes[] = {
        Types.TIMESTAMP,
        Types.TIMESTAMP,
        Types.DATE,
        Types.DATE,
        Types.TIME,
        Types.TIME,
        Types.TIME,
        Types.TIME
    };

    public static void main(String args[]) throws Exception {
        StringBuffer sbuf = new StringBuffer();
        GregorianCalendar cal = new GregorianCalendar();

        for (int i=0; i<testData.length; i++) {
            String data = testData[i];
            System.out.println("Orig: " + data);

            String result = null;
            switch (testTypes[i]) {
                case Types.TIMESTAMP:
                    result = toString(sbuf, cal, toTimestamp(cal, data));
                    break;
                case Types.TIME:
                    result = toString(sbuf, cal, toTime(cal, data));
                    break;
                case Types.DATE:
                    result = toString(sbuf, cal, toDate(cal, data));
                    break;
            }
            System.out.println(result);
            System.out.println();
        }

        System.out.println(toString(sbuf, cal, new Timestamp(0)));
    }

}
