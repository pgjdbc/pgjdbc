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
		Statement stmt2 = conn.createStatement();
		stmt2.close();
	}

	protected void tearDown() throws Exception
	{
		TestUtil.dropTable(conn, "rsmd1");
		TestUtil.dropTable(conn, "timetest");
		rs.close();
		stmt.close();
		TestUtil.closeDB(conn);
		rsmd = null;
		rs = null;
		stmt = null;
		conn = null;
	}

	public void testGetColumnCount() throws SQLException {
		assertEquals(rsmd.getColumnCount(), 6);
	}

	public void testGetColumnLabel() throws SQLException {
		assertEquals(rsmd.getColumnLabel(1), "a");
		assertEquals(rsmd.getColumnLabel(4), "total");
	}

	public void testGetColumnName() throws SQLException {
		assertEquals(rsmd.getColumnName(1), "a");
		assertEquals(rsmd.getColumnName(5), "oid");
		if (TestUtil.haveMinimumServerVersion(conn,"7.4")) {
			assertEquals("", pgrsmd.getBaseColumnName(4));
			assertEquals("b", pgrsmd.getBaseColumnName(6));
		}
	}

	public void testGetColumnType() throws SQLException {
		assertEquals(rsmd.getColumnType(1), Types.INTEGER);
		assertEquals(rsmd.getColumnType(2), Types.VARCHAR);
	}

	public void testGetColumnTypeName() throws SQLException {
		assertEquals(rsmd.getColumnTypeName(1), "int4");
		assertEquals(rsmd.getColumnTypeName(2), "text");
	}

	public void testGetPrecision() throws SQLException {
		assertEquals(rsmd.getPrecision(3), 10);
	}

	public void testGetScale() throws SQLException {
		assertEquals(rsmd.getScale(3), 2);
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
			assertEquals(rsmd.isNullable(1), ResultSetMetaData.columnNoNulls);
			assertEquals(rsmd.isNullable(2), ResultSetMetaData.columnNullable);
			assertEquals(rsmd.isNullable(4), ResultSetMetaData.columnNullableUnknown);
		} else {
			assertEquals(rsmd.isNullable(1), ResultSetMetaData.columnNullableUnknown);
		}
	}

	public void testDatabaseMetaDataNames() throws SQLException {
		DatabaseMetaData databaseMetaData = conn.getMetaData();
		ResultSet resultSet = databaseMetaData.getTableTypes();
		ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
		assertEquals(resultSetMetaData.getColumnCount(), 1);
		assertEquals(resultSetMetaData.getColumnName(1), "TABLE_TYPE");
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

}
