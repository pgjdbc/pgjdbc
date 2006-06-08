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

    public boolean isValid(int timeout) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "isValid(int)");
    }

    public void setClientInfo(String name, String value) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setClientInfo(String, String)");
    }

    public void setClientInfo(Properties properties) throws ClientInfoException
    {
        // KJJ
        throw new ClientInfoException("notImplemented - Connection.setClientInfo(Properties)", null);
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

    public Object unwrap(Class<?> iface) throws SQLException
    {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "unwrap(Class<?>)");
    }


}
