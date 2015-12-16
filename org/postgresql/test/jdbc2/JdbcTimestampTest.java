/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Properties;
import java.util.TimeZone;

import junit.framework.TestCase;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGTimestamp;

/**
 * Tests {@link java.sql.Timestamp and PGTimestamp} in various scenarios including setTimestamp, setObject for both
 * <code>timestamp with time zone</code> and <code>timestamp without time zone</code> data types.
 */
public class JdbcTimestampTest extends TestCase
{
    /** The name of the test table. */
    private static final String TEST_TABLE = "testjdbctimestamp";

    /** The database connection. */
    private Connection con;

    public JdbcTimestampTest(final String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        Properties props = new Properties();
        props.setProperty(PGProperty.STRICT_TIMESTAMP.getName(), "true");
        con = TestUtil.openDB(props);
        TestUtil.createTable(con, TEST_TABLE, "ts timestamp, tz timestamp with time zone");
    }

    protected void tearDown() throws Exception
    {
        TestUtil.dropTable(con, TEST_TABLE);
        TestUtil.closeDB(con);
    }

    public void testParameterIsNull() throws SQLException{
        long moment = 1450276739098L;
        PreparedStatement insertStmt = con.prepareStatement("INSERT INTO " + TEST_TABLE + " VALUES (?, ?)");
        insertStmt.setTimestamp(1, new java.sql.Timestamp(moment++));
        insertStmt.setTimestamp(2, new java.sql.Timestamp(moment++));
        assertEquals(1, insertStmt.executeUpdate());
        insertStmt.setTimestamp(1, new java.sql.Timestamp(moment++));
        insertStmt.setTimestamp(2, new java.sql.Timestamp(moment++));
        assertEquals(1, insertStmt.executeUpdate());
        insertStmt.setTimestamp(1, new java.sql.Timestamp(moment++));
        insertStmt.setTimestamp(2, new java.sql.Timestamp(moment++));
        assertEquals(1, insertStmt.executeUpdate());
        insertStmt.close();
        PreparedStatement selectStmt = con.prepareStatement("Select * from " + TEST_TABLE + " Where ts = ? or ? is null");
        selectStmt.setTimestamp(1, new java.sql.Timestamp(moment++));
        selectStmt.setTimestamp(2, new java.sql.Timestamp(moment++));
        ResultSet rs1 = selectStmt.executeQuery();
        assertFalse(rs1.next());
        rs1.close();
        
        selectStmt.setTimestamp(1, new java.sql.Timestamp(moment++));
        selectStmt.setTimestamp(2, null);
        ResultSet rs2 = selectStmt.executeQuery();
        assertTrue(rs2.next());
        assertTrue(rs2.next());
        assertTrue(rs2.next());
        assertFalse(rs2.next());
        rs2.close();
        selectStmt.close();
    }
    
    /**
     * Tests inserting and selecting <code>PGTimestamp</code> objects with
     * <code>timestamp</code> and <code>timestamp with time zone</code> columns.
     *
     * @throws SQLException
     *            if a JDBC or database problem occurs.
     */
    public void testTimeInsertAndSelect() throws SQLException
    {
        final long moment = 1450276827047L;
        verifyInsertAndSelect(new PGTimestamp(moment), true);
        verifyInsertAndSelect(new PGTimestamp(moment), false);

        verifyInsertAndSelect(new PGTimestamp(moment, Calendar.getInstance(TimeZone.getTimeZone("GMT"))), true);
        verifyInsertAndSelect(new PGTimestamp(moment, Calendar.getInstance(TimeZone.getTimeZone("GMT"))), false);

        verifyInsertAndSelect(new PGTimestamp(moment, Calendar.getInstance(TimeZone.getTimeZone("GMT+01:00"))), true);
        verifyInsertAndSelect(new PGTimestamp(moment, Calendar.getInstance(TimeZone.getTimeZone("GMT+01:00"))), false);
    }

    /**
     * Verifies that inserting the given <code>PGTimestamp</code> as a timestamp 
     * string and an object produces the same results.
     *
     * @param timestamp
     *           the timestamp to test.
     * @param useSetObject
     *           <code>true</code> if the setObject method should be used instead
     *           of setTimestamp.
     * @throws SQLException
     *            if a JDBC or database problem occurs.
     */
    private void verifyInsertAndSelect(PGTimestamp timestamp, boolean useSetObject) throws SQLException
    {
        // Construct the INSERT statement of a casted timestamp string.
        String sql;
        if (timestamp.getCalendar() != null)
        {
            sql = "INSERT INTO " + TEST_TABLE + " VALUES (?::timestamp with time zone, ?::timestamp with time zone)";
        }
        else
        {
            sql = "INSERT INTO " + TEST_TABLE + " VALUES (?::timestamp, ?::timestamp)";
        }

        SimpleDateFormat sdf = createSimpleDateFormat(timestamp);

        // Insert the timestamps as casted strings.
        PreparedStatement pstmt1 = con.prepareStatement(sql);
        pstmt1.setString(1, sdf.format(timestamp));
        pstmt1.setString(2, sdf.format(timestamp));
        assertEquals(1, pstmt1.executeUpdate());

        // Insert the timestamps as PGTimestamp objects.
        PreparedStatement pstmt2 = con.prepareStatement("INSERT INTO " + TEST_TABLE + " VALUES (?, ?)");

        if (useSetObject)
        {
            pstmt2.setObject(1, new java.sql.Timestamp(timestamp.getTime()));
            pstmt2.setObject(2, timestamp);
        }
        else
        {
            pstmt2.setTimestamp(1, new java.sql.Timestamp(timestamp.getTime()));
            pstmt2.setTimestamp(2, timestamp);
        }

        assertEquals(1, pstmt2.executeUpdate());

        // Query the values back out.
        Statement stmt = con.createStatement();

        ResultSet rs = stmt.executeQuery(TestUtil.selectSQL(TEST_TABLE, "ts,tz"));
        assertNotNull(rs);

        // Read the casted string values.
        assertTrue(rs.next());

        Timestamp ts1 = rs.getTimestamp(1);
        Timestamp tz1 = rs.getTimestamp(2);

        // System.out.println(pstmt1 + " -> " + ts1 + ", " + sdf.format(tz1));

        // Read the PGTimestamp values.
        assertTrue(rs.next());

        Timestamp ts2 = rs.getTimestamp(1);
        Timestamp tz2 = rs.getTimestamp(2);

        // System.out.println(pstmt2 + " -> " + ts2 + ", " + sdf.format(tz2));

        // Verify that the first and second versions match.
        assertEquals(ts1, ts2);
        assertEquals(tz1, tz2);

        // Clean up.
        assertEquals(2, stmt.executeUpdate("DELETE FROM " + TEST_TABLE));
        stmt.close();
        pstmt2.close();
        pstmt1.close();
    }

    /**
     * Creates a <code>SimpleDateFormat</code> that is appropriate for the given timestamp.
     *
     * @param timestamp
     *           the timestamp object.
     * @return the new format instance.
     */
    private SimpleDateFormat createSimpleDateFormat(PGTimestamp timestamp)
    {
        String pattern = "yyyy-MM-dd HH:mm:ss.SSS";
        if (timestamp.getCalendar() != null)
        {
            pattern += " Z";
        }

        SimpleDateFormat sdf = new SimpleDateFormat(pattern);
        if (timestamp.getCalendar() != null)
        {
            sdf.setTimeZone(timestamp.getCalendar().getTimeZone());
        }
        return sdf;
    }
    
}
