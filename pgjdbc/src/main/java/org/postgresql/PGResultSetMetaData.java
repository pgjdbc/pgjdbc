/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql;

import java.sql.SQLException;

import org.postgresql.core.Field;

public interface PGResultSetMetaData
{

    /**
     * Returns the underlying column name of a query result, or ""
     * if it is unable to be determined.
     *
     * @param column column position (1-based)
     * @throws SQLException if something wrong happens
     * @return underlying column name of a query result
     * @since 8.0
     */
    public String getBaseColumnName(int column) throws SQLException;

    /**
     * Returns the underlying table name of query result, or ""
     * if it is unable to be determined.
     *
     * @param column column position (1-based)
     * @throws SQLException if something wrong happens
     * @return underlying table name of query result
     * @since 8.0
     */
    public String getBaseTableName(int column) throws SQLException;

    /**
     * Returns the underlying schema name of query result, or ""
     * if it is unable to be determined.
     *
     * @param column column position (1-based)
     * @throws SQLException if something wrong happens
     * @return underlying schema name of query result
     * @since 8.0
     */
    public String getBaseSchemaName(int column) throws SQLException;

    /**
     * Is a column Text or Binary?
     *
     * @param column column position (1-based)
     * @throws SQLException if something wrong happens
     * @return if column is Text or Binary
     * @see Field#BINARY_FORMAT
     * @see Field#TEXT_FORMAT
     * @since 9.4
     */
    public int getFormat(int column) throws SQLException;
}
