/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.BrokenInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Checks the state of a connection after the driver fails to send a binary stream parameter
 * (<a href="https://github.com/pgjdbc/pgjdbc/issues/4504">issue 4504</a>).
 *
 * <p>A statement that fails to bind its parameters must leave the connection either usable or
 * closed. A connection that stays open but is out of sync with the backend answers the next
 * statement with the reply to an earlier one, and a pool hands it to the next caller.</p>
 */
public class BrokenBinaryStreamStateTest {
  private static final String TABLE = "broken_binary_stream_state";

  /** What the caller executes. */
  enum Shape {
    /** One statement. */
    SINGLE,
    /** A batch whose first entry is valid and whose last entry fails. */
    BATCH_LAST_ENTRY_FAILS,
    /** A batch whose first entry fails. */
    BATCH_FIRST_ENTRY_FAILS
  }

  /** How the stream parameter breaks. */
  enum Failure {
    /** {@code InputStream.read()} throws {@code IOException}. */
    READ_THROWS,
    /** The stream reaches end of stream before the declared length. */
    SHORTER_THAN_DECLARED
  }

  private static final int DECLARED_LENGTH = 2;

  @BeforeAll
  static void createTable() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, TABLE, "b bytea");
    }
  }

  @AfterAll
  static void dropTable() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, TABLE);
    }
  }

  @BeforeEach
  void emptyTable() throws SQLException {
    try (Connection con = TestUtil.openDB(); Statement st = con.createStatement()) {
      st.executeUpdate("DELETE FROM " + TABLE);
    }
  }

  static Stream<Arguments> cases() {
    List<Arguments> cases = new ArrayList<>();
    for (PreferQueryMode mode : new PreferQueryMode[]{PreferQueryMode.SIMPLE, PreferQueryMode.EXTENDED}) {
      for (boolean autoCommit : new boolean[]{true, false}) {
        for (Shape shape : Shape.values()) {
          for (Failure failure : Failure.values()) {
            cases.add(Arguments.of(mode, autoCommit, shape, failure));
          }
        }
      }
    }
    return cases.stream();
  }

  private static InputStream brokenStream(Failure failure) {
    switch (failure) {
      case READ_THROWS:
        return new BrokenInputStream(new ByteArrayInputStream(new byte[DECLARED_LENGTH]), 1);
      case SHORTER_THAN_DECLARED:
        return new ByteArrayInputStream(new byte[DECLARED_LENGTH - 1]);
      default:
        throw new IllegalArgumentException("Unexpected failure " + failure);
    }
  }

  private static InputStream validStream() {
    return new ByteArrayInputStream(new byte[DECLARED_LENGTH]);
  }

  @ParameterizedTest(name = "{0}, autoCommit={1}, {2}, {3}")
  @MethodSource("cases")
  void connectionIsUsableOrClosedAfterBrokenStream(
      PreferQueryMode mode, boolean autoCommit, Shape shape, Failure failure) throws SQLException {
    Properties props = new Properties();
    PGProperty.PREFER_QUERY_MODE.set(props, mode.value());
    try (Connection con = TestUtil.openDB(props)) {
      con.setAutoCommit(autoCommit);
      try (PreparedStatement ps = con.prepareStatement("INSERT INTO " + TABLE + "(b) VALUES (?)")) {
        assertThrows(SQLException.class, () -> execute(ps, shape, failure),
            "a stream that breaks while the driver sends it must fail the statement");
      }

      if (shape == Shape.SINGLE) {
        // The driver already supports this: nothing of a failed single statement reaches the
        // backend, so the caller can carry on (see PreparedStatementTest#testBinaryStreamErrorsRestartable)
        assertFalse(con.isClosed(), "a single statement that fails to bind must not close the connection");
      }
      if (!con.isClosed()) {
        assertConnectionAnswersItsOwnQuery(con);
      }
    }
  }

  private static void execute(PreparedStatement ps, Shape shape, Failure failure)
      throws SQLException {
    switch (shape) {
      case SINGLE:
        ps.setBinaryStream(1, brokenStream(failure), DECLARED_LENGTH);
        ps.executeUpdate();
        break;
      case BATCH_LAST_ENTRY_FAILS:
        ps.setBinaryStream(1, validStream(), DECLARED_LENGTH);
        ps.addBatch();
        ps.setBinaryStream(1, brokenStream(failure), DECLARED_LENGTH);
        ps.addBatch();
        ps.executeBatch();
        break;
      case BATCH_FIRST_ENTRY_FAILS:
        ps.setBinaryStream(1, brokenStream(failure), DECLARED_LENGTH);
        ps.addBatch();
        ps.setBinaryStream(1, validStream(), DECLARED_LENGTH);
        ps.addBatch();
        ps.executeBatch();
        break;
      default:
        throw new IllegalArgumentException("Unexpected shape " + shape);
    }
  }

  /**
   * A connection that is out of sync answers with the reply to an earlier statement, so a query
   * with a recognizable result is the check.
   */
  private static void assertConnectionAnswersItsOwnQuery(Connection con) throws SQLException {
    try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT 42")) {
      assertTrue(rs.next(), "SELECT 42 must return a row");
      assertEquals(42, rs.getInt(1), "SELECT 42");
    }
  }
}
