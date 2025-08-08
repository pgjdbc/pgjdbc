/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TransactionState;
import org.postgresql.core.Version;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.ResourceLock;
import org.postgresql.util.PSQLException;
import org.postgresql.util.URLCoder;
import org.postgresql.util.internal.FileUtils;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Utility class for JDBC tests.
 */
public class TestUtil {
  /**
   * A constant that defines the prefix used for test URL-related property keys.
   * All the properties starting with this prefix would be passed to the JDBC URL.
   */
  public static final String TEST_URL_PROPERTY_PREFIX = "test.url.";

  private static final ResourceLock lock = new ResourceLock();

  static {
    initDriver();
  }

  private static boolean isTestUrlProperty(String propertyName, PGProperty property) {
    return propertyName.startsWith(property.getName(), TEST_URL_PROPERTY_PREFIX.length());
  }

  /**
   * Sets a test URL property in the provided {@link Properties} object.
   * The given {@link PGProperty} will be passed with JDBC URL rather than {@link Properties}
   * when opening a connection.
   *
   * @param props    the {@link Properties} object where the property will be set
   * @param property the {@link PGProperty} object representing the property name
   * @param value    the value to associate with the property key
   */
  public static void setTestUrlProperty(Properties props, PGProperty property, String value) {
    props.setProperty(TEST_URL_PROPERTY_PREFIX + property.getName(), value);
  }

  /**
   * Retrieves the test URL property value based on the specified property name and default value.
   *
   * @param props the Properties object containing the application configuration
   * @param property the PGProperty object representing the specific property to retrieve
   * @return the test URL property value if it exists in the Properties object;
   *         otherwise, the default value of the specified property
   */
  public static @Nullable String getTestUrlProperty(Properties props, PGProperty property) {
    return props.getProperty(
        TEST_URL_PROPERTY_PREFIX + property.getName(), property.getDefaultValue());
  }

  /**
   * Returns the Test database JDBC URL
   */
  public static String getURL() {
    return getURL(System.getProperties());
  }

  /**
   * Constructs a JDBC URL using the provided properties. The method builds the
   * URL for a PostgreSQL database connection by utilizing specific property values
   * for host, port, and database name, and appends additional properties as parameters.
   * The URL uses {@link #setTestUrlProperty(Properties, PGProperty, String)} values for
   * building the URL.
   *
   * <p>Note: the method uses only exliclitly provided properties, and it does not use
   * build.properties and other property sources.
   *
   * @param props the properties object containing key-value pairs used to construct the URL.
   *              The values that start with {@link #TEST_URL_PROPERTY_PREFIX} will be included
   *              into the URL.
   * @return the constructed JDBC URL as a String.
   */
  public static String getURL(Properties props) {
    initDriver();
    String host = getTestUrlProperty(props, PGProperty.PG_HOST);
    String port = getTestUrlProperty(props, PGProperty.PG_PORT);
    String database = getTestUrlProperty(props, PGProperty.PG_DBNAME);
    StringBuilder sb = new StringBuilder("jdbc:postgresql://");
    sb.append(host).append(":").append(port).append("/").append(database);
    sb.append("?ApplicationName=Driver Tests");
    for (String propertyName : props.stringPropertyNames()) {
      if (!propertyName.startsWith(TEST_URL_PROPERTY_PREFIX)) {
        continue;
      }
      if (isTestUrlProperty(propertyName, PGProperty.PG_HOST)
          || isTestUrlProperty(propertyName, PGProperty.PG_PORT)
          || isTestUrlProperty(propertyName, PGProperty.PG_DBNAME)
      ) {
        continue;
      }
      String value = props.getProperty(propertyName);
      if (value == null) {
        throw new IllegalArgumentException("Null value for property " + propertyName);
      }
      String name = propertyName.substring(TEST_URL_PROPERTY_PREFIX.length());
      sb.append("&").append(URLCoder.encode(name)).append("=").append(URLCoder.encode(value));
    }
    return sb.toString();
  }

  /*
   * Returns the Test server
   */
  public static String getServer() {
    return castNonNull(getTestUrlProperty(System.getProperties(), PGProperty.PG_HOST));
  }

  /*
   * Returns the Test port
   */
  public static int getPort() {
    return Integer.parseInt(
        castNonNull(getTestUrlProperty(System.getProperties(), PGProperty.PG_PORT)));
  }

  /*
   * Returns the server side prepared statement threshold.
   */
  public static int getPrepareThreshold() throws PSQLException {
    return PGProperty.PREPARE_THRESHOLD.getInt(System.getProperties());
  }

  public static int getProtocolVersion() throws PSQLException {
    return PGProperty.PROTOCOL_VERSION.getInt(System.getProperties());
  }

  /*
   * Returns the Test database
   */
  public static String getDatabase() {
    return castNonNull(getTestUrlProperty(System.getProperties(), PGProperty.PG_DBNAME));
  }

