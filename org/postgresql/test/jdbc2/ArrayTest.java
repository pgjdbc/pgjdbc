package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import java.sql.*;
import java.math.BigDecimal;

import junit.framework.TestCase;

public class ArrayTest extends TestCase
{

	private Connection conn;

	public ArrayTest(String name)
	{
		super(name);
	}

	protected void setUp() throws SQLException
	{
		conn = TestUtil.openDB();

		TestUtil.createTable(conn, "arrtest", "intarr int[], decarr decimal(2,1)[], strarr text[]");
		Statement stmt = conn.createStatement();
		// you need a lot of backslashes to get a double quote in.
		stmt.executeUpdate("INSERT INTO arrtest VALUES ('{1,2,3}','{3.1,1.4}', '{abc,f''a,\"fa\\\\\"b\",def}')");
		stmt.close();
	}

	protected void tearDown() throws SQLException
	{
		TestUtil.dropTable(conn, "arrtest");
		TestUtil.closeDB(conn);
	}

	public void testRetrieveArrays() throws SQLException {
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
		assertTrue(rs.next());

		Array arr = rs.getArray(1);
		assertEquals(Types.INTEGER, arr.getBaseType());
		int intarr[] = (int[])arr.getArray();
		assertEquals(3,intarr.length);
		assertEquals(1,intarr[0]);
		assertEquals(2,intarr[1]);
		assertEquals(3,intarr[2]);

		arr = rs.getArray(2);
		assertEquals(Types.NUMERIC, arr.getBaseType());
		BigDecimal decarr[] = (BigDecimal[])arr.getArray();
		assertEquals(2,decarr.length);
		assertEquals(new BigDecimal("3.1"), decarr[0]);
		assertEquals(new BigDecimal("1.4"), decarr[1]);

		arr = rs.getArray(3);
		assertEquals(Types.VARCHAR, arr.getBaseType());
		String strarr[] = (String[])arr.getArray(2,2);
		assertEquals(2,strarr.length);
		assertEquals("f'a", strarr[0]);
		assertEquals("fa\"b", strarr[1]);

		rs.close();
		stmt.close();
	}

	public void testRetrieveResultSets() throws SQLException {
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery("SELECT intarr, decarr, strarr FROM arrtest");
		assertTrue(rs.next());

		Array arr = rs.getArray(1);
		assertEquals(Types.INTEGER, arr.getBaseType());
		ResultSet arrrs = arr.getResultSet();
		assertTrue(arrrs.next());
		assertEquals(1,arrrs.getInt(1));
		assertEquals(1,arrrs.getInt(2));
		assertTrue(arrrs.next());
		assertEquals(2,arrrs.getInt(1));
		assertEquals(2,arrrs.getInt(2));
		assertTrue(arrrs.next());
		assertEquals(3,arrrs.getInt(1));
		assertEquals(3,arrrs.getInt(2));
		assertTrue(!arrrs.next());
		assertTrue(arrrs.previous());
		assertEquals(3,arrrs.getInt(2));
		arrrs.first();
		assertEquals(1,arrrs.getInt(2));
		arrrs.close();

		arr = rs.getArray(2);
		assertEquals(Types.NUMERIC, arr.getBaseType());
		arrrs = arr.getResultSet();
		assertTrue(arrrs.next());
		assertEquals(new BigDecimal("3.1"), arrrs.getBigDecimal(2));
		assertTrue(arrrs.next());
		assertEquals(new BigDecimal("1.4"), arrrs.getBigDecimal(2));
		arrrs.close();

		arr = rs.getArray(3);
		assertEquals(Types.VARCHAR, arr.getBaseType());
		arrrs = arr.getResultSet(2,2);
		assertTrue(arrrs.next());
		assertEquals(2, arrrs.getInt(1));
		assertEquals("f'a", arrrs.getString(2));
		assertTrue(arrrs.next());
		assertEquals(3, arrrs.getInt(1));
		assertEquals("fa\"b", arrrs.getString(2));
		assertTrue(!arrrs.next());
		arrrs.close();

		rs.close();
		stmt.close();
	}

}

