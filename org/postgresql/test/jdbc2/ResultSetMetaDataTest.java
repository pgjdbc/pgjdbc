/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc2/ResultSetMetaDataTest.java,v 1.10 2005/01/11 08:25:48 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import org.postgresql.PGResultSetMetaData;
import org.postgresql.test.TestUtil;
import junit.framework.TestCase;
import java.sql.*;

public class ResultSetMetaDataTest extends TestCase
{

    private Connection conn;

    public ResultSetMetaDataTest(String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        conn = TestUtil.openDB();
        TestUtil.createTable(conn, "rsmd1", "a int primary key, b text, c decimal(10,2)", true);
        TestUtil.createTable(conn, "timetest", "tm time(3), tmtz timetz, ts timestamp without time zone, tstz timestamp(6) with time zone");

        TestUtil.dropSequence( conn, "serialtest_a_seq");
        TestUtil.dropSequence( conn, "serialtest_b_seq");
        TestUtil.createTable(conn, "serialtest", "a serial, b bigserial, c int");
    }

    protected void tearDown() throws Exception
    {
        TestUtil.dropTable(conn, "rsmd1");
        TestUtil.dropTable(conn, "timetest");
        TestUtil.dropTable(conn, "serialtest");
        TestUtil.dropSequence( conn, "serialtest_a_seq");
        TestUtil.dropSequence( conn, "serialtest_b_seq");
        TestUtil.closeDB(conn);
    }

    public void testStandardResultSet() throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT a,b,c,a+c as total,oid,b as d FROM rsmd1");
        runStandardTests(rs.getMetaData());
        rs.close();
        stmt.close();
    }

    public void testPreparedResultSet() throws SQLException {
        if (!TestUtil.haveMinimumServerVersion(conn, "7.4"))
            return;

        PreparedStatement pstmt = conn.prepareStatement("SELECT a,b,c,a+c as total,oid,b as d FROM rsmd1 WHERE b = ?");
        runStandardTests(pstmt.getMetaData());
        pstmt.close();
    }

    private void runStandardTests(ResultSetMetaData rsmd) throws SQLException {
        PGResultSetMetaData pgrsmd = (PGResultSetMetaData)rsmd;

        assertEquals(6, rsmd.getColumnCount());

        assertEquals("a", rsmd.getColumnLabel(1));
        assertEquals("total", rsmd.getColumnLabel(4));

        assertEquals("a", rsmd.getColumnName(1));
        assertEquals("oid", rsmd.getColumnName(5));
        if (TestUtil.haveMinimumServerVersion(conn, "7.4"))
        {
            assertEquals("", pgrsmd.getBaseColumnName(4));
            assertEquals("b", pgrsmd.getBaseColumnName(6));
        }

        assertEquals(Types.INTEGER, rsmd.getColumnType(1));
        assertEquals(Types.VARCHAR, rsmd.getColumnType(2));

        assertEquals("int4", rsmd.getColumnTypeName(1));
        assertEquals("text", rsmd.getColumnTypeName(2));

        assertEquals(10, rsmd.getPrecision(3));

        assertEquals(2, rsmd.getScale(3));

        assertEquals("", rsmd.getSchemaName(1));
        assertEquals("", rsmd.getSchemaName(4));
        if (TestUtil.haveMinimumServerVersion(conn, "7.4"))
        {
            assertEquals("public", pgrsmd.getBaseSchemaName(1));
            assertEquals("", pgrsmd.getBaseSchemaName(4));
        }

        assertEquals("", rsmd.getTableName(1));
        assertEquals("", rsmd.getTableName(4));
        if (TestUtil.haveMinimumServerVersion(conn, "7.4"))
        {
            assertEquals("rsmd1", pgrsmd.getBaseTableName(1));
            assertEquals("", pgrsmd.getBaseTableName(4));
        }

        if (TestUtil.haveMinimumServerVersion(conn, "7.4"))
        {
            assertEquals(ResultSetMetaData.columnNoNulls, rsmd.isNullable(1));
            assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(2));
            assertEquals(ResultSetMetaData.columnNullableUnknown, rsmd.isNullable(4));
        }
        else
        {
            assertEquals(ResultSetMetaData.columnNullableUnknown, rsmd.isNullable(1));
        }
    }

    // verify that a prepared update statement returns no metadata and doesn't execute.
    public void testPreparedUpdate() throws SQLException {
        PreparedStatement pstmt = conn.prepareStatement("INSERT INTO rsmd1(a,b) VALUES(?,?)");
        pstmt.setInt(1,1);
        pstmt.setString(2,"hello");
        ResultSetMetaData rsmd = pstmt.getMetaData();
        assertNull(rsmd);
        pstmt.close();

        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM rsmd1");
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1));
        rs.close();
        stmt.close();
    }

    

    public void testDatabaseMetaDataNames() throws SQLException {
        DatabaseMetaData databaseMetaData = conn.getMetaData();
        ResultSet resultSet = databaseMetaData.getTableTypes();
        ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
        assertEquals(1, resultSetMetaData.getColumnCount());
        assertEquals("TABLE_TYPE", resultSetMetaData.getColumnName(1));
        resultSet.close();
    }

    public void testTimestampInfo() throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT tm, tmtz, ts, tstz FROM timetest");
        ResultSetMetaData rsmd = rs.getMetaData();

        // For reference:
        // TestUtil.createTable(conn, "timetest", "tm time(3), tmtz timetz, ts timestamp without time zone, tstz timestamp(6) with time zone");

        assertEquals(3, rsmd.getScale(1));
        assertEquals(6, rsmd.getScale(2));
        assertEquals(6, rsmd.getScale(3));
        assertEquals(6, rsmd.getScale(4));

        assertEquals(13, rsmd.getColumnDisplaySize(1));
        assertEquals(21, rsmd.getColumnDisplaySize(2));
        assertEquals(26, rsmd.getColumnDisplaySize(3));
        assertEquals(32, rsmd.getColumnDisplaySize(4));

        rs.close();
        stmt.close();
    }

    public void testIsAutoIncrement() throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT c,b,a FROM serialtest");
        ResultSetMetaData rsmd = rs.getMetaData();

        assertTrue(!rsmd.isAutoIncrement(1));
        if (TestUtil.haveMinimumServerVersion(conn, "7.4"))
        {
            assertTrue(rsmd.isAutoIncrement(2));
            assertTrue(rsmd.isAutoIncrement(3));
        }

        rs.close();
        stmt.close();
    }

}
