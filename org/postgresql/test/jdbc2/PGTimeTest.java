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
import java.sql.Time;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

import junit.framework.TestCase;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PGTime;

/*
 * Some simple tests based on problems reported by users. Hopefully these will
 * help prevent previous problems from re-occuring ;-)
 *
 */
public class PGTimeTest extends TestCase
{
    private Connection con;
    private boolean testSetTime = false;

    public PGTimeTest(String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        con = TestUtil.openDB();
        TestUtil.createTempTable(con, "testtime", "tm time, tz time with time zone");
    }

    protected void tearDown() throws Exception
    {
        TestUtil.dropTable(con, "testtime");
        TestUtil.closeDB(con);
    }

    private long extractMillis(long time) {
        return (time >= 0) ? (time % 1000) : (time % 1000 + 1000);
    }

    public void testTimeWithInterval() throws SQLException
    {
        Calendar cal = Calendar.getInstance();
        cal.set(1970, 0, 1);

        final long now = cal.getTimeInMillis();
        verifyTimeWithInterval(new PGTime(now), new PGInterval(0, 0, 0, 1, 2, 3.14), true);
        verifyTimeWithInterval(new PGTime(now), new PGInterval(0, 0, 0, 1, 2, 3.14), false);

        verifyTimeWithInterval(new PGTime(now, Calendar.getInstance(TimeZone.getTimeZone("GMT"))), new PGInterval(0, 0, 0, 1, 2, 3.14), true);
        verifyTimeWithInterval(new PGTime(now, Calendar.getInstance(TimeZone.getTimeZone("GMT"))), new PGInterval(0, 0, 0, 1, 2, 3.14), false);
        
        verifyTimeWithInterval(new PGTime(now, Calendar.getInstance(TimeZone.getTimeZone("GMT+01:00"))), new PGInterval(0, 0, 0, 1, 2, 3.456), true);
        verifyTimeWithInterval(new PGTime(now, Calendar.getInstance(TimeZone.getTimeZone("GMT+01:00"))), new PGInterval(0, 0, 0, 1, 2, 3.456), false);
    }

    private void verifyTimeWithInterval(PGTime pgTime, PGInterval pgInterval, boolean useSetObject) throws SQLException
    {
        // Construct a local calendar for the expected result.
        Calendar cal = Calendar.getInstance();
        final int seconds = (int)pgInterval.getSeconds();
        cal.setTime(pgTime);
        cal.add(Calendar.HOUR_OF_DAY, pgInterval.getHours());
        cal.add(Calendar.MINUTE, pgInterval.getMinutes());
        cal.add(Calendar.SECOND, seconds);
        final int milliseconds = (int)((pgInterval.getSeconds() - seconds) * 1000 + 0.5);
        cal.add(Calendar.MILLISECOND, milliseconds);
        Time expected = new Time(cal.getTimeInMillis());

        // Construct the SQL query and date format.
        String sql;
        String pattern = "HH:mm:ss.SSS";
        if (pgTime.getTimeZone() != null)
        {
            sql = "SELECT ?::time with time zone + ?";
            pattern += " Z";
        }
        else
        {
           sql = "SELECT ?::time + ?";
        }

        SimpleDateFormat sdf = new SimpleDateFormat(pattern);
        if (pgTime.getTimeZone() != null)
        {
            sdf.setTimeZone(pgTime.getTimeZone().getTimeZone());
        }

        // Execute a query using a casted time string + PGInterval.
        PreparedStatement stmt = con.prepareStatement(sql);
        stmt.setString(1, sdf.format(pgTime));
        stmt.setObject(2, pgInterval);

        ResultSet rs = stmt.executeQuery();
        assertTrue(rs.next());

        Time actual = rs.getTime(1);
        //System.out.println(stmt + " = " + sdf.format(actual));
        assertEquals(sdf.format(expected) + " != " + sdf.format(actual), expected, actual);
        stmt.close();

        // Execute a query using with PGTime + PGInterval.
        stmt = con.prepareStatement("SELECT ? + ?");
        if (useSetObject)
        {
            stmt.setObject(1, pgTime);
        }
        else
        {
            stmt.setTime(1, pgTime);
        }
        stmt.setObject(2, pgInterval);

        rs = stmt.executeQuery();
        assertTrue(rs.next());

        actual = rs.getTime(1);
        //System.out.println(stmt + " = " + sdf.format(actual));
        assertEquals(sdf.format(expected) + " != " + sdf.format(actual), expected, actual);
        stmt.close();
    }

