package org.postgresql;


/* $Header$
 * This interface defines PostgreSQL extention for Notifications
 */
public interface PGNotification
{
	/*
	 * Returns name of this notification
	 */
	public String getName();

	/*
	 * Returns the process id of the backend process making this notification
	 */
	public int getPID();

}