  /*
   * Returns the Postgresql username
   */
  public static @Nullable String getUser() {
    return PGProperty.USER.getOrDefault(System.getProperties());
  }

  /*
   * Returns the user's password
   */
  public static @Nullable String getPassword() {
    return PGProperty.PASSWORD.getOrDefault(System.getProperties());
  }

  /*
   * Returns password for default callbackhandler
   */
  public static @Nullable String getSslPassword() {
    return System.getProperty(PGProperty.SSL_PASSWORD.getName());
  }

  /*
   * Returns the user for SSPI authentication tests
   */
  public static @Nullable String getSSPIUser() {
    return System.getProperty("sspiusername");
  }

  /*
   * postgres like user
   */
  public static @Nullable String getPrivilegedUser() {
    return System.getProperty("privilegedUser");
  }

  public static @Nullable String getPrivilegedPassword() {
    return System.getProperty("privilegedPassword");
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

  private static boolean initialized;

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
          p.load(FileUtils.newBufferedInputStream(f));
        } catch (IOException ex) {
          // ignore
        }
      }
    }
    return p;
  }

  private static @Nullable Properties sslTestProperties;

  private static void initSslTestProperties() {
    try (ResourceLock ignore = lock.obtain()) {
      if (sslTestProperties == null) {
        sslTestProperties = TestUtil.loadPropertyFiles("ssltest.properties");
      }
    }
  }

  private static @Nullable String getSslTestProperty(String name) {
    initSslTestProperties();
    return castNonNull(sslTestProperties).getProperty(name);
  }

  public static void assumeSslTestsEnabled() {
    assumeTrue(Boolean.parseBoolean(getSslTestProperty("enable_ssl_tests")));
  }

  public static String getSslTestCertPath(String name) {
    String certdirProp = getSslTestProperty("certdir");
    if (certdirProp == null) {
      throw new IllegalArgumentException("Missing property certdir in ssltest.properties");
    }
    File certdir = TestUtil.getFile(certdirProp);
    return new File(certdir, name).getAbsolutePath();
  }

  public static void initDriver() {
    try (ResourceLock ignore = lock.obtain()) {
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
    return openPrivilegedDB(x -> { });
  }

  public static Connection openPrivilegedDB(Consumer<? super Properties> props) throws SQLException {
    Properties properties = new Properties();
    PGProperty.USER.set(properties, getPrivilegedUser());
    PGProperty.PASSWORD.set(properties, getPrivilegedPassword());
    PGProperty.OPTIONS.set(properties, "-c synchronous_commit=on");
    props.accept(properties);
    return openDB(properties);
  }

  public static Connection openReplicationConnection() throws Exception {
    Properties properties = new Properties();
    PGProperty.ASSUME_MIN_SERVER_VERSION.set(properties, "9.4");
    PGProperty.PROTOCOL_VERSION.set(properties, "3");
    PGProperty.REPLICATION.set(properties, "database");
    //Only simple query protocol available for replication connection
    PGProperty.PREFER_QUERY_MODE.set(properties, "simple");
    PGProperty.USER.set(properties, TestUtil.getPrivilegedUser());
    PGProperty.PASSWORD.set(properties, TestUtil.getPrivilegedPassword());
    PGProperty.OPTIONS.set(properties, "-c synchronous_commit=on");
    return TestUtil.openDB(properties);
  }

  /**
   * Helper - opens a connection.
   *
   * @return connection
   */
  public static Connection openDB() throws SQLException {
    return openDB(new Properties());
  }

  /**
   * Helper - opens a connection with the allowance for passing additional parameters, like
   * "compatible".
   */
  public static Connection openDB(Properties props) throws SQLException {
    Properties propsWithDefaults = mergeDefaultProperties(props);
    return DriverManager.getConnection(getURL(propsWithDefaults), propsWithDefaults);
  }

  /**
   * Merges the default properties with the provided properties. The method prioritizes
   * properties in the following order:
   * 1. Properties passed within the method argument.
   * 2. System properties.
   * 3. Properties passed with {@code build.properties} file.
   *
   * @param props the properties object to merge with the default properties. Can be empty.
   * @return a new Properties object containing the merged properties with precedence
   *     applied as described.
   */
  public static Properties mergeDefaultProperties(Properties props) {
    // Properties priority:
    // 1. The ones that are passed within tests
    // 2. System.properties
    // 3. build.properties

    Properties propsWithDefaults;
    // System.properties should override the given props
    // The trivial case is when the input props argument is empty (including props.defaults)
    Set<String> propertyNames = props.stringPropertyNames();
    Properties systemProperties = System.getProperties();
    if (propertyNames.isEmpty()) {
      // When argument props is empty, default to System.properties
      propsWithDefaults = systemProperties;
    } else {
      // Copy all the properties from the given set and make system ones as a fallback
      propsWithDefaults = new Properties(systemProperties);
      for (String propertyName : propertyNames) {
        String value = props.getProperty(propertyName);
        if (value == null) {
          throw new IllegalArgumentException("Property " + propertyName + " is null");
        }
        propsWithDefaults.setProperty(propertyName, value);
      }
    }
    return propsWithDefaults;
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
    dropObject(con, "SCHEMA", schema);
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

  /*
   * Helper - creates a view
   */
  public static void createView(Connection con, String viewName, String query)
      throws SQLException {
    try ( Statement st = con.createStatement() ) {
      // Drop the view
      dropView(con, viewName);

      String sql = "CREATE VIEW " + viewName + " AS " + query;

      st.executeUpdate(sql);
    }
  }

  /*
   * Helper - creates a materialized view
   */
  public static void createMaterializedView(Connection con, String matViewName, String query)
      throws SQLException {
    try ( Statement st = con.createStatement() ) {
      // Drop the view
      dropMaterializedView(con, matViewName);

      String sql = "CREATE MATERIALIZED VIEW " + matViewName + " AS " + query;

      st.executeUpdate(sql);
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
   * @param domain String
   */
  public static void dropDomain(Connection con, String domain)
      throws SQLException {
    dropObject(con, "DOMAIN", domain);
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
    dropObject(con, "SEQUENCE", sequence);
  }

  /*
   * Helper - drops a table
   */
  public static void dropTable(Connection con, String table) throws SQLException {
    dropObject(con, "TABLE", table);
  }

  /*
   * Helper - drops a view
   */
  public static void dropView(Connection con, String view) throws SQLException {
    dropObject(con, "VIEW", view);
  }

  /*
   * Helper - drops a materialized view
   */
  public static void dropMaterializedView(Connection con, String matView) throws SQLException {
    dropObject(con, "MATERIALIZED VIEW", matView);
  }

  /*
   * Helper - drops a type
   */
  public static void dropType(Connection con, String type) throws SQLException {
    dropObject(con, "TYPE", type);
  }

  /*
   * Drops a function with a given signature.
   */
  public static void dropFunction(Connection con, String name, String arguments) throws SQLException {
    dropObject(con, "FUNCTION", name + "(" + arguments + ")");
  }

  private static void dropObject(Connection con, String type, String name) throws SQLException {
    Statement stmt = con.createStatement();
    try {
      if (con.getAutoCommit()) {
        // Not in a transaction so ignore error for missing object
        stmt.executeUpdate("DROP " + type + " IF EXISTS " + name + " CASCADE");
      } else {
        // In a transaction so do not ignore errors for missing object
        stmt.executeUpdate("DROP " + type + " " + name + " CASCADE");
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
      assertEquals(expectedRows, rs.getInt(1), message);
    } finally {
      closeQuietly(rs);
      closeQuietly(ps);
    }
  }

  public static void assertTransactionState(String message, Connection con, TransactionState expected) {
    TransactionState actual = TestUtil.getTransactionState(con);
    assertEquals(expected, actual, message);
  }

  /*
   * Helper - generates INSERT SQL - very simple
   */
  public static String insertSQL(String table, String values) {
    return insertSQL(table, null, values);
  }

  public static String insertSQL(String table, @Nullable String columns, String values) {
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

  public static String selectSQL(String table, String columns, @Nullable String where) {
    return selectSQL(table, columns, where, null);
  }

  public static String selectSQL(String table, String columns, @Nullable String where, @Nullable String other) {
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

  public static void assumeHaveMinimumServerVersion(Version version)
      throws SQLException {
    try (Connection conn = openPrivilegedDB()) {
      assumeTrue(TestUtil.haveMinimumServerVersion(conn, version));
    }
  }

  public static boolean haveMinimumJVMVersion(String version) {
    String jvm = java.lang.System.getProperty("java.version");
    return jvm.compareTo(version) >= 0;
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
    List<String> res = new ArrayList<>();
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
    try (ResultSet rs = query.executeQuery()) {
      if (!rs.next()) {
        return 0;
      }
      try {
        return rs.findColumn(label);
      } catch (SQLException e) {
        // Column was not found, return 0
        return 0;
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
        // ignore
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
        // ignore
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
   * Execute a SQL query with a given connection, fetch the first row, and return its
   * string value.
   */
  public static @Nullable String queryForString(Connection conn, String sql) throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(sql);
    assertTrue(rs.next(), () -> "Query should have returned exactly one row but none was found: " + sql);
    String value = rs.getString(1);
    assertFalse(rs.next(), () -> "Query should have returned exactly one row but more than one found: " + sql);
    rs.close();
    stmt.close();
    return value;
  }

  /**
   * Same as queryForString(...) above but with a single string param.
   */
  public static @Nullable String queryForString(Connection conn, String sql, String param) throws SQLException {
    PreparedStatement stmt = conn.prepareStatement(sql);
    stmt.setString(1, param);
    ResultSet rs = stmt.executeQuery();
    assertTrue(rs.next(), () -> "Query should have returned exactly one row but none was found: " + sql);
    String value = rs.getString(1);
    assertFalse(rs.next(), () -> "Query should have returned exactly one row but more than one found: " + sql);
    rs.close();
    stmt.close();
    return value;
  }

  /**
   * Execute a SQL query with a given connection, fetch the first row, and return its
   * boolean value.
   */
  public static @Nullable Boolean queryForBoolean(Connection conn, String sql) throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(sql);
    assertTrue(rs.next(), () -> "Query should have returned exactly one row but none was found: " + sql);
    Boolean value = rs.getBoolean(1);
    if (rs.wasNull()) {
      value = null;
    }
    assertFalse(rs.next(), () -> "Query should have returned exactly one row but more than one found: " + sql);
    rs.close();
    stmt.close();
    return value;
  }

  /**
   * Retrieve the backend process id for a given connection.
   */
  public static int getBackendPid(Connection conn) throws SQLException {
    PGConnection pgConn = conn.unwrap(PGConnection.class);
    return pgConn.getBackendPID();
  }

  public static boolean isPidAlive(Connection conn, int pid) throws SQLException {
    String sql = haveMinimumServerVersion(conn, ServerVersion.v9_2)
        ? "SELECT EXISTS (SELECT * FROM pg_stat_activity WHERE pid = ?)" // 9.2+ use pid column
        : "SELECT EXISTS (SELECT * FROM pg_stat_activity WHERE procpid = ?)"; // Use older procpid
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setInt(1, pid);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  public static boolean waitForBackendTermination(Connection conn, int pid) throws SQLException, InterruptedException {
    return waitForBackendTermination(conn, pid, Duration.ofSeconds(30), Duration.ofMillis(10));
  }

  /**
   * Wait for a backend process to terminate and return whether it actual terminated within the maximum wait time.
   */
  public static boolean waitForBackendTermination(Connection conn, int pid, Duration timeout, Duration sleepDelay) throws SQLException, InterruptedException {
    long started = System.currentTimeMillis();
    do {
      if (!isPidAlive(conn, pid)) {
        return true;
      }
      Thread.sleep(sleepDelay.toMillis());
    } while ((System.currentTimeMillis() - started) < timeout.toMillis());
    return !isPidAlive(conn, pid);
  }

  /**
   * Create a new connection to the same database as the supplied connection but with the privileged credentials.
   */
  private static Connection createPrivilegedConnection(Connection conn) throws SQLException {
    String url = conn.getMetaData().getURL();
    if (url == null) {
      throw new IllegalStateException("conn.getMetaData().getURL() returned null, so can't reconstruct the URL");
    }
    Properties props = new Properties(conn.getClientInfo());
    PGProperty.USER.set(props, getPrivilegedUser());
    PGProperty.PASSWORD.set(props, getPrivilegedPassword());
    return DriverManager.getConnection(url, props);
  }

  /**
   * Executed pg_terminate_backend(...) to terminate the server process for
   * a given process id with the given connection.
   * This method does not wait for the backend process to exit.
   */
  private static boolean pgTerminateBackend(Connection privConn, int backendPid) throws SQLException {
    try (PreparedStatement stmt = privConn.prepareStatement("SELECT pg_terminate_backend(?)")) {
      stmt.setInt(1, backendPid);
      try (ResultSet rs = stmt.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  /**
   * Open a new privileged connection to the same database as connection and use it to ask to terminate the connection.
   * If the connection is terminated, wait for its process to actual terminate.
   */
  public static boolean terminateBackend(Connection conn) throws SQLException, InterruptedException {
    try (Connection privConn = createPrivilegedConnection(conn)) {
      int pid = getBackendPid(conn);
      if (!pgTerminateBackend(privConn, pid)) {
        return false;
      }
      return waitForBackendTermination(privConn, pid);
    }
  }

  /**
   * Open a new privileged connection to the same database as connection and use it to ask to terminate the connection.
   * NOTE: This function does not wait for the process to terminate.
   */
  public static boolean terminateBackendNoWait(Connection conn) throws SQLException {
    try (Connection privConn = createPrivilegedConnection(conn)) {
      int pid = getBackendPid(conn);
      return pgTerminateBackend(privConn, pid);
    }
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

  /**
   * Executes given SQL via {@link Statement#execute(String)} on a given connection.
   */
  public static void execute(Connection connection, String sql) throws SQLException {
    try (Statement stmt = connection.createStatement()) {
      stmt.execute(sql);
    }
  }
}
