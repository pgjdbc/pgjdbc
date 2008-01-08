/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc4/AbstractJdbc4DatabaseMetaData.java,v 1.5 2007/10/07 19:40:02 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.sql.*;

import java.util.Vector;
import org.postgresql.core.*;

public abstract class AbstractJdbc4DatabaseMetaData extends org.postgresql.jdbc3.AbstractJdbc3DatabaseMetaData
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
        Field f[] = new Field[4];
        f[0] = new Field("NAME", Oid.VARCHAR);
        f[1] = new Field("MAX_LEN", Oid.INT4);
        f[2] = new Field("DEFAULT_VALUE", Oid.VARCHAR);
        f[3] = new Field("DESCRIPTION", Oid.VARCHAR);

        Vector v = new Vector();
        // Currently we don't support any properties.
        return (ResultSet) ((BaseStatement)createMetaDataStatement()).createDriverResultSet(f, v);
    }

    public boolean providesQueryObjectGenerator() throws SQLException
    {
        return false;
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "isWrapperFor(Class<?>)");
    }

    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "unwrap(Class<T>)");
    }

    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getFunction(String, String, String)");
    }

    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getFunctionColumns(String, String, String, String)");
    }

    public int getJDBCMajorVersion() throws SQLException
    {
        return 4;
    }

    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException
    {
        return getColumns(4, catalog, schemaPattern, tableNamePattern, columnNamePattern);
    }

}
