/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import legacy.org.postgresql.TestUtil;
import junit.framework.TestCase;

import java.sql.*;
import java.util.TimeZone;
import java.util.Calendar;

/*
 * Tests for time and date types with calendars involved.
 * TimestampTest was melting my brain, so I started afresh. -O
 *
 * Conversions that this code tests:
 *
 *   setTimestamp -> timestamp, timestamptz, date, time, timetz
 *   setDate      -> timestamp, timestamptz, date
 *   setTime      -> time, timetz
 *
 *   getTimestamp <- timestamp, timestamptz, date, time, timetz
 *   getDate      <- timestamp, timestamptz, date
 *   getTime      <- timestamp, timestamptz, time, timetz
 *
 * (this matches what we must support per JDBC 3.0, tables B-5 and B-6)
 */
public class TimezoneTest extends TestCase
{
    private Connection con;
    private TimeZone saveTZ;

    //
    // We set up everything in different timezones to try to exercise many cases:
    //
    // default JVM timezone: GMT+0100
    // server timezone:      GMT+0300
    // test timezones:       GMT+0000 GMT+0100 GMT+0300 GMT+1300 GMT-0500

    private Calendar cUTC;
    private Calendar cGMT03;
    private Calendar cGMT05;
    private Calendar cGMT13;

    private boolean min73;
    private boolean min74;

    public TimezoneTest(String name)
    {
        super(name);

        TimeZone UTC   = TimeZone.getTimeZone("UTC");    // +0000 always
        TimeZone GMT03 = TimeZone.getTimeZone("GMT+03"); // +0300 always
        TimeZone GMT05 = TimeZone.getTimeZone("GMT-05"); // -0500 always
        TimeZone GMT13 = TimeZone.getTimeZone("GMT+13"); // +1000 always

        cUTC   = Calendar.getInstance(UTC);
        cGMT03 = Calendar.getInstance(GMT03);
        cGMT05 = Calendar.getInstance(GMT05);
        cGMT13 = Calendar.getInstance(GMT13);
    }

    protected void setUp() throws Exception
    {
        // We must change the default TZ before establishing the connection.
        saveTZ = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+01")); // Arbitary timezone that doesn't match our test timezones

        con = TestUtil.openDB();
        TestUtil.createTable(con, "testtimezone",
                             "seq int4, tstz timestamp with time zone, ts timestamp without time zone, t time without time zone, tz time with time zone, d date");
        
        // This is not obvious, but the "gmt-3" timezone is actually 3 hours *ahead* of GMT
        // so will produce +03 timestamptz output
        con.createStatement().executeUpdate("set timezone = 'gmt-3'");
        
        min73 = TestUtil.haveMinimumServerVersion(con, "7.3");
        min74 = TestUtil.haveMinimumServerVersion(con, "7.4");
        
        //System.err.println("++++++ TESTS START (" + getName() + ") ++++++");
    }
    
    protected void tearDown() throws Exception
    {
        //System.err.println("++++++ TESTS END (" + getName() + ") ++++++");
        TimeZone.setDefault(saveTZ);

        TestUtil.dropTable(con, "testtimezone");
        TestUtil.closeDB(con);
    }

