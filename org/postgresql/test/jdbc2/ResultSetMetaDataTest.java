/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $PostgreSQL$
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
	private Statement stmt;
	private ResultSet rs;
	private ResultSetMetaData rsmd;
	private PGResultSetMetaData pgrsmd;

	public ResultSetMetaDataTest(String name)
	{
		super(name);
	}

	protected void setUp() throws Exception
	{
		conn = TestUtil.openDB();
		TestUtil.createTable(conn, "rsmd1", "a int primary key, b text, c decimal(10,2)");

		stmt = conn.createStatement();
		rs = stmt.executeQuery("SELECT a,b,c,a+c as total,oid,b as d FROM rsmd1");
		rsmd = rs.getMetaData();
		pgrsmd = (PGResultSetMetaData)rsmd;
		

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
		rs.close();
		stmt.close();
		TestUtil.closeDB(conn);
		rsmd = null;
		rs = null;
		stmt = null;
		conn = null;
	}

	public void testGetColumnCount() throws SQLException {
		assertEquals(6, rsmd.getColumnCount());
	}

	public void testGetColumnLabel() throws SQLException {
		assertEquals("a", rsmd.getColumnLabel(1));
		assertEquals("total", rsmd.getColumnLabel(4));
	}

	public void testGetColumnName() throws SQLException {
		assertEquals("a", rsmd.getColumnName(1));
		assertEquals("oid", rsmd.getColumnName(5));
		if (TestUtil.haveMinimumServerVersion(conn,"7.4")) {
			assertEquals("", pgrsmd.getBaseColumnName(4));
			assertEquals("b", pgrsmd.getBaseColumnName(6));
		}
	}

	public void testGetColumnType() throws SQLException {
		assertEquals(Types.INTEGER, rsmd.getColumnType(1));
		assertEquals(Types.VARCHAR, rsmd.getColumnType(2));
	}

	public void testGetColumnTypeName() throws SQLException {
		assertEquals("int4", rsmd.getColumnTypeName(1));
		assertEquals("text", rsmd.getColumnTypeName(2));
	}

	public void testGetPrecision() throws SQLException {
		assertEquals(10, rsmd.getPrecision(3));
	}

	public void testGetScale() throws SQLException {
		assertEquals(2, rsmd.getScale(3));
	}

	public void testGetSchemaName() throws SQLException {
		assertEquals("", rsmd.getSchemaName(1));
		assertEquals("", rsmd.getSchemaName(4));
		if (TestUtil.haveMinimumServerVersion(conn,"7.4")) {
			assertEquals("public", pgrsmd.getBaseSchemaName(1));
			assertEquals("", pgrsmd.getBaseSchemaName(4));
		}
	}

	public void testGetTableName() throws SQLException {
		assertEquals("", rsmd.getTableName(1));
		assertEquals("", rsmd.getTableName(4));
		if (TestUtil.haveMinimumServerVersion(conn,"7.4")) {
			assertEquals("rsmd1", pgrsmd.getBaseTableName(1));
			assertEquals("", pgrsmd.getBaseTableName(4));
		}
	}

	public void testIsNullable() throws SQLException {
		if (TestUtil.haveMinimumServerVersion(conn,"7.4")) {
			assertEquals(ResultSetMetaData.columnNoNulls, rsmd.isNullable(1));
			assertEquals(ResultSetMetaData.columnNullable, rsmd.isNullable(2));
			assertEquals(ResultSetMetaData.columnNullableUnknown, rsmd.isNullable(4));
		} else {
			assertEquals(ResultSetMetaData.columnNullableUnknown, rsmd.isNullable(1));
		}
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
		Statement stmt2 = conn.createStatement();
		ResultSet rs2 = stmt2.executeQuery("SELECT tm, tmtz, ts, tstz FROM timetest");
		ResultSetMetaData rsmd2 = rs2.getMetaData();

		// For reference:
		// TestUtil.createTable(conn, "timetest", "tm time(3), tmtz timetz, ts timestamp without time zone, tstz timestamp(6) with time zone");

		assertEquals(3, rsmd2.getScale(1));
		assertEquals(6, rsmd2.getScale(2));
		assertEquals(6, rsmd2.getScale(3));
		assertEquals(6, rsmd2.getScale(4));

		assertEquals(13, rsmd2.getColumnDisplaySize(1));
		assertEquals(21, rsmd2.getColumnDisplaySize(2));
		assertEquals(26, rsmd2.getColumnDisplaySize(3));
		assertEquals(32, rsmd2.getColumnDisplaySize(4));

		rs2.close();
		stmt2.close();
	}

	public void testIsAutoIncrement() throws SQLException {
		Statement stmt2 = conn.createStatement();
		ResultSet rs2 = stmt2.executeQuery("SELECT c,b,a FROM serialtest");
		ResultSetMetaData rsmd2 = rs2.getMetaData();

		assertTrue(!rsmd2.isAutoIncrement(1));
		if (TestUtil.haveMinimumServerVersion(conn,"7.4")) {
			assertTrue(rsmd2.isAutoIncrement(2));
			assertTrue(rsmd2.isAutoIncrement(3));
		}

		rs2.close();
		stmt2.close();
	}

}
