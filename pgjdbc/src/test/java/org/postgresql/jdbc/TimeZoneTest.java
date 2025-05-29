/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.SlowTests;
import org.postgresql.test.TestUtil;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Stream;

/**
 * TimeZone is a global parameter affecting all APIs used by an application. User must be able
 * to choose any available TimeZone or use system default. In any case application must
 * connect to database successfully and must not misinterpret time-related data. This test
 * is unlikely to catch an error when executed in local environment, where both sides,
 * client and server, share the same OS and locale-related software components. But it highlights
 * perfectly the problem when software relies on similarity of independently managed clients and server.
 */
class TimeZoneTest {
  @Nullable
  private static TimeZone savedTimeZone = null;

  @BeforeAll
  static void setup() {
    savedTimeZone = TimeZone.getDefault();
  }

  @AfterAll
  static void close() {
    TimeZone.setDefault(savedTimeZone);
  }

  /**
   * When server uses client timezone, we can run into 2 kind of bugs:
   * <ol>
   *   <li>Client sets a just created new timezone Mars/Muskbase. It sends it to the server but server rejects to connect, since it doesn't aware of a new timezone.</li>
   *   <li>The government of a country changed daylight savings rules of local timezone. Client sends its timezone id to the server,
   *   connects successfully, but server has old daylight savings rules for that timezone. If server and client rely on client's zone info, the data will be corrupted 6 months a year.
   *   This is what we have in Mexico. If server has timezone info created before 2023, it most likely misinterpret time in Mexico timezone, because it had changed.
   *   </li>
   * </ol>
   * Here we try to detect both risks, but we have no garantee, that we are going to make it. The only way we can garantee
   * the integrity of time-related data is to learn basics and use time API properly.
   *
   * @param zoneId zone ID
   * @throws SQLException Occurs, when connection can not be established
   */
  @ParameterizedTest
  @MethodSource("provideZoneIds")
  @Category(SlowTests.class)
  void testTimeZone(String zoneId) throws SQLException {
    TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
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

  @Test
  @Disabled("Use more reliable testTimeZone")
  void testRandomTimeZone() throws SQLException {
    Set<String> set = ZoneId.getAvailableZoneIds();
    Random random = new Random();
    int pos = random.nextInt(set.size());
    Optional<String> value = set.stream().skip(pos).findFirst();
    if (value.isPresent()) {
      String zone = value.get();
      testTimeZone(zone);
    }
  }

  static Stream<Arguments> provideZoneIds() {
    Set<String> zoneIds = ZoneId.getAvailableZoneIds();
    return zoneIds.stream()
        .sorted()
        .map(Arguments::of);
  }
}