    public void testGetTimestamp() throws Exception
    {
        con.createStatement().executeUpdate("INSERT INTO testtimezone(tstz,ts,t,tz,d) VALUES('2005-01-01 15:00:00 +0300', '2005-01-01 15:00:00', '15:00:00', '15:00:00 +0300', '2005-01-01')");

        ResultSet rs = con.createStatement().executeQuery("SELECT tstz,ts,t,tz,d from testtimezone");

        assertTrue(rs.next());
        assertEquals("2005-01-01 15:00:00+03", rs.getString(1));
        assertEquals("2005-01-01 15:00:00", rs.getString(2));
        assertEquals("15:00:00", rs.getString(3));
        assertEquals("15:00:00+03", rs.getString(4));
        assertEquals("2005-01-01", rs.getString(5));

        Timestamp ts;
        
        // timestamptz: 2005-01-01 15:00:00+03
        ts = rs.getTimestamp(1);                      // Represents an instant in time, timezone is irrelevant.
        assertEquals(1104580800000L, ts.getTime());   // 2005-01-01 12:00:00 UTC
        ts = rs.getTimestamp(1, cUTC);                // Represents an instant in time, timezone is irrelevant.
        assertEquals(1104580800000L, ts.getTime());   // 2005-01-01 12:00:00 UTC
        ts = rs.getTimestamp(1, cGMT03);              // Represents an instant in time, timezone is irrelevant.
        assertEquals(1104580800000L, ts.getTime());   // 2005-01-01 12:00:00 UTC
        ts = rs.getTimestamp(1, cGMT05);              // Represents an instant in time, timezone is irrelevant.
        assertEquals(1104580800000L, ts.getTime());   // 2005-01-01 12:00:00 UTC
        ts = rs.getTimestamp(1, cGMT13);              // Represents an instant in time, timezone is irrelevant.
        assertEquals(1104580800000L, ts.getTime());   // 2005-01-01 12:00:00 UTC

        // timestamp: 2005-01-01 15:00:00
        ts = rs.getTimestamp(2);                     // Convert timestamp to +0100
        assertEquals(1104588000000L, ts.getTime());  // 2005-01-01 15:00:00 +0100
        ts = rs.getTimestamp(2, cUTC);               // Convert timestamp to UTC
        assertEquals(1104591600000L, ts.getTime());  // 2005-01-01 15:00:00 +0000
        ts = rs.getTimestamp(2, cGMT03);             // Convert timestamp to +0300
        assertEquals(1104580800000L, ts.getTime());  // 2005-01-01 15:00:00 +0300
        ts = rs.getTimestamp(2, cGMT05);             // Convert timestamp to -0500
        assertEquals(1104609600000L, ts.getTime());  // 2005-01-01 15:00:00 -0500
        ts = rs.getTimestamp(2, cGMT13);             // Convert timestamp to +1300
        assertEquals(1104544800000L, ts.getTime());  // 2005-01-01 15:00:00 +1300

        // time: 15:00:00
        ts = rs.getTimestamp(3);
        assertEquals(50400000L, ts.getTime());        // 1970-01-01 15:00:00 +0100
        ts = rs.getTimestamp(3, cUTC);
        assertEquals(54000000L, ts.getTime());        // 1970-01-01 15:00:00 +0000
        ts = rs.getTimestamp(3, cGMT03);
        assertEquals(43200000L, ts.getTime());        // 1970-01-01 15:00:00 +0300
        ts = rs.getTimestamp(3, cGMT05);
        assertEquals(72000000L, ts.getTime());        // 1970-01-01 15:00:00 -0500
        ts = rs.getTimestamp(3, cGMT13);
        assertEquals(7200000L, ts.getTime());         // 1970-01-01 15:00:00 +1300

        // timetz: 15:00:00+03
        ts = rs.getTimestamp(4);
        assertEquals(43200000L, ts.getTime());        // 1970-01-01 15:00:00 +0300 -> 1970-01-01 13:00:00 +0100
        ts = rs.getTimestamp(4, cUTC);
        assertEquals(43200000L, ts.getTime());        // 1970-01-01 15:00:00 +0300 -> 1970-01-01 12:00:00 +0000
        ts = rs.getTimestamp(4, cGMT03);
        assertEquals(43200000L, ts.getTime());        // 1970-01-01 15:00:00 +0300 -> 1970-01-01 15:00:00 +0300
        ts = rs.getTimestamp(4, cGMT05);
        assertEquals(43200000L, ts.getTime());        // 1970-01-01 15:00:00 +0300 -> 1970-01-01 07:00:00 -0500
        ts = rs.getTimestamp(4, cGMT13);
        assertEquals(43200000L, ts.getTime());        // 1970-01-01 15:00:00 +0300 -> 1970-01-02 01:00:00 +1300 (CHECK ME)

        // date: 2005-01-01
        ts = rs.getTimestamp(5);
        assertEquals(1104534000000L, ts.getTime());        // 2005-01-01 00:00:00 +0100
        ts = rs.getTimestamp(5, cUTC);
        assertEquals(1104537600000L, ts.getTime());        // 2005-01-01 00:00:00 +0000
        ts = rs.getTimestamp(5, cGMT03);
        assertEquals(1104526800000L, ts.getTime());        // 2005-01-01 00:00:00 +0300
        ts = rs.getTimestamp(5, cGMT05);
        assertEquals(1104555600000L, ts.getTime());        // 2005-01-01 00:00:00 -0500
        ts = rs.getTimestamp(5, cGMT13);
        assertEquals(1104490800000L, ts.getTime());        // 2005-01-01 00:00:00 +1300

        assertTrue(!rs.next());
    }

