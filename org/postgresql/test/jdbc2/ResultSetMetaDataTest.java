package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import junit.framework.TestCase;
import java.sql.*;

public class ResultSetMetaDataTest extends TestCase
{

	private Connection conn;
	private Statement stmt;
	private ResultSet rs;
	private ResultSetMetaData rsmd;

	public ResultSetMetaDataTest(String name)
	{
		super(name);
	}

	protected void setUp() throws Exception
	{
		conn = TestUtil.openDB();
		TestUtil.createTable(conn, "rsmd1", "a int primary key, b text, c decimal(10,2)");
		stmt = conn.createStatement();
		rs = stmt.executeQuery("SELECT a,b,c,a+c as total,oid FROM rsmd1");
		rsmd = rs.getMetaData();
	}

	protected void tearDown() throws Exception
	{
		TestUtil.dropTable(conn, "rsmd1");
		TestUtil.closeDB(conn);
		rs.close();
		stmt.close();
		rsmd = null;
		rs = null;
		stmt = null;
		conn = null;
	}

	public void testGetColumnCount() throws SQLException {
		assertEquals(rsmd.getColumnCount(), 5);
	}

	public void testGetColumnLabel() throws SQLException {
		assertEquals(rsmd.getColumnLabel(1), "a");
		assertEquals(rsmd.getColumnLabel(4), "total");
	}

	public void testGetColumnName() throws SQLException {
		assertEquals(rsmd.getColumnName(1), "a");
		assertEquals(rsmd.getColumnName(5), "oid");
		if (TestUtil.haveMinimumServerVersion(conn,"7.4")) {
			assertEquals(rsmd.getColumnName(4), "");
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
		if (TestUtil.haveMinimumServerVersion(conn,"7.4")) {
			assertEquals(rsmd.getSchemaName(1), "public");
			assertEquals(rsmd.getSchemaName(4), "");
		}
	}

	public void testGetTableName() throws SQLException {
		if (TestUtil.haveMinimumServerVersion(conn,"7.4")) {
			assertEquals(rsmd.getTableName(1), "rsmd1");
			assertEquals(rsmd.getTableName(4), "");
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

}
