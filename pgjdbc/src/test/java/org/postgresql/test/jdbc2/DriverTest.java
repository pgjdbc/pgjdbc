/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import org.postgresql.Driver;
import org.postgresql.PGEnvironment;
import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.DisableLogger;
import org.postgresql.util.GT;
import org.postgresql.util.PGPropertyUtil;
import org.postgresql.util.StubEnvironmentAndProperties;
import org.postgresql.util.TestLogHandler;
import org.postgresql.util.URLCoder;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.properties.SystemProperties;
import uk.org.webcompere.systemstubs.resource.Resources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/*
 * Tests the dynamically created class org.postgresql.Driver
 *
 */
@StubEnvironmentAndProperties
@Isolated("Records org.postgresql logs, so it needs to work in isolation from the other tests")
class DriverTest {

  @Test
  void urlIsNotForPostgreSQL() throws SQLException {
    Driver driver = new Driver();

    assertNull(driver.connect("jdbc:otherdb:database", new Properties()));
  }

  /**
   * According to the javadoc of java.sql.Driver.connect(...), calling abort when the {@code executor} is {@code null}
   * results in SQLException
   */
  @Test
  void urlIsNull() throws SQLException {
    Driver driver = new Driver();

    assertThrows(SQLException.class, () -> driver.connect(null, new Properties()));
  }

  /**
   * A null URL reaching acceptsURL() is a caller bug. It already threw an NPE before, but an
   * opaque one from deep inside URL parsing; assert the explicit, message-carrying NPE so the
   * fail-fast check is not silently dropped.
   */
  @Test
  void acceptsUrlRejectsNull() {
    Driver driver = new Driver();

    NullPointerException npe =
        assertThrows(NullPointerException.class, () -> driver.acceptsURL(null));
    assertEquals("url", npe.getMessage());
  }

