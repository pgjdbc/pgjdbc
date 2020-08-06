/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TransactionState;
import org.postgresql.core.Version;
import org.postgresql.jdbc.GSSEncMode;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.util.PSQLException;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Assert;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Utility class for JDBC tests.
 */
public class TestUtil {
  /*
   * The case is as follows:
   * 1. Typically the database and hostname are taken from System.properties or build.properties or build.local.properties
   *    That enables to override test DB via system property
   * 2. There are tests where different DBs should be used (e.g. SSL tests), so we can't just use DB name from system property
   *    That is why _test_ properties exist: they overpower System.properties and build.properties
   */
  public static final String SERVER_HOST_PORT_PROP = "_test_hostport";
  public static final String DATABASE_PROP = "_test_database";

  /*
   * Returns the Test database JDBC URL
   */
  public static String getURL() {
    return getURL(getServer(), + getPort());
  }

  public static String getURL(String server, int port) {
    return getURL(server + ":" + port, getDatabase());
  }

  public static String getURL(String hostport, String database) {
    String logLevel = "";
    if (getLogLevel() != null && !getLogLevel().equals("")) {
      logLevel = "&loggerLevel=" + getLogLevel();
    }

    String logFile = "";
    if (getLogFile() != null && !getLogFile().equals("")) {
      logFile = "&loggerFile=" + getLogFile();
    }

    String protocolVersion = "";
    if (getProtocolVersion() != 0) {
      protocolVersion = "&protocolVersion=" + getProtocolVersion();
    }

    String options = "";
    if (getOptions() != null) {
      options = "&options=" + getOptions();
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
        + hostport + "/"
        + database
        + "?ApplicationName=Driver Tests"
        + logLevel
        + logFile
        + protocolVersion
        + options
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

  public static String getOptions() {
    return System.getProperty("options");
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
   * Returns password for default callbackhandler
   */
  public static String getSslPassword() {
    return System.getProperty(PGProperty.SSL_PASSWORD.getName());
  }

  /*
   *  Return the GSSEncMode for the tests
   */
  public static GSSEncMode getGSSEncMode() throws PSQLException {
    return GSSEncMode.of(System.getProperties());
  }

  /*
   * Returns the user for SSPI authentication tests
   */
  public static String getSSPIUser() {
    return System.getProperty("sspiusername");
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
  public static String getLogLevel() {
    return System.getProperty("loggerLevel");
  }

  /*
   * Returns the log file to use
   */
  public static String getLogFile() {
    return System.getProperty("loggerFile");
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

  public static void initDriver() {
    synchronized (TestUtil.class) {
      if (initialized) {
        return;
      }

      Properties p = loadPropertyFiles("build.properties");
      p.putAll(System.getProperties());
      System.getProperties().putAll(p);

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
   * Get a connection using a privileged user mostly for tests that the ability to load C functions
   * now as of 4/14.
   *
   * @return connection using a privileged user mostly for tests that the ability to load C
   *         functions now as of 4/14
   */
  public static Connection openPrivilegedDB() throws SQLException {
    initDriver();
    Properties properties = new Properties();

    PGProperty.GSS_ENC_MODE.set(properties,getGSSEncMode().value);
    properties.setProperty("user", getPrivilegedUser());
    properties.setProperty("password", getPrivilegedPassword());
    return DriverManager.getConnection(getURL(), properties);

  }

  /**
   * Helper - opens a connection.
   *
   * @return connection
   */
  public static Connection openDB() throws SQLException {
    return openDB(new Properties());
  }

  /*
   * Helper - opens a connection with the allowance for passing additional parameters, like
   * "compatible".
   */
  public static Connection openDB(Properties props) throws SQLException {
    initDriver();

    // Allow properties to override the user name.
    String user = props.getProperty("username");
    if (user == null) {
      user = getUser();
    }
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
    String sslPassword = getSslPassword();
    if (sslPassword != null) {
      PGProperty.SSL_PASSWORD.set(props, sslPassword);
    }

    if (!props.containsKey(PGProperty.PREPARE_THRESHOLD.getName())) {
      PGProperty.PREPARE_THRESHOLD.set(props, getPrepareThreshold());
    }
    if (!props.containsKey(PGProperty.PREFER_QUERY_MODE.getName())) {
      String value = System.getProperty(PGProperty.PREFER_QUERY_MODE.getName());
      if (value != null) {
        props.put(PGProperty.PREFER_QUERY_MODE.getName(), value);
      }
    }
    // Enable Base4 tests to override host,port,database
    String hostport = props.getProperty(SERVER_HOST_PORT_PROP, getServer() + ":" + getPort());
    String database = props.getProperty(DATABASE_PROP, getDatabase());

    // Set GSSEncMode for tests
    PGProperty.GSS_ENC_MODE.set(props,getGSSEncMode().value);

    return DriverManager.getConnection(getURL(hostport, database), props);
  }

  /*
   * Helper - closes an open connection.
   */
  public static void closeDB(@Nullable Connection con) throws SQLException {
    if (con != null) {
      con.close();
    }
  }

  /*
   * Helper - creates a test schema for use by a test
   */
  public static void createSchema(Connection con, String schema) throws SQLException {
    Statement st = con.createStatement();
    try {
      // Drop the schema
      dropSchema(con, schema);

      // Now create the schema
      String sql = "CREATE SCHEMA " + schema;

      st.executeUpdate(sql);
    } finally {
      closeQuietly(st);
    }
  }

  /*
   * Helper - drops a schema
   */
  public static void dropSchema(Connection con, String schema) throws SQLException {
    Statement stmt = con.createStatement();
    try {
      if (con.getAutoCommit()) {
        // Not in a transaction so ignore error for missing object
        stmt.executeUpdate("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      } else {
        // In a transaction so do not ignore errors for missing object
        stmt.executeUpdate("DROP SCHEMA " + schema + " CASCADE");
      }
    } finally {
      closeQuietly(stmt);
    }
  }

  /*
   * Helper - creates a test table for use by a test
   */
  public static void createTable(Connection con, String table, String columns) throws SQLException {
    Statement st = con.createStatement();
    try {
      // Drop the table
      dropTable(con, table);

      // Now create the table
      String sql = "CREATE TABLE " + table + " (" + columns + ")";

      st.executeUpdate(sql);
    } finally {
      closeQuietly(st);
    }
  }

  /**
   * Helper creates a temporary table.
   *
   * @param con Connection
   * @param table String
   * @param columns String
   */

  public static void createTempTable(Connection con, String table, String columns)
      throws SQLException {
    Statement st = con.createStatement();
    try {
      // Drop the table
      dropTable(con, table);

      // Now create the table
      st.executeUpdate("create temp table " + table + " (" + columns + ")");
    } finally {
      closeQuietly(st);
    }
  }

  /*
   * Helper - creates a unlogged table for use by a test.
   * Unlogged tables works from PostgreSQL 9.1+
   */
  public static void createUnloggedTable(Connection con, String table, String columns)
      throws SQLException {
    Statement st = con.createStatement();
    try {
      // Drop the table
      dropTable(con, table);

      String unlogged = haveMinimumServerVersion(con, ServerVersion.v9_1) ? "UNLOGGED" : "";

      // Now create the table
      st.executeUpdate("CREATE " + unlogged + " TABLE " + table + " (" + columns + ")");
    } finally {
      closeQuietly(st);
    }
  }

  /**
   * Helper creates an enum type.
   *
   * @param con Connection
   * @param name String
   * @param values String
   */

  public static void createEnumType(Connection con, String name, String values)
      throws SQLException {
    Statement st = con.createStatement();
    try {
      dropType(con, name);

      // Now create the table
      st.executeUpdate("create type " + name + " as enum (" + values + ")");
    } finally {
      closeQuietly(st);
    }
  }

  /**
   * Helper creates an composite type.
   *
   * @param con Connection
   * @param name String
   * @param values String
   */
  public static void createCompositeType(Connection con, String name, String values) throws SQLException {
    createCompositeType(con, name, values, true);
  }

  /**
   * Helper creates an composite type.
   *
   * @param con Connection
   * @param name String
   * @param values String
   */
  public static void createCompositeType(Connection con, String name, String values, boolean shouldDrop)
      throws SQLException {
    Statement st = con.createStatement();
    try {
      if (shouldDrop) {
        dropType(con, name);
      }
      // Now create the type
      st.executeUpdate("CREATE TYPE " + name + " AS (" + values + ")");
    } finally {
      closeQuietly(st);
    }
  }

  /**
   * Drops a domain.
   *
   * @param con Connection
   * @param name String
   */
  public static void dropDomain(Connection con, String name)
      throws SQLException {
    Statement stmt = con.createStatement();
    try {
      if (con.getAutoCommit()) {
        // Not in a transaction so ignore error for missing object
        stmt.executeUpdate("DROP DOMAIN IF EXISTS " + name + " CASCADE");
      } else {
        // In a transaction so do not ignore errors for missing object
        stmt.executeUpdate("DROP DOMAIN " + name + " CASCADE");
      }
    } finally {
      closeQuietly(stmt);
    }
  }

  /**
   * Helper creates a domain.
   *
   * @param con Connection
   * @param name String
   * @param values String
   */
  public static void createDomain(Connection con, String name, String values)
      throws SQLException {
    Statement st = con.createStatement();
    try {
      dropDomain(con, name);
      // Now create the table
      st.executeUpdate("create domain " + name + " as " + values);
    } finally {
      closeQuietly(st);
    }
  }

  /*
   * drop a sequence because older versions don't have dependency information for serials
   */
  public static void dropSequence(Connection con, String sequence) throws SQLException {
    Statement stmt = con.createStatement();
    try {
      if (con.getAutoCommit()) {
        // Not in a transaction so ignore error for missing object
        stmt.executeUpdate("DROP SEQUENCE IF EXISTS " + sequence + " CASCADE");
      } else {
        // In a transaction so do not ignore errors for missing object
        stmt.executeUpdate("DROP SEQUENCE " + sequence + " CASCADE");
      }
    } finally {
      closeQuietly(stmt);
    }
  }

  /*
   * Helper - drops a table
   */
  public static void dropTable(Connection con, String table) throws SQLException {
    Statement stmt = con.createStatement();
    try {
      if (con.getAutoCommit()) {
        // Not in a transaction so ignore error for missing object
        stmt.executeUpdate("DROP TABLE IF EXISTS " + table + " CASCADE ");
      } else {
        // In a transaction so do not ignore errors for missing object
        stmt.executeUpdate("DROP TABLE " + table + " CASCADE ");
      }
    } finally {
      closeQuietly(stmt);
    }
  }

  /*
   * Helper - drops a type
   */
  public static void dropType(Connection con, String type) throws SQLException {
    Statement stmt = con.createStatement();
    try {
      if (con.getAutoCommit()) {
        // Not in a transaction so ignore error for missing object
        stmt.executeUpdate("DROP TYPE IF EXISTS " + type + " CASCADE");
      } else {
        // In a transaction so do not ignore errors for missing object
        stmt.executeUpdate("DROP TYPE " + type + " CASCADE");
      }
    } finally {
      closeQuietly(stmt);
    }
  }

  public static void assertNumberOfRows(Connection con, String tableName, int expectedRows, String message)
      throws SQLException {
    PreparedStatement ps = null;
    ResultSet rs = null;
    try {
      ps = con.prepareStatement("select count(*) from " + tableName + " as t");
      rs = ps.executeQuery();
      rs.next();
      Assert.assertEquals(message, expectedRows, rs.getInt(1));
    } finally {
      closeQuietly(rs);
      closeQuietly(ps);
    }
  }

  public static void assertTransactionState(String message, Connection con, TransactionState expected) {
    TransactionState actual = TestUtil.getTransactionState(con);
    Assert.assertEquals(message, expected, actual);
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
   *
   * @param v value to prefix
   *
   * @param l number of digits (0-10)
   */
  public static String fix(int v, int l) {
    String s = "0000000000".substring(0, l) + Integer.toString(v);
    return s.substring(s.length() - l);
  }

  public static String escapeString(Connection con, String value) throws SQLException {
    if (con == null) {
      throw new NullPointerException("Connection is null");
    }
    if (con instanceof PgConnection) {
      return ((PgConnection) con).escapeString(value);
    }
    return value;
  }

  public static boolean getStandardConformingStrings(Connection con) {
    if (con == null) {
      throw new NullPointerException("Connection is null");
    }
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
  public static boolean haveMinimumServerVersion(Connection con, int version) throws SQLException {
    if (con == null) {
      throw new NullPointerException("Connection is null");
    }
    if (con instanceof PgConnection) {
      return ((PgConnection) con).haveMinimumServerVersion(version);
    }
    return false;
  }

  public static boolean haveMinimumServerVersion(Connection con, Version version)
      throws SQLException {
    if (con == null) {
      throw new NullPointerException("Connection is null");
    }
    if (con instanceof PgConnection) {
      return ((PgConnection) con).haveMinimumServerVersion(version);
    }
    return false;
  }

  public static boolean haveMinimumJVMVersion(String version) {
    String jvm = java.lang.System.getProperty("java.version");
    return (jvm.compareTo(version) >= 0);
  }

  public static boolean haveIntegerDateTimes(Connection con) {
    if (con == null) {
      throw new NullPointerException("Connection is null");
    }
    if (con instanceof PgConnection) {
      return ((PgConnection) con).getQueryExecutor().getIntegerDateTimes();
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

  public static List<String> resultSetToLines(ResultSet rs) throws SQLException {
    List<String> res = new ArrayList<String>();
    ResultSetMetaData rsmd = rs.getMetaData();
    StringBuilder sb = new StringBuilder();
    while (rs.next()) {
      sb.setLength(0);
      for (int i = 1; i <= rsmd.getColumnCount(); i++) {
        if (i != 1) {
          sb.append(',');
        }
        sb.append(rs.getString(i));
      }
      res.add(sb.toString());
    }
    return res;
  }

  public static String join(List<String> list) {
    StringBuilder sb = new StringBuilder();
    for (String s : list) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(s);
    }
    return sb.toString();
  }

  /*
   * Find the column for the given label. Only SQLExceptions for system or set-up problems are
   * thrown. The PSQLState.UNDEFINED_COLUMN type exception is consumed to allow cleanup. Relying on
   * the caller to detect if the column lookup was successful.
   */
  public static int findColumn(PreparedStatement query, String label) throws SQLException {
    int returnValue = 0;
    ResultSet rs = query.executeQuery();
    if (rs.next()) {
      try {
        returnValue = rs.findColumn(label);
      } catch (SQLException sqle) {
      } // consume exception to allow cleanup of resource.
    }
    rs.close();
    return returnValue;
  }

  /**
   * Close a Connection and ignore any errors during closing.
   */
  public static void closeQuietly(@Nullable Connection conn) {
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
  public static void closeQuietly(@Nullable Statement stmt) {
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
  public static void closeQuietly(@Nullable ResultSet rs) {
    if (rs != null) {
      try {
        rs.close();
      } catch (SQLException ignore) {
      }
    }
  }

  public static void recreateLogicalReplicationSlot(Connection connection, String slotName, String outputPlugin)
      throws SQLException, InterruptedException, TimeoutException {
    //drop previous slot
    dropReplicationSlot(connection, slotName);

    PreparedStatement stm = null;
    try {
      stm = connection.prepareStatement("SELECT * FROM pg_create_logical_replication_slot(?, ?)");
      stm.setString(1, slotName);
      stm.setString(2, outputPlugin);
      stm.execute();
    } finally {
      closeQuietly(stm);
    }
  }

  public static void recreatePhysicalReplicationSlot(Connection connection, String slotName)
      throws SQLException, InterruptedException, TimeoutException {
    //drop previous slot
    dropReplicationSlot(connection, slotName);

    PreparedStatement stm = null;
    try {
      stm = connection.prepareStatement("SELECT * FROM pg_create_physical_replication_slot(?)");
      stm.setString(1, slotName);
      stm.execute();
    } finally {
      closeQuietly(stm);
    }
  }

  public static void dropReplicationSlot(Connection connection, String slotName)
      throws SQLException, InterruptedException, TimeoutException {
    if (haveMinimumServerVersion(connection, ServerVersion.v9_5)) {
      PreparedStatement stm = null;
      try {
        stm = connection.prepareStatement(
            "select pg_terminate_backend(active_pid) from pg_replication_slots "
                + "where active = true and slot_name = ?");
        stm.setString(1, slotName);
        stm.execute();
      } finally {
        closeQuietly(stm);
      }
    }

    waitStopReplicationSlot(connection, slotName);

    PreparedStatement stm = null;
    try {
      stm = connection.prepareStatement(
          "select pg_drop_replication_slot(slot_name) "
              + "from pg_replication_slots where slot_name = ?");
      stm.setString(1, slotName);
      stm.execute();
    } finally {
      closeQuietly(stm);
    }
  }

  public static boolean isReplicationSlotActive(Connection connection, String slotName)
      throws SQLException {
    PreparedStatement stm = null;
    ResultSet rs = null;

    try {
      stm =
          connection.prepareStatement("select active from pg_replication_slots where slot_name = ?");
      stm.setString(1, slotName);
      rs = stm.executeQuery();
      return rs.next() && rs.getBoolean(1);
    } finally {
      closeQuietly(rs);
      closeQuietly(stm);
    }
  }

  /**
   * Execute a SQL query with a given connection and return whether any rows were
   * returned. No column data is fetched.
   */
  public static boolean executeQuery(Connection conn, String sql) throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(sql);
    boolean hasNext = rs.next();
    rs.close();
    stmt.close();
    return hasNext;
  }

  /**
   * Retrieve the backend process id for a given connection.
   */
  public static int getBackendPid(Connection conn) throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT pg_backend_pid()");
    rs.next();
    int pid = rs.getInt(1);
    rs.close();
    stmt.close();
    return pid;
  }

  /**
   * Executed pg_terminate_backend(...) to terminate the server process for
   * a given process id with the given connection.
   */
  public static boolean terminateBackend(Connection conn, int backendPid) throws SQLException {
    PreparedStatement stmt = conn.prepareStatement("SELECT pg_terminate_backend(?)");
    stmt.setInt(1, backendPid);
    ResultSet rs = stmt.executeQuery();
    rs.next();
    boolean wasTerminated = rs.getBoolean(1);
    rs.close();
    stmt.close();
    return wasTerminated;
  }

  /**
   * Create a new connection using the default test credentials and use it to
   * attempt to terminate the specified backend process.
   */
  public static boolean terminateBackend(int backendPid) throws SQLException {
    Connection conn = TestUtil.openPrivilegedDB();
    try {
      return terminateBackend(conn, backendPid);
    } finally {
      conn.close();
    }
  }

  /**
   * Retrieve the given connection backend process id, then create a new connection
   * using the default test credentials and attempt to terminate the process.
   */
  public static boolean terminateBackend(Connection conn) throws SQLException {
    int pid = getBackendPid(conn);
    return terminateBackend(pid);
  }

  public static TransactionState getTransactionState(Connection conn) {
    return ((BaseConnection) conn).getTransactionState();
  }

  private static void waitStopReplicationSlot(Connection connection, String slotName)
      throws InterruptedException, TimeoutException, SQLException {
    long startWaitTime = System.currentTimeMillis();
    boolean stillActive;
    long timeInWait = 0;

    do {
      stillActive = isReplicationSlotActive(connection, slotName);
      if (stillActive) {
        TimeUnit.MILLISECONDS.sleep(100L);
        timeInWait = System.currentTimeMillis() - startWaitTime;
      }
    } while (stillActive && timeInWait <= 30000);

    if (stillActive) {
      throw new TimeoutException("Wait stop replication slot " + timeInWait + " timeout occurs");
    }
  }

  public static void execute(String sql, Connection connection) throws SQLException {
    Statement stmt = connection.createStatement();
    try {
      stmt.execute(sql);
    } finally {
      try {
        stmt.close();
      } catch (SQLException e) {
      }
    }
  }
}
