/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql;

import java.sql.SQLException;

public interface PGResultSetMetaData
{

    /**
     * Returns the underlying column name of a query result, or ""
     * if it is unable to be determined.
     * 
     * @since 8.0
     */
    public String getBaseColumnName(int column) throws SQLException;

    /**
     * Returns the underlying table name of query result, or ""
     * if it is unable to be determined.
     *
     * @since 8.0
     */
    public String getBaseTableName(int column) throws SQLException;

    /**
     * Returns the underlying table name of query result, or ""
     * if it is unable to be determined.
     *
     * @since 8.0
     */
    public String getBaseSchemaName(int column) throws SQLException;

}
