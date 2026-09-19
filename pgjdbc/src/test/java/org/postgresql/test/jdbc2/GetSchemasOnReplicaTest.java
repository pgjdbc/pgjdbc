/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.postgresql.test.TestUtil.closeDB;
import static org.postgresql.test.TestUtil.openDB;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.tags.Replication;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Regression test for issue #4274: {@code DatabaseMetaData.getSchemas()} must not force creation
 * of the session's temporary namespace, which fails on a hot standby with
 * "cannot create temporary tables during recovery".
 *
 * <p>Requires a streaming replica. The test infrastructure exposes one on
 * {@code secondaryServer1}/{@code secondaryPort1} (defaulting to the primary host and
 * {@code port + 1}), created by the {@code CREATE_REPLICAS=on} docker option. When no replica is
 * available, or the configured secondary is not actually in recovery mode, the test is skipped.</p>
 */
@Replication
class GetSchemasOnReplicaTest {

  @BeforeAll
  static void checkReplicaAvailable() {
    assumeTrue(isReplicaAvailable(), "No streaming replica available; skipping standby test");
  }

  @Test
  void getSchemasDoesNotForceTempNamespaceCreation() throws Exception {
    try (Connection replica = openReplicaDB()) {
      assumeTrue(isInRecovery(replica),
          "Configured secondary is not in recovery mode; skipping standby-only test");

      // Put pg_temp first in search_path so the pre-fix current_schemas() query would force
      // temp-namespace creation and fail on the standby.
      try (Statement stmt = replica.createStatement()) {
        stmt.execute("SET search_path = pg_temp, public");
      }

      DatabaseMetaData dbmd = replica.getMetaData();
      boolean foundPublic = false;
      try (ResultSet rs = dbmd.getSchemas()) {
        while (rs.next()) {
          if ("public".equals(rs.getString("TABLE_SCHEM"))) {
            foundPublic = true;
          }
        }
      }
      assertTrue(foundPublic, "getSchemas() should list the public schema on a standby");
    }
  }

  private static boolean isInRecovery(Connection con) throws Exception {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT pg_is_in_recovery()")) {
      return rs.next() && rs.getBoolean(1);
    }
  }

  private static boolean isReplicaAvailable() {
    try {
      closeDB(openReplicaDB());
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static Connection openReplicaDB() throws Exception {
    TestUtil.initDriver();
    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getUser());
    PGProperty.PASSWORD.set(props, TestUtil.getPassword());
    TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, getReplicaServer());
    TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT, String.valueOf(getReplicaPort()));
    return openDB(props);
  }

  private static String getReplicaServer() {
    return System.getProperty("secondaryServer1", TestUtil.getServer());
  }

  private static int getReplicaPort() {
    return Integer.parseInt(
        System.getProperty("secondaryPort1", String.valueOf(TestUtil.getPort() + 1)));
  }
}
