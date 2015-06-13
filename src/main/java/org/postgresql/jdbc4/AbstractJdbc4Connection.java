/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc4;

import java.sql.Array;
import java.sql.Blob;
import java.sql.ClientInfoStatus;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLPermission;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.Executor;

import org.postgresql.PGProperty;
import org.postgresql.core.Oid;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TypeInfo;
import org.postgresql.core.Utils;
import org.postgresql.jdbc2.AbstractJdbc2Array;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

public abstract class AbstractJdbc4Connection extends org.postgresql.jdbc3g.AbstractJdbc3gConnection
{
    private static final SQLPermission SQL_PERMISSION_ABORT = new SQLPermission("callAbort");

    private final Properties _clientInfo;

    public AbstractJdbc4Connection(HostSpec[] hostSpecs, String user, String database, Properties info, String url) throws SQLException {
        super(hostSpecs, user, database, info, url);

        TypeInfo types = getTypeInfo();
        if (haveMinimumServerVersion(ServerVersion.v8_3)) {
            types.addCoreType("xml", Oid.XML, java.sql.Types.SQLXML, "java.sql.SQLXML", Oid.XML_ARRAY);
        }

        _clientInfo = new Properties();
        if (haveMinimumServerVersion(ServerVersion.v9_0)) {
            String appName = PGProperty.APPLICATION_NAME.get(info);
            if (appName == null) {
                appName = "";
            }
            _clientInfo.put("ApplicationName", appName);
        }
    }

    protected abstract Array makeArray(int oid, String fieldString) throws SQLException;
    protected abstract Clob makeClob(long oid) throws SQLException;
    protected abstract Blob makeBlob(long oid) throws SQLException;
    protected abstract SQLXML makeSQLXML() throws SQLException;

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
        return makeSQLXML();
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
        StringBuilder sb = new StringBuilder();
        appendArray(sb, elements, delim);

        return makeArray(oid, sb.toString());
    }

    private static void appendArray(StringBuilder sb, Object elements, char delim)
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
    	try {
    		if (!isClosed()) {
            	stmt = createStatement();
            	stmt.setQueryTimeout( timeout );
            	stmt.executeUpdate( "" );
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

        if (haveMinimumServerVersion(ServerVersion.v9_0) && "ApplicationName".equals(name)) {
            if (value == null)
                value = "";

            try {
                StringBuilder sql = new StringBuilder("SET application_name = '");
                Utils.escapeLiteral(sql, value, getStandardConformingStrings());
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
        Statement stmt = createStatement();
        try
        {
            ResultSet rs = stmt.executeQuery( "select current_schema()");
            try
            {
                if (!rs.next())
                {
                    return null; // Is it ever possible?
                }
                return rs.getString(1);
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
    }

    public void setSchema(String schema) throws SQLException
    {
        checkClosed();
        Statement stmt = createStatement();
        try
        {
            if (schema == null)
            {
                stmt.executeUpdate("SET SESSION search_path TO DEFAULT");
            } else
            {
                StringBuilder sb = new StringBuilder();
                sb.append("SET SESSION search_path TO '");
                Utils.escapeLiteral(sb, schema, getStandardConformingStrings());
                sb.append("'");
                stmt.executeUpdate(sb.toString());
            }
        }
        finally
        {
            stmt.close();
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
