/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.test.annotations.tags.Replication;

import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

@Replication
@EnabledForServerVersionRange(gte = "9.4")
class ReplicationConnectionTest {
  private Connection replConnection;

  @BeforeEach
  void setUp() throws Exception {
    replConnection = TestUtil.openReplicationConnection();
    //DriverManager.setLogWriter(new PrintWriter(System.out));
  }

  @AfterEach
  void tearDown() throws Exception {
    replConnection.close();
  }

  @Test
  void isValid() throws Exception {
    boolean result = replConnection.isValid(3);

    PGConnection connection = (PGConnection) replConnection;
    connection.getBackendPID();

    assertThat("Replication connection as Simple connection can be check on valid",
        result, equalTo(true)
    );
  }

  @Test
  void connectionNotValidWhenSessionTerminated() throws Exception {
    TestUtil.terminateBackend(replConnection);

    boolean result = replConnection.isValid(3);

    assertThat("When postgresql terminate session with replication connection, "
            + "isValid() should return false, because next query on this connection will fail",
        result, equalTo(false)
    );
  }

  @Test
  void replicationCommandResultSetAccessByIndex() throws Exception {
    Statement statement = replConnection.createStatement();
    ResultSet resultSet = statement.executeQuery("IDENTIFY_SYSTEM");

    String xlogpos = null;
    if (resultSet.next()) {
      xlogpos = resultSet.getString(3);
    }

    resultSet.close();
    statement.close();

    assertThat("Replication protocol supports a limited number of commands, "
            + "and it command can be execute via Statement(simple query protocol), "
            + "and result fetch via ResultSet",
        xlogpos, CoreMatchers.notNullValue()
    );
  }

  @Test
  void replicationCommandResultSetAccessByName() throws Exception {
    Statement statement = replConnection.createStatement();
    ResultSet resultSet = statement.executeQuery("IDENTIFY_SYSTEM");

    String xlogpos = null;
    if (resultSet.next()) {
      xlogpos = resultSet.getString("xlogpos");
    }

    resultSet.close();
    statement.close();

    assertThat("Replication protocol supports a limited number of commands, "
            + "and it command can be execute via Statement(simple query protocol), "
            + "and result fetch via ResultSet",
        xlogpos, CoreMatchers.notNullValue()
    );
  }
}
