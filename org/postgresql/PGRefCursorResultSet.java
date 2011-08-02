/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/PGRefCursorResultSet.java,v 1.8 2008/01/08 06:56:26 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql;

/**
 * A ref cursor based result set.
 *
 * @deprecated As of 8.0, this interface is only present for backwards-
 *   compatibility purposes. New code should call getString() on the ResultSet
 *   that contains the refcursor to obtain the underlying cursor name.
 */
public interface PGRefCursorResultSet
{

    /** @return the name of the cursor.
     *  @deprecated As of 8.0, replaced with calling getString() on
     *    the ResultSet that this ResultSet was obtained from.
     */
    public String getRefCursor ();
}
