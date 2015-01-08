/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql;

import java.sql.DriverPropertyInfo;
import java.util.Properties;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * All connection parameters that can be either set in JDBC URL, in Driver properties or in datasource setters.
 */
public enum PGProperty
{

    /**
     * Database name to connect to (may be specified directly in the JDBC URL)
     */
    PG_DBNAME("PGDBNAME", null, "Database name to connect to (may be specified directly in the JDBC URL)", true),

    /**
     * Hostname of the PostgreSQL server (may be specified directly in the JDBC URL)
     */
    PG_HOST("PGHOST", null, "Hostname of the PostgreSQL server (may be specified directly in the JDBC URL)", false),

    /**
     * Port of the PostgreSQL server (may be specified directly in the JDBC URL)
     */
    PG_PORT("PGPORT", null, "Port of the PostgreSQL server (may be specified directly in the JDBC URL)"),

    /**
     * Username to connect to the database as.
     */
    USER("user", null, "Username to connect to the database as.", true),

    /**
     * Password to use when authenticating.
     */
    PASSWORD("password", null, "Password to use when authenticating.", false),

    /**
     * Force use of a particular protocol version when connecting, if set, disables protocol version fallback.
     */
    PROTOCOL_VERSION("protocolVersion", null, "Force use of a particular protocol version when connecting, if set, disables protocol version fallback.", false, "2", "3"),

    /**
     * The loglevel.
     * Can be one of {@link Driver#DEBUG}, {@link Driver#INFO}, {@link Driver#OFF}.
     */
    LOG_LEVEL("loglevel", "0", "The log level", false, "0", "1", "2"),

    /**
     * Sets the default threshold for enabling server-side prepare. A value of {@code -1} stands for forceBinary
     */
    PREPARE_THRESHOLD("prepareThreshold", "5", "Statement prepare threshold. A value of {@code -1} stands for forceBinary"),

    /**
     * Use binary format for sending and receiving data if possible.
     */
    BINARY_TRANSFER("binaryTransfer", "true", "Use binary format for sending and receiving data if possible"),

    /**
     * Force compatibility of some features with an older version of the driver.
     */
    COMPATIBLE("compatible", Driver.MAJORVERSION + "." + Driver.MINORVERSION, "Force compatibility of some features with an older version of the driver"),

    /**
     * Puts this connection in read-only mode.
     */
    READ_ONLY("readOnly", "false", "Puts this connection in read-only mode"),

    /**
     * Comma separated list of types to enable binary transfer. Either OID numbers or names
     */
    BINARY_TRANSFER_ENABLE("binaryTransferEnable", "", "Comma separated list of types to enable binary transfer. Either OID numbers or names"),

    /**
     * Comma separated list of types to disable binary transfer. Either OID numbers or names. Overrides values in the driver default set and values set with binaryTransferEnable.
     */
    BINARY_TRANSFER_DISABLE("binaryTransferDisable", "", "Comma separated list of types to disable binary transfer. Either OID numbers or names. Overrides values in the driver default set and values set with binaryTransferEnable."),

    /**
     * Bind String to either {@code unspecified} or {@code varchar}. Default is {@code varchar} for 8.0+ backends.
     */
    STRING_TYPE("stringtype", null, "The type to bind String parameters as (usually 'varchar', 'unspecified' allows implicit casting to other types)", false, new String[] { "unspecified", "varchar" }),

    /**
     * Specifies the length to return for types of unknown length.
     */
    UNKNOWN_LENGTH("unknownLength", Integer.toString(Integer.MAX_VALUE), "Specifies the length to return for types of unknown length"),

    /**
     * When connections that are not explicitly closed are garbage collected, log the stacktrace from the opening of the connection to trace the leak source.
     */
    LOG_UNCLOSED_CONNECTIONS("logUnclosedConnections", "false", "When connections that are not explicitly closed are garbage collected, log the stacktrace from the opening of the connection to trace the leak source"),

    /**
     * Enable optimization that disables column name sanitiser.
     */
    DISABLE_COLUMN_SANITISER("disableColumnSanitiser", "false", "Enable optimization that disables column name sanitiser"),

