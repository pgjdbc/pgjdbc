/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.*;
import java.util.Properties;

import org.postgresql.jdbc2.AbstractJdbc2Connection;

/**
 * Utility class for JDBC tests
 */
public class TestUtil
{
    /*
     * Returns the Test database JDBC URL
     */
    public static String getURL()
    {
        String protocolVersion = "";
        if (getProtocolVersion() != 0) {
            protocolVersion = "&protocolVersion=" + getProtocolVersion();
        }

        String binaryTransfer = "";
        if (getBinaryTransfer() != null && !getBinaryTransfer().equals("")) {
            binaryTransfer = "&binaryTransfer=" + getBinaryTransfer();
        }

		String receiveBufferSize = "";
		if (getReceiveBufferSize() != -1 ){
			receiveBufferSize = "&receiveBufferSize="+getReceiveBufferSize();
		}
		
		String sendBufferSize = "";
		if (getSendBufferSize() != -1 ){
			sendBufferSize = "&sendBufferSize="+getSendBufferSize();
		}
		
        return "jdbc:postgresql://"
                                + getServer() + ":" 
                                + getPort() + "/" 
                                + getDatabase() 
                                + "?prepareThreshold=" + getPrepareThreshold()
                                + "&loglevel=" + getLogLevel()
                                + protocolVersion
                                + binaryTransfer
								+ receiveBufferSize
								+ sendBufferSize;
    }

    /*
     * Returns the Test server
     */
    public static String getServer()
    {
        return System.getProperty("server", "localhost");
    }

    /*
     * Returns the Test port
     */
    public static int getPort()
    {
        return Integer.parseInt(System.getProperty("port", System.getProperty("def_pgport")));
    }

    /*
     * Returns the server side prepared statement threshold.
     */
    public static int getPrepareThreshold()
    {
        return Integer.parseInt(System.getProperty("preparethreshold", "5"));
    }

    public static int getProtocolVersion()
    {
        return Integer.parseInt(System.getProperty("protocolVersion", "0"));
    }

    /*
     * Returns the Test database
     */
    public static String getDatabase()
    {
        return System.getProperty("database");
    }

    /*
     * Returns the Postgresql username
     */
    public static String getUser()
    {
        return System.getProperty("username");
    }

    /*
     * Returns the user's password
     */
    public static String getPassword()
    {
        return System.getProperty("password");
    }

    /*
     * Returns the log level to use
     */
    public static int getLogLevel()
    {
        return Integer.parseInt(System.getProperty("loglevel", "0"));
    }

    /*
     * Returns the binary transfer mode to use
     */
    public static String getBinaryTransfer()
    {
        return System.getProperty("binaryTransfer");
    }
	
	public static int getSendBufferSize()
	{
		return Integer.parseInt(System.getProperty("sendBufferSize", "-1"));
	}
	
	public static int getReceiveBufferSize()
	{
		return Integer.parseInt(System.getProperty("receiveBufferSize","-1"));
	}
	
    private static boolean initialized = false;
    public static void initDriver() throws Exception
    {
        synchronized (TestUtil.class) {
            if (initialized)
                return;

            Properties p = new Properties();
            try {
              p.load(new FileInputStream("build.properties"));
              p.load(new FileInputStream("build.local.properties"));
            } catch (IOException ex) {
              // ignore
            }
            p.putAll(System.getProperties());
            System.getProperties().putAll(p);

            if (getLogLevel() > 0) { 
                // Ant's junit task likes to buffer stdout/stderr and tends to run out of memory.
                // So we put debugging output to a file instead.
                java.io.Writer output = new java.io.FileWriter("postgresql-jdbc-tests.debug.txt", true);
                java.sql.DriverManager.setLogWriter(new java.io.PrintWriter(output,true));
            }
            
            org.postgresql.Driver.setLogLevel(getLogLevel()); // Also loads and registers driver.
            initialized = true;
        }
    }        

    /*
     * Helper - opens a connection.
     */
    public static java.sql.Connection openDB() throws Exception
    {
        return openDB(new Properties());
    }

    /*
     * Helper - opens a connection with the allowance for passing
     * additional parameters, like "compatible".
     */
    public static java.sql.Connection openDB(Properties props) throws Exception
    {
        initDriver();

        props.setProperty("user", getUser());
        props.setProperty("password", getPassword());

        return DriverManager.getConnection(getURL(), props);
    }

    /*
     * Helper - closes an open connection.
     */
    public static void closeDB(Connection con) throws SQLException
    {
        if (con != null)
            con.close();
    }

    /*
     * Helper - creates a test table for use by a test
     */
    public static void createTable(Connection con,
                                   String table,
                                   String columns) throws SQLException
    {
        // by default we don't request oids.
        createTable(con,table,columns,false);
    }

    /*
     * Helper - creates a test table for use by a test
     */
    public static void createTable(Connection con,
                                   String table,
                                   String columns,
                                   boolean withOids) throws SQLException
    {
        Statement st = con.createStatement();
        try
        {
            // Drop the table
            dropTable(con, table);

            // Now create the table
            String sql = "CREATE TABLE " + table + " (" + columns + ") ";

            // Starting with 8.0 oids may be turned off by default.
            // Some tests need them, so they flag that here.
            if (withOids && haveMinimumServerVersion(con,"8.0")) {
                sql += " WITH OIDS";
            }
            st.executeUpdate(sql);
        }
        finally
        {
            st.close();
        }
    }

