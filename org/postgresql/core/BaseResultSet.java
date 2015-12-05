/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Driver-internal resultset interface. Application code should not use
 * this interface.
 */
public interface BaseResultSet extends ResultSet {
    /**
     * Return a sanitized numeric string for a column. This handles
     * "money" representations, stripping $ and () as appropriate.
     *
     * @param col the 1-based column to retrieve
     * @return the sanitized string
     * @throws SQLException if something goes wrong
     */
    String getFixedString(int col) throws SQLException;

}
