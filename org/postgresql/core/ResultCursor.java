/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, Open Cloud Limited.
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/ResultCursor.java,v 1.2 2004/11/07 22:15:33 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

/**
 * Abstraction of a cursor over a returned resultset.
 * This is an opaque interface that only provides a way
 * to close the cursor; all other operations are done by
 * passing a ResultCursor to QueryExecutor methods.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
public interface ResultCursor {
    /**
     * Close this cursor. This may not immediately free underlying resources
     * but may make it happen more promptly. Closed cursors should not be
     * passed to QueryExecutor methods.
     */
    void close();
}
