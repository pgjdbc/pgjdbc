/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.core.ServerVersion;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGcircle;
import org.postgresql.geometric.PGline;
import org.postgresql.geometric.PGlseg;
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Test case for geometric type I/O
 */
@ParameterizedClass
@MethodSource("data")
public class GeometricTest extends BaseTest4 {

  public GeometricTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @BeforeAll
  static void createTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "testgeometric",
          "boxval box, circleval circle, lsegval lseg, pathval path, polygonval polygon, pointval point, lineval line");
    }
  }

  @AfterAll
  static void dropTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "testgeometric");
    }
  }

  public void setUp() throws Exception {
    super.setUp();
    TestUtil.execute(con, "TRUNCATE testgeometric");
  }

  private void checkReadWrite(PGobject obj, String column) throws Exception {
    PreparedStatement insert =
        con.prepareStatement("INSERT INTO testgeometric(" + column + ") VALUES (?)");
    insert.setObject(1, obj);
    insert.executeUpdate();
    insert.close();

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT " + column + " FROM testgeometric");
    assertTrue(rs.next());
    assertEquals(obj, rs.getObject(1), "PGObject#equals(rs.getObject)");
    PGobject obj2 = (PGobject) obj.clone();
    obj2.setValue(rs.getString(1));
    assertEquals(obj, obj2, "PGobject.toString vs rs.getString");
    rs.close();

    stmt.executeUpdate("DELETE FROM testgeometric");
    stmt.close();
  }

  @Test
  public void testPGbox() throws Exception {
    checkReadWrite(new PGbox(1.0, 2.0, 3.0, 4.0), "boxval");
    checkReadWrite(new PGbox(-1.0, 2.0, 3.0, 4.0), "boxval");
    checkReadWrite(new PGbox(1.0, -2.0, 3.0, 4.0), "boxval");
    checkReadWrite(new PGbox(1.0, 2.0, -3.0, 4.0), "boxval");
    checkReadWrite(new PGbox(1.0, 2.0, 3.0, -4.0), "boxval");
  }

  @Test
  public void testPGcircle() throws Exception {
    checkReadWrite(new PGcircle(1.0, 2.0, 3.0), "circleval");
    checkReadWrite(new PGcircle(-1.0, 2.0, 3.0), "circleval");
    checkReadWrite(new PGcircle(1.0, -2.0, 3.0), "circleval");
  }

  @Test
  public void testPGlseg() throws Exception {
    checkReadWrite(new PGlseg(1.0, 2.0, 3.0, 4.0), "lsegval");
    checkReadWrite(new PGlseg(-1.0, 2.0, 3.0, 4.0), "lsegval");
    checkReadWrite(new PGlseg(1.0, -2.0, 3.0, 4.0), "lsegval");
    checkReadWrite(new PGlseg(1.0, 2.0, -3.0, 4.0), "lsegval");
    checkReadWrite(new PGlseg(1.0, 2.0, 3.0, -4.0), "lsegval");
  }

  @Test
  public void testPGpath() throws Exception {
    PGpoint[] points =
        new PGpoint[]{new PGpoint(0.0, 0.0), new PGpoint(0.0, 5.0), new PGpoint(5.0, 5.0),
            new PGpoint(5.0, -5.0), new PGpoint(-5.0, -5.0), new PGpoint(-5.0, 5.0),};

    checkReadWrite(new PGpath(points, true), "pathval");
    checkReadWrite(new PGpath(points, false), "pathval");
  }

  @Test
  public void testPGpolygon() throws Exception {
    PGpoint[] points =
        new PGpoint[]{new PGpoint(0.0, 0.0), new PGpoint(0.0, 5.0), new PGpoint(5.0, 5.0),
            new PGpoint(5.0, -5.0), new PGpoint(-5.0, -5.0), new PGpoint(-5.0, 5.0),};

    checkReadWrite(new PGpolygon(points), "polygonval");
  }

  @Test
  public void testPGline() throws Exception {
    final String columnName = "lineval";

    // PostgreSQL versions older than 9.4 support creating columns with the LINE datatype, but
    // not actually writing to those columns. Only try to write if the version if at least 9.4
    final boolean roundTripToDatabase = TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_4);

    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_4)) {

      // Apparently the driver requires public no-args constructor, and postgresql doesn't accept
      // lines with A and B
      // coefficients both being zero... so assert a no-arg instantiated instance throws an
      // exception.
      if (roundTripToDatabase) {
        try {
          checkReadWrite(new PGline(), columnName);
          fail("Expected a PSQLException to be thrown");
        } catch (PSQLException e) {
          assertEquals("22P02", e.getSQLState());
        }
      }

      // Generate a dataset for testing.
      List<PGline> linesToTest = new ArrayList<>();
      for (double i = 1; i <= 3; i += 0.25) {
        // Test the 3-arg constructor (coefficients+constant)
        linesToTest.add(new PGline(i, (0 - i), (1 / i)));
        linesToTest.add(new PGline("{" + i + "," + (0 - i) + "," + (1 / i) + "}"));
        // Test the 4-arg constructor (x/y coords of two points on the line)
        linesToTest.add(new PGline(i, (0 - i), (1 / i), (1 / i / i)));
        linesToTest.add(new PGline(i, (0 - i), i, (1 / i / i))); // tests vertical line
        // Test 2-arg constructor (2 PGpoints on the line);
        linesToTest.add(new PGline(new PGpoint(i, (0 - i)), new PGpoint((1 / i), (1 / i / i))));
        // tests vertical line
        linesToTest.add(new PGline(new PGpoint(i, (0 - i)), new PGpoint(i, (1 / i / i))));
        // Test 1-arg constructor (PGlseg on the line);
        linesToTest.add(new PGline(new PGlseg(i, (0 - i), (1 / i), (1 / i / i))));
        linesToTest.add(new PGline(new PGlseg(i, (0 - i), i, (1 / i / i))));
        linesToTest.add(
            new PGline(new PGlseg(new PGpoint(i, (0 - i)), new PGpoint((1 / i), (1 / i / i)))));
        linesToTest
            .add(new PGline(new PGlseg(new PGpoint(i, (0 - i)), new PGpoint(i, (1 / i / i)))));
      }

      // Include persistence an querying if the postgresql version supports it.
      if (roundTripToDatabase) {
        for (PGline testLine : linesToTest) {
          checkReadWrite(testLine, columnName);
        }
      }
    }
  }

  @Test
  public void testPGpoint() throws Exception {
    checkReadWrite(new PGpoint(1.0, 2.0), "pointval");
  }

  /**
   * getString must be wire-format-independent: a geometric value read in binary transfer must render
   * whole-number coordinates the same way the server's own text form does ({@code 1}, not Java's
   * {@code 1.0}). The binary codec once formatted coordinates through {@code Double.toString} and drifted
   * from the text rendering; this reads each value's getString alongside the server's {@code ::text} of
   * the same value and asserts they match, in both text and binary mode. point and box are out of scope
   * (they still render {@code 1.0} in binary, matching the released driver).
   */
  @Test
  public void testGetStringMatchesServerText() throws Exception {
    List<String[]> cases = new ArrayList<>();
    cases.add(new String[]{"lseg", "[(0,0),(1,1)]"});
    cases.add(new String[]{"lseg", "[(-1.5,-2.5),(3,4)]"});
    cases.add(new String[]{"path", "[(0,0),(1,1),(2,0)]"});
    cases.add(new String[]{"path", "((0,0),(1,1),(2,0))"});
    cases.add(new String[]{"polygon", "((0,0),(1,0),(1,1),(0,1))"});
    cases.add(new String[]{"circle", "<(0,0),1>"});
    cases.add(new String[]{"circle", "<(0,0),1e100>"});
    // Coordinates straddling float8out's fixed<->scientific switch (the leading digit's exponent
    // leaving [-4, 14]): 1e14 still renders fixed (100000000000000), 1e15 flips to scientific (1e+15),
    // and 1e-4/1e-5 do the same at the small end. These are where a Double.toString-based rendering
    // drifts from the server, so cross-check them against ::text over both wire formats.
    cases.add(new String[]{"circle", "<(0,0),1e14>"});
    cases.add(new String[]{"circle", "<(0,0),1e15>"});
    cases.add(new String[]{"circle", "<(0,0),0.0001>"});
    cases.add(new String[]{"circle", "<(0,0),1e-05>"});
    cases.add(new String[]{"lseg", "[(1e14,0.0001),(1e15,2)]"});
    cases.add(new String[]{"polygon", "((0.1,1e15),(9999999999999998,1e-05))"});
    cases.add(new String[]{"path", "[(1e-05,1e14),(2,1e15)]"});
    // line_out only exists from 9.4 on.
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_4)) {
      cases.add(new String[]{"line", "{1,-1,0}"});
      cases.add(new String[]{"line", "{1e100,1,0}"});
      cases.add(new String[]{"line", "{1e15,1e-05,1}"});
    }

    for (String[] c : cases) {
      String type = c[0];
      String literal = c[1];
      try (Statement stmt = con.createStatement();
          ResultSet rs = stmt.executeQuery(
              "SELECT '" + literal + "'::" + type + ", ('" + literal + "'::" + type + ")::text")) {
        assertTrue(rs.next());
        assertEquals(rs.getString(2), rs.getString(1),
            type + " '" + literal + "' getString should match the server text form");
      }
    }
  }

}
