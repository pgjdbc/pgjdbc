/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PGobject;
import org.postgresql.geometric.*;
import junit.framework.TestCase;
import java.sql.*;

/*
 * Test case for geometric type I/O
 */
public class GeometricTest extends TestCase
{
    private Connection con;

    public GeometricTest(String name)
    {
        super(name);
    }

    // Set up the fixture for this testcase: a connection to a database with
    // a table for this test.
    protected void setUp() throws Exception
    {
        con = TestUtil.openDB();
        TestUtil.createTable(con,
                             "testgeometric",
                             "boxval box, circleval circle, lsegval lseg, pathval path, polygonval polygon, pointval point");
    }

    // Tear down the fixture for this test case.
    protected void tearDown() throws Exception
    {
        TestUtil.dropTable(con, "testgeometric");
        TestUtil.closeDB(con);
    }

    private void checkReadWrite(PGobject obj, String column) throws Exception {
        PreparedStatement insert = con.prepareStatement("INSERT INTO testgeometric(" + column + ") VALUES (?)");
        insert.setObject(1, obj);
        insert.executeUpdate();
        insert.close();

        Statement stmt = con.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT " + column + " FROM testgeometric");
        assertTrue(rs.next());
        assertEquals(obj, rs.getObject(1));
        rs.close();

        stmt.executeUpdate("DELETE FROM testgeometric");
        stmt.close();
    }

    public void testPGbox() throws Exception {
        checkReadWrite(new PGbox(1.0, 2.0, 3.0, 4.0), "boxval");
        checkReadWrite(new PGbox( -1.0, 2.0, 3.0, 4.0), "boxval");
        checkReadWrite(new PGbox(1.0, -2.0, 3.0, 4.0), "boxval");
        checkReadWrite(new PGbox(1.0, 2.0, -3.0, 4.0), "boxval");
        checkReadWrite(new PGbox(1.0, 2.0, 3.0, -4.0), "boxval");
    }

    public void testPGcircle() throws Exception {
        checkReadWrite(new PGcircle(1.0, 2.0, 3.0), "circleval");
        checkReadWrite(new PGcircle( -1.0, 2.0, 3.0), "circleval");
        checkReadWrite(new PGcircle(1.0, -2.0, 3.0), "circleval");
    }

    public void testPGlseg() throws Exception {
        checkReadWrite(new PGlseg(1.0, 2.0, 3.0, 4.0), "lsegval");
        checkReadWrite(new PGlseg( -1.0, 2.0, 3.0, 4.0), "lsegval");
        checkReadWrite(new PGlseg(1.0, -2.0, 3.0, 4.0), "lsegval");
        checkReadWrite(new PGlseg(1.0, 2.0, -3.0, 4.0), "lsegval");
        checkReadWrite(new PGlseg(1.0, 2.0, 3.0, -4.0), "lsegval");
    }

    public void testPGpath() throws Exception {
        PGpoint[] points = new PGpoint[] {
                               new PGpoint(0.0, 0.0),
                               new PGpoint(0.0, 5.0),
                               new PGpoint(5.0, 5.0),
                               new PGpoint(5.0, -5.0),
                               new PGpoint( -5.0, -5.0),
                               new PGpoint( -5.0, 5.0),
                           };

        checkReadWrite(new PGpath(points, true), "pathval");
        checkReadWrite(new PGpath(points, false), "pathval");
    }

    public void testPGpolygon() throws Exception {
        PGpoint[] points = new PGpoint[] {
                               new PGpoint(0.0, 0.0),
                               new PGpoint(0.0, 5.0),
                               new PGpoint(5.0, 5.0),
                               new PGpoint(5.0, -5.0),
                               new PGpoint( -5.0, -5.0),
                               new PGpoint( -5.0, 5.0),
                           };

        checkReadWrite(new PGpolygon(points), "polygonval");
    }

    public void testPGpoint() throws Exception {
        checkReadWrite(new PGpoint(1.0, 2.0), "pointval");
    }
}
