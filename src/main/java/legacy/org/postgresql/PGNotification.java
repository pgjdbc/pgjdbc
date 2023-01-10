/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql;

/**
 *    This interface defines the public PostgreSQL extension for Notifications
 */
public interface PGNotification
{
    /**
     * Returns name of this notification
     * @since 7.3
     */
    public String getName();

    /**
     * Returns the process id of the backend process making this notification
     * @since 7.3
     */
    public int getPID();

    /**
     * Returns additional information from the notifying process.
     * This feature has only been implemented in server versions 9.0
     * and later, so previous versions will always return an empty String.
     *
     * @since 8.0
     */
    public String getParameter();

}

