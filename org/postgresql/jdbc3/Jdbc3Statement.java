/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc3;

import java.sql.*;
import java.util.List;
import org.postgresql.core.*;

/**
 * This class implements the java.sql.Statement interface for JDBC3.
 * However most of the implementation is really done in
 * org.postgresql.jdbc3.AbstractJdbc3Statement or one of it's parents
 */
class Jdbc3Statement extends AbstractJdbc3Statement implements Statement
{
    Jdbc3Statement (Jdbc3Connection c, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(c, rsType, rsConcurrency, rsHoldability);
    }

    protected Jdbc3Statement(Jdbc3Connection connection, String sql, boolean isCallable, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(connection, sql, isCallable, rsType, rsConcurrency, rsHoldability);
    }

    public ResultSet createResultSet (Query originalQuery, Field[] fields, List tuples, ResultCursor cursor)
    throws SQLException
    {
        Jdbc3ResultSet newResult = new Jdbc3ResultSet(originalQuery, this, fields, tuples, cursor,
                                   getMaxRows(), getMaxFieldSize(),
                                   getResultSetType(), getResultSetConcurrency(), getResultSetHoldability());
        newResult.setFetchSize(getFetchSize());
        newResult.setFetchDirection(getFetchDirection());
        return newResult;
    }

    public ParameterMetaData createParameterMetaData(BaseConnection conn, int oids[]) throws SQLException
    {
        return new Jdbc3ParameterMetaData(conn, oids);
    }

}
