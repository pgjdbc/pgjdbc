/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Integration tests for adaptive fetch process.
 */
public class AdaptiveFetchSizeTest {

  private Connection connection;
  private Statement statement;
  private ResultSet resultSet;

  private String table = "test_adaptive_fetch";
  private String columns = "value VARCHAR";

  /**
   * Method to drop table and close connection.
   */
  @After
  public void tearDown() throws SQLException {
    if (connection != null && !connection.isClosed()) {
      connection.setAutoCommit(true);
      if (resultSet != null) {
        resultSet.close();
      }
      if (statement != null) {
        statement.close();
      }
      TestUtil.dropTable(connection, table);
      TestUtil.closeDB(connection);
    }
  }

  /**
   * First integration test. First is created table with rows sizes like 4 x 35B, 1 x 40B, 45 x 30B.
   * Next fetching is happening. First fetch is using default fetch size, so it returns 4 rows.
   * After reading 4 rows, new fetch size is computed. As biggest rows size so far was 35B, then
   * 300/35B = 8 rows. Second fetch is done with 8 rows. First row in this fetch has size 40B, which
   * gonna change fetch size to 7 rows (300/40B = 7), next fetch reads won't change size and 7 will
   * be used to the end.
   * To check if this works correctly checked is:
   * - if first 4 rows from result set have fetch size as 4;
   * - if next 8 rows from result set have fetch size as 8;
   * - if next 38 rows from result set have fetch size as 7;
   * - check if all 50 rows were read.
   */
  @Test
  public void testAdaptiveFetching() throws SQLException {
    int startFetchSize = 4;
    int expectedFirstSize = 8;
    int expectedSecondSize = 7;
    int expectedCounter = 50;
    int resultCounter = 0;
    Properties properties = new Properties();
    PGProperty.DEFAULT_ROW_FETCH_SIZE.set(properties, startFetchSize);
    PGProperty.MAX_RESULT_BUFFER.set(properties, "300");
    PGProperty.ADAPTIVE_FETCH.set(properties, true);

    connection = TestUtil.openDB(properties);

    TestUtil.createTable(connection, table, columns);

    for (int i = 0; i != expectedCounter; i++) {
      if (i == 4) {
        addStringWithSize(40);
      } else {
        addStringWithSize(35);
      }
    }

    executeFetchingQuery();

    for (int i = 0; i != 4; i++) {
      resultSet.next();
      resultCounter++;
      assertEquals(startFetchSize, resultSet.getFetchSize());
    }
    for (int i = 0; i != 8; i++) {
      resultSet.next();
      resultCounter++;
      assertEquals(expectedFirstSize, resultSet.getFetchSize());
    }
    while (resultSet.next()) {
      resultCounter++;
      assertEquals(expectedSecondSize, resultSet.getFetchSize());
    }

    assertEquals(expectedCounter, resultCounter);
  }


  /**
   * Second integration test. The main purpose of this set is to check if minimum size was used
   * during adaptive fetching. To a table are added 50 rows with sizes: 1x270B, 49x10B. First fetch
   * is done with default size 4. As first row from result set have size 270B, then computed size
   * should be 1 (300/270 = 1), however minimum size set to 10 should make that next fetch should be
   * done with size 10. After this fetch size shouldn't change to the end.
   * To check if this works correctly checked is:
   * - if first 4 rows from result set have fetch size as 4;
   * - if next 46 rows from result set have fetch size as 10;
   * - check if all 50 rows were read.
   */
  @Test
  public void testAdaptiveFetchingWithMinimumSize() throws SQLException {
    int startFetchSize = 4;
    int expectedSize = 10;
    int expectedCounter = 50;
    int resultCounter = 0;
    Properties properties = new Properties();
    PGProperty.DEFAULT_ROW_FETCH_SIZE.set(properties, startFetchSize);
    PGProperty.MAX_RESULT_BUFFER.set(properties, "300");
    PGProperty.ADAPTIVE_FETCH.set(properties, true);
    PGProperty.ADAPTIVE_FETCH_MINIMUM.set(properties, expectedSize);

    connection = TestUtil.openDB(properties);

    TestUtil.createTable(connection, table, columns);

    for (int i = 0; i != expectedCounter; i++) {
      if (i == 0) {
        addStringWithSize(270);
      } else {
        addStringWithSize(10);
      }
    }

    executeFetchingQuery();

    for (int i = 0; i != 4; i++) {
      resultSet.next();
      resultCounter++;
      assertEquals(startFetchSize, resultSet.getFetchSize());
    }
    while (resultSet.next()) {
      resultCounter++;
      assertEquals(expectedSize, resultSet.getFetchSize());
    }

    assertEquals(expectedCounter, resultCounter);
  }

