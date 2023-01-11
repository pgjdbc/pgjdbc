/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.copy;

import java.sql.SQLException;

public interface CopyOut extends CopyOperation {
    byte[] readFromCopy() throws SQLException;
}
