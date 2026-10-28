/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2.optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.Driver;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.ds.common.BaseDataSource;
import org.postgresql.test.TestUtil;
import org.postgresql.util.TestLogHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * Checks that a data source masks credentials in the URL it logs or puts in an exception message.
 */
@Isolated("Records org.postgresql logs, so it needs to work in isolation from the other tests")
class DataSourceUrlLoggingTest {

  /**
   * Sets {@code sslpassword} on the data source and returns the URL it builds, with that value
   * masked as the data source should log it.
   */
  private static String setSslPasswordAndGetMaskedUrl(PGSimpleDataSource dataSource) {
    dataSource.setSslPassword("passphrase");
    String url = dataSource.getUrl();
    assertTrue(url.contains("sslpassword=passphrase"), () -> "Unexpected data source URL " + url);
    return url.replace("sslpassword=passphrase", "sslpassword=***");
  }

  @Test
  void createdConnectionRecordMasksCredentials() throws Exception {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    BaseDataSourceTest.setupDataSource(dataSource);
    // sslpassword only unlocks a client key, so the value does not matter to a connection without one
    String maskedUrl = setSslPasswordAndGetMaskedUrl(dataSource);

    List<String> messages = TestLogHandler.recordFineMessages(BaseDataSource.class,
        () -> assertDoesNotThrow(() -> {
          try (Connection ignored = dataSource.getConnection()) {
            // Only the record of the opened connection matters
          }
        }));

    assertEquals(
        Arrays.asList("Created a " + dataSource.getDescription() + " for " + TestUtil.getUser()
            + " at " + maskedUrl),
        messages);
  }

  @Test
  void setUrlMasksCredentialsInItsError() {
    // The URL has too many / characters, so parseURL logs a WARNING; keep it off the console
    TestLogHandler.recordFineMessages(Driver.class, () -> {
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
          () -> new PGSimpleDataSource().setURL(
              "jdbc:postgresql://localhost/a/b?password=secret&sslpassword=passphrase&oauthToken=token"));
      assertEquals(
          "URL invalid jdbc:postgresql://localhost/a/b?password=***&sslpassword=***&oauthToken=***",
          e.getMessage());
    });
  }

  @Test
  void getConnectionMasksCredentialsInItsLog() throws Exception {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setServerNames(new String[]{"127.0.0.1"});
    dataSource.setPortNumbers(new int[]{TestUtil.getClosedLoopbackPort()});
    dataSource.setOauthToken("token");
    String sslPasswordMaskedUrl = setSslPasswordAndGetMaskedUrl(dataSource);
    assertTrue(sslPasswordMaskedUrl.contains("oauthToken=token"),
        () -> "Unexpected data source URL " + sslPasswordMaskedUrl);
    String maskedUrl = sslPasswordMaskedUrl.replace("oauthToken=token", "oauthToken=***");

    List<String> messages = TestLogHandler.recordFineMessages(BaseDataSource.class,
        () -> assertThrows(SQLException.class, dataSource::getConnection));

    String expectedPrefix = "Failed to create a " + dataSource.getDescription() + " for null at "
        + maskedUrl + ": ";
    assertEquals(1, messages.size(), () -> "Expected one record, but got " + messages);
    assertTrue(messages.get(0).startsWith(expectedPrefix),
        () -> "Expected the record to start with " + expectedPrefix + ", but got " + messages.get(0));
  }

  @Test
  void setUrlDoesNotDiscloseOtherDriverUrl() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> new PGSimpleDataSource().setURL("jdbc:sqlserver://h;user=u;password=secret"));
    assertEquals("URL invalid: it does not start with jdbc:postgresql:", e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "jdbc:postgresql://alice:secret@db:5432/app",
      "jdbc:postgresql://alice:sec/ret@db:5432/app",
      "jdbc:postgresql://alice:sec?ret@db:5432/app",
  })
  void setUrlMasksUserInfoInItsError(String url) {
    // parseURL logs why it refuses the URL; keep the WARNING off the console
    TestLogHandler.recordFineMessages(Driver.class, () -> {
      IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
          () -> new PGSimpleDataSource().setURL(url));
      assertEquals("URL invalid jdbc:postgresql://***@db:5432/app", e.getMessage());
    });
  }
}