  /*
   * This tests the acceptsURL() method with a couple of well and poorly formed jdbc urls.
   */
  @Test
  void acceptsURL() throws Exception {
    TestUtil.initDriver(); // Set up log levels, etc.

    // Load the driver (note clients should never do it this way!)
    Driver drv = new Driver();
    assertNotNull(drv);

    // These are always correct
    verifyUrl(drv, "jdbc:postgresql:test", "localhost", "5432", "test");
    verifyUrl(drv, "jdbc:postgresql://localhost/test", "localhost", "5432", "test");
    verifyUrl(drv, "jdbc:postgresql://localhost,locahost2/test", "localhost,locahost2", "5432,5432", "test");
    verifyUrl(drv, "jdbc:postgresql://localhost:5433,locahost2:5434/test", "localhost,locahost2", "5433,5434", "test");
    verifyUrl(drv, "jdbc:postgresql://[::1]:5433,:5434,[::1]/test", "[::1],localhost,[::1]", "5433,5434,5432", "test");
    verifyUrl(drv, "jdbc:postgresql://localhost/test?port=8888", "localhost", "8888", "test");
    verifyUrl(drv, "jdbc:postgresql://localhost:5432/test", "localhost", "5432", "test");
    verifyUrl(drv, "jdbc:postgresql://localhost:5432/test?dbname=test2", "localhost", "5432", "test2");
    verifyUrl(drv, "jdbc:postgresql://127.0.0.1/anydbname", "127.0.0.1", "5432", "anydbname");
    verifyUrl(drv, "jdbc:postgresql://127.0.0.1:5433/hidden", "127.0.0.1", "5433", "hidden");
    verifyUrl(drv, "jdbc:postgresql://127.0.0.1:5433/hidden?port=7777", "127.0.0.1", "7777", "hidden");
    verifyUrl(drv, "jdbc:postgresql://[::1]:5740/db", "[::1]", "5740", "db");
    verifyUrl(drv, "jdbc:postgresql://[::1]:5740/my%20data%23base%251?loggerFile=C%3A%5Cdir%5Cfile.log", "[::1]", "5740", "my data#base%1");

    // tests for service syntax
    URL urlFileProps = getClass().getResource("/pg_service/pgservicefileProps.conf");
    assertNotNull(urlFileProps);
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile())
    ).execute(() -> {
      // correct cases
      verifyUrl(drv, "jdbc:postgresql://?service=driverTestService1", "test-host1", "5444", "testdb1");
      verifyUrl(drv, "jdbc:postgresql://?service=driverTestService1&host=other-host", "other-host", "5444", "testdb1");
      verifyUrl(drv, "jdbc:postgresql:///?service=driverTestService1", "test-host1", "5444", "testdb1");
      verifyUrl(drv, "jdbc:postgresql:///?service=driverTestService1&port=3333&dbname=other-db", "test-host1", "3333", "other-db");
      verifyUrl(drv, "jdbc:postgresql://localhost:5432/test?service=driverTestService1", "localhost", "5432", "test");
      verifyUrl(drv, "jdbc:postgresql://localhost:5432/test?port=7777&dbname=other-db&service=driverTestService1", "localhost", "7777", "other-db");
      verifyUrl(drv, "jdbc:postgresql://[::1]:5740/?service=driverTestService1", "[::1]", "5740", "testdb1");
      verifyUrl(drv, "jdbc:postgresql://:5740/?service=driverTestService1", "localhost", "5740", "testdb1");
      verifyUrl(drv, "jdbc:postgresql://[::1]/?service=driverTestService1", "[::1]", "5432", "testdb1");
      verifyUrl(drv, "jdbc:postgresql://localhost/?service=driverTestService2", "localhost", "5432", "testdb1");
    });

    // failover urls
    verifyUrl(drv, "jdbc:postgresql://localhost,127.0.0.1:5432/test", "localhost,127.0.0.1",
        "5432,5432", "test");
    verifyUrl(drv, "jdbc:postgresql://localhost:5433,127.0.0.1:5432/test", "localhost,127.0.0.1",
        "5433,5432", "test");
    verifyUrl(drv, "jdbc:postgresql://[::1],[::1]:5432/db", "[::1],[::1]", "5432,5432", "db");
    verifyUrl(drv, "jdbc:postgresql://[::1]:5740,127.0.0.1:5432/db", "[::1],127.0.0.1", "5740,5432",
        "db");
  }

  @Test
  @DisableLogger(PGPropertyUtil.class)
  void badUrlsTest() throws Exception {
    TestUtil.initDriver(); // Set up log levels, etc.

    // Load the driver (note clients should never do it this way!)
    Driver drv = new Driver();

    // tests for service syntax
    URL urlFileProps = getClass().getResource("/pg_service/pgservicefileProps.conf");
    assertNotNull(urlFileProps);
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile())
    ).execute(() -> {
      // fail cases
      assertFalse(drv.acceptsURL("jdbc:postgresql://?service=driverTestService2"));
    });
  }

  @Test
  @DisableLogger({Driver.class, PGPropertyUtil.class})
  void rejectsBadUrls() {
    TestUtil.initDriver(); // Set up log levels, etc.
    // Load the driver (note clients should never do it this way!)
    Driver drv = new Driver();

    // Badly formatted url's
    assertFalse(drv.acceptsURL("jdbc:postgres:test"));
    assertFalse(drv.acceptsURL("jdbc:postgresql:/test"));
    assertFalse(drv.acceptsURL("jdbc:postgresql:////"));
    assertFalse(drv.acceptsURL("jdbc:postgresql:///?service=my data#base%1"));
    assertFalse(drv.acceptsURL("jdbc:postgresql://[::1]:5740/my data#base%1"));
    assertFalse(drv.acceptsURL("jdbc:postgresql://localhost/dbname?loggerFile=C%3A%5Cdir%5Cfile.%log"));
    assertFalse(drv.acceptsURL("postgresql:test"));
    assertFalse(drv.acceptsURL("db"));
    assertFalse(drv.acceptsURL("jdbc:postgresql://localhost:5432a/test"));
    assertFalse(drv.acceptsURL("jdbc:postgresql://localhost:500000/test"));
    assertFalse(drv.acceptsURL("jdbc:postgresql://localhost:0/test"));
    assertFalse(drv.acceptsURL("jdbc:postgresql://localhost:-2/test"));
  }

  private static void verifyUrl(Driver drv, String url, String hosts, String ports, String dbName)
      throws Exception {
    assertTrue(drv.acceptsURL(url), url);
    Method parseMethod =
        drv.getClass().getDeclaredMethod("parseURL", String.class, Properties.class);
    parseMethod.setAccessible(true);
    Properties p = (Properties) parseMethod.invoke(drv, url, null);
    assertEquals(dbName, p.getProperty(PGProperty.PG_DBNAME.getName()), url);
    assertEquals(hosts, p.getProperty(PGProperty.PG_HOST.getName()), url);
    assertEquals(ports, p.getProperty(PGProperty.PG_PORT.getName()), url);
  }

  /**
   * Tests the connect method by connecting to the test database.
   */
  @Test
  void connect() throws Exception {
    // Test with the url, username & password
    String url = TestUtil.getURL();
    String user = TestUtil.getUser();
    String password = TestUtil.getPassword();

    try (Connection con = DriverManager.getConnection(url, user, password);) {
      assertNotNull(con, "DriverManager.getConnection(url, user, password) should succeed");
    }

    // Test with the username in the url
    try (Connection con = DriverManager.getConnection(
        url
            + "&user=" + URLCoder.encode(user)
            + "&password=" + URLCoder.encode(password));) {
      assertNotNull(con,
          "DriverManager.getConnection(url + \"&user=...&password=...\") should succeed");
    }
  }

  /**
   * Verifies that {@code localSocketAddress} reaches {@code socket.bind()} (see
   * {@code PGStream.createSocket}).
   *
   * <p>The test points the property at an address that is not assigned to any local interface, so
   * the bind must fail and surface as a {@link BindException}. If the property were ignored, the
   * connection would proceed instead. This avoids inspecting {@code inet_client_addr()}, which is
   * unreliable when the server is reached through a proxy or NAT — a Dockerised PostgreSQL, for
   * example, reports the bridge gateway rather than the bound client address.</p>
   *
   * <p>The test is skipped on hosts that allow binding non-local addresses
   * ({@code ip_nonlocal_bind}), where the bind would not fail.</p>
   */
  @Test
  void connectWithLocalSocketAddress() throws Exception {
    // 203.0.113.0/24 is TEST-NET-3 (RFC 5737); it is never assigned to a real interface.
    String unassignableAddress = "203.0.113.1";
    assumeFalse(canBind(unassignableAddress),
        "host allows binding non-local addresses; cannot exercise the bind failure");

    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getUser());
    PGProperty.PASSWORD.set(props, TestUtil.getPassword());
    PGProperty.LOCAL_SOCKET_ADDRESS.set(props, unassignableAddress);

    SQLException ex = assertThrows(SQLException.class,
        () -> DriverManager.getConnection(TestUtil.getURL(), props),
        "binding localSocketAddress to an unassignable address should fail the connection");

    if (!hasCause(ex, BindException.class)) {
      try {
        fail("binding localSocketAddress to " + unassignableAddress
            + " should fail with a BindException, but the connection failed differently");
      } catch (AssertionError e) {
        // Attach the original failure so its full stack trace reaches the CI logs.
        e.addSuppressed(ex);
        throw e;
      }
    }
  }

  private static boolean canBind(String address) throws Exception {
    try (Socket socket = new Socket()) {
      socket.bind(new InetSocketAddress(InetAddress.getByName(address), 0));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
    for (Throwable c = throwable; c != null; c = c.getCause()) {
      if (type.isInstance(c)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Tests the connect method by connecting to the test database.
   */
  @Test
  @DisableLogger(PGPropertyUtil.class)
  @Disabled
  void connectService() throws Exception {
    TestUtil.initDriver(); // Set up log levels, etc.
    String wrongPort = "65536";

    // Create temporary pg_service.conf file
    Path tempDirWithPrefix = Files.createTempDirectory("junit");
    Path tempFile = Files.createTempFile(tempDirWithPrefix, "pg_service", "conf");
    try {
      // Write service section
      String testService1 = "testService1"; // with correct port
      String testService2 = "testService2"; // with wrong port
      try (PrintStream ps = new PrintStream(Files.newOutputStream(tempFile))) {
        ps.printf("[%s]%nhost=%s%nport=%s%ndbname=%s%nuser=%s%npassword=%s%n", testService1, TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(), TestUtil.getUser(), TestUtil.getPassword());
        ps.printf("[%s]%nhost=%s%nport=%s%ndbname=%s%nuser=%s%npassword=%s%n", testService2, TestUtil.getServer(), wrongPort, TestUtil.getDatabase(), TestUtil.getUser(), TestUtil.getPassword());
      }
      // consume service
      Resources.with(
          new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), tempFile.toString(), PGEnvironment.PGSYSCONFDIR.getName(), ""),
          new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", "/tmp/dir-nonexistent")
      ).execute(() -> {
        //
        // testing that properties overriding priority is correct (POSITIVE cases)
        //
        // service=correct port
        Connection con = DriverManager.getConnection(String.format("jdbc:postgresql://?service=%s", testService1));
        assertNotNull(con);
        con.close();
        // service=wrong port; Properties=correct port
        Properties info = new Properties();
        info.setProperty("PGPORT", String.valueOf(TestUtil.getPort()));
        con = DriverManager.getConnection(String.format("jdbc:postgresql://?service=%s", testService2), info);
        assertNotNull(con);
        con.close();
        // service=wrong port; Properties=wrong port; URL port=correct
        info.setProperty("PGPORT", wrongPort);
        con = DriverManager.getConnection(String.format("jdbc:postgresql://:%s/?service=%s", TestUtil.getPort(), testService2), info);
        assertNotNull(con);
        con.close();
        // service=wrong port; Properties=wrong port; URL port=wrong; URL argument=correct port
        con = DriverManager.getConnection(String.format("jdbc:postgresql://:%s/?service=%s&port=%s", wrongPort, testService2, TestUtil.getPort()), info);
        assertNotNull(con);
        con.close();

        //
        // testing that properties overriding priority is correct (NEGATIVE cases)
        //
        // service=wrong port
        try {
          con = DriverManager.getConnection(String.format("jdbc:postgresql://?service=%s", testService2));
          fail("Expected an SQLException because port is out of range");
        } catch (SQLException e) {
          // Expected exception.
        }
        // service=correct port; Properties=wrong port
        info.setProperty("PGPORT", wrongPort);
        try {
          con = DriverManager.getConnection(String.format("jdbc:postgresql://?service=%s", testService1), info);
          fail("Expected an SQLException because port is out of range");
        } catch (SQLException e) {
          // Expected exception.
        }
        // service=correct port; Properties=correct port; URL port=wrong
        info.setProperty("PGPORT", String.valueOf(TestUtil.getPort()));
        try {
          con = DriverManager.getConnection(String.format("jdbc:postgresql://:%s/?service=%s", wrongPort, testService1), info);
          fail("Expected an SQLException because port is out of range");
        } catch (SQLException e) {
          // Expected exception.
        }
        // service=correct port; Properties=correct port; URL port=correct; URL argument=wrong port
        try {
          con = DriverManager.getConnection(String.format("jdbc:postgresql://:%s/?service=%s&port=%s", TestUtil.getPort(), testService1, wrongPort), info);
          fail("Expected an SQLException because port is out of range");
        } catch (SQLException e) {
          // Expected exception.
        }
      });
    } finally {
      // cleanup
      Files.delete(tempFile);
      Files.delete(tempDirWithPrefix);
    }
  }

  /**
   * Tests the password by connecting to the test database.
   * password from .pgpass (correct)
   */
  @Test
  void connectPassword01() throws Exception {
    TestUtil.initDriver(); // Set up log levels, etc.

    // Create temporary .pgpass file
    Path tempDirWithPrefix = Files.createTempDirectory("junit");
    Path tempPgPassFile = Files.createTempFile(tempDirWithPrefix, "pgpass", "conf");
    try {
      try (PrintStream psPass = new PrintStream(Files.newOutputStream(tempPgPassFile))) {
        psPass.printf("%s:%s:%s:%s:%s%n", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(), TestUtil.getUser(), TestUtil.getPassword());
      }
      // ignore pg_service.conf, use .pgpass
      Resources.with(
          new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", PGEnvironment.PGSYSCONFDIR.getName(), ""),
          new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", "/tmp/dir-nonexistent",
              PGEnvironment.ORG_POSTGRESQL_PGPASSFILE.getName(), tempPgPassFile.toString())
      ).execute(() -> {
        // password from .pgpass (correct)
        Connection con = DriverManager.getConnection(String.format("jdbc:postgresql://%s:%s/%s?user=%s", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(), TestUtil.getUser()));
        assertNotNull(con);
        con.close();
      });
    } finally {
      // cleanup
      Files.delete(tempPgPassFile);
      Files.delete(tempDirWithPrefix);
    }
  }

  /**
   * Tests the password by connecting to the test database.
   * password from service (correct) and .pgpass (wrong)
   */
  @Test
  void connectPassword02() throws Exception {
    TestUtil.initDriver(); // Set up log levels, etc.
    String wrongPassword = "random wrong";

    // Create temporary pg_service.conf and .pgpass file
    Path tempDirWithPrefix = Files.createTempDirectory("junit");
    Path tempPgServiceFile = Files.createTempFile(tempDirWithPrefix, "pg_service", "conf");
    Path tempPgPassFile = Files.createTempFile(tempDirWithPrefix, "pgpass", "conf");
    try {
      // Write service section
      String testService1 = "testService1";
      try (PrintStream psService = new PrintStream(Files.newOutputStream(tempPgServiceFile));
           PrintStream psPass = new PrintStream(Files.newOutputStream(tempPgPassFile))) {
        psService.printf("[%s]%nhost=%s%nport=%s%ndbname=%s%nuser=%s%npassword=%s%n", testService1, TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(), TestUtil.getUser(), TestUtil.getPassword());
        psPass.printf("%s:%s:%s:%s:%s%n", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(), TestUtil.getUser(), wrongPassword);
      }
      // ignore pg_service.conf, use .pgpass
      Resources.with(
          new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), tempPgServiceFile.toString(), PGEnvironment.ORG_POSTGRESQL_PGPASSFILE.getName(), tempPgPassFile.toString())
      ).execute(() -> {
        // password from service (correct) and .pgpass (wrong)
        Connection con = DriverManager.getConnection(String.format("jdbc:postgresql://?service=%s", testService1));
        assertNotNull(con);
        con.close();
      });
    } finally {
      // cleanup
      Files.delete(tempPgPassFile);
      Files.delete(tempPgServiceFile);
      Files.delete(tempDirWithPrefix);
    }
  }

  /**
   * Tests the password by connecting to the test database.
   * password from java property (correct) and service (wrong) and .pgpass (wrong)
   */
  @Test
  void connectPassword03() throws Exception {
    TestUtil.initDriver(); // Set up log levels, etc.
    String wrongPassword = "random wrong";

    // Create temporary pg_service.conf and .pgpass file
    Path tempDirWithPrefix = Files.createTempDirectory("junit");
    Path tempPgServiceFile = Files.createTempFile(tempDirWithPrefix, "pg_service", "conf");
    Path tempPgPassFile = Files.createTempFile(tempDirWithPrefix, "pgpass", "conf");
    try {
      // Write service section
      String testService1 = "testService1";
      try (PrintStream psService = new PrintStream(Files.newOutputStream(tempPgServiceFile));
           PrintStream psPass = new PrintStream(Files.newOutputStream(tempPgPassFile))) {
        psService.printf("[%s]%nhost=%s%nport=%s%ndbname=%s%nuser=%s%npassword=%s%n", testService1, TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(), TestUtil.getUser(), wrongPassword);
        psPass.printf("%s:%s:%s:%s:%s%n", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(), TestUtil.getUser(), wrongPassword);
      }
      // ignore pg_service.conf, use .pgpass
      Resources.with(
          new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), tempPgServiceFile.toString(), PGEnvironment.ORG_POSTGRESQL_PGPASSFILE.getName(), tempPgPassFile.toString())
      ).execute(() -> {
        // password from java property (correct) and service (wrong) and .pgpass (wrong)
        Properties info = new Properties();
        PGProperty.PASSWORD.set(info, TestUtil.getPassword());
        Connection con = DriverManager.getConnection(String.format("jdbc:postgresql://?service=%s", testService1), info);
        assertNotNull(con);
        con.close();
      });
    } finally {
      // cleanup
      Files.delete(tempPgPassFile);
      Files.delete(tempPgServiceFile);
      Files.delete(tempDirWithPrefix);
    }
  }

  /**
   * Tests the password by connecting to the test database.
   * password from URL parameter (correct) and java property (wrong) and service (wrong) and .pgpass (wrong)
   */
  @Test
  void connectPassword04() throws Exception {
    TestUtil.initDriver(); // Set up log levels, etc.
    String wrongPassword = "random wrong";

    // Create temporary pg_service.conf and .pgpass file
    Path tempDirWithPrefix = Files.createTempDirectory("junit");
    Path tempPgServiceFile = Files.createTempFile(tempDirWithPrefix, "pg_service", "conf");
    Path tempPgPassFile = Files.createTempFile(tempDirWithPrefix, "pgpass", "conf");
    try {
      // Write service section
      String testService1 = "testService1";
      try (PrintStream psService = new PrintStream(Files.newOutputStream(tempPgServiceFile));
           PrintStream psPass = new PrintStream(Files.newOutputStream(tempPgPassFile))) {
        psService.printf("[%s]%nhost=%s%nport=%s%ndbname=%s%nuser=%s%npassword=%s%n", testService1, TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(), TestUtil.getUser(), wrongPassword);
        psPass.printf("%s:%s:%s:%s:%s%n", TestUtil.getServer(), TestUtil.getPort(), TestUtil.getDatabase(), TestUtil.getUser(), wrongPassword);
      }
      // ignore pg_service.conf, use .pgpass
      Resources.with(
          new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), tempPgServiceFile.toString(), PGEnvironment.ORG_POSTGRESQL_PGPASSFILE.getName(), tempPgPassFile.toString())
      ).execute(() -> {
        //
        Properties info = new Properties();
        PGProperty.PASSWORD.set(info, wrongPassword);
        Connection con = DriverManager.getConnection(String.format("jdbc:postgresql://?service=%s&password=%s", testService1, TestUtil.getPassword()), info);
        assertNotNull(con);
        con.close();
      });
    } finally {
      // cleanup
      Files.delete(tempPgPassFile);
      Files.delete(tempPgServiceFile);
      Files.delete(tempDirWithPrefix);
    }
  }

  /**
   * Tests that pgjdbc performs connection failover if unable to connect to the first host in the
   * URL.
   *
   * @throws Exception if something wrong happens
   */
  @Test
  void connectFailover() throws Exception {
    String url = "jdbc:postgresql://invalidhost.not.here," + TestUtil.getServer() + ":"
        + TestUtil.getPort() + "/" + TestUtil.getDatabase() + "?connectTimeout=5";
    Connection con = DriverManager.getConnection(url, TestUtil.getUser(), TestUtil.getPassword());
    assertNotNull(con);
    con.close();
  }

  /*
   * Test that the readOnly property works.
   */
  @Test
  void readOnly() throws Exception {
    TestUtil.initDriver(); // Set up log levels, etc.

    Connection con = DriverManager.getConnection(TestUtil.getURL() + "&readOnly=true",
        TestUtil.getUser(), TestUtil.getPassword());
    assertNotNull(con);
    assertTrue(con.isReadOnly());
    con.close();

    con = DriverManager.getConnection(TestUtil.getURL() + "&readOnly=false", TestUtil.getUser(),
        TestUtil.getPassword());
    assertNotNull(con);
    assertFalse(con.isReadOnly());
    con.close();

    con =
        DriverManager.getConnection(TestUtil.getURL(), TestUtil.getUser(), TestUtil.getPassword());
    assertNotNull(con);
    assertFalse(con.isReadOnly());
    con.close();
  }

  @Test
  void registration() throws Exception {
    TestUtil.initDriver();

    // Driver is initially registered because it is automatically done when class is loaded
    assertTrue(Driver.isRegistered());

    ArrayList<java.sql.Driver> drivers = Collections.list(DriverManager.getDrivers());
    searchInstanceOf: {

      for (java.sql.Driver driver : drivers) {
        if (driver instanceof Driver) {
          break searchInstanceOf;
        }
      }
      fail("Driver has not been found in DriverManager's list but it should be registered");
    }

    // Deregister the driver
    Driver.deregister();
    assertFalse(Driver.isRegistered());

    drivers = Collections.list(DriverManager.getDrivers());
    for (java.sql.Driver driver : drivers) {
      if (driver instanceof Driver) {
        fail("Driver should be deregistered but it is still present in DriverManager's list");
      }
    }

    // register again the driver
    Driver.register();
    assertTrue(Driver.isRegistered());

    drivers = Collections.list(DriverManager.getDrivers());
    for (java.sql.Driver driver : drivers) {
      if (driver instanceof Driver) {
        return;
      }
    }
    fail("Driver has not been found in DriverManager's list but it should be registered");
  }

  @Test
  void systemErrIsNotClosedWhenCreatedMultipleConnections() throws Exception {
    TestUtil.initDriver();
    PrintStream err = System.err;
    PrintStream buffer = new PrintStream(new ByteArrayOutputStream());
    System.setErr(buffer);
    try {
      Connection con = DriverManager.getConnection(TestUtil.getURL(), TestUtil.getUser(), TestUtil.getPassword());
      try {
        assertNotNull(con);
      } finally {
        con.close();
      }
      con = DriverManager.getConnection(TestUtil.getURL(), TestUtil.getUser(), TestUtil.getPassword());
      try {
        assertNotNull(con);
        System.err.println();
        assertFalse(System.err.checkError(), "The System.err should not be closed.");
      } finally {
        con.close();
      }
    } finally {
      System.setErr(err);
    }
  }

  private static void setProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static final String URL_CREDENTIALS =
      "password=secret&sslpassword=passphrase&oauthToken=token";

  private static final String MASKED_URL_CREDENTIALS =
      "password=***&sslpassword=***&oauthToken=***";

  @Test
  void malformedUrlWarningsMaskCredentials() {
    List<String> messages = TestLogHandler.recordFineMessages(Driver.class, () -> {
      assertNull(Driver.parseURL("jdbc:postgresql://localhost/a/b?" + URL_CREDENTIALS, null));
      assertNull(Driver.parseURL("jdbc:postgresql://localhost?" + URL_CREDENTIALS, null));
    });

    assertEquals(
        Arrays.asList(
            "JDBC URL contains too many / characters: jdbc:postgresql://localhost/a/b?"
                + MASKED_URL_CREDENTIALS,
            "JDBC URL must contain a / at the end of the host or port: jdbc:postgresql://localhost?"
                + MASKED_URL_CREDENTIALS),
        messages);
  }

  @Test
  void unableToParseUrlMasksCredentials() {
    // The URL has too many / characters, so parseURL logs a WARNING; keep it off the console
    TestLogHandler.recordFineMessages(Driver.class, () -> {
      SQLException e = assertThrows(SQLException.class,
          () -> new Driver().connect("jdbc:postgresql://localhost/a/b?" + URL_CREDENTIALS,
              new Properties()));
      assertEquals(
          GT.tr("Unable to parse URL {0}", "jdbc:postgresql://localhost/a/b?" + MASKED_URL_CREDENTIALS),
          e.getMessage());
    });
  }

  /**
   * DriverManager asks every driver whether it accepts a URL, so the driver sees the URLs of other
   * drivers, whose credentials it cannot mask.
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "jdbc:sqlserver://h;user=u;password=secret",
      "jdbc:oracle:thin:u/secret@h:1521/x",
  })
  void otherDriverUrlIsNotLogged(String url) {
    List<String> messages = TestLogHandler.recordFineMessages(Driver.class,
        () -> assertFalse(new Driver().acceptsURL(url), url));
    assertEquals(Arrays.asList("JDBC URL does not start with \"jdbc:postgresql:\""), messages);
  }

  private static final String USER_INFO_WARNING =
      "JDBC URL must not contain user:password@ before the host: use the user and password"
          + " parameters instead";
  private static final String PARAMETER_NAME_WARNING =
      "JDBC URL must not contain @ or / in a parameter name: use the user and password"
          + " parameters instead of user:password@";

  static Stream<Arguments> urlsWithUserInfo() {
    return Stream.of(
        arguments("jdbc:postgresql://alice:secret@db/app", USER_INFO_WARNING),
        arguments("jdbc:postgresql://alice:secret@db:5432/app", USER_INFO_WARNING),
        arguments("jdbc:postgresql://db1,alice:secret@db2/app", USER_INFO_WARNING),
        arguments("jdbc:postgresql://alice@db/app", USER_INFO_WARNING),
        // As in libpq, a raw "?" in the password does not end the search for user info, even when
        // the text after it looks like a known parameter
        arguments("jdbc:postgresql://alice:sec?ret@db/app", USER_INFO_WARNING),
        arguments("jdbc:postgresql://alice:sec?user=ret@db/app", USER_INFO_WARNING),
        // A raw "/" and then "?" in the password ends the search at the "/" and moves the "@" into a
        // parameter name. The port "pa" would reach the port warning
        arguments("jdbc:postgresql://alice:pa/x?y@db/app", PARAMETER_NAME_WARNING),
        // The port "1234" is valid, so the URL would be accepted and logged when connecting
        arguments("jdbc:postgresql://alice:1234/x?y@db/app", PARAMETER_NAME_WARNING),
        // "x%zz" would otherwise be decoded as the database name
        arguments("jdbc:postgresql://alice:pa/x%zz?y@db/app", PARAMETER_NAME_WARNING),
        arguments("jdbc:postgresql://alice:pa/x?y&z@db/app", PARAMETER_NAME_WARNING));
  }

  /**
   * libpq accepts postgresql://user:password@host, the driver does not, and must not disclose the
   * password while it refuses the URL: the parts of the password that end up as the host, the port,
   * or the database name must not be logged, or used to connect.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("urlsWithUserInfo")
  void userInfoIsRejectedWithoutDisclosingIt(String url, String expectedWarning) {
    List<String> driverMessages = new ArrayList<>();
    List<String> propertyUtilMessages = TestLogHandler.recordFineMessages(PGPropertyUtil.class,
        () -> driverMessages.addAll(TestLogHandler.recordFineMessages(Driver.class,
            () -> assertNull(Driver.parseURL(url, null), url))));
    assertEquals(Arrays.asList(expectedWarning), driverMessages);
    assertEquals(Collections.emptyList(), propertyUtilMessages);
  }

  static Stream<Arguments> urlsWithSlashInPassword() {
    return Stream.of(
        arguments("jdbc:postgresql://alice:sec/ret@db/app",
            Arrays.asList("JDBC URL contains too many / characters: jdbc:postgresql://***@db/app"),
            Collections.emptyList()),
        // Host "alice", port "sec", and database "ret@db"
        arguments("jdbc:postgresql://alice:sec/ret@db",
            Collections.emptyList(),
            Arrays.asList("Invalid port number: port 1 of 1 is not a number")),
        arguments("jdbc:postgresql://alice:sec/ret%zz@db",
            Arrays.asList("JDBC URL database name could not be decoded"),
            Collections.emptyList()));
  }

  /**
   * As in libpq, a raw "/" in the password ends the search for user info, so the "@" after it is
   * a part of the database name. Such a URL is refused for another reason, and the parts of the
   * password that end up as the port or the database name must not be logged.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("urlsWithSlashInPassword")
  void slashInPasswordIsNotDisclosed(String url, List<String> expectedDriverMessages,
      List<String> expectedPropertyUtilMessages) {
    List<String> driverMessages = new ArrayList<>();
    List<String> propertyUtilMessages = TestLogHandler.recordFineMessages(PGPropertyUtil.class,
        () -> driverMessages.addAll(TestLogHandler.recordFineMessages(Driver.class,
            () -> assertNull(Driver.parseURL(url, null), url))));
    assertEquals(expectedDriverMessages, driverMessages);
    assertEquals(expectedPropertyUtilMessages, propertyUtilMessages);
  }

  static Stream<Arguments> invalidPorts() {
    return Stream.of(
        arguments("jdbc:postgresql://db1:5432,db2:54x2,db3:5432/app",
            "Invalid port number: port 2 of 3 is not a number"),
        arguments("jdbc:postgresql://db:99999/app",
            "Invalid port number: port 1 of 1 is not in 1..65535"));
  }

  @ParameterizedTest
  @MethodSource("invalidPorts")
  void invalidPortWarningDoesNotEchoThePort(String url, String expectedWarning) {
    List<String> messages = TestLogHandler.recordFineMessages(PGPropertyUtil.class,
        () -> assertNull(Driver.parseURL(url, null), url));
    assertEquals(Arrays.asList(expectedWarning), messages);
  }

  static Stream<Arguments> refusedUrlMessages() {
    return Stream.of(
        arguments("jdbc:postgresql://alice:secret@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:sec/ret@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:sec/ret@db", "jdbc:postgresql://***@db"),
        arguments("jdbc:postgresql://alice:sec?ret@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:sec?user=ret@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:pa/x?y@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:1234/x?y@db/app", "jdbc:postgresql://***@db/app"),
        arguments("jdbc:postgresql://alice:pa/x?a=b@db/app", "jdbc:postgresql://***@db/app"),
        // A URL refused for another reason keeps an "@" in the value of a known parameter, such as
        // Azure's user@server, so the message still shows the host and the database
        arguments("jdbc:postgresql://db:99999/app?user=alice@example.com",
            "jdbc:postgresql://db:99999/app?user=alice@example.com"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refusedUrlMessages")
  void unableToParseUrlMasksUserInfo(String url, String maskedUrl) {
    // parseURL logs why it refuses the URL; keep the WARNING off the console
    TestLogHandler.recordFineMessages(PGPropertyUtil.class,
        () -> TestLogHandler.recordFineMessages(Driver.class, () -> {
          SQLException e = assertThrows(SQLException.class,
              () -> new Driver().connect(url, new Properties()));
          assertEquals(GT.tr("Unable to parse URL {0}", maskedUrl), e.getMessage());
        }));
  }

  /** Azure Database for PostgreSQL single server needs a user of the form user@server. */
  @Test
  void atSignInQueryStringIsAccepted() {
    Properties props = Driver.parseURL("jdbc:postgresql://db/app?user=alice@example.com", null);
    assertNotNull(props);
    assertEquals("alice@example.com", PGProperty.USER.getOrDefault(props));
  }

  @Test
  void encodedAtSignInDatabaseNameIsAccepted() {
    Properties props = Driver.parseURL("jdbc:postgresql://db/my%40app", null);
    assertNotNull(props);
    assertEquals("my@app", PGProperty.PG_DBNAME.getOrDefault(props));
  }

  /** As in libpq, an "@" after the first "/" is a part of the database name. */
  @ParameterizedTest
  @ValueSource(strings = {"jdbc:postgresql://db/my@app", "jdbc:postgresql:my@app"})
  void atSignInDatabaseNameIsAccepted(String url) {
    Properties props = Driver.parseURL(url, null);
    assertNotNull(props, url);
    assertEquals("my@app", PGProperty.PG_DBNAME.getOrDefault(props));
  }

  @Test
  void connectingWithUrlMasksCredentials() throws Exception {
    String server = "jdbc:postgresql://127.0.0.1:" + TestUtil.getClosedLoopbackPort() + "/test?";
    assertConnectingRecord(server + URL_CREDENTIALS, server + MASKED_URL_CREDENTIALS);
  }

  /**
   * A raw "@" in the database name cannot be told apart from user info, as in user:1234/word@host,
   * which is accepted as libpq accepts it, so masking runs up to that "@".
   */
  @Test
  void connectingWithAtSignInDatabaseNameMasksUpToIt() throws Exception {
    String url = "jdbc:postgresql://127.0.0.1:" + TestUtil.getClosedLoopbackPort() + "/my@app?";
    assertConnectingRecord(url + URL_CREDENTIALS,
        "jdbc:postgresql://***@app?" + MASKED_URL_CREDENTIALS);
  }

  private static void assertConnectingRecord(String url, String maskedUrl) {
    List<String> messages = TestLogHandler.recordFineMessages(Driver.class,
        () -> assertThrows(SQLException.class,
            () -> new Driver().connect(url, new Properties())));

    String expected = "Connecting with URL: " + maskedUrl;
    assertTrue(messages.contains(expected),
        () -> "Expected the record " + expected + ", but got " + messages);
  }

  /**
   * A parameter value that fails to URL-decode may be a credential, so only its name is logged.
   */
  @Test
  void malformedCredentialValueIsNotLogged() {
    List<String> messages = TestLogHandler.recordFineMessages(Driver.class,
        // "%zz" is not a valid percent-escape, so decoding the value fails
        () -> assertNull(Driver.parseURL(
            "jdbc:postgresql://localhost:5432/test?sslpassword=secret%zz", null)));
    assertEquals(Arrays.asList("URL parameter [sslpassword] could not be decoded"), messages);
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdbc:postgresql:db%zz", "jdbc:postgresql://localhost/db%zz"})
  void malformedDatabaseNameIsRejected(String url) {
    List<String> messages = TestLogHandler.recordFineMessages(Driver.class,
        // "%zz" is not a valid percent-escape, so decoding the database name fails
        () -> assertNull(Driver.parseURL(url, null), url));
    assertEquals(Arrays.asList("JDBC URL database name could not be decoded"), messages);
  }
}
