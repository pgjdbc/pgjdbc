package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;

import junit.framework.TestCase;

/*
 * ResultSet tests.
 */
public class ResultSetTest extends TestCase
{
	private Connection con;

	public ResultSetTest(String name)
	{
		super(name);
	}

	protected void setUp() throws SQLException
	{
        con = TestUtil.openDB();
        Statement stmt = con.createStatement();

        TestUtil.createTable(con, "testrs", "id integer");

        stmt.executeUpdate("INSERT INTO testrs VALUES (1)");
        stmt.executeUpdate("INSERT INTO testrs VALUES (2)");
        stmt.executeUpdate("INSERT INTO testrs VALUES (3)");
        stmt.executeUpdate("INSERT INTO testrs VALUES (4)");
        stmt.executeUpdate("INSERT INTO testrs VALUES (6)");
        stmt.executeUpdate("INSERT INTO testrs VALUES (9)");

        TestUtil.createTable(con, "teststring", "a text");
        stmt.executeUpdate("INSERT INTO teststring VALUES ('12345')");

        TestUtil.createTable(con, "testint", "a int");
        stmt.executeUpdate("INSERT INTO testint VALUES (12345)");

        TestUtil.createTable(con, "testbool", "a boolean");

        TestUtil.createTable(con, "testbit", "a bit");

        TestUtil.createTable(con, "testboolstring", "a varchar(30)");

        stmt.executeUpdate("INSERT INTO testboolstring VALUES('true')");
        stmt.executeUpdate("INSERT INTO testboolstring VALUES('false')");
        stmt.executeUpdate("INSERT INTO testboolstring VALUES('t')");
        stmt.executeUpdate("INSERT INTO testboolstring VALUES('f')");
        stmt.executeUpdate("INSERT INTO testboolstring VALUES('1.0')");
        stmt.executeUpdate("INSERT INTO testboolstring VALUES('0.0')");
        stmt.executeUpdate("INSERT INTO testboolstring VALUES('TRUE')");
        stmt.executeUpdate(
            "INSERT INTO testboolstring VALUES('this is not true')");

        TestUtil.createTable(con, "testnumeric", "a numeric");
        stmt.executeUpdate("INSERT INTO testnumeric VALUES('1.0')");
        stmt.executeUpdate("INSERT INTO testnumeric VALUES('0.0')");
        stmt.executeUpdate("INSERT INTO testnumeric VALUES('-1.0')");
        stmt.executeUpdate("INSERT INTO testnumeric VALUES('1.2')");
        stmt.executeUpdate("INSERT INTO testnumeric VALUES('99999.2')");
        stmt.executeUpdate("INSERT INTO testnumeric VALUES('99999')");
        stmt.executeUpdate("INSERT INTO testnumeric VALUES('-2.5')");
        stmt.executeUpdate("INSERT INTO testnumeric VALUES('-99999.2')");
        stmt.executeUpdate("INSERT INTO testnumeric VALUES('-99999')");

        stmt.close();


	}

	protected void tearDown() throws SQLException
	{
		TestUtil.dropTable(con, "testrs");
		TestUtil.dropTable(con, "teststring");
		TestUtil.dropTable(con, "testint");
		TestUtil.dropTable(con, "testbool");
		TestUtil.dropTable(con, "testbit");
		TestUtil.dropTable(con, "testboolstring");
		TestUtil.dropTable(con, "testnumeric");
		TestUtil.closeDB(con);
	}

	public void testBackward() throws SQLException
	{
		Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
		ResultSet rs = stmt.executeQuery("SELECT * FROM testrs");
		rs.afterLast();
		assertTrue(rs.previous());
		rs.close();
		stmt.close();
	}

	public void testAbsolute() throws SQLException
	{
		Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
		ResultSet rs = stmt.executeQuery("SELECT * FROM testrs");

		assertTrue(!rs.absolute(0));
		assertEquals(0, rs.getRow());

		assertTrue(rs.absolute( -1));
		assertEquals(6, rs.getRow());

		assertTrue(rs.absolute(1));
		assertEquals(1, rs.getRow());

		assertTrue(!rs.absolute( -10));
		assertEquals(0, rs.getRow());
		assertTrue(rs.next());
		assertEquals(1, rs.getRow());

		assertTrue(!rs.absolute(10));
		assertEquals(0, rs.getRow());
		assertTrue(rs.previous());
		assertEquals(6, rs.getRow());

		stmt.close();
	}

