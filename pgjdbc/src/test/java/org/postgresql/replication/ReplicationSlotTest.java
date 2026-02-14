/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.test.annotations.tags.Replication;

import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

@Replication
@EnabledForServerVersionRange(gte = "9.4")
class ReplicationSlotTest {
  private Connection sqlConnection;
  private Connection replConnection;

  private String slotName;

  @BeforeEach
  void setUp() throws Exception {
    sqlConnection = TestUtil.openPrivilegedDB();
    replConnection = TestUtil.openReplicationConnection();
    //DriverManager.setLogWriter(new PrintWriter(System.out));
  }

  @AfterEach
  void tearDown() throws Exception {
    replConnection.close();
    dropReplicationSlot();
    slotName = null;
    sqlConnection.close();
  }

  @Test
  void notAvailableCreatePhysicalSlotWithoutSlotName() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> {
      PGConnection pgConnection = (PGConnection) replConnection;

      pgConnection
          .getReplicationAPI()
          .createReplicationSlot()
          .physical()
          .make();

      fail("Replication slot name it required parameter and can't be null");
    });
  }

  @Test
  void createPhysicalSlot() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    slotName = "pgjdbc_test_create_physical_replication_slot";

    pgConnection
        .getReplicationAPI()
        .createReplicationSlot()
        .physical()
        .withSlotName(slotName)
        .make();

    boolean result = isPhysicalSlotExists(slotName);

    assertThat("Slot should exist", result, CoreMatchers.equalTo(true));

    result = isSlotTemporary(slotName);

    assertThat("Slot should not be temporary by default", result, CoreMatchers.equalTo(false));
  }

  @Test
  void createTemporaryPhysicalSlotPg10AndHigher()
      throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(replConnection, ServerVersion.v10));

    BaseConnection baseConnection = (BaseConnection) replConnection;

    String slotName = "pgjdbc_test_create_temporary_physical_replication_slot_pg_10_or_higher";

    assertDoesNotThrow(() -> {

      baseConnection
          .getReplicationAPI()
          .createReplicationSlot()
          .physical()
          .withSlotName(slotName)
          .withTemporaryOption()
          .make();

    }, "PostgreSQL >= 10 should support temporary replication slots");

    boolean result = isSlotTemporary(slotName);

    assertThat("Slot is not temporary", result, CoreMatchers.equalTo(true));
  }

  @Test
  void createTemporaryPhysicalSlotPgLowerThan10()
      throws SQLException {
    assumeFalse(TestUtil.haveMinimumServerVersion(replConnection, ServerVersion.v10));

    BaseConnection baseConnection = (BaseConnection) replConnection;

    String slotName = "pgjdbc_test_create_temporary_physical_replication_slot_pg_lower_than_10";

    try {

      baseConnection
          .getReplicationAPI()
          .createReplicationSlot()
          .physical()
          .withSlotName(slotName)
          .withTemporaryOption()
          .make();

      fail("PostgreSQL < 10 does not support temporary replication slots");

    } catch (SQLFeatureNotSupportedException e) {
      // success
    }
  }

  @Test
  void dropPhysicalSlot() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    slotName = "pgjdbc_test_create_physical_replication_slot";

    pgConnection
        .getReplicationAPI()
        .createReplicationSlot()
        .physical()
        .withSlotName(slotName)
        .make();

    pgConnection
        .getReplicationAPI()
        .dropReplicationSlot(slotName);

    boolean result = isPhysicalSlotExists(slotName);

    slotName = null;

    assertThat(result, CoreMatchers.equalTo(false));
  }

  @Test
  void notAvailableCreateLogicalSlotWithoutSlotName() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> {
      PGConnection pgConnection = (PGConnection) replConnection;

      pgConnection
          .getReplicationAPI()
          .createReplicationSlot()
          .logical()
          .withOutputPlugin("test_decoding")
          .make();

      fail("Replication slot name it required parameter and can't be null");
    });
  }

  @Test
  void notAvailableCreateLogicalSlotWithoutOutputPlugin() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> {
      PGConnection pgConnection = (PGConnection) replConnection;

      pgConnection
          .getReplicationAPI()
          .createReplicationSlot()
          .logical()
          .withSlotName("pgjdbc_test_create_logical_replication_slot")
          .make();

      fail("output plugin required parameter for logical replication slot and can't be null");
    });
  }

  @Test
  void createLogicalSlot() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    slotName = "pgjdbc_test_create_logical_replication_slot";

    pgConnection
        .getReplicationAPI()
        .createReplicationSlot()
        .logical()
        .withSlotName(slotName)
        .withOutputPlugin("test_decoding")
        .make();

    boolean result = isLogicalSlotExists(slotName);

    assertThat("Slot should exist", result, CoreMatchers.equalTo(true));

    result = isSlotTemporary(slotName);

    assertThat("Slot should not be temporary by default", result, CoreMatchers.equalTo(false));
  }

  @Test
  void createLogicalSlotReturnedInfo() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    slotName = "pgjdbc_test_create_logical_replication_slot_info";

    ReplicationSlotInfo info = pgConnection
        .getReplicationAPI()
        .createReplicationSlot()
        .logical()
        .withSlotName(slotName)
        .withOutputPlugin("test_decoding")
        .make();

    assertEquals(slotName, info.getSlotName());
    assertEquals(ReplicationType.LOGICAL, info.getReplicationType());
    assertNotNull(info.getConsistentPoint());
    assertNotNull(info.getSnapshotName());
    assertEquals("test_decoding", info.getOutputPlugin());
  }

  @Test
  void createPhysicalSlotReturnedInfo() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    slotName = "pgjdbc_test_create_physical_replication_slot_info";

    ReplicationSlotInfo info = pgConnection
        .getReplicationAPI()
        .createReplicationSlot()
        .physical()
        .withSlotName(slotName)
        .make();

    assertEquals(slotName, info.getSlotName());
    assertEquals(ReplicationType.PHYSICAL, info.getReplicationType());
    assertNotNull(info.getConsistentPoint());
    assertNull(info.getSnapshotName());
    assertNull(info.getOutputPlugin());
  }

  @Test
  void createTemporaryLogicalSlotPg10AndHigher()
      throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(replConnection, ServerVersion.v10));

    BaseConnection baseConnection = (BaseConnection) replConnection;

    String slotName = "pgjdbc_test_create_temporary_logical_replication_slot_pg_10_or_higher";

    assertDoesNotThrow(() -> {

      baseConnection
          .getReplicationAPI()
          .createReplicationSlot()
          .logical()
          .withSlotName(slotName)
          .withOutputPlugin("test_decoding")
          .withTemporaryOption()
          .make();

    }, "PostgreSQL >= 10 should support temporary replication slots");

    boolean result = isSlotTemporary(slotName);

    assertThat("Slot is not temporary", result, CoreMatchers.equalTo(true));
  }

  @Test
  void createTemporaryLogicalSlotPgLowerThan10()
      throws SQLException {
    assumeFalse(TestUtil.haveMinimumServerVersion(replConnection, ServerVersion.v10));

    BaseConnection baseConnection = (BaseConnection) replConnection;

    String slotName = "pgjdbc_test_create_temporary_logical_replication_slot_pg_lower_than_10";

    try {

      baseConnection
          .getReplicationAPI()
          .createReplicationSlot()
          .logical()
          .withSlotName(slotName)
          .withOutputPlugin("test_decoding")
          .withTemporaryOption()
          .make();

      fail("PostgreSQL < 10 does not support temporary replication slots");

    } catch (SQLFeatureNotSupportedException e) {
      // success
    }
  }

  @Test
  void dropLogicalSlot() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    slotName = "pgjdbc_test_create_logical_replication_slot";

    pgConnection
        .getReplicationAPI()
        .createReplicationSlot()
        .logical()
        .withSlotName(slotName)
        .withOutputPlugin("test_decoding")
        .make();

    pgConnection
        .getReplicationAPI()
        .dropReplicationSlot(slotName);

    boolean result = isLogicalSlotExists(slotName);

    slotName = null;

    assertThat(result, CoreMatchers.equalTo(false));
  }

  private boolean isPhysicalSlotExists(String slotName) throws SQLException {
    boolean result;

    Statement st = sqlConnection.createStatement();
    ResultSet resultSet = st.executeQuery(
        "select * from pg_replication_slots where slot_name = '" + slotName
            + "' and slot_type = 'physical'");
    result = resultSet.next();
    resultSet.close();
    st.close();
    return result;
  }

  private boolean isLogicalSlotExists(String slotName) throws SQLException {
    boolean result;

    Statement st = sqlConnection.createStatement();
    ResultSet resultSet = st.executeQuery(
        "select 1 from pg_replication_slots where slot_name = '" + slotName
            + "' and slot_type = 'logical'");
    result = resultSet.next();
    resultSet.close();
    st.close();
    return result;
  }

  private boolean isSlotTemporary(String slotName) throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(sqlConnection, ServerVersion.v10)) {
      return false;
    }

    boolean result;

    Statement st = sqlConnection.createStatement();
    ResultSet resultSet = st.executeQuery(
            "select 1 from pg_replication_slots where slot_name = '" + slotName
                    + "' and temporary = true");
    result = resultSet.next();
    resultSet.close();
    st.close();
    return result;
  }

  private void dropReplicationSlot() throws Exception {
    if (slotName != null) {
      TestUtil.dropReplicationSlot(sqlConnection, slotName);
    }
  }
}
