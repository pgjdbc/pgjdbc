package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import org.postgresql.test.util.BrokenInputStream;
import junit.framework.TestCase;
import java.io.*;
import java.sql.*;


public class PreparedStatementTest extends TestCase
{

	private Connection conn;

	public PreparedStatementTest(String name)
	{
		super(name);
	}

	protected void setUp() throws SQLException
	{
		conn = TestUtil.openDB();
		TestUtil.createTable(conn, "streamtable", "bin bytea, str text");
	}

	protected void tearDown() throws SQLException
	{
		TestUtil.dropTable(conn, "streamtable");
		TestUtil.closeDB(conn);
	}

	public void testSetBinaryStream() throws SQLException
	{
		ByteArrayInputStream bais;
		byte buf[] = new byte[10];
		for (int i=0; i<buf.length; i++) {
			buf[i] = (byte)i;
		}

		bais = null;
		doSetBinaryStream(bais,0);

		bais = new ByteArrayInputStream(new byte[0]);
		doSetBinaryStream(bais,0);

		bais = new ByteArrayInputStream(buf);
		doSetBinaryStream(bais,0);

		bais = new ByteArrayInputStream(buf);
		doSetBinaryStream(bais,10);
	}

	public void testSetAsciiStream() throws Exception
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos,"ASCII"));
		pw.println("Hello");
		pw.flush();
		
		ByteArrayInputStream bais;
		
		bais = new ByteArrayInputStream(baos.toByteArray());
		doSetAsciiStream(bais, 0);

		bais = new ByteArrayInputStream(baos.toByteArray());
		doSetAsciiStream(bais, 6);

		bais = new ByteArrayInputStream(baos.toByteArray());
		doSetAsciiStream(bais, 100);
	}

	public void testExecuteStringOnPreparedStatement() throws Exception {
		PreparedStatement pstmt = conn.prepareStatement("SELECT 1");

		try {
			pstmt.executeQuery("SELECT 2");
			fail("Expected an exception when executing a new SQL query on a prepared statement");
		} catch (SQLException e) {}

		try {
			pstmt.executeUpdate("UPDATE streamtable SET bin=bin");
			fail("Expected an exception when executing a new SQL update on a prepared statement");
		} catch (SQLException e) {}

		try {
			pstmt.execute("UPDATE streamtable SET bin=bin");
			fail("Expected an exception when executing a new SQL statement on a prepared statement");
		} catch (SQLException e) {}
	}

	public void testBinaryStreamErrorsRestartable() throws SQLException {
		// The V2 protocol does not have the ability to recover when
		// streaming data to the server.  We could potentially try
		// introducing a syntax error to force the query to fail, but
		// that seems dangerous.
		//
		if(!TestUtil.haveMinimumServerVersion(conn, "7.4")) {
			return;
		}

		byte buf[] = new byte[10];
		for (int i=0; i<buf.length; i++) {
			buf[i] = (byte)i;
		}

		// InputStream is shorter than the length argument implies.
		InputStream is = new ByteArrayInputStream(buf);
		runBrokenStream(is, buf.length+1);

		// InputStream throws an Exception during read.
		is = new BrokenInputStream(new ByteArrayInputStream(buf), buf.length/2);
		runBrokenStream(is, buf.length);

		// Invalid length < 0.
		is = new ByteArrayInputStream(buf);
		runBrokenStream(is, -1);

		// Total Bind message length too long.
		is = new ByteArrayInputStream(buf);
		runBrokenStream(is, Integer.MAX_VALUE);
	}

	private void runBrokenStream(InputStream is, int length) throws SQLException
	{
		PreparedStatement pstmt = null;
		try {
			pstmt = conn.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");
			pstmt.setBinaryStream(1, is, length);
			pstmt.setString(2, "Other");
			pstmt.executeUpdate();
			fail("This isn't supposed to work.");
		} catch (SQLException sqle) {
			// don't need to rollback because we're in autocommit mode
			pstmt.close();

			// verify the connection is still valid and the row didn't go in.
			Statement stmt = conn.createStatement();
			ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM streamtable");
			assertTrue(rs.next());
			assertEquals(0, rs.getInt(1));
			rs.close();
			stmt.close();
		}
	}

	private void doSetBinaryStream(ByteArrayInputStream bais, int length) throws SQLException
	{
		PreparedStatement pstmt = conn.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");
		pstmt.setBinaryStream(1,bais, length);
		pstmt.setString(2,null);
		pstmt.executeUpdate();
		pstmt.close();
	}

	private void doSetAsciiStream(InputStream is, int length) throws SQLException
	{
		PreparedStatement pstmt = conn.prepareStatement("INSERT INTO streamtable (bin,str) VALUES (?,?)");
		pstmt.setBytes(1,null);
		pstmt.setAsciiStream(2, is, length);
		pstmt.executeUpdate();
		pstmt.close();
	}
}