	public void testEmptyResult() throws SQLException
	{
		Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
		ResultSet rs = stmt.executeQuery("SELECT * FROM testrs where id=100");
		rs.beforeFirst();
		rs.afterLast();
		assertTrue(!rs.first());
		assertTrue(!rs.last());
		assertTrue(!rs.next());
	}

	public void testMaxFieldSize() throws SQLException
	{
			Statement stmt = con.createStatement();
			stmt.setMaxFieldSize(2);

   			ResultSet rs = stmt.executeQuery("select * from testint");

   			//max should not apply to the following since per the spec
   			//it should apply only to binary and char/varchar columns
   			rs.next();
   			assertEquals(rs.getString(1),"12345");
   			assertEquals(new String(rs.getBytes(1)), "12345");

   			//max should apply to the following since the column is
   			//a varchar column
   			rs = stmt.executeQuery("select * from teststring");
   			rs.next();
   			assertEquals(rs.getString(1), "12");
   			assertEquals(new String(rs.getBytes(1)), "12");
	}

	public void booleanTests(boolean useServerPrepare) throws SQLException
        {
                java.sql.PreparedStatement pstmt = con.prepareStatement("insert into testbool values (?)");
                if (useServerPrepare)
			((org.postgresql.PGStatement)pstmt).setUseServerPrepare(true);

		pstmt.setObject(1, new Float(0), java.sql.Types.BIT);
                pstmt.executeUpdate();

                pstmt.setObject(1, new Float(1), java.sql.Types.BIT);
                pstmt.executeUpdate();

                pstmt.setObject(1, "False", java.sql.Types.BIT);
                pstmt.executeUpdate();

                pstmt.setObject(1, "True", java.sql.Types.BIT);
                pstmt.executeUpdate();

                ResultSet rs = con.createStatement().executeQuery("select * from testbool");
		for (int i = 0; i<2; i++)
                {
                	assertTrue(rs.next());
                        assertEquals(false, rs.getBoolean(1));
                        assertTrue(rs.next());
                        assertEquals(true, rs.getBoolean(1));
                }

                pstmt = con.prepareStatement("insert into testbit values (?)");

                pstmt.setObject(1, new Float(0), java.sql.Types.BIT);
                pstmt.executeUpdate();

                pstmt.setObject(1, new Float(1), java.sql.Types.BIT);
                pstmt.executeUpdate();

                pstmt.setObject(1, "false", java.sql.Types.BIT);
                pstmt.executeUpdate();

                pstmt.setObject(1, "true", java.sql.Types.BIT);
                pstmt.executeUpdate();

		rs = con.createStatement().executeQuery("select * from testbit");

                for (int i = 0;i<2; i++)
                {
		        assertTrue(rs.next());
                	assertEquals(false, rs.getBoolean(1));
                        assertTrue(rs.next());
                        assertEquals(true, rs.getBoolean(1));
                }

                rs = con.createStatement().executeQuery("select * from testboolstring");

                for (int i = 0;i<4; i++)
                {
                        assertTrue(rs.next());
                        assertEquals(true, rs.getBoolean(1));
                        assertTrue(rs.next());
                        assertEquals(false, rs.getBoolean(1));
               }
       }

       public void testBoolean() throws SQLException
       {
               booleanTests(true);
               booleanTests(false);
       }

       public void testgetByte() throws SQLException
       {
       		ResultSet rs = con.createStatement().executeQuery("select * from testnumeric");
		boolean thrown = false;

		assertTrue(rs.next());
		assertEquals(1,rs.getByte(1));

		assertTrue(rs.next());
		assertEquals(0,rs.getByte(1));

		assertTrue(rs.next());
		assertEquals(-1,rs.getByte(1));

		while (rs.next())
		{
			thrown = false;
			try
			{
				rs.getByte(1);
			}
			catch (Exception e)
			{
				thrown = true;
			}
			if (!thrown)
				fail("Exception expected.");
		}
	}

       public void testgetShort() throws SQLException
       {
       		ResultSet rs = con.createStatement().executeQuery("select * from testnumeric");
		boolean thrown = false;

		assertTrue(rs.next());
		assertEquals(1,rs.getShort(1));

		assertTrue(rs.next());
		assertEquals(0,rs.getShort(1));

		assertTrue(rs.next());
		assertEquals(-1,rs.getShort(1));

		while (rs.next())
		{
			thrown = false;
			try
			{
				rs.getShort(1);
			}
			catch (Exception e)
			{
				thrown = true;
			}
			if (!thrown)
				fail("Exception expected.");
		}
	}

