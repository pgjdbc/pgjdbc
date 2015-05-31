/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.ds.common;

import javax.naming.*;

import org.postgresql.PGProperty;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;

/**
 * Base class for data sources and related classes.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public abstract class BaseDataSource implements Referenceable
{
    // Load the normal driver, since we'll use it to actually connect to the
    // database.  That way we don't have to maintain the connecting code in
    // multiple places.
    static {
        try
        {
            Class.forName("org.postgresql.Driver");
        }
        catch (ClassNotFoundException e)
        {
            System.err.println("PostgreSQL DataSource unable to load PostgreSQL JDBC Driver");
        }
    }

    // Needed to implement the DataSource/ConnectionPoolDataSource interfaces
    private transient PrintWriter logger;

    // Standard properties, defined in the JDBC 2.0 Optional Package spec
    private String serverName = "localhost";
    private String databaseName = "";
    private String user;
    private String password;
    private int portNumber = 0;

    // Map for all other properties
    private Properties properties = new Properties();

    /**
     * Gets a connection to the PostgreSQL database.  The database is identified by the
     * DataSource properties serverName, databaseName, and portNumber. The user to
     * connect as is identified by the DataSource properties user and password.
     *
     * @return A valid database connection.
     * @throws SQLException
     *     Occurs when the database connection cannot be established.
     */
    public Connection getConnection() throws SQLException
    {
        return getConnection(user, password);
    }

    /**
     * Gets a connection to the PostgreSQL database.  The database is identified by the
     * DataSource properties serverName, databaseName, and portNumber. The user to
     * connect as is identified by the arguments user and password, which override
     * the DataSource properties by the same name.
     *
     * @return A valid database connection.
     * @throws SQLException
     *     Occurs when the database connection cannot be established.
     */
    public Connection getConnection(String user, String password) throws SQLException
    {
        try
        {
            Connection con = DriverManager.getConnection(getUrl(), user, password);
            if (logger != null)
            {
                logger.println("Created a non-pooled connection for " + user + " at " + getUrl());
            }
            return con;
        }
        catch (SQLException e)
        {
            if (logger != null)
            {
                logger.println("Failed to create a non-pooled connection for " + user + " at " + getUrl() + ": " + e);
            }
            throw e;
        }
    }

    /**
     * Gets the log writer used to log connections opened.
     */
    public PrintWriter getLogWriter()
    {
        return logger;
    }

    /**
     * The DataSource will note every connection opened to the provided log writer.
     */
    public void setLogWriter(PrintWriter printWriter)
    {
        logger = printWriter;
    }

    /**
     * Gets the name of the host the PostgreSQL database is running on.
     */
    public String getServerName()
    {
        return serverName;
    }

    /**
     * Sets the name of the host the PostgreSQL database is running on.  If this
     * is changed, it will only affect future calls to getConnection.  The default
     * value is <tt>localhost</tt>.
     */
    public void setServerName(String serverName)
    {
        if (serverName == null || serverName.equals(""))
        {
            this.serverName = "localhost";
        }
        else
        {
            this.serverName = serverName;
        }
    }

    /**
     * Gets the name of the PostgreSQL database, running on the server identified
     * by the serverName property.
     */
    public String getDatabaseName()
    {
        return databaseName;
    }

    /**
     * Sets the name of the PostgreSQL database, running on the server identified
     * by the serverName property. If this is changed, it will only affect
     * future calls to getConnection.
     */
    public void setDatabaseName(String databaseName)
    {
        this.databaseName = databaseName;
    }

    /**
     * Gets a description of this DataSource-ish thing.  Must be customized by
     * subclasses.
     */
    public abstract String getDescription();

    /**
     * Gets the user to connect as by default. If this is not specified, you must
     * use the getConnection method which takes a user and password as parameters.
     */
    public String getUser()
    {
        return user;
    }

    /**
     * Sets the user to connect as by default. If this is not specified, you must
     * use the getConnection method which takes a user and password as parameters.
     * If this is changed, it will only affect future calls to getConnection.
     */
    public void setUser(String user)
    {
        this.user = user;
    }

    /**
     * Gets the password to connect with by default.  If this is not specified but a
     * password is needed to log in, you must use the getConnection method which takes
     * a user and password as parameters.
     */
    public String getPassword()
    {
        return password;
    }

    /**
     * Sets the password to connect with by default.  If this is not specified but a
     * password is needed to log in, you must use the getConnection method which takes
     * a user and password as parameters.  If this is changed, it will only affect
     * future calls to getConnection.
     */
    public void setPassword(String password)
    {
        this.password = password;
    }

    /**
     * Gets the port which the PostgreSQL server is listening on for TCP/IP
     * connections.
     *
     * @return The port, or 0 if the default port will be used.
     */
    public int getPortNumber()
    {
        return portNumber;
    }

    /**
     * Gets the port which the PostgreSQL server is listening on for TCP/IP
     * connections.  Be sure the -i flag is passed to postmaster when PostgreSQL
     * is started. If this is not set, or set to 0, the default port will be used.
     */
    public void setPortNumber(int portNumber)
    {
        this.portNumber = portNumber;
    }

    /**
     * @see PGProperty#COMPATIBLE 
     */
    public String getCompatible()
    {
        return PGProperty.COMPATIBLE.get(properties);
    }

    /**
     * @see PGProperty#COMPATIBLE 
     */
    public void setCompatible(String compatible)
    {
        PGProperty.COMPATIBLE.set(properties, compatible);
    }

    /**
     * @see PGProperty#LOGIN_TIMEOUT 
     */
    public int getLoginTimeout()
    {
        return PGProperty.LOGIN_TIMEOUT.getIntNoCheck(properties);
    }

    /**
     * @see PGProperty#LOGIN_TIMEOUT 
     */
    public void setLoginTimeout(int loginTimeout)
    {
        PGProperty.LOGIN_TIMEOUT.set(properties, loginTimeout);
    }

    /**
     * @see PGProperty#CONNECT_TIMEOUT 
     */
    public int getConnectTimeout()
    {
        return PGProperty.CONNECT_TIMEOUT.getIntNoCheck(properties);
    }

    /**
     * @see PGProperty#CONNECT_TIMEOUT 
     */
    public void setConnectTimeout(int connectTimeout)
    {
        PGProperty.CONNECT_TIMEOUT.set(properties, connectTimeout);
    }

    /**
     * @see PGProperty#LOG_LEVEL 
     */
    public int getLogLevel()
    {
        return PGProperty.LOG_LEVEL.getIntNoCheck(properties);
    }

    /**
     * @see PGProperty#LOG_LEVEL 
     */
    public void setLogLevel(int logLevel)
    {
        PGProperty.LOG_LEVEL.set(properties, logLevel);
    }

    /**
     * @see PGProperty#PROTOCOL_VERSION 
     */
    public int getProtocolVersion()
    {
        if (!PGProperty.PROTOCOL_VERSION.isPresent(properties))
        {
            return 0;
        }
        else
        {
            return PGProperty.PROTOCOL_VERSION.getIntNoCheck(properties);
        }
    }

    /**
     * @see PGProperty#PROTOCOL_VERSION 
     */
    public void setProtocolVersion(int protocolVersion)
    {
        if (protocolVersion == 0)
        {
            PGProperty.PROTOCOL_VERSION.set(properties, null);
        }
        else
        {
            PGProperty.PROTOCOL_VERSION.set(properties, protocolVersion);
        }
    }

    /**
     * @see PGProperty#RECEIVE_BUFFER_SIZE 
     */
    public int getReceiveBufferSize()
    {
        return PGProperty.RECEIVE_BUFFER_SIZE.getIntNoCheck(properties);
    }

    /**
     * @see PGProperty#RECEIVE_BUFFER_SIZE 
     */
    public void setReceiveBufferSize(int nbytes)
    {
        PGProperty.RECEIVE_BUFFER_SIZE.set(properties, nbytes);
    }

    /**
     * @see PGProperty#SEND_BUFFER_SIZE 
     */
    public int getSendBufferSize()
    {
        return PGProperty.SEND_BUFFER_SIZE.getIntNoCheck(properties);
    }

    /**
     * @see PGProperty#SEND_BUFFER_SIZE 
     */
    public void setSendBufferSize(int nbytes)
    {
        PGProperty.SEND_BUFFER_SIZE.set(properties, nbytes);
    }

    /**
     * @see PGProperty#PREPARE_THRESHOLD 
     */
    public void setPrepareThreshold(int count)
    {
        PGProperty.PREPARE_THRESHOLD.set(properties, count);
    }

    /**
     * @see PGProperty#PREPARE_THRESHOLD 
     */
    public int getPrepareThreshold()
    {
        return PGProperty.PREPARE_THRESHOLD.getIntNoCheck(properties);
    }

    /**
     * @see PGProperty#DEFAULT_ROW_FETCH_SIZE
     */
    public void setDefaultRowFetchSize(int fetchSize)
    {
        PGProperty.DEFAULT_ROW_FETCH_SIZE.set(properties, fetchSize);
    }

    /**
     * @see PGProperty#DEFAULT_ROW_FETCH_SIZE
     */
    public int getDefaultRowFetchSize()
    {
        return PGProperty.DEFAULT_ROW_FETCH_SIZE.getIntNoCheck(properties);
    }

    /**
     * @see PGProperty#UNKNOWN_LENGTH 
     */
    public void setUnknownLength(int unknownLength)
    {
        PGProperty.UNKNOWN_LENGTH.set(properties, unknownLength);
    }

    /**
     * @see PGProperty#UNKNOWN_LENGTH 
     */
    public int getUnknownLength()
    {
        return PGProperty.UNKNOWN_LENGTH.getIntNoCheck(properties);
    }

    /**
     * @see PGProperty#SOCKET_TIMEOUT 
     */
    public void setSocketTimeout(int seconds)
    {
        PGProperty.SOCKET_TIMEOUT.set(properties, seconds);
    }

    /**
     * @see PGProperty#SOCKET_TIMEOUT 
     */
    public int getSocketTimeout() 
    {
        return PGProperty.SOCKET_TIMEOUT.getIntNoCheck(properties);
    }


    /**
     * @see PGProperty#SSL 
     */
    public void setSsl(boolean enabled)
    {
        if (enabled)
        {
            PGProperty.SSL.set(properties, true);
        }
        else
        {
            PGProperty.SSL.set(properties, null);
        }
    }

    /**
     * @see PGProperty#SSL
     */
    public boolean getSsl()
    {
        return PGProperty.SSL.isPresent(properties);
    }

    /**
     * @see PGProperty#SSL_FACTORY
     */
    public void setSslfactory(String classname)
    {
        PGProperty.SSL_FACTORY.set(properties, classname);
    }

    /**
     * @see PGProperty#SSL_FACTORY
     */
    public String getSslfactory()
    {
        return PGProperty.SSL_FACTORY.get(properties);
    }

    /**
     * @see PGProperty#SSL_MODE
     */
    public String getSslMode()
    {
        return PGProperty.SSL_MODE.get(properties);
    }

    /**
     * @see PGProperty#SSL_MODE
     */
    public void setSslMode(String mode)
    {
        PGProperty.SSL_MODE.set(properties, mode);
    }

    /**
     * @see PGProperty#SSL_FACTORY_ARG
     */
    public String getSslFactoryArg()
    {
        return PGProperty.SSL_FACTORY_ARG.get(properties);
    }

    /**
     * @see PGProperty#SSL_FACTORY_ARG
     */
    public void setSslFactoryArg(String arg)
    {
        PGProperty.SSL_FACTORY_ARG.set(properties, arg);
    }

    /**
     * @see PGProperty#SSL_HOSTNAME_VERIFIER
     */
    public String getSslHostnameVerifier()
    {
        return PGProperty.SSL_HOSTNAME_VERIFIER.get(properties);
    }

    /**
     * @see PGProperty#SSL_HOSTNAME_VERIFIER
     */
    public void setSslHostnameVerifier(String className)
    {
        PGProperty.SSL_HOSTNAME_VERIFIER.set(properties, className);
    }

    /**
     * @see PGProperty#SSL_CERT
     */
    public String getSslCert()
    {
        return PGProperty.SSL_CERT.get(properties);
    }

    /**
     * @see PGProperty#SSL_CERT
     */
    public void setSslCert(String file)
    {
        PGProperty.SSL_CERT.set(properties, file);
    }

    /**
     * @see PGProperty#SSL_KEY
     */
    public String getSslKey()
    {
        return PGProperty.SSL_KEY.get(properties);
    }

    /**
     * @see PGProperty#SSL_KEY
     */
    public void setSslKey(String file)
    {
        PGProperty.SSL_KEY.set(properties, file);
    }

    /**
     * @see PGProperty#SSL_ROOT_CERT
     */
    public String getSslRootCert()
    {
        return PGProperty.SSL_ROOT_CERT.get(properties);
    }

    /**
     * @see PGProperty#SSL_ROOT_CERT
     */
    public void setSslRootCert(String file)
    {
        PGProperty.SSL_ROOT_CERT.set(properties, file);
    }

    /**
     * @see PGProperty#SSL_PASSWORD
     */
    public String getSslPassword()
    {
        return PGProperty.SSL_PASSWORD.get(properties);
    }

    /**
     * @see PGProperty#SSL_PASSWORD
     */
    public void setSslPassword(String password)
    {
        PGProperty.SSL_PASSWORD.set(properties, password);
    }

    /**
     * @see PGProperty#SSL_PASSWORD_CALLBACK
     */
    public String getSslPasswordCallback()
    {
        return PGProperty.SSL_PASSWORD_CALLBACK.get(properties);
    }

    /**
     * @see PGProperty#SSL_PASSWORD_CALLBACK
     */
    public void setSslPasswordCallback(String className)
    {
        PGProperty.SSL_PASSWORD_CALLBACK.set(properties, className);
    }

    /**
     * @see PGProperty#APPLICATION_NAME
     */
    public void setApplicationName(String applicationName)
    {
        PGProperty.APPLICATION_NAME.set(properties, applicationName);
    }

    /**
     * @see PGProperty#APPLICATION_NAME
     */
    public String getApplicationName()
    {
        return PGProperty.APPLICATION_NAME.get(properties);
    }

    /**
     * @see PGProperty#TARGET_SERVER_TYPE
     */
    public void setTargetServerType(String targetServerType)
    {
        PGProperty.TARGET_SERVER_TYPE.set(properties, targetServerType);
    }

    /**
     * @see PGProperty#TARGET_SERVER_TYPE
     */
    public String getTargetServerType()
    {
        return PGProperty.TARGET_SERVER_TYPE.get(properties);
    }

    /**
     * @see PGProperty#LOAD_BALANCE_HOSTS
     */
    public void setLoadBalanceHosts(boolean loadBalanceHosts)
    {
        PGProperty.LOAD_BALANCE_HOSTS.set(properties, loadBalanceHosts);
    }

    /**
     * @see PGProperty#LOAD_BALANCE_HOSTS
     */
    public boolean getLoadBalanceHosts()
    {
        return PGProperty.LOAD_BALANCE_HOSTS.isPresent(properties);
    }

    /**
     * @see PGProperty#HOST_RECHECK_SECONDS
     */
    public void setHostRecheckSeconds(int hostRecheckSeconds)
    {
        PGProperty.HOST_RECHECK_SECONDS.set(properties, hostRecheckSeconds);
    }

    /**
     * @see PGProperty#HOST_RECHECK_SECONDS
     */
    public int getHostRecheckSeconds()
    {
        return PGProperty.HOST_RECHECK_SECONDS.getIntNoCheck(properties);
    }

    /**
     * @see PGProperty#TCP_KEEP_ALIVE
     */
    public void setTcpKeepAlive(boolean enabled)
    {
        PGProperty.TCP_KEEP_ALIVE.set(properties, enabled);
    }

    /**
     * @see PGProperty#TCP_KEEP_ALIVE
     */
    public boolean getTcpKeepAlive()
    {
        return PGProperty.TCP_KEEP_ALIVE.getBoolean(properties);
    }

    /**
     * @see PGProperty#BINARY_TRANSFER
     */
    public void setBinaryTransfer(boolean enabled)
    {
        PGProperty.BINARY_TRANSFER.set(properties, enabled);
    }

    /**
     * @see PGProperty#BINARY_TRANSFER
     */
    public boolean getBinaryTransfer()
    {
        return PGProperty.BINARY_TRANSFER.getBoolean(properties);
    }

    /**
     * @see PGProperty#BINARY_TRANSFER_ENABLE
     */
    public void setBinaryTransferEnable(String oidList)
    {
        PGProperty.BINARY_TRANSFER_ENABLE.set(properties, oidList);
    }

    /**
     * @see PGProperty#BINARY_TRANSFER_ENABLE
     */
    public String getBinaryTransferEnable()
    {
        return PGProperty.BINARY_TRANSFER_ENABLE.get(properties);
    }

    /**
     * @see PGProperty#BINARY_TRANSFER_DISABLE
     */
    public void setBinaryTransferDisable(String oidList)
    {
        PGProperty.BINARY_TRANSFER_DISABLE.set(properties, oidList);
    }

    /**
     * @see PGProperty#BINARY_TRANSFER_DISABLE
     */
    public String getBinaryTransferDisable()
    {
        return PGProperty.BINARY_TRANSFER_DISABLE.get(properties);
    }

    /**
     * @see PGProperty#STRING_TYPE
     */
    public String getStringType()
    {
        return PGProperty.STRING_TYPE.get(properties);
    }

    /**
     * @see PGProperty#STRING_TYPE
     */
    public void setStringType(String stringType)
    {
        PGProperty.STRING_TYPE.set(properties, stringType);
    }

    /**
     * @see PGProperty#DISABLE_COLUMN_SANITISER
     */
    public boolean isColumnSanitiserDisabled()
    {
        return PGProperty.DISABLE_COLUMN_SANITISER.getBoolean(properties);
    }

    /**
     * @see PGProperty#DISABLE_COLUMN_SANITISER
     */
    public boolean getDisableColumnSanitiser()
    {
        return PGProperty.DISABLE_COLUMN_SANITISER.getBoolean(properties);
    }

    /**
     * @see PGProperty#DISABLE_COLUMN_SANITISER
     */
    public void setDisableColumnSanitiser(boolean disableColumnSanitiser)
    {
        PGProperty.DISABLE_COLUMN_SANITISER.set(properties, disableColumnSanitiser);
    }

    /**
     * @see PGProperty#CURRENT_SCHEMA
     */
    public String getCurrentSchema()
    {
        return PGProperty.CURRENT_SCHEMA.get(properties);
    }

    /**
     * @see PGProperty#CURRENT_SCHEMA
     */
    public void setCurrentSchema(String currentSchema)
    {
        PGProperty.CURRENT_SCHEMA.set(properties, currentSchema);
    }

    /**
     * @see PGProperty#READ_ONLY
     */
    public boolean getReadOnly()
    {
        return PGProperty.READ_ONLY.getBoolean(properties);
    }

    /**
     * @see PGProperty#READ_ONLY
     */
    public void setReadOnly(boolean readOnly)
    {
        PGProperty.READ_ONLY.set(properties, readOnly);
    }

    /**
     * @see PGProperty#LOG_UNCLOSED_CONNECTIONS
     */
    public boolean getLogUnclosedConnections()
    {
        return PGProperty.LOG_UNCLOSED_CONNECTIONS.getBoolean(properties);
    }

    /**
     * @see PGProperty#LOG_UNCLOSED_CONNECTIONS
     */
    public void setLogUnclosedConnections(boolean enabled)
    {
        PGProperty.LOG_UNCLOSED_CONNECTIONS.set(properties, enabled);
    }

    /**
     * @see PGProperty#AUTO_CLOSE_UNCLOSED_STATEMENTS
     */
    public boolean getAutoCloseUnclosedStatements()
    {
        return PGProperty.AUTO_CLOSE_UNCLOSED_STATEMENTS.getBoolean(properties);
    }

    /**
     * @see PGProperty#AUTO_CLOSE_UNCLOSED_STATEMENTS
     */
    public void setAutoCloseUnclosedStatements(boolean enable)
    {
        PGProperty.AUTO_CLOSE_UNCLOSED_STATEMENTS.set(properties, enable);
    }

    /**
     * @see PGProperty#ASSUME_MIN_SERVER_VERSION
     */
    public String getAssumeMinServerVersion()
    {
        return PGProperty.ASSUME_MIN_SERVER_VERSION.get(properties);
    }

    /**
     * @see PGProperty#ASSUME_MIN_SERVER_VERSION
     */
    public void setAssumeMinServerVersion(String minVersion)
    {
        PGProperty.ASSUME_MIN_SERVER_VERSION.set(properties, minVersion);
    }

    /**
     * @see PGProperty#JAAS_APPLICATION_NAME
     */
    public String getJaasApplicationName()
    {
        return PGProperty.JAAS_APPLICATION_NAME.get(properties);
    }

    /**
     * @see PGProperty#JAAS_APPLICATION_NAME
     */
    public void setJaasApplicationName(String name)
    {
        PGProperty.JAAS_APPLICATION_NAME.set(properties, name);
    }

    /**
     * @see PGProperty#KERBEROS_SERVER_NAME
     */
    public String getKerberosServerName()
    {
        return PGProperty.KERBEROS_SERVER_NAME.get(properties);
    }

    /**
     * @see PGProperty#KERBEROS_SERVER_NAME
     */
    public void setKerberosServerName(String serverName)
    {
        PGProperty.KERBEROS_SERVER_NAME.set(properties, serverName);
    }

    /**
     * @see PGProperty#USE_SPNEGO
     */
    public boolean getUseSpNego()
    {
        return PGProperty.USE_SPNEGO.getBoolean(properties);
    }

    /**
     * @see PGProperty#USE_SPNEGO
     */
    public void setUseSpNego(boolean use)
    {
        PGProperty.USE_SPNEGO.set(properties, use);
    }

    /**
     * @see PGProperty#GSS_LIB
     */
    public String getGssLib()
    {
        return PGProperty.GSS_LIB.get(properties);
    }

    /**
     * @see PGProperty#GSS_LIB
     */
    public void setGssLib(String lib)
    {
        PGProperty.GSS_LIB.set(properties, lib);
    }

    /**
     * @see PGProperty#SSPI_SERVICE_CLASS
     */
    public String getSspiServiceClass()
    {
        return PGProperty.SSPI_SERVICE_CLASS.get(properties);
    }

    /**
     * @see PGProperty#SSPI_SERVICE_CLASS
     */
    public void setSspiServiceClass(String serviceClass)
    {
        PGProperty.SSPI_SERVICE_CLASS.set(properties, serviceClass);
    }

    /**
     * @see PGProperty#CHARSET
     */
    public String getCharset()
    {
        return PGProperty.CHARSET.get(properties);
    }

    /**
     * @see PGProperty#CHARSET
     */
    public void setCharset(String charset)
    {
        PGProperty.CHARSET.set(properties, charset);
    }

    /**
     * @see PGProperty#ALLOW_ENCODING_CHANGES
     */
    public boolean getAllowEncodingChanges()
    {
        return PGProperty.ALLOW_ENCODING_CHANGES.getBoolean(properties);
    }

    /**
     * @see PGProperty#ALLOW_ENCODING_CHANGES
     */
    public void setAllowEncodingChanges(boolean allow)
    {
        PGProperty.ALLOW_ENCODING_CHANGES.set(properties, allow);
    }

    /**
     * Generates a DriverManager URL from the other properties supplied.
     */
    public String getUrl()
    {
        StringBuilder url = new StringBuilder(100);
        url.append("jdbc:postgresql://");
        url.append(serverName);
        if (portNumber != 0) {
            url.append(":").append(portNumber);
        }
        url.append("/").append(databaseName);

        StringBuilder query = new StringBuilder(100);
        for (PGProperty property: PGProperty.values())
        {
            if (property.isPresent(properties))
            {
                if (query.length() != 0)
                {
                    query.append("&");
                }
                query.append(property.getName());
                query.append("=");
                query.append(property.get(properties));
            }
        }

        if (query.length() > 0)
        {
            url.append("?");
            url.append(query);
        }

        return url.toString();
    }

    /**
     * Sets properties from a DriverManager URL.
     */
    public void setUrl(String url) {

        Properties p = org.postgresql.Driver.parseURL(url, null);
        
        for (PGProperty property: PGProperty.values())
        {
            setProperty(property, property.get(p));
        }
    }

    public String getProperty(String name)
      throws SQLException
    {
        PGProperty pgProperty = PGProperty.forName(name);
        if (pgProperty != null)
        {
            return getProperty(pgProperty);
        }
        else
        {
            throw new PSQLException(GT.tr("Unsupported property name: {0}", name),
                                    PSQLState.INVALID_PARAMETER_VALUE);
        }
    }

    public void setProperty(String name, String value)
      throws SQLException
    {
        PGProperty pgProperty = PGProperty.forName(name);
        if (pgProperty != null)
        {
            setProperty(pgProperty, value);
        }
        else
        {
            throw new PSQLException(GT.tr("Unsupported property name: {0}", name),
                                    PSQLState.INVALID_PARAMETER_VALUE);
        }
    }

    public String getProperty(PGProperty property)
    {
        return property.get(properties);
    }

    public void setProperty(PGProperty property, String value)
    {
        if (value == null) {
            return;
        }
        switch(property)
        {
            case PG_HOST:
                serverName = value;
                break;
            case PG_PORT:
                try
                {
                    portNumber = Integer.parseInt(value);
                }
                catch (NumberFormatException e)
                {
                    portNumber = 0;
                }
                break;
            case PG_DBNAME:
                databaseName = value;
                break;
            case USER:
                user = value;
                break;
            case PASSWORD:
                password = value;
                break;
            default:
                properties.setProperty(property.getName(), value);
        }
    }

    /**
     * Generates a reference using the appropriate object factory.
     */
    protected Reference createReference() {
        return new Reference(
                   getClass().getName(),
                   PGObjectFactory.class.getName(),
                   null);
    }

    public Reference getReference() throws NamingException
    {
        Reference ref = createReference();
        ref.add(new StringRefAddr("serverName", serverName));
        if (portNumber != 0)
        {
            ref.add(new StringRefAddr("portNumber", Integer.toString(portNumber)));
        }
        ref.add(new StringRefAddr("databaseName", databaseName));
        if (user != null)
        {
            ref.add(new StringRefAddr("user", user));
        }
        if (password != null)
        {
            ref.add(new StringRefAddr("password", password));
        }

        for (PGProperty property: PGProperty.values())
        {
            if (property.isPresent(properties))
            {
                ref.add(new StringRefAddr(property.getName(), property.get(properties)));
            }
        }

        return ref;
    }

    public void setFromReference(Reference ref)
    {
        databaseName = getReferenceProperty(ref, "databaseName");
        String port = getReferenceProperty(ref, "portNumber");
        if (port != null)
        {
            portNumber = Integer.parseInt(port);
        }
        serverName = getReferenceProperty(ref, "serverName");
        user = getReferenceProperty(ref, "user");
        password = getReferenceProperty(ref, "password");

        for (PGProperty property: PGProperty.values())
        {
            property.set(properties, getReferenceProperty(ref, property.getName()));
        }
    }

    private String getReferenceProperty(Reference ref, String propertyName)
    {
        RefAddr addr = ref.get(propertyName);
        if (addr == null)
        {
            return null;
        }
        return (String)addr.getContent();
    }

    protected void writeBaseObject(ObjectOutputStream out) throws IOException
    {
        out.writeObject(serverName);
        out.writeObject(databaseName);
        out.writeObject(user);
        out.writeObject(password);
        out.writeInt(portNumber);

        out.writeObject(properties);
    }

    protected void readBaseObject(ObjectInputStream in) throws IOException, ClassNotFoundException
    {
        serverName = (String)in.readObject();
        databaseName = (String)in.readObject();
        user = (String)in.readObject();
        password = (String)in.readObject();
        portNumber = in.readInt();

        properties = (Properties)in.readObject();
    }

    public void initializeFrom(BaseDataSource source) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        source.writeBaseObject(oos);
        oos.close();
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bais);
        readBaseObject(ois);
    }

    public void setLoglevel(int logLevel)
    {
        PGProperty.LOG_LEVEL.set(properties, logLevel);
    }

    public int getLoglevel() {
        return PGProperty.LOG_LEVEL.getIntNoCheck(properties);
    }
}
