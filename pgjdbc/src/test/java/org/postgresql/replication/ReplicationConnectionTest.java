/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.rules.annotation.HaveMinimalServerVersion;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

@HaveMinimalServerVersion("9.4")
public class ReplicationConnectionTest {

  private Connection replConnection;

  @Before
  public void setUp() throws Exception {
    replConnection = openReplicationConnection();
    //DriverManager.setLogWriter(new PrintWriter(System.out));
  }

  @After
  public void tearDown() throws Exception {
    replConnection.close();
  }

  @Test
  public void testIsValid() throws Exception {
    boolean result = replConnection.isValid(3);

    PGConnection connection = (PGConnection) replConnection;
    connection.getBackendPID();

    assertThat("Replication connection as Simple connection can be check on valid",
        result, equalTo(true)
    );
  }

  @Test
  public void testConnectionNotValidWhenSessionTerminated() throws Exception {
    int backendId = ((PGConnection) replConnection).getBackendPID();

    Connection sqlConnection = TestUtil.openDB();

    Statement terminateStatement = sqlConnection.createStatement();
    terminateStatement.execute("SELECT pg_terminate_backend(" + backendId + ")");
    terminateStatement.close();
    sqlConnection.close();

    boolean result = replConnection.isValid(3);

    assertThat("When postgresql terminate session with replication connection, "
            + "isValid methos should return false, because next query on this connection will fail",
        result, equalTo(false)
    );
  }

  @Test
  public void testReplicationCommandResultSetAccessByIndex() throws Exception {
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
  public void testReplicationCommandResultSetAccessByName() throws Exception {
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

  private Connection openReplicationConnection() throws Exception {
    Properties properties = new Properties();
    PGProperty.ASSUME_MIN_SERVER_VERSION.set(properties, "9.4");
    PGProperty.REPLICATION.set(properties, "database");
    //Only symple query protocol available for replication connection
    PGProperty.PREFER_QUERY_MODE.set(properties, "simple");
    return TestUtil.openDB(properties);
  }
}