	public void testParameters() throws SQLException
	{
		Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
		stmt.setFetchSize(100);
		stmt.setFetchDirection(ResultSet.FETCH_UNKNOWN);

		ResultSet rs = stmt.executeQuery("SELECT * FROM testrs");

		assertEquals(stmt.getResultSetConcurrency(), ResultSet.CONCUR_UPDATABLE);
		assertEquals(stmt.getResultSetType(), ResultSet.TYPE_SCROLL_SENSITIVE);
		assertEquals(stmt.getFetchSize(), 100);
		assertEquals(stmt.getFetchDirection(), ResultSet.FETCH_UNKNOWN);

		assertEquals(rs.getConcurrency(), ResultSet.CONCUR_UPDATABLE);
		assertEquals(rs.getType(), ResultSet.TYPE_SCROLL_SENSITIVE);
		assertEquals(rs.getFetchSize(), 100);
		assertEquals(rs.getFetchDirection(), ResultSet.FETCH_UNKNOWN);

		rs.close();
		stmt.close();
	}

	public void testZeroRowResultPositioning() throws SQLException
	{
		Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
		ResultSet rs = stmt.executeQuery("SELECT * FROM pg_database WHERE datname='nonexistantdatabase'");
		assertEquals(rs.previous(),false);
		assertEquals(rs.previous(),false);
		assertEquals(rs.next(),false);
		assertEquals(rs.next(),false);
		assertEquals(rs.next(),false);
		assertEquals(rs.next(),false);
		assertEquals(rs.next(),false);
		assertEquals(rs.previous(),false);
		assertEquals(rs.first(),false);
		assertEquals(rs.last(),false);
		assertEquals(rs.getRow(),0);
		assertEquals(rs.absolute(1),false);
		assertEquals(rs.relative(1),false);
		assertEquals(rs.isBeforeFirst(),false);
		assertEquals(rs.isAfterLast(),false);
		assertEquals(rs.isFirst(),false);
		assertEquals(rs.isLast(),false);
		rs.close();
		stmt.close();
	}

	public void testRowResultPositioning() throws SQLException
	{
		Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
		// Create a one row result set.
		ResultSet rs = stmt.executeQuery("SELECT * FROM pg_database WHERE datname='template1'");
		assertTrue(rs.isBeforeFirst());
		assertTrue(rs.next());
		assertTrue(rs.isFirst());
		assertTrue(rs.isLast());
		assertTrue(!rs.next());
		assertTrue(rs.isAfterLast());
		assertTrue(rs.previous());
		assertTrue(rs.absolute(1));
		rs.close();
		stmt.close();
	}

	public void testForwardOnlyExceptions() throws SQLException
	{
		// Test that illegal operations on a TYPE_FORWARD_ONLY resultset
		// correctly result in throwing an exception.
		Statement stmt = con.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
		ResultSet rs = stmt.executeQuery("SELECT * FROM testnumeric");

		try { rs.absolute(1);   fail("absolute() on a TYPE_FORWARD_ONLY resultset did not throw an exception"); } catch (SQLException e) {}
		try { rs.afterLast();   fail("afterLast() on a TYPE_FORWARD_ONLY resultset did not throw an exception on a TYPE_FORWARD_ONLY resultset"); } catch (SQLException e) {}
		try { rs.beforeFirst(); fail("beforeFirst() on a TYPE_FORWARD_ONLY resultset did not throw an exception"); } catch (SQLException e) {}
		try { rs.first();       fail("first() on a TYPE_FORWARD_ONLY resultset did not throw an exception"); } catch (SQLException e) {}
		try { rs.last();        fail("last() on a TYPE_FORWARD_ONLY resultset did not throw an exception"); } catch (SQLException e) {}
		try { rs.previous();    fail("previous() on a TYPE_FORWARD_ONLY resultset did not throw an exception"); } catch (SQLException e) {}
		try { rs.relative(1);   fail("relative() on a TYPE_FORWARD_ONLY resultset did not throw an exception"); } catch (SQLException e) {}

		try {
			rs.setFetchDirection(ResultSet.FETCH_REVERSE);
			fail("setFetchDirection(FETCH_REVERSE) on a TYPE_FORWARD_ONLY resultset did not throw an exception");
		} catch (SQLException e) {}

		try {
			rs.setFetchDirection(ResultSet.FETCH_UNKNOWN);
			fail("setFetchDirection(FETCH_UNKNOWN) on a TYPE_FORWARD_ONLY resultset did not throw an exception");
		} catch (SQLException e) {}

		rs.close();
		stmt.close();
	}
}
