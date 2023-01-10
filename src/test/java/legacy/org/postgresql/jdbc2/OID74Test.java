/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import legacy.org.postgresql.TestUtil;
import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.sql.*;

/**
 * User: alexei
 */
public class OID74Test extends TestCase
{
    private Connection conn;

    public OID74Test( String name )
    {
        super(name);
    }
    public void setUp() throws Exception
    {
        //set up conection here
        Properties props = new Properties();
        props.setProperty("compatible", "7.1");
        conn = TestUtil.openDB(props);

        TestUtil.createTable(conn, "temp", "col oid");
        conn.setAutoCommit(false);
    }

    public void tearDown() throws Exception
    {
        conn.setAutoCommit(true);
        TestUtil.dropTable(conn, "temp");
        TestUtil.closeDB(conn);
    }

    public void testSetNull() throws SQLException {
        PreparedStatement pstmt = conn.prepareStatement("INSERT INTO temp VALUES (?)");
        pstmt.setNull(1, Types.VARBINARY);
        pstmt.executeUpdate();
        pstmt.setNull(1, Types.BLOB);
        pstmt.executeUpdate();
        pstmt.setNull(1, Types.CLOB);
        pstmt.executeUpdate();
        pstmt.close();
    }

    public void testBinaryStream() throws Exception
    {

        PreparedStatement pstmt = null;

        pstmt = conn.prepareStatement("INSERT INTO temp VALUES (?)");
        pstmt.setBinaryStream(1, new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}), 5);
        assertTrue( (pstmt.executeUpdate() == 1) );
        pstmt.close();

        pstmt = conn.prepareStatement("SELECT col FROM temp LIMIT 1");
        ResultSet rs = pstmt.executeQuery();

        assertTrue("No results from query", rs.next() );

        InputStream in = rs.getBinaryStream(1);
        int data;
        int i = 1;
        while ((data = in.read()) != -1)
            assertEquals(i++, data);
        rs.close();
        pstmt.close();
    }
}
