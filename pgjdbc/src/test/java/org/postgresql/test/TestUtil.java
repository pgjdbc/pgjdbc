/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.Version;
import org.postgresql.jdbc.PgConnection;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Utility class for JDBC tests
 */
public class TestUtil {
  /*
   * Returns the Test database JDBC URL
   */
  public static String getURL() {
    return getURL(getServer(), getPort());
  }

  public static String getURL(String server, int port) {
    String protocolVersion = "";
    if (getProtocolVersion() != 0) {
      protocolVersion = "&protocolVersion=" + getProtocolVersion();
    }

    String binaryTransfer = "";
    if (getBinaryTransfer() != null && !getBinaryTransfer().equals("")) {
      binaryTransfer = "&binaryTransfer=" + getBinaryTransfer();
    }

    String receiveBufferSize = "";
    if (getReceiveBufferSize() != -1) {
      receiveBufferSize = "&receiveBufferSize=" + getReceiveBufferSize();
    }

    String sendBufferSize = "";
    if (getSendBufferSize() != -1) {
      sendBufferSize = "&sendBufferSize=" + getSendBufferSize();
    }

    String ssl = "";
    if (getSSL() != null) {
      ssl = "&ssl=" + getSSL();
    }

    return "jdbc:postgresql://"
        + server + ":"
        + port + "/"
        + getDatabase()
        + "?loglevel=" + getLogLevel()
        + protocolVersion
        + binaryTransfer
        + receiveBufferSize
        + sendBufferSize
        + ssl;
  }

  /*
   * Returns the Test server
   */
  public static String getServer() {
    return System.getProperty("server", "localhost");
  }

  /*
   * Returns the Test port
   */
  public static int getPort() {
    return Integer.parseInt(System.getProperty("port", System.getProperty("def_pgport")));
  }

  /*
   * Returns the server side prepared statement threshold.
   */
  public static int getPrepareThreshold() {
    return Integer.parseInt(System.getProperty("preparethreshold", "5"));
  }

  public static int getProtocolVersion() {
    return Integer.parseInt(System.getProperty("protocolVersion", "0"));
  }

  /*
   * Returns the Test database
   */
  public static String getDatabase() {
    return System.getProperty("database");
  }

  /*
   * Returns the Postgresql username
   */
  public static String getUser() {
    return System.getProperty("username");
  }

  /*
   * Returns the user's password
   */
  public static String getPassword() {
    return System.getProperty("password");
  }

  /*
   * postgres like user
   */
  public static String getPrivilegedUser() {
    return System.getProperty("privilegedUser");
  }

  public static String getPrivilegedPassword() {
    return System.getProperty("privilegedPassword");
  }

  /*
   * Returns the log level to use
   */
  public static int getLogLevel() {
    return Integer.parseInt(System.getProperty("loglevel", "0"));
  }

  /*
   * Returns the binary transfer mode to use
   */
  public static String getBinaryTransfer() {
    return System.getProperty("binaryTransfer");
  }

  public static int getSendBufferSize() {
    return Integer.parseInt(System.getProperty("sendBufferSize", "-1"));
  }

  public static int getReceiveBufferSize() {
    return Integer.parseInt(System.getProperty("receiveBufferSize", "-1"));
  }

  public static String getSSL() {
    return System.getProperty("ssl");
  }

  static {
    try {
      initDriver();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Unable to initialize driver", e);
    }
  }

  private static boolean initialized = false;

  public static Properties loadPropertyFiles(String... names) {
    Properties p = new Properties();
    for (String name : names) {
      for (int i = 0; i < 2; i++) {
        // load x.properties, then x.local.properties
        if (i == 1 && name.endsWith(".properties") && !name.endsWith(".local.properties")) {
          name = name.replaceAll("\\.properties$", ".local.properties");
        }
        File f = getFile(name);
        if (!f.exists()) {
          System.out.println("Configuration file " + f.getAbsolutePath()
              + " does not exist. Consider adding it to specify test db host and login");
          continue;
        }
        try {
          p.load(new FileInputStream(f));
        } catch (IOException ex) {
          // ignore
        }
      }
    }
    return p;
  }

