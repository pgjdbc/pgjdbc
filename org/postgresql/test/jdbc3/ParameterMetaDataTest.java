/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc3;

import java.sql.*;
import junit.framework.TestCase;
import org.postgresql.test.TestUtil;

public class ParameterMetaDataTest extends TestCase {

    private Connection _conn;

    public ParameterMetaDataTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        _conn = TestUtil.openDB();
        TestUtil.createTable(_conn, "parametertest", "a int4, b float8, c text, d point, e timestamp with time zone");
    }

    protected void tearDown() throws SQLException {
        TestUtil.dropTable(_conn, "parametertest");
        TestUtil.closeDB(_conn);
    }

    public void testParameterMD() throws SQLException {
        if (!TestUtil.isProtocolVersion(_conn, 3))
            return;

        PreparedStatement pstmt = _conn.prepareStatement("SELECT a FROM parametertest WHERE b = ? AND c = ? AND d >^ ? ");
        ParameterMetaData pmd = pstmt.getParameterMetaData();

        assertEquals(3, pmd.getParameterCount());
        assertEquals(Types.DOUBLE, pmd.getParameterType(1));
        assertEquals("float8", pmd.getParameterTypeName(1));
        assertEquals("java.lang.Double", pmd.getParameterClassName(1));
        assertEquals(Types.VARCHAR, pmd.getParameterType(2));
        assertEquals("text", pmd.getParameterTypeName(2));
        assertEquals("java.lang.String", pmd.getParameterClassName(2));
        assertEquals(Types.OTHER, pmd.getParameterType(3));
        assertEquals("point", pmd.getParameterTypeName(3));
        assertEquals("org.postgresql.geometric.PGpoint", pmd.getParameterClassName(3));

        pstmt.close();
    }

    public void testFailsOnBadIndex() throws SQLException {
        if (!TestUtil.isProtocolVersion(_conn, 3))
            return;

        PreparedStatement pstmt = _conn.prepareStatement("SELECT a FROM parametertest WHERE b = ? AND c = ?");
        ParameterMetaData pmd = pstmt.getParameterMetaData();
        try {
            pmd.getParameterType(0);
            fail("Can't get parameter for index < 1.");
        } catch (SQLException sqle) { }
        try {
            pmd.getParameterType(3);
            fail("Can't get parameter for index 3 with only two parameters.");
        } catch (SQLException sqle) { }
    }

    // Make sure we work when mashing two queries into a single statement.
    public void testMultiStatement() throws SQLException {
        if (!TestUtil.isProtocolVersion(_conn, 3))
            return;

        PreparedStatement pstmt = _conn.prepareStatement("SELECT a FROM parametertest WHERE b = ? AND c = ? ; SELECT b FROM parametertest WHERE a = ?");
        ParameterMetaData pmd = pstmt.getParameterMetaData();

        assertEquals(3, pmd.getParameterCount());
        assertEquals(Types.DOUBLE, pmd.getParameterType(1));
        assertEquals("float8", pmd.getParameterTypeName(1));
        assertEquals(Types.VARCHAR, pmd.getParameterType(2));
        assertEquals("text", pmd.getParameterTypeName(2));
        assertEquals(Types.INTEGER, pmd.getParameterType(3));
        assertEquals("int4", pmd.getParameterTypeName(3));

        pstmt.close();

    }

    // Here we test that we can legally change the resolved type
    // from text to varchar with the complicating factor that there
    // is also an unknown parameter.
    // 
    public void testTypeChangeWithUnknown() throws SQLException {
        if (!TestUtil.isProtocolVersion(_conn, 3))
            return;

        PreparedStatement pstmt = _conn.prepareStatement("SELECT a FROM parametertest WHERE c = ? AND e = ?");
        ParameterMetaData pmd = pstmt.getParameterMetaData();

        pstmt.setString(1, "Hi");
        pstmt.setTimestamp(2, new Timestamp(0L));

        ResultSet rs = pstmt.executeQuery();
        rs.close();
    }

}