    public void testGetDate() throws Exception
    {
        con.createStatement().executeUpdate("INSERT INTO testtimezone(tstz,ts,d) VALUES('2005-01-01 15:00:00 +0300', '2005-01-01 15:00:00', '2005-01-01')");

        ResultSet rs = con.createStatement().executeQuery("SELECT tstz,ts,d from testtimezone");

        assertTrue(rs.next());
        assertEquals("2005-01-01 15:00:00+03", rs.getString(1));
        assertEquals("2005-01-01 15:00:00", rs.getString(2));
        assertEquals("2005-01-01", rs.getString(3));

        Date d;

        // timestamptz: 2005-01-01 15:00:00+03
        d = rs.getDate(1);                           // 2005-01-01 13:00:00 +0100 -> 2005-01-01 00:00:00 +0100
        assertEquals(1104534000000L, d.getTime());
        d = rs.getDate(1, cUTC);                     // 2005-01-01 12:00:00 +0000 -> 2005-01-01 00:00:00 +0000
        assertEquals(1104537600000L, d.getTime());
        d = rs.getDate(1, cGMT03);                   // 2005-01-01 15:00:00 +0300 -> 2005-01-01 00:00:00 +0300
        assertEquals(1104526800000L, d.getTime());
        d = rs.getDate(1, cGMT05);                   // 2005-01-01 07:00:00 -0500 -> 2005-01-01 00:00:00 -0500
        assertEquals(1104555600000L, d.getTime());
        d = rs.getDate(1, cGMT13);                   // 2005-01-02 01:00:00 +1300 -> 2005-01-02 00:00:00 +1300
        assertEquals(1104577200000L, d.getTime());

        // timestamp: 2005-01-01 15:00:00
        d = rs.getDate(2);                           // 2005-01-01 00:00:00 +0100
        assertEquals(1104534000000L, d.getTime());
        d = rs.getDate(2, cUTC);                     // 2005-01-01 00:00:00 +0000
        assertEquals(1104537600000L, d.getTime());
        d = rs.getDate(2, cGMT03);                   // 2005-01-01 00:00:00 +0300
        assertEquals(1104526800000L, d.getTime());
        d = rs.getDate(2, cGMT05);                   // 2005-01-01 00:00:00 -0500
        assertEquals(1104555600000L, d.getTime());
        d = rs.getDate(2, cGMT13);                   // 2005-01-01 00:00:00 +1300
        assertEquals(1104490800000L, d.getTime());

        // date: 2005-01-01
        d = rs.getDate(3);                           // 2005-01-01 00:00:00 +0100
        assertEquals(1104534000000L, d.getTime());
        d = rs.getDate(3, cUTC);                     // 2005-01-01 00:00:00 +0000
        assertEquals(1104537600000L, d.getTime());
        d = rs.getDate(3, cGMT03);                   // 2005-01-01 00:00:00 +0300
        assertEquals(1104526800000L, d.getTime());
        d = rs.getDate(3, cGMT05);                   // 2005-01-01 00:00:00 -0500
        assertEquals(1104555600000L, d.getTime());
        d = rs.getDate(3, cGMT13);                   // 2005-01-01 00:00:00 +1300
        assertEquals(1104490800000L, d.getTime());

        assertTrue(!rs.next());
    }