  public static void initDriver() throws Exception {
    synchronized (TestUtil.class) {
      if (initialized) {
        return;
      }

      Properties p = loadPropertyFiles("build.properties");
      p.putAll(System.getProperties());
      System.getProperties().putAll(p);

      if (getLogLevel() > 0) {
        // Ant's junit task likes to buffer stdout/stderr and tends to run out of memory.
        // So we put debugging output to a file instead.
        java.io.Writer output = new java.io.FileWriter("postgresql-jdbc-tests.debug.txt", true);
        java.sql.DriverManager.setLogWriter(new java.io.PrintWriter(output, true));
      }

      org.postgresql.Driver.setLogLevel(getLogLevel()); // Also loads and registers driver.
      initialized = true;
    }
  }

  /**
   * Resolves file path with account of {@code build.properties.relative.path}. This is a bit tricky
   * since during maven release, maven does a temporary checkout to {@code core/target/checkout}
   * folder, so that script should somehow get {@code build.local.properties}
   *
   * @param name original name of the file, as if it was in the root pgjdbc folder
   * @return actual location of the file
   */
  public static File getFile(String name) {
    if (name == null) {
      throw new IllegalArgumentException("null file name is not expected");
    }
    if (name.startsWith("/")) {
      return new File(name);
    }
    return new File(System.getProperty("build.properties.relative.path", "../"), name);
  }

  /**
   * Get a connection using a priviliged user mostly for tests that the ability to load C functions
   * now as of 4/14
   *
   * @return connection using a priviliged user mostly for tests that the ability to load C
   * functions now as of 4/14
   */
  public static java.sql.Connection openPrivilegedDB() throws Exception {

    initDriver();
    Properties properties = new Properties();
    properties.setProperty("user", getPrivilegedUser());
    properties.setProperty("password", getPrivilegedPassword());
    return DriverManager.getConnection(getURL(), properties);

  }

  /**
   * Helper - opens a connection.
   *
   * @return connection
   */
  public static java.sql.Connection openDB() throws Exception {
    return openDB(new Properties());
  }

  /*
   * Helper - opens a connection with the allowance for passing
   * additional parameters, like "compatible".
   */
  public static java.sql.Connection openDB(Properties props) throws Exception {
    initDriver();

    String user = getUser();
    if (user == null) {
      throw new IllegalArgumentException(
          "user name is not specified. Please specify 'username' property via -D or build.properties");
    }
    props.setProperty("user", user);
    String password = getPassword();
    if (password == null) {
      password = "";
    }
    props.setProperty("password", password);
    if (!props.containsKey(PGProperty.PREPARE_THRESHOLD.getName())) {
      PGProperty.PREPARE_THRESHOLD.set(props, getPrepareThreshold());
    }

    return DriverManager.getConnection(getURL(), props);
  }

  /*
   * Helper - closes an open connection.
   */
  public static void closeDB(Connection con) throws SQLException {
    if (con != null) {
      con.close();
    }
  }

  /*
   * Helper - creates a test schema for use by a test
   */
  public static void createSchema(Connection con,
      String schema) throws SQLException {
    Statement st = con.createStatement();
    try {
      // Drop the schema
      dropSchema(con, schema);

      // Now create the schema
      String sql = "CREATE SCHEMA " + schema;

      st.executeUpdate(sql);
    } finally {
      st.close();
    }
  }

  /*
   * Helper - drops a schema
   */
  public static void dropSchema(Connection con, String schema) throws SQLException {
    Statement stmt = con.createStatement();
    try {
      String sql = "DROP SCHEMA " + schema;
      if (haveMinimumServerVersion(con, ServerVersion.v7_3)) {
        sql += " CASCADE ";
      }
      stmt.executeUpdate(sql);
    } catch (SQLException ex) {
      // Since every create schema issues a drop schema
      // it's easy to get a schema doesn't exist error.
      // we want to ignore these, but if we're in a
      // transaction then we've got trouble
      if (!con.getAutoCommit()) {
        throw ex;
      }
    }
  }

  /*
   * Helper - creates a test table for use by a test
   */
  public static void createTable(Connection con,
      String table,
      String columns) throws SQLException {
    // by default we don't request oids.
    createTable(con, table, columns, false);
  }

