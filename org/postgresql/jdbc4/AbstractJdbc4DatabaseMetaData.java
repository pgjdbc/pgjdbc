/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.sql.*;

import java.util.ArrayList;
import java.util.List;
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
        return getSchemas(4, catalog, schemaPattern);
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

        List v = new ArrayList();

        if (connection.haveMinimumServerVersion("9.0")) {
            byte[][] tuple = new byte[4][];
            tuple[0] = connection.encodeString("ApplicationName");
            tuple[1] = connection.encodeString(Integer.toString(getMaxNameLength()));
            tuple[2] = connection.encodeString("");
            tuple[3] = connection.encodeString("The name of the application currently utilizing the connection.");
            v.add(tuple);
        }

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

    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException
    {
        return getProcedures(4, catalog, schemaPattern, procedureNamePattern);
    }

    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException
    {
        return getProcedureColumns(4, catalog, schemaPattern, procedureNamePattern, columnNamePattern);
    }

    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getPseudoColumns(String, String, String, String)");
    }

    public boolean generatedKeyAlwaysReturned() throws SQLException {
        return true;
    }

}
