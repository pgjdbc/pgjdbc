/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.rules.ServerVersionRule;
import org.postgresql.test.util.rules.annotation.HaveMinimalServerVersion;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

@HaveMinimalServerVersion("9.4")
public class ReplicationSlotTest {
  @Rule
  public ServerVersionRule versionRule = new ServerVersionRule();

  private Connection sqlConnection;
  private Connection replConnection;

  private String slotName;

  @Before
  public void setUp() throws Exception {
    sqlConnection = TestUtil.openDB();
    replConnection = openReplicationConnection();
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

    assertThat(result, CoreMatchers.equalTo(true));
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

    assertThat(result, CoreMatchers.equalTo(true));
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

  private void dropReplicationSlot() throws Exception {
    if (slotName != null) {
      TestUtil.dropReplicationSlot(sqlConnection, slotName);
    }
  }


  private Connection openReplicationConnection() throws Exception {
    Properties properties = new Properties();
    PGProperty.ASSUME_MIN_SERVER_VERSION.set(properties, "9.4");
    PGProperty.REPLICATION.set(properties, "database");
    //Only symple query protocol available for replication connection
    PGProperty.PREFER_QUERY_MODE.set(properties, "simple");
    return TestUtil.openDB(properties);
  }
}
