/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Stream;

/**
 * The four methods that take column indexes agree on what an index array means: a null or empty array requests no
 * generated keys and runs the statement, and an array with an index is refused with SQLState
 * {@code 0A000}.
 *
 * <p>A null array used to reach the refusal in {@code Connection.prepareStatement} and in
 * {@code Statement.execute}, while {@code executeUpdate} and {@code executeLargeUpdate} ran the statement.</p>
 */
class GeneratedKeysColumnIndexesTest extends BaseTest4 {
  private static final String INSERT = "INSERT INTO genkeys VALUES (1, 'a', 2)";

  /**
   * The JDBC methods that take the generated-key columns as indexes. Each one inserts a row and returns the statement
   * that did it, so the caller can read the update count and the generated keys off it.
   */
  enum EntryPoint {
    PREPARE_STATEMENT {
      @Override
      Statement insertOneRow(Connection con, int @Nullable [] columnIndexes) throws SQLException {
        PreparedStatement ps = con.prepareStatement(INSERT, columnIndexes);
        ps.executeUpdate();
        return ps;
      }
    },
    EXECUTE {
      @Override
      Statement insertOneRow(Connection con, int @Nullable [] columnIndexes) throws SQLException {
        Statement st = con.createStatement();
        st.execute(INSERT, columnIndexes);
        return st;
      }
    },
    EXECUTE_UPDATE {
      @Override
      Statement insertOneRow(Connection con, int @Nullable [] columnIndexes) throws SQLException {
        Statement st = con.createStatement();
        st.executeUpdate(INSERT, columnIndexes);
        return st;
      }
    },
    EXECUTE_LARGE_UPDATE {
      @Override
      Statement insertOneRow(Connection con, int @Nullable [] columnIndexes) throws SQLException {
        Statement st = con.createStatement();
        st.executeLargeUpdate(INSERT, columnIndexes);
        return st;
      }
    };

    abstract Statement insertOneRow(Connection con, int @Nullable [] columnIndexes) throws SQLException;
  }

  /**
   * The index arrays that name no column.
   */
  enum NoColumns {
    NULL(null),
    EMPTY(new int[0]);

    private final int @Nullable [] columnIndexes;

    NoColumns(int @Nullable [] columnIndexes) {
      this.columnIndexes = columnIndexes;
    }

    int @Nullable [] columnIndexes() {
      return columnIndexes;
    }
  }

  static Stream<Arguments> entryPointsWithNoColumns() {
    Collection<Arguments> arguments = new ArrayList<>();
    for (EntryPoint entryPoint : EntryPoint.values()) {
      for (NoColumns noColumns : NoColumns.values()) {
        arguments.add(Arguments.of(entryPoint, noColumns));
      }
    }
    return arguments.stream();
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTempTable(con, "genkeys", "a serial, b varchar(5), c int");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "genkeys");
    super.tearDown();
  }

  @ParameterizedTest
  @MethodSource("entryPointsWithNoColumns")
  void anIndexArrayThatNamesNoColumnInsertsTheRowWithoutGeneratedKeys(EntryPoint entryPoint, NoColumns noColumns)
      throws SQLException {
    try (Statement st = entryPoint.insertOneRow(con, noColumns.columnIndexes())) {
      assertAll(
          () -> assertEquals(1, st.getUpdateCount(), "getUpdateCount()"),
          () -> assertFalse(st.getGeneratedKeys().next(), "getGeneratedKeys().next()"));
    }
  }

  @ParameterizedTest
  @EnumSource(EntryPoint.class)
  void aColumnIndexIsRefused(EntryPoint entryPoint) {
    SQLException e =
        assertThrows(SQLException.class, () -> entryPoint.insertOneRow(con, new int[]{1}));
    assertEquals(PSQLState.NOT_IMPLEMENTED.getState(), e.getSQLState(), e.getMessage());
  }
}
