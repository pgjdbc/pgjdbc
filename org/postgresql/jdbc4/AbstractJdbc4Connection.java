/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/jdbc4/AbstractJdbc4Connection.java,v 1.1 2006/06/08 10:34:51 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.sql.*;
import java.util.Properties;

abstract class AbstractJdbc4Connection extends org.postgresql.jdbc3.AbstractJdbc3Connection
{
    public AbstractJdbc4Connection(String host, int port, String user, String database, Properties info, String url) throws SQLException {
        super(host, port, user, database, info, url);
    }

    public Clob createClob() throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "createClob()");
    }

    public Blob createBlob() throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "createBlob()");
    }

    public NClob createNClob() throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "createNClob()");
    }

    public SQLXML createSQLXML() throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "createSQLXML()");
    }

    public Struct createStruct(String typeName, Object[] attributes) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "createStruct(String, Object[])");
    }

    public Array createArrayOf(String typeName, Object[] elements) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "createArrayOf(String, Object[])");
    }

    public boolean isValid(int timeout) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "isValid(int)");
    }

    public void setClientInfo(String name, String value) throws SQLClientInfoException
    {
        throw new SQLClientInfoException("Not implemented.", null);
    }

    public void setClientInfo(Properties properties) throws SQLClientInfoException
    {
        throw new SQLClientInfoException("Not implemented.", null);
    }

    public String getClientInfo(String name) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getClientInfo(String)");
    }

    public Properties getClientInfo() throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getClientInfo()");
    }

    public <T> T createQueryObject(Class<T> ifc) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "createQueryObject(Class<T>)");
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "isWrapperFor(Class<?>)");
    }

    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "unwrap(Class<T>)");
    }


}