    /**
     * Control use of SSL (any non-null value causes SSL to be required).
     */
    SSL("ssl", null, "Control use of SSL (any non-null value causes SSL to be required)"),

    /**
     * Parameter governing the use of SSL.
     * The allowed values are {@code require}, {@code verify-ca}, {@code verify-full}, or {@code disable} ({@code allow} and {@code prefer} are not implemented)
     * If not set, the {@code ssl} property may be checked to enable SSL mode.
     */
    SSL_MODE("sslmode", null, "Parameter governing the use of SSL"),

    /**
     * Classname of the SSL Factory to use (instance of {@code javax.net.ssl.SSLSocketFactory}).
     */
    SSL_FACTORY("sslfactory", null, "Provide a SSLSocketFactory class when using SSL."),
    
    /**
     * The String argument to give to the constructor of the SSL Factory
     */
    SSL_FACTORY_ARG("sslfactoryarg", null, "Argument forwarded to constructor of SSLSocketFactory class."),

    /**
     * Classname of the SSL HostnameVerifier to use (instance of {@code javax.net.ssl.HostnameVerifier}).
     */
    SSL_HOSTNAME_VERIFIER("sslhostnameverifier", null, "A class, implementing javax.net.ssl.HostnameVerifier that can verify the server"),

    /**
     * File containing the SSL Certificate.
     * Default will be the file {@code postgresql.crt} in {@code $HOME/.postgresql} (*nix) or {@code %APPDATA%\postgresql} (windows). 
     */
    SSL_CERT("sslcert", null, "The location of the client's SSL certificate"),

    /**
     * File containing the SSL Key.
     * Default will be the file {@code postgresql.pk8} in {@code $HOME/.postgresql} (*nix) or {@code %APPDATA%\postgresql} (windows).
     */
    SSL_KEY("sslkey", null, "The location of the client's PKCS#8 SSL key"),

    /**
     * File containing the root certificate when validating server ({@code sslmode} = {@code verify-ca} or {@code verify-full}).
     * Default will be the file {@code root.crt} in {@code $HOME/.postgresql} (*nix) or {@code %APPDATA%\postgresql} (windows).
     */
    SSL_ROOT_CERT("sslrootcert", null, "The location of the root certificate for authenticating the server."),

    /**
    * The SSL password to use in the default CallbackHandler.
    */
    SSL_PASSWORD("sslpassword", null, "The password for the client's ssl key (ignored if sslpasswordcallback is set)"),

    /**
     * The classname instantiating {@code javax.security.auth.callback.CallbackHandler} to use
     */
    SSL_PASSWORD_CALLBACK("sslpasswordcallback", null, "A class, implementing javax.security.auth.callback.CallbackHandler that can handle PassworCallback for the ssl password."),

    /**
     * Enable or disable TCP keep-alive. The default is {@code false}.
     */
    TCP_KEEP_ALIVE("tcpKeepAlive", "false", "Enable or disable TCP keep-alive. The default is {@code false}."),

    /**
     * Specify how long to wait for establishment of a database connection.
     * The timeout is specified in seconds.
     */
    LOGIN_TIMEOUT("loginTimeout", "0", "Specify how long to wait for establishment of a database connection."),

    /**
     * The timeout value used for socket connect operations. If connecting
     * to the server takes longer than this value, the connection is broken.
     * <p> 
     * The timeout is specified in seconds and a value of zero means that
     * it is disabled.
     */
    CONNECT_TIMEOUT("connectTimeout", "0", "The timeout value used for socket connect operations."),

    /**
     * The timeout value used for socket read operations.  If reading
     * from the server takes longer than this value, the connection is
     * closed.  This can be used as both a brute force global query
     * timeout and a method of detecting network problems.  The timeout
     * is specified in seconds and a value of zero means that it is disabled.
     */
    SOCKET_TIMEOUT("socketTimeout", "0", "The timeout value used for socket read operations."),

    /**
     * Socket read buffer size (SO_RECVBUF). A value of {@code -1}, which is the default, means system default.
     */
    RECEIVE_BUFFER_SIZE("receiveBufferSize", "-1", "Socket read buffer size"),

    /**
     * Socket write buffer size (SO_SNDBUF). A value of {@code -1}, which is the default, means system default.
     */
    SEND_BUFFER_SIZE("sendBufferSize", "-1", "Socket write buffer size"),

