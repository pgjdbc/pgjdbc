/*-------------------------------------------------------------------------
 *
 * PGRefCursorResultSet.java
 *	  Describes a PLPGSQL refcursor type.
 *
 * Copyright (c) 2003, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $Header$
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql;


/** A ref cursor based result set.
 */
public interface PGRefCursorResultSet
{

        /** return the name of the cursor.
         */
	public String getRefCursor ();

}
