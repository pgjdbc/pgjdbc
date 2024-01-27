/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.Driver;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import se.jiderhamn.classloader.PackagesLoadedOutsideClassLoader;
import se.jiderhamn.classloader.leak.JUnitClassloaderRunner;
import se.jiderhamn.classloader.leak.LeakPreventor;
import se.jiderhamn.classloader.leak.Leaks;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

@RunWith(JUnitClassloaderRunner.class)
@LeakPreventor(DriverSupportsClassUnloadingTest.LeakPreventor.class)
@PackagesLoadedOutsideClassLoader(
    packages = {"java.", "javax.", "jdk.", "com.sun.", "sun.", "org.w3c", "org.junit.", "junit.",
        "se.jiderhamn."}
)
class DriverSupportsClassUnloadingTest {
  // See https://github.com/mjiderhamn/classloader-leak-prevention/tree/master/classloader-leak-test-framework#verifying-prevention-measures
  public static class LeakPreventor implements Runnable {
    @Override
    public void run() {
      try {
        if (Driver.isRegistered()) {
          Driver.deregister();
        }
        for (int i = 0; i < 3; i++) {
          // Allow cleanup thread to detect and close the leaked connection
          JUnitClassloaderRunner.forceGc();
          // JUnitClassloaderRunner uses finalizers
          System.runFinalization();
        }
        // Allow for the cleanup thread to terminate
        Thread.sleep(2000);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }

  @BeforeAll
  static void setSmallCleanupThreadTtl() {
    // Make the tests faster
    System.setProperty("pgjdbc.config.cleanup.thread.ttl", "100");
  }

  @AfterAll
  static void resetCleanupThreadTtl() {
    System.clearProperty("pgjdbc.config.cleanup.thread.ttl");
  }

  @Test
  @Leaks(dumpHeapOnError = true)
  void driverUnloadsWhenConnectionLeaks() throws SQLException, InterruptedException {
    if (!Driver.isRegistered()) {
      Driver.register();
    }
    // This code intentionally leaks connection, prepared statement to verify if the classes
    // will still be able to unload
    Connection con = TestUtil.openDB();
    PreparedStatement ps = con.prepareStatement("select 1 c1, 'hello' c2");
    // TODO: getMetaData throws AssertionError, however, it should probably not
    if (con.unwrap(PgConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE) {
      ResultSetMetaData md = ps.getMetaData();
      assertEquals(
          Types.INTEGER,
          md.getColumnType(1),
          ".getColumnType for column 1 c1 should be INTEGER"
      );
    }

    // This is to trigger "query timeout" code to increase the chances for memory leaks
    ps.setQueryTimeout(1000);
    ResultSet rs = ps.executeQuery();
    rs.next();
    assertEquals(1, rs.getInt(1), ".getInt for column c1");
  }

  @Test
  @Leaks(dumpHeapOnError = true)
  void driverUnloadsWhenConnectionClosedExplicitly() throws SQLException {
    if (!Driver.isRegistered()) {
      Driver.register();
    }
    // This code intentionally leaks connection, prepared statement to verify if the classes
    // will still be able to unload
    try (Connection con = TestUtil.openDB()) {
      try (PreparedStatement ps = con.prepareStatement("select 1 c1, 'hello' c2")) {
        // TODO: getMetaData throws AssertionError, however, it should probably not
        if (con.unwrap(PgConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE) {
          ResultSetMetaData md = ps.getMetaData();
          assertEquals(
              Types.INTEGER,
              md.getColumnType(1),
              ".getColumnType for column 1 c1 should be INTEGER"
          );
        }

        // This is to trigger "query timeout" code to increase the chances for memory leaks
        ps.setQueryTimeout(1000);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          assertEquals(1, rs.getInt(1), ".getInt for column c1");
        }
      }
    }
  }
}
