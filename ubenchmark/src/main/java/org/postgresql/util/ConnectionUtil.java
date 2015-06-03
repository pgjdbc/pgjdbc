package org.postgresql.util;

import org.postgresql.PGProperty;

import java.util.Properties;

public class ConnectionUtil
{
    /**
    * @return the Postgresql username
    */
    public static String getUser()
    {
        return System.getProperty("user", "test");
    }

    /**
     * @return the user's password
     */
    public static String getPassword()
    {
        return System.getProperty("password", "password");
    }


    /**
     * @return the test server
     */
    public static String getServer()
    {
        return System.getProperty("server", "localhost");
    }

    /**
     * @return the test port
     */
    public static int getPort()
    {
        return Integer.parseInt(System.getProperty("port", System.getProperty("def_pgport", "5432")));
    }

    /**
     * @return the Test database
     */
    public static String getDatabase()
    {
        return System.getProperty("database", "test");
    }

    /**
     * @return connection url to server
     */
    public static String getURL()
    {
        return "jdbc:postgresql://" + ConnectionUtil.getServer() + ":" + ConnectionUtil.getPort() + "/" + ConnectionUtil
                .getDatabase();
    }


    /**
     * @return merged with default property list
     */
    public static Properties getProperties()
    {
        Properties properties = new Properties(System.getProperties());

        PGProperty.USER.set(properties, getUser());
        PGProperty.PASSWORD.set(properties, getPassword());
        PGProperty.PG_PORT.set(properties, getPort());
        properties.setProperty("database", getDatabase());
        properties.setProperty("server", getServer());

        return properties;
    }
}