    public void testGetTime() throws Exception
    {

        con.createStatement().executeUpdate("INSERT INTO testtimezone(tstz,ts,t,tz) VALUES('2005-01-01 15:00:00 +0300', '2005-01-01 15:00:00', '15:00:00', '15:00:00 +0300')");

        ResultSet rs = con.createStatement().executeQuery("SELECT tstz,ts,t,tz from testtimezone");

        assertTrue(rs.next());
        assertEquals("2005-01-01 15:00:00+03", rs.getString(1));
        assertEquals("2005-01-01 15:00:00", rs.getString(2));
        assertEquals("15:00:00", rs.getString(3));
        assertEquals("15:00:00+03", rs.getString(4));

        Time t;

        // timestamptz: 2005-01-01 15:00:00+03
        t = rs.getTime(1);
        assertEquals(43200000L, t.getTime());        // 2005-01-01 13:00:00 +0100 -> 1970-01-01 13:00:00 +0100
        t = rs.getTime(1, cUTC);
        assertEquals(43200000L, t.getTime());        // 2005-01-01 12:00:00 +0000 -> 1970-01-01 12:00:00 +0000
        t = rs.getTime(1, cGMT03);
        assertEquals(43200000L, t.getTime());        // 2005-01-01 15:00:00 +0300 -> 1970-01-01 15:00:00 +0300
        t = rs.getTime(1, cGMT05);
        assertEquals(43200000L, t.getTime());        // 2005-01-01 07:00:00 -0500 -> 1970-01-01 07:00:00 -0500
        t = rs.getTime(1, cGMT13);
        assertEquals(-43200000L, t.getTime());       // 2005-01-02 01:00:00 +1300 -> 1970-01-01 01:00:00 +1300

        // timestamp: 2005-01-01 15:00:00
        t = rs.getTime(2);
        assertEquals(50400000L, t.getTime());        // 1970-01-01 15:00:00 +0100
        t = rs.getTime(2, cUTC);
        assertEquals(54000000L, t.getTime());        // 1970-01-01 15:00:00 +0000
        t = rs.getTime(2, cGMT03);
        assertEquals(43200000L, t.getTime());        // 1970-01-01 15:00:00 +0300
        t = rs.getTime(2, cGMT05);
        assertEquals(72000000L, t.getTime());        // 1970-01-01 15:00:00 -0500
        t = rs.getTime(2, cGMT13);
        assertEquals(7200000L, t.getTime());         // 1970-01-01 15:00:00 +1300

        // time: 15:00:00
        t = rs.getTime(3);
        assertEquals(50400000L, t.getTime());        // 1970-01-01 15:00:00 +0100
        t = rs.getTime(3, cUTC);
        assertEquals(54000000L, t.getTime());        // 1970-01-01 15:00:00 +0000
        t = rs.getTime(3, cGMT03);
        assertEquals(43200000L, t.getTime());        // 1970-01-01 15:00:00 +0300
        t = rs.getTime(3, cGMT05);
        assertEquals(72000000L, t.getTime());        // 1970-01-01 15:00:00 -0500
        t = rs.getTime(3, cGMT13);
        assertEquals(7200000L, t.getTime());         // 1970-01-01 15:00:00 +1300

        // timetz: 15:00:00+03
        t = rs.getTime(4);
        assertEquals(43200000L, t.getTime());        // 1970-01-01 13:00:00 +0100
        t = rs.getTime(4, cUTC);
        assertEquals(43200000L, t.getTime());        // 1970-01-01 12:00:00 +0000
        t = rs.getTime(4, cGMT03);
        assertEquals(43200000L, t.getTime());        // 1970-01-01 15:00:00 +0300
        t = rs.getTime(4, cGMT05);
        assertEquals(43200000L, t.getTime());        // 1970-01-01 07:00:00 -0500
        t = rs.getTime(4, cGMT13);
        assertEquals(43200000L, t.getTime());        // 1970-01-01 01:00:00 +1300
    }