    /**
     * Test use of calendar
     */
    public void testGetTimeZone() throws Exception
    {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeZone(TimeZone.getTimeZone("GMT"));
        calendar.set(1970, 0, 1, 0, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        final Time midnight = new PGTime(calendar.getTimeInMillis());
        Statement stmt = con.createStatement();
        Calendar cal = Calendar.getInstance();

        cal.setTimeZone(TimeZone.getTimeZone("GMT"));

        int localOffset=cal.getTimeZone().getOffset(midnight.getTime());
        System.out.println(localOffset);

        // set the time to midnight to make this easy
        assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testtime", "'00:00:00','00:00:00'")));
        assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testtime", "'00:00:00.1','00:00:00.01'")));
        assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testtime", "CAST(CAST(now() AS timestamp without time zone) AS time),now()")));
        ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("testtime", "tm,tz"));
        assertNotNull(rs);
        assertTrue(rs.next());


        Time time = rs.getTime(1);
        Timestamp timestamp = rs.getTimestamp(1);
        assertNotNull(timestamp);

        Timestamp timestamptz = rs.getTimestamp(2);
        assertNotNull(timestamptz);

        Time timetz = rs.getTime(2);
        assertEquals(midnight.getTime(), time.getTime());

        time = rs.getTime(1, cal);
        assertEquals(midnight.getTime() , time.getTime() - localOffset);

        assertTrue(rs.next());

        time = rs.getTime(1);
        assertNotNull(time);
        assertEquals(100, extractMillis(time.getTime()));
        timestamp = rs.getTimestamp(1);
        assertNotNull(timestamp);

        // Pre 1.4 JVM's considered the nanos field completely separate
        // and wouldn't return it in getTime()
        if (TestUtil.haveMinimumJVMVersion("1.4"))
        {
            assertEquals(100, extractMillis(timestamp.getTime()));
        }
        else
        {
            assertEquals(100, extractMillis(timestamp.getTime() + timestamp.getNanos() / 1000000));
        }
        assertEquals(100000000, timestamp.getNanos());

        timetz = rs.getTime(2);
        assertNotNull(timetz);
        assertEquals(10, extractMillis(timetz.getTime()));
        timestamptz = rs.getTimestamp(2);
        assertNotNull(timestamptz);

        // Pre 1.4 JVM's considered the nanos field completely separate
        // and wouldn't return it in getTime()
        if (TestUtil.haveMinimumJVMVersion("1.4"))
        {
            assertEquals(10, extractMillis(timestamptz.getTime()));
        }
        else
        {
            assertEquals(10, extractMillis(timestamptz.getTime() + timestamptz.getNanos() / 1000000));
        }
        assertEquals(10000000, timestamptz.getNanos());

        assertTrue(rs.next());

        time = rs.getTime(1);
        assertNotNull(time);
        timestamp = rs.getTimestamp(1);
        assertNotNull(timestamp);

        timetz = rs.getTime(2);
        assertNotNull(timetz);
        timestamptz = rs.getTimestamp(2);
        assertNotNull(timestamptz);
    }
    /*
     * Tests the time methods in ResultSet
     */
    public void testGetTime() throws SQLException
    {
        Statement stmt = con.createStatement();

        assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testtime", "'01:02:03'")));
        assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testtime", "'23:59:59'")));
        assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testtime", "'12:00:00'")));
        assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testtime", "'05:15:21'")));
        assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testtime", "'16:21:51'")));
        assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testtime", "'12:15:12'")));
        assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testtime", "'22:12:01'")));
        assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testtime", "'08:46:44'")));


        // Fall through helper
        timeTest();

        assertEquals(8, stmt.executeUpdate("DELETE FROM testtime"));
        stmt.close();
    }

