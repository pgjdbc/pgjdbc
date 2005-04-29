/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/util/PGInterval.java,v 1.6 2005/01/24 20:33:01 oliver Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.util;

import java.io.Serializable;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.StringTokenizer;

/**
 * This implements a class that handles the PostgreSQL interval type
 */
public class PGInterval extends PGobject implements Serializable, Cloneable
{

    private int years;
    private int months;
    private int days;
    private int hours;
    private int minutes;
    private double seconds;

    private final static DecimalFormat secondsFormat = new DecimalFormat("#.00####");


    /**
     * reuired by the driver
     */
    public PGInterval()
    {
        setType("interval");
    }

    /**
     * Initialize a interval with a given interval string representation
     *
     * @param value String representated interval (e.g. '3 years 2 mons')
     * @throws SQLException Is thrown if the string representation has an unknown format
     * @see #setValue(String)
     */
    public PGInterval(String value)
    throws SQLException
    {
        this();
        setValue(value);
    }

    /**
     * Initializes all values of this interval to the specified values
     *
     * @param years Years
     * @param months Months
     * @param days Days
     * @param hours Hours
     * @param minutes Minutes
     * @param seconds Seconds
     * @see #setValue(int, int, int, int, int, double)
     */
    public PGInterval(int years, int months, int days, int hours, int minutes, double seconds)
    {
        this();
        setValue(years, months, days, hours, minutes, seconds);
    }

    /**
     * Sets a interval string represented value to this instance.
     * This method only recognize the format, that Postgres returns -
     * not all input formats are supported (e.g. '1 yr 2 m 3 s')!
     *
     * @param value String representated interval (e.g. '3 years 2 mons')
     * @throws SQLException Is thrown if the string representation has an unknown format
     */
    public void setValue(String value)
    throws SQLException
    {
        final boolean ISOFormat = !value.startsWith("@");

        // Just a simple '0'
        if (!ISOFormat && value.length() == 3 && value.charAt(2) == '0')
        {
            setValue(0, 0, 0, 0, 0, 0.0);
            return;
        }

        int    years   = 0;
        int    months  = 0;
        int    days    = 0;
        int    hours   = 0;
        int    minutes = 0;
        double seconds = 0;

        try
        {
            String valueToken = null;

            value = value.replace('+', ' ').replace('@', ' ');
            final StringTokenizer st = new StringTokenizer(value);
            for (int i = 1; st.hasMoreTokens(); i++)
            {
                String token = st.nextToken();

                if ((i & 1) == 1)
                {
                    if (token.indexOf(':') == -1)
                    {
                        valueToken = token;
                        continue;
                    }

                    // This handles hours, minutes, seconds and microseconds for
                    // ISO intervals
                    int offset = (token.charAt(0) == '-') ? 1 : 0;

                    hours    = nullSafeIntGet(token.substring(offset+0, offset+2));
                    minutes  = nullSafeIntGet(token.substring(offset+3, offset+5));
                    seconds  = nullSafeDoubleGet(token.substring(offset+6, token.length()));

                    if (offset == 1)
                    {
                        hours   = -hours;
                        minutes = -minutes;
                        seconds = -seconds;
                    }

                    valueToken = null;
                }
                else
                {
                    // This handles years, months, days for both, ISO and
                    // Non-ISO intervals. Hours, minutes, seconds and microseconds
                    // are handled for Non-ISO intervals here.

                    if (token.startsWith("year"))
                        years = nullSafeIntGet(valueToken);
                    else if (token.startsWith("mon"))
                        months = nullSafeIntGet(valueToken);
                    else if (token.startsWith("day"))
                        days = nullSafeIntGet(valueToken);
                    else if (token.startsWith("hour"))
                        hours = nullSafeIntGet(valueToken);
                    else if (token.startsWith("min"))
                        minutes = nullSafeIntGet(valueToken);
                    else if (token.startsWith("sec"))
                        seconds = nullSafeDoubleGet(valueToken);
                }
            }
        }
        catch (NumberFormatException e)
        {
            throw new PSQLException(GT.tr("Conversion of interval failed"), PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE, e);
        }

        if (!ISOFormat && value.endsWith("ago"))
        {
            // Inverse the leading sign
            setValue(-years, -months, -days, -hours, -minutes, -seconds);
        }
        else
        {
            setValue(years, months, days, hours, minutes, seconds);
        }
    }

    /**
     * Set all values of this interval to the specified values
     *
     * @param years Years
     * @param months Months
     * @param days Days
     * @param hours Hours
     * @param minutes Minutes
     * @param seconds Seconds
     */
    public void setValue(int years, int months, int days, int hours, int minutes, double seconds)
    {
        setYears(years);
        setMonths(months);
        setDays(days);
        setHours(hours);
        setMinutes(minutes);
        setSeconds(seconds);
    }

