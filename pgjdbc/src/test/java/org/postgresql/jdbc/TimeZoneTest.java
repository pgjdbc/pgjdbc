/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.DateTimeZoneStamp;
import org.postgresql.test.SlowTests;
import org.postgresql.test.TestUtil;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * TimeZone is a global parameter affecting all APIs used by an application. User must be able
 * to choose any available TimeZone or use system default. In any case application must
 * connect to database successfully and must not misinterpret time-related data. This test
 * is unlikely to catch an error when executed in local environment, where both sides,
 * client and server, share the same OS and locale-related software components. But it highlights
 * perfectly the problem when software relies on similarity of independently managed clients and server.
 */
@RunWith(Parameterized.class)
public class TimeZoneTest {
  @Nullable
  private static TimeZone savedTimeZone = null;
  private final String zoneId;

  /**
   * Create test for zoneId
   * @param zoneId Zone ID
   */
  public TimeZoneTest(String zoneId) {
    this.zoneId = zoneId;
  }

  @Parameterized.Parameters(name = "zoneId = {0}")
  public static Iterable<Object[]> data() {
    List<Object[]> data = new ArrayList<>();
    ZoneId.getAvailableZoneIds()
        .stream()
        .sorted()
        .forEach(zone -> data.add(new Object[]{zone}));
    return data;
  }

  @Before
  public void setup() {
    savedTimeZone = TimeZone.getDefault();
  }

  @After
  public void tearDown() {
    TimeZone.setDefault(savedTimeZone);
  }

  /**
   * When server uses client timezone, we can run into 2 kind of bugs:
   * <ol>
   *   <li>Client sets a just created new timezone Mars/Muskbase. It sends it to the server but server rejects to connect, since it isn't aware of a new timezone.</li>
   *   <li>The government of a country changed daylight savings rules of local timezone. Client sends its timezone id to the server,
   *   connects successfully, but server has old daylight savings rules for that timezone. If server and client rely on client's zone info, the data will be corrupted 6 months a year.
   *   This is what we have in Mexico. If server has timezone info created before 2023, it most likely misinterpret time in Mexico timezone, because it had changed.
   *   </li>
   * </ol>
   * Here we try to detect both risks, but we have no garantee, that we are going to make it. The only way we can garantee
   * the integrity of time-related data is to learn basics and use time API properly.
   *
   * @throws SQLException Occurs, when connection can not be established
   */
  @Test
  @Category({SlowTests.class, DateTimeZoneStamp.class})
  public void testConnection() throws SQLException {
    TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
    // Here we reproduce FATAL: invalid value for parameter "TimeZone"
    try (Connection connection = TestUtil.openDB()) {
      try (Statement stmt = connection.createStatement()) {
        long clientTime = System.currentTimeMillis();
        try (ResultSet rs = stmt.executeQuery("select now()")) {
          rs.next();
          Timestamp ts = rs.getTimestamp(1);
          Instant instant = ts.toInstant();
          long serverTime = instant.toEpochMilli();
          assertTrue(Math.abs(serverTime - clientTime) <= 10_000L, "Client and server time are close enough");
        }
      }
    }
  }
}
