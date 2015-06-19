/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004-2014, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.util;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * This class augments the Java built-in Timestamp to allow for explicit setting
 * of the time zone.
 */
public class PGTimestamp extends Timestamp
{
    /** The serial version UID. */
    private static final long serialVersionUID = -6245623465210738466L;

    /** The optional time zone for this timestamp. */
    private Calendar timeZone;

    /**
     * Constructs a <code>PGTimestamp</code> without a time zone. The integral
     * seconds are stored in the underlying date value; the fractional seconds
     * are stored in the <code>nanos</code> field of the <code>Timestamp</code>
     * object.
     *
     * @param time
     *            milliseconds since January 1, 1970, 00:00:00 GMT. A negative
     *            number is the number of milliseconds before January 1, 1970,
     *            00:00:00 GMT.
     * @see Timestamp#Timestamp(long)
     */
    public PGTimestamp(long time)
    {
	this(time, null);
    }

    /**
     * Constructs a <code>PGTimestamp</code> with the given time zone. The
     * integral seconds are stored in the underlying date value; the fractional
     * seconds are stored in the <code>nanos</code> field of the
     * <code>Timestamp</code> object.
     *
     * @param time
     *            milliseconds since January 1, 1970, 00:00:00 GMT. A negative
     *            number is the number of milliseconds before January 1, 1970,
     *            00:00:00 GMT.
     * @param timeZone
     *            the time zone for the given time or <code>null</code>.
     * @see Timestamp#Timestamp(long)
     */
    public PGTimestamp(long time, Calendar timeZone)
    {
	super(time);
	this.setTimeZone(timeZone);
    }

    /**
     * Sets the time zone for this timestamp.
     *
     * @param timeZone
     *            the time zone or <code>null</code>.
     */
    public void setTimeZone(Calendar timeZone)
    {
	this.timeZone = timeZone;
    }

    /**
     * Returns the time zone for this timestamp.
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
        if (!(obj instanceof PGTimestamp))
            return false;
        PGTimestamp other = (PGTimestamp) obj;
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
