/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

@ParameterizedClass
@MethodSource("data")
public class BatchFailureTest extends BaseTest4 {
  private final BatchType batchType;
  private final AutoCommit autoCommit;
  private final FailMode failMode;
  private final FailPosition failPosition;
  private final BinaryMode binaryMode;
  private final boolean insertRewrite;

  enum BatchType {
    SIMPLE {
      @Override
      public Statement createStatement(Connection con) throws SQLException {
        return con.createStatement();
      }
    },
    PREPARED {
      @Override
      public Statement createStatement(Connection con) throws SQLException {
        return con.prepareStatement("INSERT INTO batchUpdCnt(id) VALUES (?)");
      }
    },
    PREPARED_WITH_GENERATED {
      @Override
      public Statement createStatement(Connection con) throws SQLException {
        return con.prepareStatement("INSERT INTO batchUpdCnt(id) VALUES (?)", new String[]{"id"});
      }
    };

    public abstract Statement createStatement(Connection con) throws SQLException;

    public void addRow(Statement statement, String value) throws SQLException {
      switch (this) {
        case SIMPLE:
          statement.addBatch("INSERT INTO batchUpdCnt(id) VALUES ('" + value + "')");
          break;
        case PREPARED:
        case PREPARED_WITH_GENERATED:
          PreparedStatement ps = (PreparedStatement) statement;
          ps.setString(1, value);
          ps.addBatch();
          break;
      }
    }
  }

  private enum FailMode {
    NO_FAIL_JUST_INSERTS, NO_FAIL_SELECT,
    FAIL_VIA_SELECT_PARSE, FAIL_VIA_SELECT_RUNTIME,
    FAIL_VIA_DUP_KEY;

    public boolean supports(BatchType batchType) {
      return batchType != BatchType.SIMPLE ^ this.name().contains("SELECT");
    }

    public void injectFailure(Statement statement, BatchType batchType) throws SQLException {
      switch (this) {
        case NO_FAIL_JUST_INSERTS:
          break;
        case NO_FAIL_SELECT:
          statement.addBatch("select 1 union all select 2");
          break;
        case FAIL_VIA_SELECT_RUNTIME:
          statement.addBatch("select 0/count(*) where 1=2");
          break;
        case FAIL_VIA_SELECT_PARSE:
          statement.addBatch("seeeeleeeect 1");
          break;
        case FAIL_VIA_DUP_KEY:
          batchType.addRow(statement, "key-2");
          break;
        default:
          throw new IllegalArgumentException("Unexpected value " + this);
      }
    }
  }

  private enum FailPosition {
    NONE, FIRST_ROW, SECOND_ROW, MIDDLE, ALMOST_LAST_ROW, LAST_ROW;

    public boolean supports(FailMode mode) {
      return this == NONE ^ mode.name().startsWith("FAIL");
    }
  }

  public BatchFailureTest(BatchType batchType, AutoCommit autoCommit,
      FailMode failMode, FailPosition failPosition, BinaryMode binaryMode,
      boolean insertRewrite) {
    this.batchType = batchType;
    this.autoCommit = autoCommit;
    this.failMode = failMode;
    this.failPosition = failPosition;
    this.binaryMode = binaryMode;
    this.insertRewrite = insertRewrite;
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    boolean[] booleans = new boolean[]{true, false};
    for (BatchType batchType : BatchType.values()) {
      for (FailMode failMode : FailMode.values()) {
        if (!failMode.supports(batchType)) {
          continue;
        }
        for (FailPosition failPosition : FailPosition.values()) {
          if (!failPosition.supports(failMode)) {
            continue;
          }
          for (AutoCommit autoCommit : AutoCommit.values()) {
            for (BinaryMode binaryMode : BinaryMode.values()) {
              for (boolean insertRewrite : booleans) {
                ids.add(new Object[]{batchType, autoCommit, failMode, failPosition, binaryMode, insertRewrite});
              }
            }
          }
        }
      }
    }
    return ids;
  }

