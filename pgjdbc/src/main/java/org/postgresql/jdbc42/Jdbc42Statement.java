/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc42;

import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Field;
import org.postgresql.core.Query;
import org.postgresql.core.ResultCursor;
import org.postgresql.jdbc4.AbstractJdbc4Connection;

public class Jdbc42Statement extends AbstractJdbc42Statement
{

    Jdbc42Statement(AbstractJdbc4Connection c, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(c, rsType, rsConcurrency, rsHoldability);
    }

    Jdbc42Statement(AbstractJdbc42Connection connection, String sql, boolean isCallable, int rsType, int rsConcurrency, int rsHoldability) throws SQLException
    {
        super(connection, sql, isCallable, rsType, rsConcurrency, rsHoldability);
    }

    public ResultSet createResultSet (Query originalQuery, Field[] fields, List tuples, ResultCursor cursor)
    throws SQLException
    {
        Jdbc42ResultSet newResult = new Jdbc42ResultSet(originalQuery,
                                                        this,
                                                        fields,
                                                        tuples,
                                                        cursor,
                                                        getMaxRows(),
                                                        getMaxFieldSize(),
                                                        getResultSetType(),
                                                        getResultSetConcurrency(),
                                                        getResultSetHoldability());
        newResult.setFetchSize(getFetchSize());
        newResult.setFetchDirection(getFetchDirection());
        return newResult;
    }

    public ParameterMetaData createParameterMetaData(BaseConnection conn, int oids[]) throws SQLException
    {
        return new Jdbc42ParameterMetaData(conn, oids);
    }
}
