/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/PGRefCursorResultSet.java,v 1.5 2004/11/09 08:43:42 jurka Exp $
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