  @Override
  protected void updateProperties(Properties props) {
    if (binaryMode == BinaryMode.FORCE) {
      forceBinary(props);
    }
    PGProperty.REWRITE_BATCHED_INSERTS.set(props, insertRewrite);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTempTable(con, "batchUpdCnt", "id varchar(512) primary key, data varchar(512)");
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO batchUpdCnt(id) VALUES ('key-2')");
    stmt.close();
    con.setAutoCommit(autoCommit == AutoCommit.YES);
  }

  @Test
  public void run() throws SQLException {
    Statement statement = batchType.createStatement(con);

    int minBatchResults = 0;
    int pos = 0;
    if (failPosition == FailPosition.FIRST_ROW) {
      failMode.injectFailure(statement, batchType);
      pos++;
      minBatchResults = pos;
    }

    batchType.addRow(statement, "key-1");
    pos++;

    if (failPosition == FailPosition.SECOND_ROW) {
      failMode.injectFailure(statement, batchType);
      pos++;
      minBatchResults = pos;
    }

    for (int i = 0; i < 1000; i++) {
      batchType.addRow(statement, "key_" + i);
      pos++;
      if (failPosition == FailPosition.ALMOST_LAST_ROW && i == 997
          || failPosition == FailPosition.MIDDLE && i == 500) {
        failMode.injectFailure(statement, batchType);
        pos++;
        minBatchResults = pos;
      }
    }

    if (failPosition == FailPosition.LAST_ROW) {
      failMode.injectFailure(statement, batchType);
      pos++;
      minBatchResults = pos;
    }

    List<String> keys = new ArrayList<>();
    int[] batchResult;
    int expectedRows = 1;
    try {
      batchResult = statement.executeBatch();
      assertTrue(failPosition == FailPosition.NONE, "Expecting BatchUpdateException due to " + failMode
              + ", executeBatch returned " + Arrays.toString(batchResult));
      expectedRows = pos + 1; // +1 since key-2 is already in the DB
    } catch (BatchUpdateException ex) {
      batchResult = ex.getUpdateCounts();
      assertTrue(failPosition != FailPosition.NONE, "Should not fail since fail mode should be " + failMode
              + ", executeBatch returned " + Arrays.toString(batchResult));

      for (int i : batchResult) {
        if (i != Statement.EXECUTE_FAILED) {
          expectedRows++;
        }
      }

      assertTrue(batchResult.length >= minBatchResults, "Batch should fail at row " + minBatchResults
              + ", thus at least " + minBatchResults
              + " items should be returned, actual result is " + batchResult.length + " items, "
              + Arrays.toString(batchResult));
    } finally {
      if (batchType == BatchType.PREPARED_WITH_GENERATED) {
        ResultSet rs = statement.getGeneratedKeys();
        while (rs.next()) {
          keys.add(rs.getString(1));
        }
      }
      statement.close();
    }

    if (!con.getAutoCommit()) {
      con.commit();
    }

    int finalCount = getBatchUpdCount();
    int[] batchResultForAssertion = batchResult;
    assertEquals(
        expectedRows - 1,
        finalCount - 1,
        () -> "Number of new rows in batchUpdCnt should match number of non-error batchResult items"
            + Arrays.toString(batchResultForAssertion));

    if (batchType != BatchType.PREPARED_WITH_GENERATED) {
      return;
    }

    if (finalCount > 1) {
      assertFalse(
          keys.isEmpty(),
          () -> (finalCount - 1) + " rows were inserted, thus expecting generated keys");
    }
    Set<String> uniqueKeys = new HashSet<>(keys);
    assertEquals(
        keys.size(),
        uniqueKeys.size(),
        () -> "Generated keys should be unique: " + keys);
    assertEquals(
        keys.size(),
        finalCount - 1,
        () -> "Number of generated keys should match the number of inserted rows" + keys);
  }

  private int getBatchUpdCount() throws SQLException {
    PreparedStatement ps = con.prepareStatement("select count(*) from batchUpdCnt");
    ResultSet rs = ps.executeQuery();
    assertTrue(rs.next(), "count(*) must return 1 row");
    return rs.getInt(1);
  }
}