    /**
     * Helper creates a temporary table
     * @param con Connection
     * @param table String
     * @param columns String
     * @throws SQLException
     */

    public static void createTempTable( Connection con,
                                        String table,
                                        String columns) throws SQLException
    {
        Statement st = con.createStatement();
        try
        {
            // Drop the table
            dropTable(con, table);

            // Now create the table
            st.executeUpdate("create temp table " + table + " (" + columns + ")");
        }
        finally
        {
            st.close();
        }
    }

    /*
     * drop a sequence because older versions don't have dependency
     * information for serials
     */
    public static void dropSequence(Connection con, String sequence) throws SQLException
    {
        Statement stmt = con.createStatement();
        try
        {
            String sql = "DROP SEQUENCE " + sequence;
            stmt.executeUpdate(sql);
        }
        catch (SQLException sqle)
        {
            if (!con.getAutoCommit())
                throw sqle;
        }
    }

    /*
     * Helper - drops a table
     */
    public static void dropTable(Connection con, String table) throws SQLException
    {
        Statement stmt = con.createStatement();
        try
        {
            String sql = "DROP TABLE " + table;
            if (haveMinimumServerVersion(con, "7.3"))
            {
                sql += " CASCADE ";
            }
            stmt.executeUpdate(sql);
        }
        catch (SQLException ex)
        {
            // Since every create table issues a drop table
            // it's easy to get a table doesn't exist error.
            // we want to ignore these, but if we're in a
            // transaction then we've got trouble
            if (!con.getAutoCommit())
                throw ex;
        }
    }

    /*
     * Helper - generates INSERT SQL - very simple
     */
    public static String insertSQL(String table, String values)
    {
        return insertSQL(table, null, values);
    }

    public static String insertSQL(String table, String columns, String values)
    {
        String s = "INSERT INTO " + table;

        if (columns != null)
            s = s + " (" + columns + ")";

        return s + " VALUES (" + values + ")";
    }

    /*
     * Helper - generates SELECT SQL - very simple
     */
    public static String selectSQL(String table, String columns)
    {
        return selectSQL(table, columns, null, null);
    }

    public static String selectSQL(String table, String columns, String where)
    {
        return selectSQL(table, columns, where, null);
    }

    public static String selectSQL(String table, String columns, String where, String other)
    {
        String s = "SELECT " + columns + " FROM " + table;

        if (where != null)
            s = s + " WHERE " + where;
        if (other != null)
            s = s + " " + other;

        return s;
    }

    /*
     * Helper to prefix a number with leading zeros - ugly but it works...
     * @param v value to prefix
     * @param l number of digits (0-10)
     */
    public static String fix(int v, int l)
    {
        String s = "0000000000".substring(0, l) + Integer.toString(v);
        return s.substring(s.length() - l);
    }

    public static String escapeString(Connection con, String value) throws SQLException
    {
        if (con instanceof org.postgresql.jdbc2.AbstractJdbc2Connection)
        {
            return ((org.postgresql.jdbc2.AbstractJdbc2Connection)con).escapeString(value);
        }
        return value;
    }
    
    public static boolean getStandardConformingStrings(Connection con)
    {
        if (con instanceof org.postgresql.jdbc2.AbstractJdbc2Connection)
        {
            return ((org.postgresql.jdbc2.AbstractJdbc2Connection)con).getStandardConformingStrings();
        }
        return false;
    }
    
    /**
     * Determine if the given connection is connected to a server with
     * a version of at least the given version.
     * This is convenient because we are working with a java.sql.Connection,
     * not an Postgres connection.
     */
    public static boolean haveMinimumServerVersion(Connection con, String version) throws SQLException {
        if (con instanceof org.postgresql.jdbc2.AbstractJdbc2Connection)
        {
            return ((org.postgresql.jdbc2.AbstractJdbc2Connection)con).haveMinimumServerVersion(version);
        }
        return false;
    }

    public static boolean haveMinimumJVMVersion(String version) {
        String jvm = java.lang.System.getProperty("java.version");
        return (jvm.compareTo(version) >= 0);
    }

    public static boolean isProtocolVersion( Connection con, int version )
    {
        if ( con instanceof AbstractJdbc2Connection )
        {
            return (version == ((AbstractJdbc2Connection)con).getProtocolVersion());
          
        }
        return false;
    }
    /**
     * Print a ResultSet to System.out.
     * This is useful for debugging tests.
     */
    public static void printResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData rsmd = rs.getMetaData();
        for (int i = 1; i <= rsmd.getColumnCount(); i++)
        {
            if (i != 1)
            {
                System.out.print(", ");
            }
            System.out.print(rsmd.getColumnName(i));
        }
        System.out.println();
        while (rs.next())
        {
            for (int i = 1; i <= rsmd.getColumnCount(); i++)
            {
                if (i != 1)
                {
                    System.out.print(", ");
                }
                System.out.print(rs.getString(i));
            }
            System.out.println();
        }
    }
}
