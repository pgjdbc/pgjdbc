/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc2/TimestampUtils.java,v 1.5 2004/11/09 08:49:32 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.text.ParseException;

import org.postgresql.Driver;
import org.postgresql.util.*;

/**
 * Misc utils for handling time and date values.
 * Extracted from AbstractJdbc2ResultSet.
 */
public class TimestampUtils {
    private static StringBuffer sbuf = new StringBuffer();
    private static SimpleDateFormat tstzFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
    private static SimpleDateFormat tsFormat = new SimpleDateFormat ("yyyy-MM-dd HH:mm:ss");
    private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    /**
    * Parse a string and return a timestamp representing its value.
    *
    * The driver is set to return ISO date formated strings. We modify this
    * string from the ISO format to a format that Java can understand. Java
    * expects timezone info as 'GMT+09:00' where as ISO gives '+09'.
    * Java also expects fractional seconds to 3 places where postgres
    * will give, none, 2 or 6 depending on the time and postgres version.
    * From version 7.2 postgres returns fractional seconds to 6 places.
    *
    * According to the Timestamp documentation, fractional digits are kept
    * in the nanos field of Timestamp and not in the milliseconds of Date.
    * Thus, parsing for fractional digits is entirely separated from the
    * rest.
    *
    * The method assumes that there are no more than 9 fractional
    * digits given. Undefined behavior if this is not the case.
    *
    * @param s      The ISO formated date string to parse.
    * @param pgDataType The (server) type of the date string.
    *
    * @return null if s is null or a timestamp of the parsed string s.
    *
    * @throws SQLException if there is a problem parsing s.
    **/
    public static Timestamp toTimestamp(String s, String pgDataType) throws SQLException
    {
        if (s == null)
            return null;

        s = s.trim();

        // We must be synchronized here incase more theads access the ResultSet
        // bad practice but possible. Anyhow this is to protect sbuf and
        // SimpleDateFormat objects
        synchronized (sbuf)
        {
            SimpleDateFormat df = null;
            if ( Driver.logDebug )
                Driver.debug("the data from the DB is " + s);

            sbuf.setLength(0);

            // Copy s into sbuf for parsing.
            sbuf.append(s);
            int slen = s.length();

            // For a Timestamp, the fractional seconds are stored in the
            // nanos field. As a DateFormat is used for parsing which can
            // only parse to millisecond precision and which returns a
            // Date object, the fractional second parsing is completely
            // separate.
            int nanos = 0;

            if (slen > 19)
            {
                // The len of the ISO string to the second value is 19 chars. If
                // greater then 19, there may be tz info and perhaps fractional
                // second info which we need to change to java to read it.

                // cut the copy to second value "2001-12-07 16:29:22"
                int i = 19;
                sbuf.setLength(i);

                char c = s.charAt(i++);
                if (c == '.')
                {
                    // Found a fractional value.
                    final int start = i;
                    while (true)
                    {
                        c = s.charAt(i++);
                        if (!Character.isDigit(c))
                            break;
                        if (i == slen)
                        {
                            i++;
                            break;
                        }
                    }

                    // The range [start, i - 1) contains all fractional digits.
                    final int end = i - 1;
                    try
                    {
                        nanos = Integer.parseInt(s.substring(start, end));
                    }
                    catch (NumberFormatException e)
                    {
                        throw new PSQLException(GT.tr("Could not extract nanoseconds from {0}.", s.substring(start, end)), PSQLState.UNEXPECTED_ERROR, e);
                    }

                    // The nanos field stores nanoseconds. Adjust the parsed
                    // value to the correct magnitude.
                    for (int digitsToNano = 9 - (end - start);
                            digitsToNano > 0; --digitsToNano)
                        nanos *= 10;
                }

                if (i < slen)
                {
                    // prepend the GMT part and then add the remaining bit of
                    // the string.
                    sbuf.append(" GMT");
                    sbuf.append(c);
                    sbuf.append(s.substring(i, slen));

                    // Lastly, if the tz part doesn't specify the :MM part then
                    // we add ":00" for java.
                    if (slen - i < 5)
                        sbuf.append(":00");

                    // we'll use this dateformat string to parse the result.
                    df = tstzFormat;
                }
                else
                {
                    // Just found fractional seconds but no timezone.
                    //If timestamptz then we use GMT, else local timezone
                    if (pgDataType.equals("timestamptz"))
                    {
                        sbuf.append(" GMT");
                        df = tstzFormat;
                    }
                    else
                    {
                        df = tsFormat;
                    }
                }
            }
            else if (slen == 19)
            {
                // No tz or fractional second info.
                //If timestamptz then we use GMT, else local timezone
                if (pgDataType.equals("timestamptz"))
                {
                    sbuf.append(" GMT");
                    df = tstzFormat;
                }
                else
                {
                    df = tsFormat;
                }
            }
            else
            {
                if (slen == 8 && s.equals("infinity"))
                    //java doesn't have a concept of postgres's infinity
                    //so set to an arbitrary future date
                    s = "9999-01-01";
                if (slen == 9 && s.equals("-infinity"))
                    //java doesn't have a concept of postgres's infinity
                    //so set to an arbitrary old date
                    s = "0001-01-01";

                // We must just have a date. This case is
                // needed if this method is called on a date
                // column
                if ( pgDataType.compareTo("date") == 0 )
                {
                    df = dateFormat;
                }
                else
                {
                    try
                    {
                        df = new SimpleDateFormat();
                        s = parseTime(s, df);
                        java.util.Date d = df.parse(s);
                        return new Timestamp( d.getTime() );
                    }
                    catch ( ParseException ex )
                    {
                        throw new PSQLException(GT.tr("The timestamp given {0} does not match the format required: {1}.",
                                                      new Object[]{s, df}),
                                                PSQLState.BAD_DATETIME_FORMAT,
                                                ex);
                    }
                }
            }

            try
            {
                // All that's left is to parse the string and return the ts.
                if ( Driver.logDebug )
                    Driver.debug("the data after parsing is "
                                 + sbuf.toString() + " with " + nanos + " nanos");

                Timestamp result = new Timestamp(df.parse(sbuf.toString()).getTime());
                result.setNanos(nanos);
                return result;
            }
            catch (ParseException e)
            {
                throw new PSQLException(GT.tr("The timestamp given {0} does not match the format required: {1}.", new Object[]{sbuf.toString(), df}), PSQLState.BAD_DATETIME_FORMAT, e);
            }
        }
    }

