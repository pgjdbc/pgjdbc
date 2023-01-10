/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.ds.common;

import legacy.org.postgresql.PGConnection;

import javax.naming.*;
import java.sql.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

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
            Class.forName("legacy.org.postgresql.Driver");
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
    private String databaseName;
    private String user;
    private String password;
    private int portNumber = 0;
    private int prepareThreshold = 5;
    private int unknownLength = Integer.MAX_VALUE;
    private int loginTimeout = 0; // in seconds
    private int socketTimeout = 0; // in seconds
    private boolean ssl = false;
    private String sslfactory;
    private boolean tcpKeepAlive = false;
    private String compatible;
    private int logLevel = 0;
    private int protocolVersion = 0;
    private String applicationName;

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
     * @return the login timeout, in seconds.
     */
    public int getLoginTimeout() throws SQLException
    {
        return loginTimeout;
    }

    /**
     * Set the login timeout, in seconds.
     */
    public void setLoginTimeout(int i) throws SQLException
    {
        this.loginTimeout = i;
    }

    /**
     * Gets the log writer used to log connections opened.
     */
    public PrintWriter getLogWriter() throws SQLException
    {
        return logger;
    }

    /**
     * The DataSource will note every connection opened to the provided log writer.
     */
    public void setLogWriter(PrintWriter printWriter) throws SQLException
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

    public String getCompatible()
    {
        return compatible;
    }

    public void setCompatible(String compatible)
    {
        this.compatible = compatible;
    }

    public int getLogLevel()
    {
        return logLevel;
    }

    public void setLogLevel(int logLevel)
    {
        this.logLevel = logLevel;
    }

    public int getProtocolVersion()
    {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion)
    {
        this.protocolVersion = protocolVersion;
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
     * Sets the default threshold for enabling server-side prepare.
     * See {@link PGConnection#setPrepareThreshold(int)} for details.
     *
     * @param count the number of times a statement object must be reused before server-side
     *   prepare is enabled.
     */
    public void setPrepareThreshold(int count)
    {
        this.prepareThreshold = count;
    }

    /**
     * Gets the default threshold for enabling server-side prepare.
     *
     * @see #setPrepareThreshold(int)
     */
    public int getPrepareThreshold()
    {
        return prepareThreshold;
    }

    public void setUnknownLength(int unknownLength)
    {
        this.unknownLength = unknownLength;
    }

    public int getUnknownLength()
    {
        return unknownLength;
    }

    /**
     * Sets the socket timeout (SOTimeout), in seconds 
     */
    public void setSocketTimeout(int seconds)
    {
        this.socketTimeout = seconds;
    }
    
    /**
     * @return the socket timeout (SOTimeout), in seconds
     */
    public int getSocketTimeout() 
    {
        return this.socketTimeout;
    }


    /**
     * Set whether the connection will be SSL encrypted or not.
     *
     * @param enabled if <CODE>true</CODE>, connect with SSL.
     */
    public void setSsl(boolean enabled)
    {
        this.ssl = enabled;
    }

    /**
     * Gets SSL encryption setting.
     *
     * @return <CODE>true</CODE> if connections will be encrypted with SSL.
     */
    public boolean getSsl()
    {
        return this.ssl;
    }

    /**
     * Set the name of the {@link javax.net.ssl.SSLSocketFactory} to use for connections.
     * Use <CODE>org.postgresql.ssl.NonValidatingFactory</CODE> if you don't want certificate validation.
     *
     * @param classname name of a subclass of <CODE>javax.net.ssl.SSLSocketFactory</CODE> or <CODE>null</CODE> for the default implementation.
     */
    public void setSslfactory(String classname)
    {
        this.sslfactory = classname;
    }

    /**
     * Gets the name of the {@link javax.net.ssl.SSLSocketFactory} used for connections.
     *
     * @return name of the class or <CODE>null</CODE> if the default implementation is used.
     */
    public String getSslfactory()
    {
        return this.sslfactory;
    }

    public void setApplicationName(String applicationName)
    {
        this.applicationName = applicationName;
    }

    public String getApplicationName()
    {
        return applicationName;
    }

    public void setTcpKeepAlive(boolean enabled)
    {
        tcpKeepAlive = enabled;
    }

    public boolean getTcpKeepAlive()
    {
        return tcpKeepAlive;
    }

    /**
     * Generates a DriverManager URL from the other properties supplied.
     */
    private String getUrl()
    {
        StringBuffer sb = new StringBuffer(100);
        sb.append("jdbc:postgresqllegacy://");
        sb.append(serverName);
        if (portNumber != 0) {
            sb.append(":").append(portNumber);
        }
        sb.append("/").append(databaseName);
        sb.append("?loginTimeout=").append(loginTimeout);
        sb.append("&socketTimeout=").append(socketTimeout);
        sb.append("&prepareThreshold=").append(prepareThreshold);
        sb.append("&unknownLength=").append(unknownLength);
        sb.append("&loglevel=").append(logLevel);
        if (protocolVersion != 0) {
            sb.append("&protocolVersion=").append(protocolVersion);
        }
        if (ssl) {
            sb.append("&ssl=true");
            if (sslfactory != null) {
                sb.append("&sslfactory=").append(sslfactory);
            }
        }
        sb.append("&tcpkeepalive=").append(tcpKeepAlive);
        if (compatible != null) {
            sb.append("&compatible="+compatible);
        }
        if (applicationName != null) {
            sb.append("&ApplicationName=");
            sb.append(applicationName);
        }

        return sb.toString();
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
        
        ref.add(new StringRefAddr("prepareThreshold", Integer.toString(prepareThreshold)));
        ref.add(new StringRefAddr("unknownLength", Integer.toString(unknownLength)));
        ref.add(new StringRefAddr("loginTimeout", Integer.toString(loginTimeout)));
        ref.add(new StringRefAddr("socketTimeout", Integer.toString(socketTimeout)));

        ref.add(new StringRefAddr("ssl", Boolean.toString(ssl)));
        ref.add(new StringRefAddr("sslfactory", sslfactory));

        ref.add(new StringRefAddr("tcpKeepAlive", Boolean.toString(tcpKeepAlive)));
        if (compatible != null)
        {
            ref.add(new StringRefAddr("compatible", compatible));
        }

        ref.add(new StringRefAddr("logLevel", Integer.toString(logLevel)));
        ref.add(new StringRefAddr("protocolVersion", Integer.toString(protocolVersion)));
        ref.add(new StringRefAddr("ApplicationName", applicationName));

        return ref;
    }

    protected void writeBaseObject(ObjectOutputStream out) throws IOException
    {
        out.writeObject(serverName);
        out.writeObject(databaseName);
        out.writeObject(user);
        out.writeObject(password);
        out.writeInt(portNumber);
        out.writeInt(prepareThreshold);
        out.writeInt(unknownLength);
        out.writeInt(loginTimeout);
        out.writeInt(socketTimeout);
        out.writeBoolean(ssl);
        out.writeObject(sslfactory);
        out.writeBoolean(tcpKeepAlive);
        out.writeObject(compatible);
        out.writeInt(logLevel);
        out.writeInt(protocolVersion);
        out.writeObject(applicationName);
    }

    protected void readBaseObject(ObjectInputStream in) throws IOException, ClassNotFoundException
    {
        serverName = (String)in.readObject();
        databaseName = (String)in.readObject();
        user = (String)in.readObject();
        password = (String)in.readObject();
        portNumber = in.readInt();
        prepareThreshold = in.readInt();
        unknownLength = in.readInt();
        loginTimeout = in.readInt();
        socketTimeout = in.readInt();
        ssl = in.readBoolean();
        sslfactory = (String)in.readObject();
        tcpKeepAlive = in.readBoolean();
        compatible = (String)in.readObject();
        logLevel = in.readInt();
        protocolVersion = in.readInt();
        applicationName = (String)in.readObject();
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

}
