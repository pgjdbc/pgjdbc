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
import java.util.Calendar;
import java.util.TimeZone;

import junit.framework.TestCase;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PGTimestamp;

/*
 * Test get/setTimestamp for both timestamp with time zone and
 * timestamp without time zone datatypes
 *
 */
public class PGTimestampTest extends TestCase
{
    /** The database connection. */
    private Connection con;

    public PGTimestampTest(final String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        con = TestUtil.openDB();
        TestUtil.createTable(con, TSWTZ_TABLE, "ts timestamp with time zone");
        TestUtil.createTable(con, TSWOTZ_TABLE, "ts timestamp without time zone");
        TestUtil.createTable(con, DATE_TABLE, "ts date");
    }

    protected void tearDown() throws Exception
    {
        TestUtil.dropTable(con, TSWTZ_TABLE);
        TestUtil.dropTable(con, TSWOTZ_TABLE);
        TestUtil.dropTable(con, DATE_TABLE);
        TestUtil.closeDB(con);
    }

    /**
     * Ensure the driver doesn't modify a Calendar that is passed in.
     */
    public void testCalendarModification() throws SQLException
    {
        verifyCalendarUnmodified(null);
    }

    /**
     * Ensure the driver doesn't modify a Calendar that is passed in.
     */
    public void testCalendarModificationGmtMinus6() throws SQLException
    {
        verifyCalendarUnmodified(Calendar.getInstance(TimeZone.getTimeZone("GMT-6:00")));
    }

    /**
     * Ensure the driver doesn't modify a Calendar that is passed in.
     */
    public void testCalendarModificationGmtMinus7() throws SQLException
    {
        verifyCalendarUnmodified(Calendar.getInstance(TimeZone.getTimeZone("GMT-7:00")));
    }

    /**
     * Verifies that all calendars are unmodified.
     * @param pgTsCal
     * @throws SQLException
     */
    private void verifyCalendarUnmodified(Calendar pgTsCal) throws SQLException
    {
        Calendar cal = Calendar.getInstance();
        Calendar origCal = (Calendar)cal.clone();
        Calendar origPgTsCal = pgTsCal == null ? null : (Calendar)pgTsCal.clone();
        PreparedStatement ps = con.prepareStatement("INSERT INTO " + TSWOTZ_TABLE + " VALUES (?)");

        ps.setTimestamp(1, new PGTimestamp(0, pgTsCal), cal);
        ps.executeUpdate();
        assertEquals(origCal, cal);
        assertEquals(origPgTsCal, pgTsCal);

        ps.close();
        Statement stmt = con.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT ts FROM " + TSWOTZ_TABLE);
        assertTrue(rs.next());

        rs.getDate(1, cal);
        assertEquals(origCal, cal);
        assertEquals(origPgTsCal, pgTsCal);

        rs.getTimestamp(1, cal);
        assertEquals(origCal, cal);
        assertEquals(origPgTsCal, pgTsCal);

        rs.getTime(1, cal);
        assertEquals(origCal, cal);
        assertEquals(origPgTsCal, pgTsCal);

        rs.close();
        stmt.close();
    }

    private static final String TSWTZ_TABLE = "testtimestampwtz";
    private static final String TSWOTZ_TABLE = "testtimestampwotz";
    private static final String DATE_TABLE = "testtimestampdate";
}
