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


/** A ref cursor based result set.
 */
public interface PGRefCursorResultSet
{

        /** return the name of the cursor.
         */
	public String getRefCursor ();

}