    /*
     * Tests the time methods in PreparedStatement
     */
    public void testSetTime() throws SQLException
    {
        PreparedStatement ps = con.prepareStatement(TestUtil.insertSQL("testtime", "?"));
        Statement stmt = con.createStatement();

        ps.setTime(1, makeTime(1, 2, 3));
        assertEquals(1, ps.executeUpdate());

        ps.setTime(1, makeTime(23, 59, 59));
        assertEquals(1, ps.executeUpdate());

        ps.setObject(1, java.sql.Time.valueOf("12:00:00"), java.sql.Types.TIME);
        assertEquals(1, ps.executeUpdate());

        ps.setObject(1, java.sql.Time.valueOf("05:15:21"), java.sql.Types.TIME);
        assertEquals(1, ps.executeUpdate());

        ps.setObject(1, java.sql.Time.valueOf("16:21:51"), java.sql.Types.TIME);
        assertEquals(1, ps.executeUpdate());

        ps.setObject(1, java.sql.Time.valueOf("12:15:12"), java.sql.Types.TIME);
        assertEquals(1, ps.executeUpdate());

        ps.setObject(1, "22:12:1", java.sql.Types.TIME);
        assertEquals(1, ps.executeUpdate());

        ps.setObject(1, "8:46:44", java.sql.Types.TIME);
        assertEquals(1, ps.executeUpdate());

        ps.setObject(1, "5:1:2-03", java.sql.Types.TIME);
        assertEquals(1, ps.executeUpdate());

        ps.setObject(1, "23:59:59+11", java.sql.Types.TIME);
        assertEquals(1, ps.executeUpdate());

        // Need to let the test know this one has extra test cases.
        testSetTime = true;
        // Fall through helper
        timeTest();
        testSetTime = false;

        assertEquals(10, stmt.executeUpdate("DELETE FROM testtime"));
        stmt.close();
        ps.close();
    }

    /*
     * Helper for the TimeTests. It tests what should be in the db
     */
    private void timeTest() throws SQLException
    {
        Statement st = con.createStatement();
        ResultSet rs;
        java.sql.Time t;

        rs = st.executeQuery(TestUtil.selectSQL("testtime", "tm"));
        assertNotNull(rs);

        assertTrue(rs.next());
        t = rs.getTime(1);
        assertNotNull(t);
        assertEquals(makeTime(1, 2, 3), t);

        assertTrue(rs.next());
        t = rs.getTime(1);
        assertNotNull(t);
        assertEquals(makeTime(23, 59, 59), t);

        assertTrue(rs.next());
        t = rs.getTime(1);
        assertNotNull(t);
        assertEquals(makeTime(12, 0, 0), t);

        assertTrue(rs.next());
        t = rs.getTime(1);
        assertNotNull(t);
        assertEquals(makeTime(5, 15, 21), t);

        assertTrue(rs.next());
        t = rs.getTime(1);
        assertNotNull(t);
        assertEquals(makeTime(16, 21, 51), t);

        assertTrue(rs.next());
        t = rs.getTime(1);
        assertNotNull(t);
        assertEquals(makeTime(12, 15, 12), t);

        assertTrue(rs.next());
        t = rs.getTime(1);
        assertNotNull(t);
        assertEquals(makeTime(22, 12, 1), t);

        assertTrue(rs.next());
        t = rs.getTime(1);
        assertNotNull(t);
        assertEquals(makeTime(8, 46, 44), t);

        // If we're checking for timezones.
        if (testSetTime)
        {
            assertTrue(rs.next());
            t = rs.getTime(1);
            assertNotNull(t);
            java.sql.Time tmpTime = java.sql.Time.valueOf("5:1:2");
            int localoffset=java.util.Calendar.getInstance().getTimeZone().getOffset(tmpTime.getTime());
            int Timeoffset = 3 * 60 * 60 * 1000;
            tmpTime.setTime(tmpTime.getTime() + Timeoffset + localoffset);
            assertEquals(makeTime(tmpTime.getHours(), tmpTime.getMinutes(), tmpTime.getSeconds()), t);

            assertTrue(rs.next());
            t = rs.getTime(1);
            assertNotNull(t);
            tmpTime = java.sql.Time.valueOf("23:59:59");
            localoffset=java.util.Calendar.getInstance().getTimeZone().getOffset(tmpTime.getTime());
            Timeoffset = -11 * 60 * 60 * 1000;
            tmpTime.setTime(tmpTime.getTime() + Timeoffset + localoffset);
            assertEquals(makeTime(tmpTime.getHours(), tmpTime.getMinutes(), tmpTime.getSeconds()), t);
        }

        assertTrue(! rs.next());

        rs.close();
    }

    private java.sql.Time makeTime(int h, int m, int s)
    {
        return java.sql.Time.valueOf(TestUtil.fix(h, 2) + ":" +
                                     TestUtil.fix(m, 2) + ":" +
                                     TestUtil.fix(s, 2));
    }
}
