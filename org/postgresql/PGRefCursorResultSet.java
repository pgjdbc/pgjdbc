/*-------------------------------------------------------------------------
 *
 * PGRefCursorResultSet.java
 *	  Describes a PLPGSQL refcursor type.
 *
 * Copyright (c) 2003, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $PostgreSQL: /cvsroot/pgsql-server/src/interfaces/jdbc/org/postgresql/PGRefCursorResultSet.java,v 1.1 2003/05/03 20:40:45 barry Exp $
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql;

/**
 * A ref cursor based result set.
 *
 * @deprecated As of build 303, this interface is only present for backwards-
 *   compatibility purposes. New code should call getString() on the ResultSet
 *   that contains the refcursor to obtain the underlying cursor name.
 */
public interface PGRefCursorResultSet
{
	
	/** @return the name of the cursor.
	 *  @deprecated As of build 303, replaced with calling getString() on
	 *    the ResultSet that this ResultSet was obtained from.
	 */
	public String getRefCursor ();	
}
