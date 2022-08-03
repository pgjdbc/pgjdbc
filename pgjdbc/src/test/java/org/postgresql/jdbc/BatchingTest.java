package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.v3.BatchedQuery;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Properties;

public class BatchingTest {
  private Connection conn;

  @BeforeEach
  public void beforeAll() throws Exception {
    Properties props = new Properties();
    props.setProperty("reWriteBatchedInserts", "true");
    conn = TestUtil.openDB(props);
    assumeTrue(conn.getMetaData().supportsBatchUpdates());
  }

  @AfterEach
  public void afterAll() throws Exception {
    TestUtil.dropTable(conn, "Test");
    TestUtil.dropSchema(conn, "batching");
    TestUtil.closeDB(conn);
  }

  @Test
  public void testInsert() throws Exception {
    TestUtil.createSchema(conn, "batching");
    TestUtil.createTable(conn, "batching.Test", "id INT, name VARCHAR(20)");

    String sql = "INSERT INTO batching.Test (id, name) VALUES (?, ?)";
    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setInt(1, 100);
      stmt.setString(2, "hello 1");
      stmt.addBatch();

      stmt.setInt(1, 101);
      stmt.setString(2, "hello 2");
      stmt.addBatch();

      stmt.setInt(1, 102);
      stmt.setString(2, "hello 3");
      stmt.addBatch();

      stmt.setInt(1, 103);
      stmt.setString(2, "hello 4");
      stmt.addBatch();

      int[] results = stmt.executeBatch();
      assertArrayEquals(new int[]{Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO,
          Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO}, results);

      PgPreparedStatement pgStmt = (PgPreparedStatement) stmt;
      assertEquals(BatchedQuery.class, pgStmt.preparedQuery.query.getClass());
    }

    TestUtil.assertNumberOfRows(conn, "batching.Test", 4, "4 row expected");
  }

  @Test
  public void testMerge() throws Exception {
    if (!TestUtil.haveMinimumServerVersion(conn, ServerVersion.v15)) {
      return;
    }

    TestUtil.createSchema(conn, "batching");
    TestUtil.createTable(conn, "batching.Test", "id INT, name VARCHAR(20)");

    String sql = "MERGE INTO batching.Test AS t " +
        "USING (VALUES (?, ?)) AS src (id, name) " +
        "ON t.id = src.id " +
        "WHEN MATCHED THEN DELETE " +
        "WHEN NOT MATCHED THEN INSERT (id, name) VALUES (src.id, src.name)";

    try (Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(TestUtil.insertSQL("batching.Test", "100, 'hello 100'"));
      stmt.executeUpdate(TestUtil.insertSQL("batching.Test", "101, 'hello 102'"));
    }

    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setInt(1, 100);
      stmt.setString(2, "");
      stmt.addBatch();

      stmt.setInt(1, 101);
      stmt.setString(2, "");
      stmt.addBatch();

      stmt.setInt(1, 102);
      stmt.setString(2, "hello 102");
      stmt.addBatch();

      stmt.setInt(1, 103);
      stmt.setString(2, "hello 103");
      stmt.addBatch();

      int[] results = stmt.executeBatch();
      assertArrayEquals(new int[]{Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO,
          Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO}, results);

      PgPreparedStatement pgStmt = (PgPreparedStatement) stmt;
      assertEquals(BatchedQuery.class, pgStmt.preparedQuery.query.getClass());
    }

    TestUtil.assertNumberOfRows(conn, "batching.Test", 2, "2 row expected");
  }
}
