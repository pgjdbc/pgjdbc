/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/*
 * Test case for geometric type I/O
 */
@RunWith(Parameterized.class)
public class GeometricTest extends BaseTest4 {

  public GeometricTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "testgeometric",
        "boxval box, circleval circle, lsegval lseg, pathval path, polygonval polygon, pointval point, lineval line");
  }

  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testgeometric");
    super.tearDown();
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
    assertEquals("PGObject#equals(rs.getObject)", obj, rs.getObject(1));
    PGobject obj2 = (PGobject) obj.clone();
    obj2.setValue(rs.getString(1));
    assertEquals("PGobject.toString vs rs.getString", obj, obj2);
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
      List<PGline> linesToTest = new ArrayList<PGline>();
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

}