    /**
     * Assume the server is at least that version
     */
    ASSUME_MIN_SERVER_VERSION("assumeMinServerVersion", null, "Assume the server is at least that version"),
    
    /**
     * The application name (require server version >= 9.0)
     */
    APPLICATION_NAME("ApplicationName", null, "name of the application (backend >= 9.0)"),
    
    /**
     * Specifies the name of the JAAS system or application login configuration.
     */
    JAAS_APPLICATION_NAME("jaasApplicationName", null, "Specifies the name of the JAAS system or application login configuration."),

    /**
     * The Kerberos service name to use when authenticating with GSSAPI.  This is equivalent to libpq's PGKRBSRVNAME environment variable.
     */
    KERBEROS_SERVER_NAME("kerberosServerName", null, "The Kerberos service name to use when authenticating with GSSAPI."),

    /**
     * Use SPNEGO in SSPI authentication requests
     */
    USE_SPNEGO("useSpnego", "false", "Use SPNEGO in SSPI authentication requests"),

    /**
     * Force one of
     * <ul>
     *   <li><acronym>SSPI</acronym> (Windows transparent single-sign-on)</li>
     *   <li>GSSAPI</acronym> (Kerberos, via JSSE)
     * </ul>
     * to be used when the server requests Kerberos or SSPI authentication.
     */
    GSS_LIB("gsslib", "auto", "Force SSSPI or GSSAPI", false, "auto", "sspi", "gssapi"),

    /**
     * Specifies the name of the SSPI service class
     * that forms the service class part of the SPN.
     * The default, {@code POSTGRES}, is almost always correct.
     */
    SSPI_SERVICE_CLASS("sspiServiceClass", "POSTGRES", "The Windows SSPI service class for SPN"),

    /**
     * The character set to use for data sent to the database or received
     * from the database.  This property is only relevant for server
     * versions less than or equal to 7.2.
     */
    CHARSET("charSet", null, "The character set to use for data sent to the database or received from the database (for backend <= 7.2)"),

    /**
     * When using the V3 protocol the driver monitors changes in certain
     * server configuration parameters that should not be touched by
     * end users.  The {@code client_encoding} setting is set
     * by the driver and should not be altered.  If the driver detects
     * a change it will abort the connection. 
     */
    ALLOW_ENCODING_CHANGES("allowEncodingChanges", "false", "Allow for changes in client_encoding"),

    /**
     * Specify the schema to be set in the search-path. This schema will be used
     * to resolve unqualified object names used in statements over this connection.
     */
    CURRENT_SCHEMA("currentSchema", null, "Specify the schema to be set in the search-path"),

    TARGET_SERVER_TYPE("targetServerType", "any", "Specifies what kind of server to connect", false, "any", "master", "slave", "preferSlave"),

    LOAD_BALANCE_HOSTS("loadBalanceHosts", "false", "If disabled hosts are connected in the given order. If enabled hosts are chosen randomly from the set of suitable candidates"),

    HOST_RECHECK_SECONDS("hostRecheckSeconds", "10", "Specifies period (seconds) after host statuses are checked again in case they have changed");

    private String _name;
    private String _defaultValue;
    private boolean _required;
    private String _description;
    private String[] _choices;

    private PGProperty(String name, String defaultValue, String description)
    {
        this(name, defaultValue, description, false);
    }

    private PGProperty(String name, String defaultValue, String description, boolean required)
    {
        this(name, defaultValue, description, required, (String[]) null);
    }
    
    private PGProperty(String name, String defaultValue, String description, boolean required, String... choices)
    {
        _name = name;
        _defaultValue = defaultValue;
        _required = required;
        _description = description;
        _choices = choices;
    }

    /**
     * Returns the name of the connection parameter.
     * The name is the key that must be used in JDBC URL or in Driver properties
     * @return the name of the connection parameter
     */
    public String getName()
    {
        return _name;
    }

    /**
     * Returns the default value for this connection parameter
     * @return the default value for this connection parameter or null
     */
    public String getDefaultValue()
    {
        return _defaultValue;
    }