    /**
     * Returns the stored interval information as a string
     *
     * @return String represented interval
     */
    public String getValue()
    {
        return years + " years " +
               months + " mons " +
               days + " days " +
               hours + " hours " +
               minutes + " mins " +
               secondsFormat.format(seconds) + " secs";
    }

    /**
     * Returns the years represented by this interval
     *
     * @return Years
     */
    public int getYears()
    {
        return years;
    }

    /**
     * Set the years of this interval to the specified value
     *
     * @param Years
     */
    public void setYears(int years)
    {
        this.years = years;
    }

    /**
     * Returns the months represented by this interval
     *
     * @return Months
     */
    public int getMonths()
    {
        return months;
    }

    /**
     * Set the months of this interval to the specified value
     *
     * @param Months
     */
    public void setMonths(int months)
    {
        this.months = months;
    }

    /**
     * Returns the days represented by this interval
     *
     * @return Days
     */
    public int getDays()
    {
        return days;
    }

    /**
     * Set the days of this interval to the specified value
     *
     * @param Days
     */
    public void setDays(int days)
    {
        this.days = days;
    }

    /**
     * Returns the hours represented by this interval
     *
     * @return Hours
     */
    public int getHours()
    {
        return hours;
    }

    /**
     * Set the hours of this interval to the specified value
     *
     * @param Hours
     */
    public void setHours(int hours)
    {
        this.hours = hours;
    }

    /**
     * Returns the minutes represented by this interval
     *
     * @return Minutes
     */
    public int getMinutes()
    {
        return minutes;
    }

    /**
     * Set the minutes of this interval to the specified value
     *
     * @param Minutes
     */
    public void setMinutes(int minutes)
    {
        this.minutes = minutes;
    }

    /**
     * Returns the seconds represented by this interval
     *
     * @return Seconds
     */
    public double getSeconds()
    {
        return seconds;
    }

    /**
     * Set the seconds of this interval to the specified value
     *
     * @param Seconds
     */
    public void setSeconds(double seconds)
    {
        this.seconds = seconds;
    }

    /**
     * Rolls this interval on a given calendar
     *
     * @param cal Calendar instance to roll on
     */
    public void add(Calendar cal)
    {
        // Avoid precision loss
        // Be aware postgres doesn't return more than 60 seconds - no overflow can happen
        final int microseconds = (int)(getSeconds() * 1000000.0);
        final int milliseconds = (microseconds + ((microseconds < 0) ? -500 : 500)) / 1000;

        cal.add(Calendar.MILLISECOND, milliseconds);
        cal.add(Calendar.MINUTE, getMinutes());
        cal.add(Calendar.HOUR, getHours());
        cal.add(Calendar.DAY_OF_MONTH, getDays());
        cal.add(Calendar.MONTH, getMonths());
        cal.add(Calendar.YEAR, getYears());
    }

    /**
     * Rolls this interval on a given date
     *
     * @param cal Date instance to roll on
     */
    public void add(Date date)
    {
        final Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        add(cal);
        date.setTime(cal.getTime().getTime());
    }

    /**
     * Returns integer value of value or 0 if value is null
     *
     * @param value integer as string value
     * @return integer parsed from string value
     * @throws NumberFormatException if the string contains invalid chars
     */
    private int nullSafeIntGet(String value)
    throws NumberFormatException
    {
        return (value == null) ? 0 : Integer.parseInt(value);
    }

    /**
     * Returns double value of value or 0 if value is null
     *
     * @param value double as string value
     * @return double parsed from string value
     * @throws NumberFormatException if the string contains invalid chars
     */
    private double nullSafeDoubleGet(String value)
    throws NumberFormatException
    {
        return (value == null) ? 0 : Double.parseDouble(value);
    }

    /**
     * Returns whether an object is equal to this one or not
     *
     * @param obj Object to compare with
     * @return true if the two intervals are identical
     */
    public boolean equals(Object obj)
    {
        if (obj == null)
            return false;

        if (obj == this)
            return true;

        if (!(obj instanceof PGInterval))
            return false;

        final PGInterval pgi = (PGInterval)obj;

        return
            pgi.years        == years &&
            pgi.months       == months &&
            pgi.days         == days &&
            pgi.hours        == hours &&
            pgi.minutes      == minutes &&
            Double.doubleToLongBits(pgi.seconds) == Double.doubleToLongBits(seconds);
    }

    /**
     * Returns a hashCode for this object
     *
     * @return hashCode
     */
    public int hashCode()
    {
        return ((((((7 * 31 +
                (int)Double.doubleToLongBits(seconds)) * 31 +
                minutes) * 31 +
                hours) * 31 +
                days) * 31 +
                months) * 31 +
                years) * 31;
    }

    /**
     * This clones the interval
     *
     * @return Cloned interval
     */
    public Object clone()
    {
        return new PGInterval(years, months, days, hours, minutes, seconds);
    }

}
