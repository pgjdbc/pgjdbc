/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;

import java.sql.*;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.Executor;

import legacy.org.postgresql.Driver;
import legacy.org.postgresql.core.Oid;
import legacy.org.postgresql.core.TypeInfo;
import legacy.org.postgresql.core.Utils;
import legacy.org.postgresql.jdbc2.AbstractJdbc2Array;
import legacy.org.postgresql.jdbc3g.AbstractJdbc3gConnection;
import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PSQLException;
import legacy.org.postgresql.util.PSQLState;

abstract class AbstractJdbc4Connection extends AbstractJdbc3gConnection
{
    private final Properties _clientInfo;

    public AbstractJdbc4Connection(String host, int port, String user, String database, Properties info, String url) throws SQLException {
        super(host, port, user, database, info, url);

        TypeInfo types = getTypeInfo();
        if (haveMinimumServerVersion("8.3")) {
            types.addCoreType("xml", Oid.XML, java.sql.Types.SQLXML, "java.sql.SQLXML", Oid.XML_ARRAY);
        }

        _clientInfo = new Properties();
        if (haveMinimumServerVersion("9.0")) {
            String appName = info.getProperty("ApplicationName");
            if (appName == null) {
                appName = "";
            }
            _clientInfo.put("ApplicationName", appName);
        }
    }

    public Clob createClob() throws SQLException
    {
        checkClosed();
        throw Driver.notImplemented(this.getClass(), "createClob()");
    }

    public Blob createBlob() throws SQLException
    {
        checkClosed();
        throw Driver.notImplemented(this.getClass(), "createBlob()");
    }

    public NClob createNClob() throws SQLException
    {
        checkClosed();
        throw Driver.notImplemented(this.getClass(), "createNClob()");
    }

    public SQLXML createSQLXML() throws SQLException
    {
        checkClosed();
        return new Jdbc4SQLXML(this);
    }

    public Struct createStruct(String typeName, Object[] attributes) throws SQLException
    {
        checkClosed();
        throw Driver.notImplemented(this.getClass(), "createStruct(String, Object[])");
    }

    public Array createArrayOf(String typeName, Object[] elements) throws SQLException
    {
        checkClosed();
        int oid = getTypeInfo().getPGArrayType(typeName);
        if (oid == Oid.UNSPECIFIED)
            throw new PSQLException(GT.tr("Unable to find server array type for provided name {0}.", typeName), PSQLState.INVALID_NAME);

        char delim = getTypeInfo().getArrayDelimiter(oid);
        StringBuffer sb = new StringBuffer();
        appendArray(sb, elements, delim);

        // This will not work once we have a JDBC 5,
        // but it'll do for now.
        return new Jdbc4Array(this, oid, sb.toString());
    }

    private static void appendArray(StringBuffer sb, Object elements, char delim)
    {
        sb.append('{');

        int nElements = java.lang.reflect.Array.getLength(elements);
        for (int i=0; i<nElements; i++) {
            if (i > 0) {
                sb.append(delim);
            }

            Object o = java.lang.reflect.Array.get(elements, i);
            if (o == null) {
                sb.append("NULL");
            } else if (o.getClass().isArray()) {
                appendArray(sb, o, delim);
            } else {
                String s = o.toString();
                AbstractJdbc2Array.escapeArrayElement(sb, s);
            }
        }
        sb.append('}');
    }

    public boolean isValid(int timeout) throws SQLException
    {
        checkClosed();
    	if (timeout < 0) {
            throw new PSQLException(GT.tr("Invalid timeout ({0}<0).", timeout), PSQLState.INVALID_PARAMETER_VALUE);
        }
    	boolean valid = false;
	    Statement stmt = null;
    	try {
    		if (!isClosed()) {
            	stmt = createStatement();
            	stmt.setQueryTimeout( timeout );
            	stmt.executeQuery( "SELECT 1" );
            	valid = true;
    	    }
    	}
    	catch ( SQLException e) {
    		getLogger().log(GT.tr("Validating connection."),e);
    	}
    	finally
    	{
    		if(stmt!=null) try {stmt.close();}catch(Exception ex){}
    	}
        return valid;    
}

    public void setClientInfo(String name, String value) throws SQLClientInfoException
    {
        if (haveMinimumServerVersion("9.0") && "ApplicationName".equals(name)) {
            if (value == null)
                value = "";

            try {
                StringBuffer sql = new StringBuffer("SET application_name = '");
                Utils.appendEscapedLiteral(sql, value, getStandardConformingStrings());
                sql.append("'");
                execSQLUpdate(sql.toString());
            } catch (SQLException sqle) {
                Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
                failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
                throw new SQLClientInfoException(GT.tr("Failed to set ClientInfo property: {0}", "ApplicationName"), sqle.getSQLState(), failures, sqle);
            }

            _clientInfo.put(name, value);
            return;
        }

        Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
        failures.put(name, ClientInfoStatus.REASON_UNKNOWN_PROPERTY);
        throw new SQLClientInfoException(GT.tr("ClientInfo property not supported."), PSQLState.NOT_IMPLEMENTED.getState(), failures);
    }

    public void setClientInfo(Properties properties) throws SQLClientInfoException
    {
        if (properties == null || properties.size() == 0)
            return;

        Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();

        Iterator<String> i = properties.stringPropertyNames().iterator();
        while (i.hasNext()) {
            String name = i.next();
            if (haveMinimumServerVersion("9.0") && "ApplicationName".equals(name)) {
                String value = properties.getProperty(name);
                setClientInfo(name, value);
            } else {
                failures.put(i.next(), ClientInfoStatus.REASON_UNKNOWN_PROPERTY);
            }
        }

        if (!failures.isEmpty())
            throw new SQLClientInfoException(GT.tr("ClientInfo property not supported."), PSQLState.NOT_IMPLEMENTED.getState(), failures);
    }

    public String getClientInfo(String name) throws SQLException
    {
        checkClosed();
        return _clientInfo.getProperty(name);
    }

    public Properties getClientInfo() throws SQLException
    {
        checkClosed();
        return _clientInfo;
    }

    public <T> T createQueryObject(Class<T> ifc) throws SQLException
    {
        checkClosed();
        throw Driver.notImplemented(this.getClass(), "createQueryObject(Class<T>)");
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        checkClosed();
        throw Driver.notImplemented(this.getClass(), "isWrapperFor(Class<?>)");
    }

    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        checkClosed();
        throw Driver.notImplemented(this.getClass(), "unwrap(Class<T>)");
    }

    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException
    {
        throw Driver.notImplemented(this.getClass(), "getParentLogger()");
    }

    public void setSchema(String schema) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "setSchema(String)");
    }

    public String getSchema() throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "getSchema()");
    }

    public void abort(Executor executor) throws SQLException
    {
        throw Driver.notImplemented(this.getClass(), "abort(Executor)");
    }

    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        throw Driver.notImplemented(this.getClass(), "setNetworkTimeout(Executor, int)");
    }

    public int getNetworkTimeout() throws SQLException {
        throw Driver.notImplemented(this.getClass(), "getNetworkTimeout()");
    }

}
