/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.PGStatement;
import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.test.util.CountingSocketFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.Properties;

/**
 * Tests that {@link PreparedStatement#getParameterMetaData()} reuses the cached results of
 * previous "describe statement" requests instead of a network round trip per call, and that the
 * cache is invalidated when the resolution can change: a different set of parameter types, DDL,
 * or a new search_path.
 *
 * @see <a href="https://github.com/pgjdbc/pgjdbc/issues/621">Issue #621</a>
 */
@ParameterizedClass
@MethodSource("data")
public class ParameterMetaDataRoundtripTest extends BaseTest4 {
  private final CountingSocketFactory.Counters socketCounters = CountingSocketFactory.register();
  private final int prepareThreshold;

  public ParameterMetaDataRoundtripTest(int prepareThreshold) {
    this.prepareThreshold = prepareThreshold;
  }

  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{{0}, {5}});
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.PREPARE_THRESHOLD.set(props, prepareThreshold);
    PGProperty.SOCKET_FACTORY.set(props, CountingSocketFactory.class.getName());
    PGProperty.SOCKET_FACTORY_ARG.set(props, socketCounters.key());
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE,
        "simple protocol only does not support describe statement requests");
  }

  @Override
  protected void tearDown() throws SQLException {
    try {
      super.tearDown();
    } finally {
      CountingSocketFactory.unregister(socketCounters);
    }
  }

  private interface SqlAction {
    void run() throws SQLException;
  }

  private void assertRoundtrips(long expected, String message, SqlAction action)
      throws SQLException {
    long before = socketCounters.roundtrips.get();
    action.run();
    assertEquals(expected, socketCounters.roundtrips.get() - before, message);
  }

  @Test
  void repeatedGetParameterMetaDataDescribesOnce() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("SELECT ?::int4, ?::text")) {
      assertRoundtrips(1, "the first getParameterMetaData() should describe the statement", () -> {
        ParameterMetaData pmd = ps.getParameterMetaData();
        assertEquals(Types.INTEGER, pmd.getParameterType(1));
        assertEquals(Types.VARCHAR, pmd.getParameterType(2));
      });
      assertRoundtrips(0, "the second getParameterMetaData() should reuse the cached describe result", () -> {
        ParameterMetaData pmd = ps.getParameterMetaData();
        assertEquals(Types.INTEGER, pmd.getParameterType(1));
        assertEquals(Types.VARCHAR, pmd.getParameterType(2));
      });
    }
  }

  @Test
  void newStatementWithSameSqlUsesCachedDescribe() throws SQLException {
    String sql = "SELECT ?::int4 + ?::int4";
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      assertRoundtrips(1, "the first getParameterMetaData() should describe the statement",
          () -> ps.getParameterMetaData());
    }
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      assertRoundtrips(0, "the describe result should be cached in the connection query cache,"
          + " so a new PreparedStatement with the same SQL should reuse it", () -> {
            ParameterMetaData pmd = ps.getParameterMetaData();
            assertEquals(Types.INTEGER, pmd.getParameterType(1));
            assertEquals(Types.INTEGER, pmd.getParameterType(2));
          });
    }
  }

  @Test
  void changedParameterTypeIsDescribedAgain() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("SELECT ?::timestamp")) {
      assertRoundtrips(1, "an unset parameter type requires a describe",
          () -> assertEquals("timestamp", ps.getParameterMetaData().getParameterTypeName(1)));
      ps.setNull(1, Types.DATE);
      assertRoundtrips(1, "a parameter type change should invalidate the cached describe result",
          () -> assertEquals("date", ps.getParameterMetaData().getParameterTypeName(1)));
      ps.clearParameters();
      assertRoundtrips(0, "the describe result for unset parameter types should still be cached",
          () -> assertEquals("timestamp", ps.getParameterMetaData().getParameterTypeName(1)));
      ps.setNull(1, Types.DATE);
      assertRoundtrips(0, "the describe result for the date parameter should still be cached",
          () -> assertEquals("date", ps.getParameterMetaData().getParameterTypeName(1)));
    }
  }

  @Test
  void ambiguousTypesAreNotResolvedFromCache() throws SQLException {
    try (Statement st = con.createStatement()) {
      st.execute("CREATE OR REPLACE FUNCTION pmd_ambiguous(int4, int4)"
          + " RETURNS int4 LANGUAGE sql AS 'SELECT $1'");
      st.execute("CREATE OR REPLACE FUNCTION pmd_ambiguous(text, text)"
          + " RETURNS text LANGUAGE sql AS 'SELECT $1'");
    }
    try (PreparedStatement ps = con.prepareStatement("SELECT pmd_ambiguous(?, ?)")) {
      ps.setInt(1, 42);
      assertRoundtrips(1, "the type of the set parameter drives the resolution of the unset one",
          () -> assertEquals("int4", ps.getParameterMetaData().getParameterTypeName(2)));
      assertRoundtrips(0, "the describe result for the int variant should be cached",
          () -> assertEquals("int4", ps.getParameterMetaData().getParameterTypeName(2)));

      ps.clearParameters();
      ps.setString(1, "42");
      assertRoundtrips(1, "a different type of the set parameter changes the resolution,"
          + " so the driver should describe the statement again",
          () -> assertEquals("text", ps.getParameterMetaData().getParameterTypeName(2)));

      ps.clearParameters();
      ps.setInt(1, 42);
      assertRoundtrips(0, "the describe result for the int variant should still be cached",
          () -> assertEquals("int4", ps.getParameterMetaData().getParameterTypeName(2)));

      // A result described with the first parameter type set says nothing about describing
      // with it unset, so the driver must ask the server again. The server resolves unknown
      // arguments with a preference for the string category, hence the text variant
      ps.clearParameters();
      assertRoundtrips(1, "unset parameter types should not be resolved from results described"
          + " with a parameter type set",
          () -> assertEquals("text", ps.getParameterMetaData().getParameterTypeName(2)));
    } finally {
      try (Statement st = con.createStatement()) {
        st.execute("DROP FUNCTION IF EXISTS pmd_ambiguous(int4, int4)");
        st.execute("DROP FUNCTION IF EXISTS pmd_ambiguous(text, text)");
      }
    }
  }

  @Test
  void ambiguityErrorIsNotMaskedByCache() throws SQLException {
    try (Statement st = con.createStatement()) {
      st.execute("CREATE OR REPLACE FUNCTION pmd_ambiguous_num(int4, int4)"
          + " RETURNS int4 LANGUAGE sql AS 'SELECT $1'");
      st.execute("CREATE OR REPLACE FUNCTION pmd_ambiguous_num(int8, int8)"
          + " RETURNS int8 LANGUAGE sql AS 'SELECT $1'");
    }
    try (PreparedStatement ps = con.prepareStatement("SELECT pmd_ambiguous_num(?, ?)")) {
      ps.setInt(1, 42);
      assertRoundtrips(1, "the type of the set parameter drives the resolution of the unset one",
          () -> assertEquals("int4", ps.getParameterMetaData().getParameterTypeName(2)));

      // With no types set neither variant wins, so the driver must ask the server,
      // and the cache must not mask the ambiguity error
      ps.clearParameters();
      SQLException e = assertThrows(SQLException.class, ps::getParameterMetaData,
          "with no parameter types set the function call is ambiguous");
      assertEquals("42725", e.getSQLState(), "expecting ERRCODE_AMBIGUOUS_FUNCTION");
    } finally {
      try (Statement st = con.createStatement()) {
        st.execute("DROP FUNCTION IF EXISTS pmd_ambiguous_num(int4, int4)");
        st.execute("DROP FUNCTION IF EXISTS pmd_ambiguous_num(int8, int8)");
      }
    }
  }

  @Test
  void deallocateAllKeepsParameterTypesCorrect() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("SELECT ?::int4")) {
      assertRoundtrips(1, "the first getParameterMetaData() should describe the statement",
          () -> ps.getParameterMetaData());
      try (Statement st = con.createStatement()) {
        st.execute("DEALLOCATE ALL");
      }
      // The describe results share the invalidation epoch with the server-side statements, so
      // DEALLOCATE ALL drops them as well. That costs one extra describe, and the types stay right
      assertEquals(Types.INTEGER, ps.getParameterMetaData().getParameterType(1));
    }
  }

  @Test
  void describeSurvivesUnnoticedDeallocateAll() throws SQLException {
    BaseConnection baseConnection = con.unwrap(BaseConnection.class);
    // Stop the driver from noticing the DEALLOCATE ALL, which is how a server-side statement
    // disappears when something other than the driver resets the session, a connection pooler
    // for instance. The driver then still believes its named statement is on the server
    baseConnection.setFlushCacheOnDeallocate(false);
    try (PreparedStatement ps = con.prepareStatement("SELECT ?::int4")) {
      ((PGStatement) ps).setPrepareThreshold(1);
      ps.setInt(1, 42);
      // Executing prepares the statement on the server under a name, without describing it
      ps.executeQuery().close();

      try (Statement st = con.createStatement()) {
        st.execute("DEALLOCATE ALL");
      }

      // The describe would target the name the server no longer knows, so the driver has to
      // re-parse and describe again rather than surface "prepared statement does not exist"
      assertEquals(Types.INTEGER,
          assertDoesNotThrow(() -> ps.getParameterMetaData().getParameterType(1),
              "getParameterMetaData() should not fail once the server-side statement is gone"));
    } finally {
      baseConnection.setFlushCacheOnDeallocate(true);
    }
  }

  @Test
  void ddlChangeIsDescribedAgain() throws SQLException {
    TestUtil.createTempTable(con, "pmd_ddl", "id int4, name varchar(100)");
    try (PreparedStatement ps =
        con.prepareStatement("INSERT INTO pmd_ddl(id, name) VALUES (?, ?)")) {
      assertRoundtrips(1, "the first getParameterMetaData() should describe the statement",
          () -> assertEquals("int4", ps.getParameterMetaData().getParameterTypeName(1)));
      assertRoundtrips(0, "the describe result should be cached",
          () -> assertEquals("int4", ps.getParameterMetaData().getParameterTypeName(1)));

      try (Statement st = con.createStatement()) {
        st.execute("ALTER TABLE pmd_ddl ALTER COLUMN id TYPE varchar(10)");
      }

      // A describe stores the resolved types in the parameter list, and the next describe would
      // then request them explicitly, so ask for the types of unset parameters again
      ps.clearParameters();
      // The column now drives a different parameter type. The driver re-prepares statements after
      // DDL, so the describe results follow the same rule and the caller sees the current type
      // rather than the one resolved before the ALTER
      assertRoundtrips(1, "DDL changes how the server resolves the parameter, so the driver"
          + " should describe the statement again",
          () -> assertEquals("varchar", ps.getParameterMetaData().getParameterTypeName(1)));
      assertRoundtrips(0, "the describe result for the altered column should be cached",
          () -> assertEquals("varchar", ps.getParameterMetaData().getParameterTypeName(1)));
    }
  }

  @Test
  void searchPathChangeIsDescribedAgain() throws SQLException {
    try (Statement st = con.createStatement()) {
      // CREATE SCHEMA learnt IF NOT EXISTS in 9.3, so drop leftovers from an interrupted run
      // instead, which keeps the test runnable against the oldest server we support
      st.execute("DROP SCHEMA IF EXISTS pmd_sp_int CASCADE");
      st.execute("DROP SCHEMA IF EXISTS pmd_sp_text CASCADE");
      st.execute("CREATE SCHEMA pmd_sp_int");
      st.execute("CREATE SCHEMA pmd_sp_text");
      st.execute("CREATE OR REPLACE FUNCTION pmd_sp_int.pmd_sp(int4)"
          + " RETURNS int4 LANGUAGE sql AS 'SELECT $1'");
      st.execute("CREATE OR REPLACE FUNCTION pmd_sp_text.pmd_sp(text)"
          + " RETURNS text LANGUAGE sql AS 'SELECT $1'");
      st.execute("SET search_path TO pmd_sp_int");
    }
    try (PreparedStatement ps = con.prepareStatement("SELECT pmd_sp(?)")) {
      assertRoundtrips(1, "the first getParameterMetaData() should describe the statement",
          () -> assertEquals("int4", ps.getParameterMetaData().getParameterTypeName(1)));
      try (Statement st = con.createStatement()) {
        st.execute("SET search_path TO pmd_sp_text");
      }
      // A describe stores the resolved types in the parameter list, and the next describe would
      // then request them explicitly, so ask for the types of unset parameters again
      ps.clearParameters();
      assertRoundtrips(1, "the new search_path resolves pmd_sp to a function with a different"
          + " parameter type, so the driver should describe the statement again",
          () -> assertEquals("text", ps.getParameterMetaData().getParameterTypeName(1)));
      assertRoundtrips(0, "the describe result for the new search_path should be cached",
          () -> assertEquals("text", ps.getParameterMetaData().getParameterTypeName(1)));
    } finally {
      try (Statement st = con.createStatement()) {
        st.execute("RESET search_path");
        st.execute("DROP SCHEMA IF EXISTS pmd_sp_int CASCADE");
        st.execute("DROP SCHEMA IF EXISTS pmd_sp_text CASCADE");
      }
    }
  }

  @Test
  void setNullBatchDescribesOnce() throws SQLException {
    TestUtil.createTempTable(con, "pmd_batch", "a int4, b varchar(50)");
    try (PreparedStatement ps = con.prepareStatement("INSERT INTO pmd_batch(a, b) VALUES (?, ?)")) {
      assertRoundtrips(1, "querying parameter types for setNull should describe the statement"
          + " once per batch, not once per row", () -> {
            for (int i = 0; i < 10; i++) {
              ParameterMetaData pmd = ps.getParameterMetaData();
              ps.setNull(1, pmd.getParameterType(1));
              ps.setNull(2, pmd.getParameterType(2));
              ps.addBatch();
            }
          });
      ps.executeBatch();
    }
  }

  @Test
  void setNullBatchWithSetParameterDescribesOnce() throws SQLException {
    TestUtil.createTempTable(con, "pmd_wide",
        "c1 int4, c2 varchar(50), c3 int8, c4 int2, c5 float4,"
            + " c6 float8, c7 numeric, c8 date, c9 timestamp, c10 varchar(10)");
    String sql = "INSERT INTO pmd_wide VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      // The first row describes with the key parameter set and the rest unset. Every following row
      // asks with the types of the previous setNull calls, which are the ones the server resolved,
      // so the describe result stays compatible
      assertRoundtrips(1, "a batch that queries the parameter types of every row should describe"
          + " the statement once, not once per row", () -> {
            for (int row = 0; row < 10; row++) {
              ps.setInt(1, row);
              ParameterMetaData pmd = ps.getParameterMetaData();
              for (int column = 2; column <= 10; column++) {
                ps.setNull(column, pmd.getParameterType(column));
              }
              ps.addBatch();
            }
          });
      ps.executeBatch();
      assertEquals("10", TestUtil.queryForString(con, "SELECT count(*) FROM pmd_wide"),
          "the batch should insert one row per iteration");
    }
  }

  @Test
  void zeroParameterQueryIsNotDescribed() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("SELECT 1")) {
      assertRoundtrips(0, "a query that binds no parameters leaves the server nothing to resolve,"
          + " so it should not be described at all",
          () -> assertEquals(0, ps.getParameterMetaData().getParameterCount()));
      assertRoundtrips(0, "and it stays that way on a second call",
          () -> assertEquals(0, ps.getParameterMetaData().getParameterCount()));
    }
  }

  @Test
  void multiStatementQueryUsesCachedDescribe() throws SQLException {
    // Bounded result types keep the flushIfDeadlockRisk estimation below the forced-Sync
    // threshold, so every describe of this statement is a single round trip
    try (PreparedStatement ps = con.prepareStatement("SELECT ?::int4; SELECT ?::int8")) {
      assertRoundtrips(1, "the first getParameterMetaData() should describe both statements", () -> {
        ParameterMetaData pmd = ps.getParameterMetaData();
        assertEquals(2, pmd.getParameterCount());
        assertEquals(Types.INTEGER, pmd.getParameterType(1));
        assertEquals(Types.BIGINT, pmd.getParameterType(2));
      });
      assertRoundtrips(0, "the second getParameterMetaData() should reuse the cached describe results",
          () -> assertEquals(Types.INTEGER, ps.getParameterMetaData().getParameterType(1)));
      ps.setNull(1, Types.BIGINT);
      assertRoundtrips(1, "a type change in one subquery should describe the whole statement again",
          () -> assertEquals(Types.BIGINT, ps.getParameterMetaData().getParameterType(1)));
    }
  }
}
