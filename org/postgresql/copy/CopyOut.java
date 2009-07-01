/*-------------------------------------------------------------------------
*
* Copyright (c) 2009, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL$
*
*-------------------------------------------------------------------------
*/
package org.postgresql.copy;

import java.sql.SQLException;

public interface CopyOut extends CopyOperation {
    byte[] readFromCopy() throws SQLException;
}
