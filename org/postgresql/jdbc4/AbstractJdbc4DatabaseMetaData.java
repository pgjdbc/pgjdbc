/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL$
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.sql.*;

public class AbstractJdbc4DatabaseMetaData extends org.postgresql.jdbc3.AbstractJdbc3DatabaseMetaData
{

    public AbstractJdbc4DatabaseMetaData(AbstractJdbc4Connection conn)
    {
        super(conn);
    }

    public RowIdLifetime getRowIdLifetime() throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getRowIdLifetime()");
    }

    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getSchemas(String, String)");
    }

    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException
    {
        return true;
    }

    public boolean autoCommitFailureClosesAllResultSets() throws SQLException
    {
        return false;
    }

    public ResultSet getClientInfoProperties() throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getClientInfoProperties()");
    }

    public boolean providesQueryObjectGenerator() throws SQLException
    {
        return false;
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "isWrapperFor(Class<?>)");
    }

    public Object unwrap(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "unwrap(Class<?>)");
    }

}