    /* parse out the various endings of a time string
     *
     *    hh:mm:ss.SSS+/-HH:MM
     *
     *    Everything after the last s is optional
     *    we will return a string with everything filled in so that a
     *    SimpleDateFormat (hh:mm:ss.SSS z) will parse the date
     *    This function expects the string to begin with the string after the last s
     */
    private static String parseTime( String s, SimpleDateFormat df ) throws ParseException
    {
        StringBuffer sb = new StringBuffer(s.substring(0, 8));
        StringBuffer timeFormat = new StringBuffer("HH:mm:ss");

        int msIndex = s.indexOf('.');
        int tzIndex = s.indexOf('-');
        if ( tzIndex == -1 )
            tzIndex = s.indexOf('+');

        if ( msIndex != -1 )
        {
            String microseconds = s.substring(msIndex + 1, tzIndex != -1 ? tzIndex : s.length());
            int msec = 0;

            // I want to have a peek at the 4th digit to see if we round up

            for ( int i = 0; i < (microseconds.length() > 3 ? 4 : microseconds.length()) ; i++ )
            {
                int digit = Character.digit(microseconds.charAt(i), 10);
                if (digit == -1)
                    throw new ParseException(s, tzIndex);
                if ( i == 0 )
                    msec += digit * 100;
                else if ( i == 1 )
                    msec += digit * 10;
                else if ( i == 2 )
                    msec += digit;
                else if ( i == 3 && digit >= 5)
                    msec += 1;

            }
            sb.append('.').append( msec );
            timeFormat.append(".SSS");
        }

        if ( tzIndex != -1 )
        { // we have a time zone
            String tz = s.substring(tzIndex);
            sb.append(" GMT").append(tz);
            if (tz.length() < 6)
                sb.append(":00");
            timeFormat.append( " z" );
        }
        df.applyPattern(timeFormat.toString());
        return sb.toString();
    }

    public static Time toTime(String s, String pgDataType) throws SQLException {
        if (s == null)
            return null; // SQL NULL

        SimpleDateFormat df = null;
        try
        {
            s = s.trim();

            if (s.length() == 8)
            {
                //value is a time value
                return java.sql.Time.valueOf(s);
            }

            if ( !pgDataType.startsWith("timestamp") )
            {
                df = new SimpleDateFormat();
                s = parseTime(s, df);
                java.util.Date d = df.parse(s);
                return new java.sql.Time( d.getTime() );
            }

            //value is a timestamp
            return new java.sql.Time(toTimestamp(s, pgDataType).getTime());
        }
        catch (ParseException e)
        {
            throw new PSQLException(GT.tr("The time given {0} does not match the format required: {1}.", new Object[]{s, df}), PSQLState.BAD_DATETIME_FORMAT);
        }
    }
}

