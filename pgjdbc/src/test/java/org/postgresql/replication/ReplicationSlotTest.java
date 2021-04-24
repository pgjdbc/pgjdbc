/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.Replication;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.rules.ServerVersionRule;
import org.postgresql.test.util.rules.annotation.HaveMinimalServerVersion;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

@Category(Replication.class)
@HaveMinimalServerVersion("9.4")
public class ReplicationSlotTest {
  @Rule
  public ServerVersionRule versionRule = new ServerVersionRule();

  private Connection sqlConnection;
  private Connection replConnection;

  private String slotName;

  @Before
  public void setUp() throws Exception {
    sqlConnection = TestUtil.openPrivilegedDB();
    replConnection = TestUtil.openReplicationConnection();
    //DriverManager.setLogWriter(new PrintWriter(System.out));
  }

  @After
  public void tearDown() throws Exception {
    replConnection.close();
    dropReplicationSlot();
    slotName = null;
    sqlConnection.close();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNotAvailableCreatePhysicalSlotWithoutSlotName() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    pgConnection
        .getReplicationAPI()
        .createReplicationSlot()
        .physical()
        .make();

    fail("Replication slot name it required parameter and can't be null");
  }

  @Test
  public void testCreatePhysicalSlot() throws Exception {
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
  public void testCreateTemporaryPhysicalSlotPg10AndHigher()
      throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(replConnection, ServerVersion.v10));

    BaseConnection baseConnection = (BaseConnection) replConnection;

    String slotName = "pgjdbc_test_create_temporary_physical_replication_slot_pg_10_or_higher";

    try {

      baseConnection
          .getReplicationAPI()
          .createReplicationSlot()
          .physical()
          .withSlotName(slotName)
          .withTemporaryOption()
          .make();

    } catch (SQLFeatureNotSupportedException e) {

      fail("PostgreSQL >= 10 should support temporary replication slots");

    }

    boolean result = isSlotTemporary(slotName);

    assertThat("Slot is not temporary", result, CoreMatchers.equalTo(true));
  }

  @Test
  public void testCreateTemporaryPhysicalSlotPgLowerThan10()
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
  public void testDropPhysicalSlot() throws Exception {
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

  @Test(expected = IllegalArgumentException.class)
  public void testNotAvailableCreateLogicalSlotWithoutSlotName() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    pgConnection
        .getReplicationAPI()
        .createReplicationSlot()
        .logical()
        .withOutputPlugin("test_decoding")
        .make();

    fail("Replication slot name it required parameter and can't be null");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNotAvailableCreateLogicalSlotWithoutOutputPlugin() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    pgConnection
        .getReplicationAPI()
        .createReplicationSlot()
        .logical()
        .withSlotName("pgjdbc_test_create_logical_replication_slot")
        .make();

    fail("output plugin required parameter for logical replication slot and can't be null");
  }

  @Test
  public void testCreateLogicalSlot() throws Exception {
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
  public void testCreateLogicalSlotReturnedInfo() throws Exception {
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
  public void testCreatePhysicalSlotReturnedInfo() throws Exception {
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
  public void testCreateTemporaryLogicalSlotPg10AndHigher()
      throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(replConnection, ServerVersion.v10));

    BaseConnection baseConnection = (BaseConnection) replConnection;

    String slotName = "pgjdbc_test_create_temporary_logical_replication_slot_pg_10_or_higher";

    try {

      baseConnection
          .getReplicationAPI()
          .createReplicationSlot()
          .logical()
          .withSlotName(slotName)
          .withOutputPlugin("test_decoding")
          .withTemporaryOption()
          .make();

    } catch (SQLFeatureNotSupportedException e) {

      fail("PostgreSQL >= 10 should support temporary replication slots");

    }

    boolean result = isSlotTemporary(slotName);

    assertThat("Slot is not temporary", result, CoreMatchers.equalTo(true));
  }

  @Test
  public void testCreateTemporaryLogicalSlotPgLowerThan10()
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
  public void testDropLogicalSlot() throws Exception {
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