    /**
     * This test is broken off from testSetTimestamp because it does not work
     * for pre-7.4 servers and putting tons of conditionals in that test makes
     * it largely unreadable.  The time data type does not accept timestamp
     * with time zone style input on these servers.
     */
    public void testSetTimestampOnTime() throws Exception
    {
        // Pre-7.4 servers cannot convert timestamps with timezones to times.
        if (!min74)
                return;

        PreparedStatement insertTimestamp = con.prepareStatement("INSERT INTO testtimezone(seq,t) VALUES (?,?)");
        int seq = 1;

        Timestamp instant = new Timestamp(1104580800000L); // 2005-01-01 12:00:00 UTC

        // +0100 (JVM default)
        insertTimestamp.setInt(1, seq++);
        insertTimestamp.setTimestamp(2,instant);  // 13:00:00
        insertTimestamp.executeUpdate();

        // UTC
        insertTimestamp.setInt(1, seq++);
        insertTimestamp.setTimestamp(2,instant,cUTC); // 12:00:00
        insertTimestamp.executeUpdate();

        // +0300
        insertTimestamp.setInt(1, seq++);
        insertTimestamp.setTimestamp(2,instant,cGMT03); // 15:00:00
        insertTimestamp.executeUpdate();

        // -0500
        insertTimestamp.setInt(1, seq++);
        insertTimestamp.setTimestamp(2,instant,cGMT05); // 07:00:00
        insertTimestamp.executeUpdate();

        if (min73) {
            // +1300
            insertTimestamp.setInt(1, seq++);
            insertTimestamp.setTimestamp(2,instant,cGMT13); // 01:00:00
            insertTimestamp.executeUpdate();
        }

        seq = 1;
        ResultSet rs = con.createStatement().executeQuery("SELECT seq,t FROM testtimezone ORDER BY seq");

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("13:00:00", rs.getString(2));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("12:00:00", rs.getString(2));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("15:00:00", rs.getString(2));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("07:00:00", rs.getString(2));
        
        if (min73) {
            assertTrue(rs.next());
            assertEquals(seq++, rs.getInt(1));
            assertEquals("01:00:00", rs.getString(2));
        }

        assertTrue(!rs.next());
    }


    public void testSetTimestamp() throws Exception
    {
        PreparedStatement insertTimestamp = con.prepareStatement("INSERT INTO testtimezone(seq,tstz,ts,tz,d) VALUES (?,?,?,?,?)");
        int seq = 1;

        Timestamp instant = new Timestamp(1104580800000L); // 2005-01-01 12:00:00 UTC

        // +0100 (JVM default)
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTimestamp(2,instant);  // 2005-01-01 13:00:00 +0100
        insertTimestamp.setTimestamp(3,instant);  // 2005-01-01 13:00:00
        insertTimestamp.setTimestamp(4,instant);  // 13:00:00 +0100
        insertTimestamp.setTimestamp(5,instant);  // 2005-01-01
        insertTimestamp.executeUpdate();

        // UTC
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTimestamp(2,instant,cUTC); // 2005-01-01 12:00:00 +0000
        insertTimestamp.setTimestamp(3,instant,cUTC); // 2005-01-01 12:00:00
        insertTimestamp.setTimestamp(4,instant,cUTC); // 12:00:00 +0000
        insertTimestamp.setTimestamp(5,instant,cUTC); // 2005-01-01
        insertTimestamp.executeUpdate();

        // +0300
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTimestamp(2,instant,cGMT03); // 2005-01-01 15:00:00 +0300
        insertTimestamp.setTimestamp(3,instant,cGMT03); // 2005-01-01 15:00:00
        insertTimestamp.setTimestamp(4,instant,cGMT03); // 15:00:00 +0300
        insertTimestamp.setTimestamp(5,instant,cGMT03); // 2005-01-01
        insertTimestamp.executeUpdate();

        // -0500
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTimestamp(2,instant,cGMT05); // 2005-01-01 07:00:00 -0500
        insertTimestamp.setTimestamp(3,instant,cGMT05); // 2005-01-01 07:00:00
        insertTimestamp.setTimestamp(4,instant,cGMT05); // 07:00:00 -0500
        insertTimestamp.setTimestamp(5,instant,cGMT05); // 2005-01-01
        insertTimestamp.executeUpdate();

        if (min73) {
            // +1300
            insertTimestamp.setInt(1,seq++);
            insertTimestamp.setTimestamp(2,instant,cGMT13); // 2005-01-02 01:00:00 +1300
            insertTimestamp.setTimestamp(3,instant,cGMT13); // 2005-01-02 01:00:00
            insertTimestamp.setTimestamp(4,instant,cGMT13); // 01:00:00 +1300
            insertTimestamp.setTimestamp(5,instant,cGMT13); // 2005-01-02
            insertTimestamp.executeUpdate();
        }

        insertTimestamp.close();

        //
        // check results
        //

        seq = 1;
        ResultSet rs = con.createStatement().executeQuery("SELECT seq,tstz,ts,tz,d FROM testtimezone ORDER BY seq");

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("2005-01-01 15:00:00+03", rs.getString(2));
        assertEquals("2005-01-01 13:00:00", rs.getString(3));
        assertEquals("13:00:00+01", rs.getString(4));
        assertEquals("2005-01-01", rs.getString(5));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("2005-01-01 15:00:00+03", rs.getString(2));
        assertEquals("2005-01-01 12:00:00", rs.getString(3));
        assertEquals("12:00:00+00", rs.getString(4));
        assertEquals("2005-01-01", rs.getString(5));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("2005-01-01 15:00:00+03", rs.getString(2));
        assertEquals("2005-01-01 15:00:00", rs.getString(3));
        assertEquals("15:00:00+03", rs.getString(4));
        assertEquals("2005-01-01", rs.getString(5));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("2005-01-01 15:00:00+03", rs.getString(2));
        assertEquals("2005-01-01 07:00:00", rs.getString(3));
        assertEquals("07:00:00-05", rs.getString(4));
        assertEquals("2005-01-01", rs.getString(5));

        if (min73) {
            assertTrue(rs.next());
            assertEquals(seq++, rs.getInt(1));
            assertEquals("2005-01-01 15:00:00+03", rs.getString(2));
            assertEquals("2005-01-02 01:00:00", rs.getString(3));
            assertEquals("01:00:00+13", rs.getString(4));
            assertEquals("2005-01-02", rs.getString(5));
        }

        assertTrue(!rs.next());
    }