    /**
     * Returns the available values for this connection parameter
     * @return the available values for this connection parameter or null
     */
    public String[] getChoices()
    {
        return _choices;
    }

    /**
     * Returns the value of the connection parameters according to the given {@code Properties} or the
     * default value 
     * @param properties properties to take actual value from
     * @return evaluated value for this connection parameter
     */
    public String get(Properties properties)
    {
        return properties.getProperty(_name, _defaultValue);
    }

    /**
     * Set the value for this connection parameter in the given {@code Properties}
     * @param properties properties in which the value should be set
     * @param value value for this connection parameter
     */
    public void set(Properties properties, String value)
    {
        if (value == null)
        {
            properties.remove(_name);
        }
        else
        {
            properties.setProperty(_name, value);
        }
    }

    /**
     * Return the boolean value for this connection parameter in the given {@code Properties}
     * @param properties properties to take actual value from
     * @return evaluated value for this connection parameter converted to boolean
     */
    public boolean getBoolean(Properties properties)
    {
        return Boolean.valueOf(get(properties));
    }

    /**
     * Return the int value for this connection parameter in the given {@code Properties}.
     * Prefer the use of {@link #getInt(Properties)} anywhere you can throw an {@link java.sql.SQLException}
     * @param properties properties to take actual value from
     * @return evaluated value for this connection parameter converted to int
     * @throws NumberFormatException if it cannot be converted to int.
     */
    public int getIntNoCheck(Properties properties)
    {
        String value = get(properties);
        return Integer.parseInt(value);
    }

    /**
     * Return the int value for this connection parameter in the given {@code Properties}
     * @param properties properties to take actual value from
     * @return evaluated value for this connection parameter converted to int
     * @throws PSQLException if it cannot be converted to int.
     */
    public int getInt(Properties properties) throws PSQLException
    {
        String value = get(properties);
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException nfe)
        {
            throw new PSQLException(
                    GT.tr("{0} parameter value must be an integer but was: {1}", new Object[] { getName(), value }),
                    PSQLState.INVALID_PARAMETER_VALUE,
                    nfe);
        }
    }

    /**
     * Return the {@code Integer} value for this connection parameter in the given {@code Properties}
     * @param properties properties to take actual value from
     * @return evaluated value for this connection parameter converted to Integer or null
     * @throws PSQLException
     */
    public Integer getInteger(Properties properties) throws PSQLException
    {
        String value = get(properties);
        if (value == null)
        {
            return null;
        }
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException nfe)
        {
            throw new PSQLException(
                    GT.tr("{0} parameter value must be an integer but was: {1}", new Object[] { getName(), value }),
                    PSQLState.INVALID_PARAMETER_VALUE,
                    nfe);
        }
    }

    /**
     * Set the boolean value for this connection parameter in the given {@code Properties}
     * @param properties properties in which the value should be set
     * @param value boolean value for this connection parameter
     */
    public void set(Properties properties, boolean value)
    {
        properties.setProperty(_name, Boolean.toString(value));
    }

    /**
     * Set the int value for this connection parameter in the given {@code Properties}
     * @param properties properties in which the value should be set
     * @param value int value for this connection parameter
     */
    public void set(Properties properties, int value)
    {
        properties.setProperty(_name, Integer.toString(value));
    }

    /**
     * Test whether this property is present in the given {@code Properties}
     * @return true if the parameter is specified in the given properties
     */
    public boolean isPresent(Properties properties)
    {
        return properties.containsKey(_name) && get(properties) != null;
    }

    /**
     * Convert this connection parameter and the value read from the given {@code Properties} into
     * a {@code DriverPropertyInfo}
     * @param properties properties to take actual value from
     * @return a DriverPropertyInfo representing this connection parameter
     */
    public DriverPropertyInfo toDriverPropertyInfo(Properties properties)
    {
        DriverPropertyInfo propertyInfo = new DriverPropertyInfo(_name, get(properties));
        propertyInfo.required = _required;
        propertyInfo.description = _description;
        propertyInfo.choices = _choices;
        return propertyInfo;
    }

    public static PGProperty forName(String name)
    {
        for (PGProperty property : PGProperty.values())
        {
            if (property.getName().equals(name))
            {
                return property;
            }
        }
        return null;
    }
}
