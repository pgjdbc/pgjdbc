/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.sql.*;
import java.util.Map;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.postgresql.core.Oid;
import org.postgresql.core.Utils;
import org.postgresql.core.TypeInfo;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLState;
import org.postgresql.util.PSQLException;
import org.postgresql.jdbc2.AbstractJdbc2Array;

abstract class AbstractJdbc4Connection extends org.postgresql.jdbc3g.AbstractJdbc3gConnection
{
    private static final SQLPermission SQL_PERMISSION_ABORT = new SQLPermission("callAbort");

    /**
     * Pattern used to unquote the result of {@link #getSchema()}
     */
    private static final Pattern PATTERN_GET_SCHEMA = Pattern.compile("^\\\"(.*)\\\"(?!\\\")");

    private final Properties _clientInfo;

    public AbstractJdbc4Connection(HostSpec[] hostSpecs, String user, String database, Properties info, String url) throws SQLException {
        super(hostSpecs, user, database, info, url);

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
        throw org.postgresql.Driver.notImplemented(this.getClass(), "createClob()");
    }

    public Blob createBlob() throws SQLException
    {
        checkClosed();
        throw org.postgresql.Driver.notImplemented(this.getClass(), "createBlob()");
    }

    public NClob createNClob() throws SQLException
    {
        checkClosed();
        throw org.postgresql.Driver.notImplemented(this.getClass(), "createNClob()");
    }

    public SQLXML createSQLXML() throws SQLException
    {
        checkClosed();
        return new Jdbc4SQLXML(this);
    }

    public Struct createStruct(String typeName, Object[] attributes) throws SQLException
    {
        checkClosed();
        throw org.postgresql.Driver.notImplemented(this.getClass(), "createStruct(String, Object[])");
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
        if (isClosed()) {
            return false;
        }
    	if (timeout < 0) {
            throw new PSQLException(GT.tr("Invalid timeout ({0}<0).", timeout), PSQLState.INVALID_PARAMETER_VALUE);
        }
    	boolean valid = false;
        Statement stmt = null;
		ResultSet rs;
    	try {
    		if (!isClosed()) {
            	stmt = createStatement();
            	stmt.setQueryTimeout( timeout );
            	rs = stmt.executeQuery( "SELECT 1" );
				rs.close();
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
        try
        {
            checkClosed();
        }
        catch (final SQLException cause)
        {
            Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
            failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
            throw new SQLClientInfoException(GT.tr("This connection has been closed."),
                                             failures,
                                             cause);
        }

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

        addWarning(new SQLWarning(GT.tr("ClientInfo property not supported."), PSQLState.NOT_IMPLEMENTED.getState()));
    }

    public void setClientInfo(Properties properties) throws SQLClientInfoException
    {
        try
        {
            checkClosed();
        }
        catch (final SQLException cause)
        {
            Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
            for (Entry<Object, Object> e : properties.entrySet())
            {
                failures.put((String) e.getKey(), ClientInfoStatus.REASON_UNKNOWN);
            }
            throw new SQLClientInfoException(GT.tr("This connection has been closed."),
                                             failures,
                                             cause);
        }

        Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
        for (String name : new String[] { "ApplicationName" })
        {
            try
            {
                setClientInfo(name, properties.getProperty(name, null));
            }
            catch (SQLClientInfoException e)
            {
                failures.putAll(e.getFailedProperties());
            }
        }

        if (!failures.isEmpty())
            throw new SQLClientInfoException(GT.tr("One ore more ClientInfo failed."), PSQLState.NOT_IMPLEMENTED.getState(), failures);
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
        throw org.postgresql.Driver.notImplemented(this.getClass(), "createQueryObject(Class<T>)");
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException
    {
        checkClosed();
        return iface.isAssignableFrom(getClass());
    }

    public <T> T unwrap(Class<T> iface) throws SQLException
    {
        checkClosed();
        if (iface.isAssignableFrom(getClass()))
        {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    public String getSchema() throws SQLException
    {
        checkClosed();
        String searchPath;
        Statement stmt = createStatement();
        try
        {
            ResultSet rs = stmt.executeQuery( "SHOW search_path");
            try
            {
                if (!rs.next())
                {
                    return null;
                }
                searchPath = rs.getString(1);
            }
            finally
            {
                rs.close();
            }
        }
        finally
        {
            stmt.close();
        }

        if (searchPath.startsWith("\""))
        {
            // unquote the result if it's a quoted string
            Matcher matcher = PATTERN_GET_SCHEMA.matcher(searchPath);
            matcher.find();
            return matcher.group(1).replaceAll("\"\"", "\"");
        }
        else
        {
            // keep only the first schema of the search path if there are many
            int commaIndex = searchPath.indexOf(',');
            if (commaIndex == -1)
            {
                return searchPath;
            }
            else
            {
                return searchPath.substring(0, commaIndex);
            }
        }
    }

    public void abort(Executor executor) throws SQLException
    {
        if (isClosed())
        {
            return;
        }

        SQL_PERMISSION_ABORT.checkGuard(this);

        AbortCommand command = new AbortCommand();
        if (executor != null)
        {
            executor.execute(command);
        }
        else
        {
            command.run();
        }
    }

    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "setNetworkTimeout(Executor, int)");
    }

    public int getNetworkTimeout() throws SQLException {
        throw org.postgresql.Driver.notImplemented(this.getClass(), "getNetworkTimeout()");
    }

    public class AbortCommand implements Runnable
    {
        public void run()
        {
            abort();
        }
    }

}