    public void testSetDate() throws Exception
    {
        PreparedStatement insertTimestamp = con.prepareStatement("INSERT INTO testtimezone(seq,tstz,ts,d) VALUES (?,?,?,?)");

        int seq = 1;
        
        Date d;

        // +0100 (JVM default)
        d = new Date(1104534000000L);    // 2005-01-01 00:00:00 +0100
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setDate(2,d);    // 2005-01-01 00:00:00 +0100
        insertTimestamp.setDate(3,d);    // 2005-01-01 00:00:00
        insertTimestamp.setDate(4,d);    // 2005-01-01
        insertTimestamp.executeUpdate();

        // UTC
        d = new Date(1104537600000L);      // 2005-01-01 00:00:00 +0000
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setDate(2,d,cUTC); // 2005-01-01 00:00:00 +0000
        insertTimestamp.setDate(3,d,cUTC); // 2005-01-01 00:00:00
        insertTimestamp.setDate(4,d,cUTC); // 2005-01-01
        insertTimestamp.executeUpdate();

        // +0300
        d = new Date(1104526800000L);        // 2005-01-01 00:00:00 +0300
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setDate(2,d,cGMT03); // 2005-01-01 00:00:00 +0300
        insertTimestamp.setDate(3,d,cGMT03); // 2005-01-01 00:00:00
        insertTimestamp.setDate(4,d,cGMT03); // 2005-01-01
        insertTimestamp.executeUpdate();

        // -0500
        d = new Date(1104555600000L);        // 2005-01-01 00:00:00 -0500
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setDate(2,d,cGMT05); // 2005-01-01 00:00:00 -0500
        insertTimestamp.setDate(3,d,cGMT05); // 2005-01-01 00:00:00
        insertTimestamp.setDate(4,d,cGMT05); // 2005-01-01
        insertTimestamp.executeUpdate();

        if (min73) {
            // +1300
            d = new Date(1104490800000L);        // 2005-01-01 00:00:00 +1300
            insertTimestamp.setInt(1,seq++);
            insertTimestamp.setDate(2,d,cGMT13); // 2005-01-01 00:00:00 +1300
            insertTimestamp.setDate(3,d,cGMT13); // 2005-01-01 00:00:00
            insertTimestamp.setDate(4,d,cGMT13); // 2005-01-01
            insertTimestamp.executeUpdate();
        }
        
        insertTimestamp.close();

        //
        // check results
        //

        seq = 1;
        ResultSet rs = con.createStatement().executeQuery("SELECT seq,tstz,ts,d FROM testtimezone ORDER BY seq");

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("2005-01-01 02:00:00+03", rs.getString(2));
        assertEquals("2005-01-01 00:00:00", rs.getString(3));
        assertEquals("2005-01-01", rs.getString(4));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("2005-01-01 03:00:00+03", rs.getString(2));
        assertEquals("2005-01-01 00:00:00", rs.getString(3));
        assertEquals("2005-01-01", rs.getString(4));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("2005-01-01 00:00:00+03", rs.getString(2));
        assertEquals("2005-01-01 00:00:00", rs.getString(3));
        assertEquals("2005-01-01", rs.getString(4));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("2005-01-01 08:00:00+03", rs.getString(2));
        assertEquals("2005-01-01 00:00:00", rs.getString(3));
        assertEquals("2005-01-01", rs.getString(4));

        if (min73) {
            assertTrue(rs.next());
            assertEquals(seq++, rs.getInt(1));
            assertEquals("2004-12-31 14:00:00+03", rs.getString(2));
            assertEquals("2005-01-01 00:00:00", rs.getString(3));
            assertEquals("2005-01-01", rs.getString(4));
        }

        assertTrue(!rs.next());        
    }

