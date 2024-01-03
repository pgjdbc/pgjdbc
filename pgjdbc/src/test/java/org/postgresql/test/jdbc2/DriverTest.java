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

import org.postgresql.Driver;
import org.postgresql.PGEnvironment;
import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.StubEnvironmentAndProperties;
import org.postgresql.util.URLCoder;

import org.junit.jupiter.api.Test;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.properties.SystemProperties;
import uk.org.webcompere.systemstubs.resource.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;

/*
 * Tests the dynamically created class org.postgresql.Driver
 *
 */
@StubEnvironmentAndProperties
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
      // fail cases
      assertFalse(drv.acceptsURL("jdbc:postgresql://?service=driverTestService2"));
    });

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

    // failover urls
    verifyUrl(drv, "jdbc:postgresql://localhost,127.0.0.1:5432/test", "localhost,127.0.0.1",
        "5432,5432", "test");
    verifyUrl(drv, "jdbc:postgresql://localhost:5433,127.0.0.1:5432/test", "localhost,127.0.0.1",
        "5433,5432", "test");
    verifyUrl(drv, "jdbc:postgresql://[::1],[::1]:5432/db", "[::1],[::1]", "5432,5432", "db");
    verifyUrl(drv, "jdbc:postgresql://[::1]:5740,127.0.0.1:5432/db", "[::1],127.0.0.1", "5740,5432",
        "db");
  }

  private void verifyUrl(Driver drv, String url, String hosts, String ports, String dbName)
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
    TestUtil.initDriver(); // Set up log levels, etc.

    // Test with the url, username & password
    Connection con =
        DriverManager.getConnection(TestUtil.getURL(), TestUtil.getUser(), TestUtil.getPassword());
    assertNotNull(con);
    con.close();

    // Test with the username in the url
    con = DriverManager.getConnection(
        TestUtil.getURL()
            + "&user=" + URLCoder.encode(TestUtil.getUser())
            + "&password=" + URLCoder.encode(TestUtil.getPassword()));
    assertNotNull(con);
    con.close();

    // Test with failover url
  }

  /**
   * Tests the connect method by connecting to the test database.
   */
  @Test
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

  private void setProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