  /*
   * Helper - creates a test table for use by a test
   */
  public static void createTable(Connection con,
      String table,
      String columns,
      boolean withOids) throws SQLException {
    Statement st = con.createStatement();
    try {
      // Drop the table
      dropTable(con, table);

      // Now create the table
      String sql = "CREATE TABLE " + table + " (" + columns + ") ";

      // Starting with 8.0 oids may be turned off by default.
      // Some tests need them, so they flag that here.
      if (withOids && haveMinimumServerVersion(con, ServerVersion.v8_0)) {
        sql += " WITH OIDS";
      }
      st.executeUpdate(sql);
    } finally {
      st.close();
    }
  }

  /**
   * Helper creates a temporary table
   *
   * @param con     Connection
   * @param table   String
   * @param columns String
   */

  public static void createTempTable(Connection con,
      String table,
      String columns) throws SQLException {
    Statement st = con.createStatement();
    try {
      // Drop the table
      dropTable(con, table);

      // Now create the table
      st.executeUpdate("create temp table " + table + " (" + columns + ")");
    } finally {
      st.close();
    }
  }

  /**
   * Helper creates an enum type
   *
   * @param con    Connection
   * @param name   String
   * @param values String
   */

  public static void createEnumType(Connection con,
      String name,
      String values) throws SQLException {
    Statement st = con.createStatement();
    try {
      dropType(con, name);


      // Now create the table
      st.executeUpdate("create type " + name + " as enum (" + values + ")");
    } finally {
      st.close();
    }
  }

  /**
   * Helper creates an composite type
   *
   * @param con    Connection
   * @param name   String
   * @param values String
   */

  public static void createCompositeType(Connection con,
      String name,
      String values) throws SQLException {
    Statement st = con.createStatement();
    try {
      dropType(con, name);


      // Now create the table
      st.executeUpdate("create type " + name + " as (" + values + ")");
    } finally {
      st.close();
    }
  }

  /*
   * drop a sequence because older versions don't have dependency
   * information for serials
   */
  public static void dropSequence(Connection con, String sequence) throws SQLException {
    Statement stmt = con.createStatement();
    try {
      String sql = "DROP SEQUENCE " + sequence;
      stmt.executeUpdate(sql);
    } catch (SQLException sqle) {
      if (!con.getAutoCommit()) {
        throw sqle;
      }
    }
  }

  /*
   * Helper - drops a table
   */
  public static void dropTable(Connection con, String table) throws SQLException {
    Statement stmt = con.createStatement();
    try {
      String sql = "DROP TABLE " + table;
      if (haveMinimumServerVersion(con, ServerVersion.v7_3)) {
        sql += " CASCADE ";
      }
      stmt.executeUpdate(sql);
    } catch (SQLException ex) {
      // Since every create table issues a drop table
      // it's easy to get a table doesn't exist error.
      // we want to ignore these, but if we're in a
      // transaction then we've got trouble
      if (!con.getAutoCommit()) {
        throw ex;
      }
    }
  }

  /*
   * Helper - drops a type
   */
  public static void dropType(Connection con, String type) throws SQLException {
    Statement stmt = con.createStatement();
    try {
      String sql = "DROP TYPE " + type;
      stmt.executeUpdate(sql);
    } catch (SQLException ex) {
      if (!con.getAutoCommit()) {
        throw ex;
      }
    }
  }

  /*
   * Helper - generates INSERT SQL - very simple
   */
  public static String insertSQL(String table, String values) {
    return insertSQL(table, null, values);
  }

  public static String insertSQL(String table, String columns, String values) {
    String s = "INSERT INTO " + table;

    if (columns != null) {
      s = s + " (" + columns + ")";
    }

    return s + " VALUES (" + values + ")";
  }

  /*
   * Helper - generates SELECT SQL - very simple
   */
  public static String selectSQL(String table, String columns) {
    return selectSQL(table, columns, null, null);
  }

  public static String selectSQL(String table, String columns, String where) {
    return selectSQL(table, columns, where, null);
  }