    public void testSetTime() throws Exception
    {
        if (!min74) {
            // We can't do timezones properly for time/timetz values before 7.4
            System.err.println("Skipping TimezoneTest.testSetTime on a pre-7.4 server");
            return;
        }

        PreparedStatement insertTimestamp = con.prepareStatement("INSERT INTO testtimezone(seq,t,tz) VALUES (?,?,?)");

        int seq = 1;
        
        Time t;

        // +0100 (JVM default)
        t = new Time(50400000L);         // 1970-01-01 15:00:00 +0100
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTime(2,t);    // 15:00:00
        insertTimestamp.setTime(3,t);    // 15:00:00+03
        insertTimestamp.executeUpdate();

        // UTC
        t = new Time(54000000L);           // 1970-01-01 15:00:00 +0000
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTime(2,t,cUTC); // 15:00:00
        insertTimestamp.setTime(3,t,cUTC); // 15:00:00+00
        insertTimestamp.executeUpdate();

        // +0300
        t = new Time(43200000L);             // 1970-01-01 15:00:00 +0300
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTime(2,t,cGMT03); // 15:00:00
        insertTimestamp.setTime(3,t,cGMT03); // 15:00:00+03
        insertTimestamp.executeUpdate();

        // -0500
        t = new Time(72000000L);             // 1970-01-01 15:00:00 -0500
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTime(2,t,cGMT05); // 15:00:00
        insertTimestamp.setTime(3,t,cGMT05); // 15:00:00-05
        insertTimestamp.executeUpdate();

        // +1300
        t = new Time(7200000L);              // 1970-01-01 15:00:00 +1300
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTime(2,t,cGMT13); // 15:00:00
        insertTimestamp.setTime(3,t,cGMT13); // 15:00:00+13
        insertTimestamp.executeUpdate();
        
        insertTimestamp.close();

        //
        // check results
        //

        seq = 1;
        ResultSet rs = con.createStatement().executeQuery("SELECT seq,t,tz FROM testtimezone ORDER BY seq");

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("15:00:00", rs.getString(2));
        assertEquals("15:00:00+01", rs.getString(3));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("15:00:00", rs.getString(2));
        assertEquals("15:00:00+00", rs.getString(3));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("15:00:00", rs.getString(2));
        assertEquals("15:00:00+03", rs.getString(3));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("15:00:00", rs.getString(2));
        assertEquals("15:00:00-05", rs.getString(3));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals("15:00:00", rs.getString(2));
        assertEquals("15:00:00+13", rs.getString(3));

        assertTrue(!rs.next());        
    }

    public void testHalfHourTimezone() throws Exception
    {
        Statement stmt = con.createStatement();
        stmt.execute("SET TimeZone = 'GMT+3:30'");
        ResultSet rs = stmt.executeQuery("SELECT '1969-12-31 20:30:00'::timestamptz");
        assertTrue(rs.next());
        assertEquals(0L, rs.getTimestamp(1).getTime());
    }

    public void testTimezoneWithSeconds() throws SQLException
    {
        if (!TestUtil.haveMinimumServerVersion(con, "8.2"))
            return;

        Statement stmt = con.createStatement();
        stmt.execute("SET TimeZone = 'Europe/Helsinki'");
        ResultSet rs = stmt.executeQuery("SELECT '1920-01-01'::timestamptz");
        rs.next();
        // select extract(epoch from '1920-01-01'::timestamptz - 'epoch'::timestamptz) * 1000;

        assertEquals(-1577929189000L, rs.getTimestamp(1).getTime());
    }

}
