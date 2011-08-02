/*-------------------------------------------------------------------------
*
* (c) 2008-2011, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/copy/CopyOut.java,v 1.1 2009/07/01 05:00:39 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.copy;

import java.sql.SQLException;

public interface CopyOut extends CopyOperation {
    byte[] readFromCopy() throws SQLException;
}
