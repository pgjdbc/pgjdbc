/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004-2014, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.util;

import java.sql.Time;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * This class augments the Java built-in Time to allow for explicit setting of
 * the time zone.
 */
public class PGTime extends Time
{
    /** The serial version UID. */
    private static final long serialVersionUID = 3592492258676494276L;

    /** The optional time zone for this timestamp. */
    private Calendar timeZone;

    /**
     * Constructs a <code>PGTime</code> without a time zone.
     *
     * @param time
     *            milliseconds since January 1, 1970, 00:00:00 GMT; a negative
     *            number is milliseconds before January 1, 1970, 00:00:00 GMT.
     * @see Time#Time(long)
     */
    public PGTime(long time)
    {
	this(time, null);
    }

    /**
     * Constructs a <code>PGTime</code> with the given time zone.
     *
     * @param time
     *            milliseconds since January 1, 1970, 00:00:00 GMT; a negative
     *            number is milliseconds before January 1, 1970, 00:00:00 GMT.
     * @param timeZone
     *            the time zone for the given time or <code>null</code>.
     * @see Time#Time(long)
     */
    public PGTime(long time, Calendar timeZone)
    {
	super(time);
	this.setTimeZone(timeZone);
    }

    /**
     * Sets the time zone for this time.
     *
     * @param timeZone
     *            the time zone or <code>null</code>.
     */
    public void setTimeZone(Calendar timeZone)
    {
	this.timeZone = timeZone;
    }

    /**
     * Returns the time zone for this time.
     *
     * @return the time zone or <code>null</code>.
     */
    public Calendar getTimeZone()
    {
	return timeZone;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result
                + ((timeZone == null) ? 0 : timeZone.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (!(obj instanceof PGTime))
            return false;
        PGTime other = (PGTime) obj;
        if (timeZone == null)
        {
            if (other.timeZone != null)
                return false;
        }
        else if (!timeZone.equals(other.timeZone))
            return false;
        return true;
    }
}