  public static String selectSQL(String table, String columns, String where, String other) {
    String s = "SELECT " + columns + " FROM " + table;

    if (where != null) {
      s = s + " WHERE " + where;
    }
    if (other != null) {
      s = s + " " + other;
    }

    return s;
  }

  /*
   * Helper to prefix a number with leading zeros - ugly but it works...
   * @param v value to prefix
   * @param l number of digits (0-10)
   */
  public static String fix(int v, int l) {
    String s = "0000000000".substring(0, l) + Integer.toString(v);
    return s.substring(s.length() - l);
  }

  public static String escapeString(Connection con, String value) throws SQLException {
    if (con instanceof PgConnection) {
      return ((PgConnection) con).escapeString(value);
    }
    return value;
  }

  public static boolean getStandardConformingStrings(Connection con) {
    if (con instanceof PgConnection) {
      return ((PgConnection) con).getStandardConformingStrings();
    }
    return false;
  }

  /**
   * Determine if the given connection is connected to a server with a version of at least the given
   * version. This is convenient because we are working with a java.sql.Connection, not an Postgres
   * connection.
   */
  public static boolean haveMinimumServerVersion(Connection con, String version)
      throws SQLException {
    if (con instanceof PgConnection) {
      return ((PgConnection) con).haveMinimumServerVersion(version);
    }
    return false;
  }

  public static boolean haveMinimumServerVersion(Connection con, int version) throws SQLException {
    if (con instanceof PgConnection) {
      return ((PgConnection) con).haveMinimumServerVersion(version);
    }
    return false;
  }

  public static boolean haveMinimumServerVersion(Connection con, Version version)
      throws SQLException {
    if (con instanceof PgConnection) {
      return ((PgConnection) con).haveMinimumServerVersion(version);
    }
    return false;
  }

  public static boolean haveMinimumJVMVersion(String version) {
    String jvm = java.lang.System.getProperty("java.version");
    return (jvm.compareTo(version) >= 0);
  }

  public static boolean isProtocolVersion(Connection con, int version) {
    if (con instanceof PgConnection) {
      return (version == ((PgConnection) con).getProtocolVersion());

    }
    return false;
  }

  /**
   * Print a ResultSet to System.out. This is useful for debugging tests.
   */
  public static void printResultSet(ResultSet rs) throws SQLException {
    ResultSetMetaData rsmd = rs.getMetaData();
    for (int i = 1; i <= rsmd.getColumnCount(); i++) {
      if (i != 1) {
        System.out.print(", ");
      }
      System.out.print(rsmd.getColumnName(i));
    }
    System.out.println();
    while (rs.next()) {
      for (int i = 1; i <= rsmd.getColumnCount(); i++) {
        if (i != 1) {
          System.out.print(", ");
        }
        System.out.print(rs.getString(i));
      }
      System.out.println();
    }
  }

  /*
   * Find the column for the given label. Only SQLExceptions
   * for system or set-up problems are thrown.
   * The PSQLState.UNDEFINED_COLUMN type exception is
   * consumed to allow cleanup. Relying on the caller
   * to detect if the column lookup was successful.
   */
  public static int findColumn(PreparedStatement query, String label) throws SQLException {
    int returnValue = 0;
    ResultSet rs = query.executeQuery();
    if (rs.next()) {
      try {
        returnValue = rs.findColumn(label);
      } catch (SQLException sqle) {
      } //consume exception to allow  cleanup of resource.
    }
    rs.close();
    return returnValue;
  }

  /**
   * Close a Connection and ignore any errors during closing.
   */
  public static void closeQuietly(Connection conn) {
    if (conn != null) {
      try {
        conn.close();
      } catch (SQLException ignore) {
      }
    }
  }

  /**
   * Close a Statement and ignore any errors during closing.
   */
  public static void closeQuietly(Statement stmt) {
    if (stmt != null) {
      try {
        stmt.close();
      } catch (SQLException ignore) {
      }
    }
  }

  /**
   * Close a ResultSet and ignore any errors during closing.
   */
  public static void closeQuietly(ResultSet rs) {
    if (rs != null) {
      try {
        rs.close();
      } catch (SQLException ignore) {
      }
    }
  }
}