  /**
   * Third integration test. The main purpose of this set is to check if maximum size was used
   * during adaptive fetching. To a table are added 50 rows with sizes: 4x10B, 46x30B. First fetch
   * is done with default size 4. As first fetch have only rows with size 10B, then computed fetch
   * size should be 30 (300/10 = 30), however maximum size set to 10 should make that next fetch
   * should be done with size 10 (in other situation next rows will exceed size of maxResultBuffer).
   * After this fetch size shouldn't change to the end.
   * To check if this works correctly checked is:
   * - if first 4 rows from result set have fetch size as 4;
   * - if next 46 rows from result set have fetch size as 10;
   * - check if all 50 rows were read.
   */
  @Test
  public void testAdaptiveFetchingWithMaximumSize() throws SQLException {
    int startFetchSize = 4;
    int expectedSize = 10;
    int expectedCounter = 50;
    int resultCounter = 0;
    Properties properties = new Properties();
    PGProperty.DEFAULT_ROW_FETCH_SIZE.set(properties, startFetchSize);
    PGProperty.MAX_RESULT_BUFFER.set(properties, "300");
    PGProperty.ADAPTIVE_FETCH.set(properties, true);
    PGProperty.ADAPTIVE_FETCH_MAXIMUM.set(properties, expectedSize);

    connection = TestUtil.openDB(properties);

    TestUtil.createTable(connection, table, columns);

    for (int i = 0; i != expectedCounter; i++) {
      if (i < 4) {
        addStringWithSize(10);
      } else {
        addStringWithSize(30);
      }
    }

    executeFetchingQuery();

    for (int i = 0; i != 4; i++) {
      resultSet.next();
      resultCounter++;
      assertEquals(startFetchSize, resultSet.getFetchSize());
    }
    while (resultSet.next()) {
      resultCounter++;
      assertEquals(expectedSize, resultSet.getFetchSize());
    }

    assertEquals(expectedCounter, resultCounter);
  }

  /**
   * Third integration test. The main purpose of this set is to do fetching with maximum possible
   * buffer. To a table are added 1000 rows with sizes 10B each. First fetch is done with default
   * size 4, then second fetch should have size computed on maxResultBuffer, most probably that
   * second fetch would be the last.
   * To check if this works correctly checked is:
   * - if first 4 rows from result set have fetch size as 4;
   * - if next 996 rows from result set have fetch size computed with using max size of
   * maxResultBuffer;
   * - check if all 1000 rows were read.
   */
  @Test
  public void testAdaptiveFetchingWithMoreData() throws SQLException {
    int startFetchSize = 4;
    int expectedCounter = 1000;
    int resultCounter = 0;
    Properties properties = new Properties();
    PGProperty.DEFAULT_ROW_FETCH_SIZE.set(properties, startFetchSize);
    PGProperty.MAX_RESULT_BUFFER.set(properties, "90p");
    PGProperty.ADAPTIVE_FETCH.set(properties, true);

    int expectedSize = (int) (
        (long) (0.90 * ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax()) / 10);

    connection = TestUtil.openDB(properties);

    TestUtil.createTable(connection, table, columns);

    for (int i = 0; i != expectedCounter; i++) {
      addStringWithSize(10);
    }

    executeFetchingQuery();

    for (int i = 0; i != 4; i++) {
      resultSet.next();
      resultCounter++;
      assertEquals(startFetchSize, resultSet.getFetchSize());
    }
    while (resultSet.next()) {
      resultCounter++;
      assertEquals(expectedSize, resultSet.getFetchSize());
    }

    assertEquals(expectedCounter, resultCounter);
  }

  /**
   * Method to execute query, which gonna be fetched. Sets auto commit to false to make fetching
   * happen.
   */
  private void executeFetchingQuery() throws SQLException {
    connection.setAutoCommit(false);

    statement = connection.createStatement();
    resultSet = statement.executeQuery("SELECT * FROM " + table);
  }

  /**
   * Method to insert string with given size to a table.
   * @param size desired size of a string to be inserted in the table
   */
  private void addStringWithSize(int size) throws SQLException {
    StringBuilder sb = new StringBuilder(size + 2);
    sb.append("'");
    for (int i = 0; i != size; i++) {
      sb.append('H');
    }
    sb.append("'");
    String insert = TestUtil.insertSQL(table, "value", sb.toString());
    TestUtil.execute(insert, connection);
  }

}
