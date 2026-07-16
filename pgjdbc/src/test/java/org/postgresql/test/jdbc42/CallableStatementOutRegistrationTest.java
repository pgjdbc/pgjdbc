/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGResultSetMetaData;
import org.postgresql.core.Field;
import org.postgresql.core.Oid;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

/**
 * Behaviour of {@link org.postgresql.jdbc.PgCallableStatement} OUT-parameter registration after the
 * F-13 refactor: {@code Types.TIME_WITH_TIMEZONE} / {@code Types.TIMESTAMP_WITH_TIMEZONE} are
 * accepted for the matching column, genuinely mismatched pairs are still rejected, and the detached
 * snapshot is refreshed on re-execute and released on close.
 */
public class CallableStatementOutRegistrationTest extends BaseTest4 {

  private static final TimeZone ZONE = TimeZone.getTimeZone("GMT+05:00");

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.execute(con, "CREATE OR REPLACE FUNCTION f13_timetz() RETURNS timetz "
        + "AS $$ SELECT TIMETZ '12:34:56.789+03' $$ LANGUAGE sql");
    TestUtil.execute(con, "CREATE OR REPLACE FUNCTION f13_tstz() RETURNS timestamptz "
        + "AS $$ SELECT TIMESTAMPTZ '2020-01-02 12:34:56.789+03' $$ LANGUAGE sql");
    // A value that changes between executions and is read through a delegated getter (getObject),
    // so the test proves the snapshot is refreshed rather than a cached callResult.
    TestUtil.execute(con, "CREATE SEQUENCE IF NOT EXISTS f13_seq");
    TestUtil.execute(con, "CREATE OR REPLACE FUNCTION f13_next() RETURNS timestamptz "
        + "AS $$ SELECT to_timestamp(nextval('f13_seq')) $$ LANGUAGE sql");
    // Two OUT columns behind an IN parameter, so the OUT getters exercise parameterColumn > 1.
    TestUtil.execute(con, "CREATE OR REPLACE FUNCTION f13_multi("
        + "IN a int, OUT ts timestamp, OUT tz timestamptz) AS "
        + "$$ SELECT TIMESTAMP '2020-01-02 12:34:56.789', TIMESTAMPTZ '2021-06-07 01:02:03+03' $$ "
        + "LANGUAGE sql");
    // A composite OUT for the SQLData getObject(index, Class) / getObject(index, Map) paths.
    TestUtil.createCompositeType(con, "f13_point", "x int, y int");
    TestUtil.execute(con, "CREATE OR REPLACE FUNCTION f13_point_fn() RETURNS f13_point "
        + "AS $$ SELECT ROW(3, 4)::f13_point $$ LANGUAGE sql");
    TestUtil.execute(con, "CREATE OR REPLACE FUNCTION f13_null_point() RETURNS f13_point "
        + "AS $$ SELECT NULL::f13_point $$ LANGUAGE sql");
    // An anonymous record OUT: the column reports type `record`, so the concrete type used to decode
    // the SQLData must come from registerOutParameter(..., STRUCT, "f13_point").
    TestUtil.execute(con, "CREATE OR REPLACE FUNCTION f13_anon() RETURNS record "
        + "AS $$ SELECT ROW(3, 4) $$ LANGUAGE sql");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropFunction(con, "f13_timetz", "");
    TestUtil.dropFunction(con, "f13_tstz", "");
    TestUtil.dropFunction(con, "f13_next", "");
    TestUtil.dropFunction(con, "f13_multi", "int");
    TestUtil.dropFunction(con, "f13_point_fn", "");
    TestUtil.dropFunction(con, "f13_null_point", "");
    TestUtil.dropFunction(con, "f13_anon", "");
    TestUtil.dropType(con, "f13_point");
    TestUtil.execute(con, "DROP SEQUENCE IF EXISTS f13_seq");
    super.tearDown();
  }

  @Test
  public void timetzAcceptsTimeWithTimezoneRegistration() throws Exception {
    assumeCallableStatementsSupported();
    try (CallableStatement cs = con.prepareCall("{ ? = call f13_timetz() }")) {
      cs.registerOutParameter(1, Types.TIME_WITH_TIMEZONE);
      cs.execute();
      assertEquals(OffsetTime.parse("12:34:56.789+03:00"), cs.getObject(1, OffsetTime.class));
      // The relaxed checkIndex lets the java.sql getTime accessor read the same OUT parameter too.
      assertNotNull(cs.getTime(1, new GregorianCalendar(ZONE)));
    }
  }

  @Test
  public void timestamptzAcceptsTimestampWithTimezoneRegistration() throws Exception {
    assumeCallableStatementsSupported();
    try (CallableStatement cs = con.prepareCall("{ ? = call f13_tstz() }")) {
      cs.registerOutParameter(1, Types.TIMESTAMP_WITH_TIMEZONE);
      cs.execute();
      assertEquals(Instant.parse("2020-01-02T09:34:56.789Z"),
          cs.getObject(1, OffsetDateTime.class).toInstant());
      Timestamp viaCal = cs.getTimestamp(1, new GregorianCalendar(ZONE));
      assertEquals(Instant.parse("2020-01-02T09:34:56.789Z"), viaCal.toInstant());
    }
  }

  @Test
  public void mismatchedTemporalRegistrationIsRejected() throws Exception {
    assumeCallableStatementsSupported();
    try (CallableStatement cs = con.prepareCall("{ ? = call f13_tstz() }")) {
      // DATE is not the TIMESTAMP/TIMESTAMP_WITH_TIMEZONE pair, so the column/registration mismatch
      // must still be reported at execution time.
      cs.registerOutParameter(1, Types.DATE);
      SQLException ex = assertThrows(SQLException.class, cs::execute);
      assertEquals(PSQLState.DATA_TYPE_MISMATCH.getState(), ex.getSQLState());
    }
  }

  @Test
  public void reExecuteRefreshesTheSnapshot() throws Exception {
    assumeCallableStatementsSupported();
    // Read through getObject(Class), which decodes from the snapshot (not callResult), so a stale
    // snapshot would surface as an unchanged value. nextval advances one second per execution.
    try (CallableStatement cs = con.prepareCall("{ ? = call f13_next() }")) {
      cs.registerOutParameter(1, Types.TIMESTAMP);
      cs.execute();
      OffsetDateTime first = cs.getObject(1, OffsetDateTime.class);
      cs.execute();
      OffsetDateTime second = cs.getObject(1, OffsetDateTime.class);
      assertEquals(first.toInstant().plusSeconds(1), second.toInstant(),
          "re-execute must decode the new row, not the stale snapshot");
    }
  }

  @Test
  public void multipleOutParametersMapToTheRightColumn() throws Exception {
    assumeCallableStatementsSupported();
    try (CallableStatement cs = con.prepareCall("{ call f13_multi(?, ?, ?) }")) {
      cs.setInt(1, 0);
      cs.registerOutParameter(2, Types.TIMESTAMP);
      cs.registerOutParameter(3, Types.TIMESTAMP_WITH_TIMEZONE);
      cs.execute();
      // Each OUT getter must read its own column, not column 1.
      Timestamp ts = cs.getTimestamp(2, new GregorianCalendar(ZONE));
      OffsetDateTime tz = cs.getObject(3, OffsetDateTime.class);
      assertEquals(Instant.parse("2020-01-02T07:34:56.789Z"), ts.toInstant());
      assertEquals(Instant.parse("2021-06-06T22:02:03Z"), tz.toInstant());
    }
  }

  @Test
  public void sqlDataOutParameterDecodesFromWireInTextAndBinary() throws Exception {
    assumeCallableStatementsSupported();
    // Text transfer, both SQLData entry points.
    assertPointDecodes(con, "f13_point_fn", false);
    assertPointDecodes(con, "f13_point_fn", true);
    try (Connection binaryCon = openBinaryConnection()) {
      // Confirm this connection actually transfers the composite in binary, so the assertions below
      // exercise the binary decode path rather than silently falling back to text. A composite
      // upgrades to binary only once the driver has seen the type, i.e. from the second execution.
      assertEquals(Field.BINARY_FORMAT, compositeTransferFormat(binaryCon),
          "f13_point must transfer in binary on this connection");
      assertPointDecodesOverExecutions(binaryCon, "f13_point_fn", false);
      assertPointDecodesOverExecutions(binaryCon, "f13_point_fn", true);
    }
  }

  @Test
  public void registeredTypeNameDecodesAnonymousRecordInTextAndBinary() throws Exception {
    assumeCallableStatementsSupported();
    // f13_anon reports column type `record` (Types.OTHER); registerOutParameter(..., STRUCT,
    // "f13_point") supplies the concrete type the SQLData decode must use.
    assertPointDecodes(con, "f13_anon", false);
    try (Connection binaryCon = openBinaryConnection()) {
      assertPointDecodesOverExecutions(binaryCon, "f13_anon", false);
    }
  }

  @Test
  public void nullCompositeReportsWasNull() throws Exception {
    assumeCallableStatementsSupported();
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("f13_point", Point3x4.class);
    try (CallableStatement cs = con.prepareCall("{ ? = call f13_null_point() }")) {
      cs.registerOutParameter(1, Types.STRUCT, "f13_point");
      cs.execute();
      assertNull(cs.getObject(1, Point3x4.class));
      assertTrue(cs.wasNull(), "wasNull after getObject(int, Class)");
      assertNull(cs.getObject(1, typeMap));
      assertTrue(cs.wasNull(), "wasNull after getObject(int, Map)");
    }
  }

  private void assertPointDecodes(Connection c, String fn, boolean viaMap) throws SQLException {
    try (CallableStatement cs = c.prepareCall("{ ? = call " + fn + "() }")) {
      cs.registerOutParameter(1, Types.STRUCT, "f13_point");
      cs.execute();
      assertPoint(readPoint(cs, viaMap));
    }
  }

  private void assertPointDecodesOverExecutions(Connection c, String fn, boolean viaMap)
      throws SQLException {
    try (CallableStatement cs = c.prepareCall("{ ? = call " + fn + "() }")) {
      cs.registerOutParameter(1, Types.STRUCT, "f13_point");
      // Execute twice: the composite OUT upgrades to binary transfer on the second execution.
      for (int i = 0; i < 2; i++) {
        cs.execute();
        assertPoint(readPoint(cs, viaMap));
      }
    }
  }

  private static Point3x4 readPoint(CallableStatement cs, boolean viaMap) throws SQLException {
    if (viaMap) {
      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put("f13_point", Point3x4.class);
      return (Point3x4) cs.getObject(1, typeMap);
    }
    return cs.getObject(1, Point3x4.class);
  }

  private static void assertPoint(Point3x4 point) {
    assertEquals(3, point.x);
    assertEquals(4, point.y);
  }

  private Connection openBinaryConnection() throws SQLException {
    // Enable binary receive only for the composite OID and RECORD (2249), not "*": a blanket "*"
    // makes the type-cache catalog query itself request binary and recurse into type loading. With
    // just these OIDs enabled the catalog queries stay text. prepareThreshold=-1 server-prepares.
    int pointOid;
    try (PreparedStatement ps = con.prepareStatement("SELECT 'f13_point'::regtype::oid");
         ResultSet rs = ps.executeQuery()) {
      rs.next();
      pointOid = rs.getInt(1);
    }
    Properties props = new Properties();
    props.setProperty("binaryTransferEnable", pointOid + "," + Oid.RECORD);
    props.setProperty("prepareThreshold", "-1");
    return TestUtil.openDB(props);
  }

  /** The transfer format the connection settles on for the {@code f13_point} composite. */
  private static int compositeTransferFormat(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT ROW(3, 4)::f13_point")) {
      int format = Field.TEXT_FORMAT;
      // A composite upgrades to binary on the second execution once the driver has seen the type.
      for (int i = 0; i < 2; i++) {
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          format = ((PGResultSetMetaData) rs.getMetaData()).getFormat(1);
        }
      }
      return format;
    }
  }

  @Test
  public void getterAfterCloseIsRejected() throws Exception {
    assumeCallableStatementsSupported();
    CallableStatement cs = con.prepareCall("{ ? = call f13_tstz() }");
    cs.registerOutParameter(1, Types.TIMESTAMP);
    cs.execute();
    cs.close();
    assertTrue(cs.isClosed());
    assertThrows(SQLException.class, () -> cs.getTimestamp(1, new GregorianCalendar(ZONE)));
  }

  /** Minimal {@link SQLData} mapping for the {@code f13_point} composite. */
  public static final class Point3x4 implements SQLData {
    int x;
    int y;

    @Override
    public String getSQLTypeName() {
      return "f13_point";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      x = stream.readInt();
      y = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(x);
      stream.writeInt(y);
    }
  }
}
