/*-------------------------------------------------------------------------
*
* Copyright (c) 2008-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc3;

import java.sql.*;

import junit.framework.TestCase;

import legacy.org.postgresql.TestUtil;

public class GeneratedKeysTest extends TestCase {

    private Connection _conn;

    public GeneratedKeysTest(String name) throws Exception {
        super(name);
    }

    protected void setUp() throws Exception {
        _conn = TestUtil.openDB();
        Statement stmt = _conn.createStatement();
        stmt.execute("CREATE TEMP TABLE genkeys(a serial, b text, c int)");
        stmt.close();
    }

    protected void tearDown() throws SQLException {
        Statement stmt = _conn.createStatement();
        stmt.execute("DROP TABLE genkeys");
        stmt.close();
        TestUtil.closeDB(_conn);
    }

    public void testExecuteUpdateAllColumns() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        int count = stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)", Statement.RETURN_GENERATED_KEYS);
        assertEquals(1, count);
        ResultSet rs = stmt.getGeneratedKeys();
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertEquals("a", rs.getString(2));
        assertEquals(2, rs.getInt(3));
        assertEquals(1, rs.getInt("a"));
        assertEquals("a", rs.getString("b"));
        assertEquals(2, rs.getInt("c"));
        assertTrue(!rs.next());
    }

    public void testStatementUpdateCount() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)", Statement.RETURN_GENERATED_KEYS);
        assertEquals(1, stmt.getUpdateCount());
        assertNull(stmt.getResultSet());
        assertTrue(!stmt.getMoreResults());
    }

    public void testCloseStatementClosesRS() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)", Statement.RETURN_GENERATED_KEYS);
        ResultSet rs = stmt.getGeneratedKeys();
        stmt.close();
        try {
            rs.next();
            fail("Can't operate on a closed result set.");
        } catch (SQLException sqle) {}
    }

    public void testReturningWithTrailingSemicolon() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2); ", Statement.RETURN_GENERATED_KEYS);
        ResultSet rs = stmt.getGeneratedKeys();
        assertTrue(rs.next());
        assertEquals("a", rs.getString(2));
        assertTrue(!rs.next());
    }

    public void testEmptyRSWithoutReturning() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        int count = stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2); ", Statement.NO_GENERATED_KEYS);
        assertEquals(1, count);
        ResultSet rs = stmt.getGeneratedKeys();
        assertTrue(!rs.next());
    }

    public void testColumnsByName() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        int count = stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2); ", new String[]{"c","a"});
        assertEquals(1, count);
        ResultSet rs = stmt.getGeneratedKeys();
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        assertEquals(1, rs.getInt(2));
        assertEquals(1, rs.getInt("a"));
        assertEquals(2, rs.getInt("c"));
        assertTrue(!rs.next());
    }

    public void testMultipleRows() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        int count = stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2), (3, 'b', 4); ", new String[]{"c"});
        assertEquals(2, count);
        ResultSet rs = stmt.getGeneratedKeys();
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        assertTrue(rs.next());
        assertEquals(4, rs.getInt(1));
        assertTrue(!rs.next());
    }

    public void testSerialWorks() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        int count = stmt.executeUpdate("INSERT INTO genkeys (b,c) VALUES ('a', 2), ('b', 4); ", new String[]{"a"});
        assertEquals(2, count);
        ResultSet rs = stmt.getGeneratedKeys();
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        assertTrue(!rs.next());
    }

    public void testUpdate() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)");
        stmt.executeUpdate("INSERT INTO genkeys VALUES (2, 'b', 4)");
        stmt.executeUpdate("UPDATE genkeys SET c=3 WHERE a = 1", new String[]{"c", "b"});
        ResultSet rs = stmt.getGeneratedKeys();
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
        assertEquals("a", rs.getString(2));
        assertTrue(!rs.next());
    }

    public void testDelete() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)");
        stmt.executeUpdate("INSERT INTO genkeys VALUES (2, 'b', 4)");
        stmt.executeUpdate("DELETE FROM genkeys WHERE a = 1", new String[]{"c", "b"});
        ResultSet rs = stmt.getGeneratedKeys();
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        assertEquals("a", rs.getString(2));
        assertTrue(!rs.next());
    }

    public void testPSUpdate() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)");
        stmt.executeUpdate("INSERT INTO genkeys VALUES (2, 'b', 4)");
        stmt.close();

        PreparedStatement ps = _conn.prepareStatement("UPDATE genkeys SET c=? WHERE a = ?", new String[]{"c", "b"});
        ps.setInt(1,3);
        ps.setInt(2,1);
        assertEquals(1, ps.executeUpdate());
        ResultSet rs = ps.getGeneratedKeys();
        assertTrue(rs.next());
        assertEquals(3, rs.getInt(1));
        assertEquals("a", rs.getString(2));
        assertTrue(!rs.next());
    }

    public void testPSDelete() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)");
        stmt.executeUpdate("INSERT INTO genkeys VALUES (2, 'b', 4)");
        stmt.close();

        PreparedStatement ps = _conn.prepareStatement("DELETE FROM genkeys WHERE a = ?", new String[]{"c", "b"});

        ps.setInt(1,1);
        assertEquals(1, ps.executeUpdate());
        ResultSet rs = ps.getGeneratedKeys();
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
        assertEquals("a", rs.getString(2));
        assertTrue(!rs.next());

        ps.setInt(1,2);
        assertEquals(1, ps.executeUpdate());
        rs = ps.getGeneratedKeys();
        assertTrue(rs.next());
        assertEquals(4, rs.getInt(1));
        assertEquals("b", rs.getString(2));
        assertTrue(!rs.next());
    }

    public void testGeneratedKeysCleared() throws SQLException {
        Statement stmt = _conn.createStatement();
        stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2); ", Statement.RETURN_GENERATED_KEYS);
        ResultSet rs = stmt.getGeneratedKeys();
        assertTrue(rs.next());
        stmt.executeUpdate("INSERT INTO genkeys VALUES (2, 'b', 3)");
        rs = stmt.getGeneratedKeys();
        assertTrue(!rs.next());
    }

    public void testBatchGeneratedKeys() throws SQLException {
        PreparedStatement ps = _conn.prepareStatement("INSERT INTO genkeys(c) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
        ps.setInt(1,4);
        ps.addBatch();
        ps.setInt(1,7);
        ps.addBatch();
        ps.executeBatch();
        ResultSet rs = ps.getGeneratedKeys();
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("a"));
        assertTrue(rs.next());
        assertEquals(2, rs.getInt("a"));
        assertTrue(!rs.next());
    }

}

