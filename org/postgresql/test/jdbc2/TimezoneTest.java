/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
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
    private static final int DAY = 24 * 3600 * 1000;
    private Connection con;
    private static final TimeZone saveTZ = TimeZone.getDefault();;

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
        checkDatabaseContents("SELECT tstz::text,ts::text,t::text,tz::text,d::text from testtimezone",
                              new String[]{"2005-01-01 12:00:00+00",
                                           "2005-01-01 15:00:00",
                                           "15:00:00",
                                           "15:00:00+03",
                                           "2005-01-01"});

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
        checkDatabaseContents("SELECT tstz::text,ts::text,d::text from testtimezone",
                              new String[]{"2005-01-01 12:00:00+00",
                                           "2005-01-01 15:00:00",
                                           "2005-01-01"});

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
        checkDatabaseContents("SELECT tstz::text,ts::text,t::text,tz::text,d::text from testtimezone",
                new String[]{"2005-01-01 12:00:00+00",
                             "2005-01-01 15:00:00",
                             "15:00:00",
                             "15:00:00+03"});

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
        Timestamp instantTime = new Timestamp(instant.getTime() % DAY);

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

        checkDatabaseContents("SELECT seq::text,t::text from testtimezone ORDER BY seq",
                new String[][]{new String[]{"1", "13:00:00"},
                               new String[]{"2", "12:00:00"},
                               new String[]{"3", "15:00:00"},
                               new String[]{"4", "07:00:00"},
                               new String[]{"5", "01:00:00"}});

        seq = 1;
        ResultSet rs = con.createStatement().executeQuery("SELECT seq,t FROM testtimezone ORDER BY seq");

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(instantTime, rs.getTimestamp(2));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(instantTime, rs.getTimestamp(2, cUTC));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(instantTime, rs.getTimestamp(2, cGMT03));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(instantTime, rs.getTimestamp(2, cGMT05));
        
        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(normalizeTimeOfDayPart(instantTime, cGMT13), rs.getTimestamp(2, cGMT13));

        assertTrue(!rs.next());
    }


    public void testSetTimestamp() throws Exception
    {
        PreparedStatement insertTimestamp = con.prepareStatement("INSERT INTO testtimezone(seq,tstz,ts,tz,d) VALUES (?,?,?,?,?)");
        int seq = 1;

        Timestamp instant = new Timestamp(1104580800000L); // 2005-01-01 12:00:00 UTC
        Timestamp instantTime = new Timestamp(instant.getTime() % DAY);
        Timestamp instantDateJVM = new Timestamp(instant.getTime() - (instant.getTime() % DAY) - TimeZone.getDefault().getRawOffset());
        Timestamp instantDateUTC = new Timestamp(instant.getTime() - (instant.getTime() % DAY) - cUTC.getTimeZone().getRawOffset());
        Timestamp instantDateGMT03 = new Timestamp(instant.getTime() - (instant.getTime() % DAY) - cGMT03.getTimeZone().getRawOffset());
        Timestamp instantDateGMT05 = new Timestamp(instant.getTime() - (instant.getTime() % DAY) - cGMT05.getTimeZone().getRawOffset());
        Timestamp instantDateGMT13 = new Timestamp(instant.getTime() - (instant.getTime() % DAY) - cGMT13.getTimeZone().getRawOffset() + DAY);

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

        // check that insert went correctly by parsing the raw contents in UTC
        checkDatabaseContents("SELECT seq::text,tstz::text,ts::text,tz::text,d::text from testtimezone ORDER BY seq",
                new String[][]{new String[]{"1", "2005-01-01 12:00:00+00", "2005-01-01 13:00:00", "13:00:00+01", "2005-01-01"},
                               new String[]{"2", "2005-01-01 12:00:00+00", "2005-01-01 12:00:00", "12:00:00+00", "2005-01-01"},
                               new String[]{"3", "2005-01-01 12:00:00+00", "2005-01-01 15:00:00", "15:00:00+03", "2005-01-01"},
                               new String[]{"4", "2005-01-01 12:00:00+00", "2005-01-01 07:00:00", "07:00:00-05", "2005-01-01"},
                               new String[]{"5", "2005-01-01 12:00:00+00", "2005-01-02 01:00:00", "01:00:00+13", "2005-01-02"}});
        
        //
        // check results
        //

        seq = 1;
        ResultSet rs = con.createStatement().executeQuery("SELECT seq,tstz,ts,tz,d FROM testtimezone ORDER BY seq");

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(instant, rs.getTimestamp(2));
        assertEquals(instant, rs.getTimestamp(3));
        assertEquals(instantTime, rs.getTimestamp(4));
        assertEquals(instantDateJVM, rs.getTimestamp(5));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(instant, rs.getTimestamp(2, cUTC));
        assertEquals(instant, rs.getTimestamp(3, cUTC));
        assertEquals(instantTime, rs.getTimestamp(4, cUTC));
        assertEquals(instantDateUTC, rs.getTimestamp(5, cUTC));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(instant, rs.getTimestamp(2, cGMT03));
        assertEquals(instant, rs.getTimestamp(3, cGMT03));
        assertEquals(instantTime, rs.getTimestamp(4, cGMT03));
        assertEquals(instantDateGMT03, rs.getTimestamp(5, cGMT03));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(instant, rs.getTimestamp(2, cGMT05));
        assertEquals(instant, rs.getTimestamp(3, cGMT05));
        assertEquals(instantTime, rs.getTimestamp(4, cGMT05));
        assertEquals(instantDateGMT05, rs.getTimestamp(5, cGMT05));

        if (min73) {
            assertTrue(rs.next());
            assertEquals(seq++, rs.getInt(1));
            assertEquals(instant, rs.getTimestamp(2, cGMT13));
            assertEquals(instant, rs.getTimestamp(3, cGMT13));
            assertEquals(normalizeTimeOfDayPart(instantTime, cGMT13), rs.getTimestamp(4, cGMT13));
            assertEquals(instantDateGMT13, rs.getTimestamp(5, cGMT13));
        }

        assertTrue(!rs.next());
    }

    public void testSetDate() throws Exception
    {
        PreparedStatement insertTimestamp = con.prepareStatement("INSERT INTO testtimezone(seq,tstz,ts,d) VALUES (?,?,?,?)");

        int seq = 1;
        
        Date dJVM, dUTC, dGMT03, dGMT05, dGMT13 = null;

        // +0100 (JVM default)
        dJVM = new Date(1104534000000L);    // 2005-01-01 00:00:00 +0100
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setDate(2,dJVM);    // 2005-01-01 00:00:00 +0100
        insertTimestamp.setDate(3,dJVM);    // 2005-01-01 00:00:00
        insertTimestamp.setDate(4,dJVM);    // 2005-01-01
        insertTimestamp.executeUpdate();

        // UTC
        dUTC = new Date(1104537600000L);      // 2005-01-01 00:00:00 +0000
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setDate(2,dUTC,cUTC); // 2005-01-01 00:00:00 +0000
        insertTimestamp.setDate(3,dUTC,cUTC); // 2005-01-01 00:00:00
        insertTimestamp.setDate(4,dUTC,cUTC); // 2005-01-01
        insertTimestamp.executeUpdate();

        // +0300
        dGMT03 = new Date(1104526800000L);        // 2005-01-01 00:00:00 +0300
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setDate(2,dGMT03,cGMT03); // 2005-01-01 00:00:00 +0300
        insertTimestamp.setDate(3,dGMT03,cGMT03); // 2005-01-01 00:00:00
        insertTimestamp.setDate(4,dGMT03,cGMT03); // 2005-01-01
        insertTimestamp.executeUpdate();

        // -0500
        dGMT05 = new Date(1104555600000L);        // 2005-01-01 00:00:00 -0500
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setDate(2,dGMT05,cGMT05); // 2005-01-01 00:00:00 -0500
        insertTimestamp.setDate(3,dGMT05,cGMT05); // 2005-01-01 00:00:00
        insertTimestamp.setDate(4,dGMT05,cGMT05); // 2005-01-01
        insertTimestamp.executeUpdate();

        if (min73) {
            // +1300
            dGMT13 = new Date(1104490800000L);        // 2005-01-01 00:00:00 +1300
            insertTimestamp.setInt(1,seq++);
            insertTimestamp.setDate(2,dGMT13,cGMT13); // 2005-01-01 00:00:00 +1300
            insertTimestamp.setDate(3,dGMT13,cGMT13); // 2005-01-01 00:00:00
            insertTimestamp.setDate(4,dGMT13,cGMT13); // 2005-01-01
            insertTimestamp.executeUpdate();
        }
        
        insertTimestamp.close();

        // check that insert went correctly by parsing the raw contents in UTC
        checkDatabaseContents("SELECT seq::text,tstz::text,ts::text,d::text from testtimezone ORDER BY seq",
                new String[][]{new String[]{"1", "2004-12-31 23:00:00+00", "2005-01-01 00:00:00", "2005-01-01"},
                               new String[]{"2", "2005-01-01 00:00:00+00", "2005-01-01 00:00:00", "2005-01-01"},
                               new String[]{"3", "2004-12-31 21:00:00+00", "2005-01-01 00:00:00", "2005-01-01"},
                               new String[]{"4", "2005-01-01 05:00:00+00", "2005-01-01 00:00:00", "2005-01-01"},
                               new String[]{"5", "2004-12-31 11:00:00+00", "2005-01-01 00:00:00", "2005-01-01"}});
        //
        // check results
        //

        seq = 1;
        ResultSet rs = con.createStatement().executeQuery("SELECT seq,tstz,ts,d FROM testtimezone ORDER BY seq");

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(dJVM, rs.getDate(2));
        assertEquals(dJVM, rs.getDate(3));
        assertEquals(dJVM, rs.getDate(4));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(dUTC, rs.getDate(2, cUTC));
        assertEquals(dUTC, rs.getDate(3, cUTC));
        assertEquals(dUTC, rs.getDate(4, cUTC));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(dGMT03, rs.getDate(2, cGMT03));
        assertEquals(dGMT03, rs.getDate(3, cGMT03));
        assertEquals(dGMT03, rs.getDate(4, cGMT03));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(dGMT05, rs.getDate(2, cGMT05));
        assertEquals(dGMT05, rs.getDate(3, cGMT05));
        assertEquals(dGMT05, rs.getDate(4, cGMT05));

        if (min73) {
            assertTrue(rs.next());
            assertEquals(seq++, rs.getInt(1));
            assertEquals(dGMT13, rs.getDate(2, cGMT13));
            assertEquals(dGMT13, rs.getDate(3, cGMT13));
            assertEquals(dGMT13, rs.getDate(4, cGMT13));
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
        
        Time tJVM, tUTC, tGMT03, tGMT05, tGMT13;

        // +0100 (JVM default)
        tJVM = new Time(50400000L);         // 1970-01-01 15:00:00 +0100
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTime(2,tJVM);    // 15:00:00
        insertTimestamp.setTime(3,tJVM);    // 15:00:00+03
        insertTimestamp.executeUpdate();

        // UTC
        tUTC = new Time(54000000L);           // 1970-01-01 15:00:00 +0000
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTime(2,tUTC,cUTC); // 15:00:00
        insertTimestamp.setTime(3,tUTC,cUTC); // 15:00:00+00
        insertTimestamp.executeUpdate();

        // +0300
        tGMT03 = new Time(43200000L);             // 1970-01-01 15:00:00 +0300
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTime(2,tGMT03,cGMT03); // 15:00:00
        insertTimestamp.setTime(3,tGMT03,cGMT03); // 15:00:00+03
        insertTimestamp.executeUpdate();

        // -0500
        tGMT05 = new Time(72000000L);             // 1970-01-01 15:00:00 -0500
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTime(2,tGMT05,cGMT05); // 15:00:00
        insertTimestamp.setTime(3,tGMT05,cGMT05); // 15:00:00-05
        insertTimestamp.executeUpdate();

        // +1300
        tGMT13 = new Time(7200000L);              // 1970-01-01 15:00:00 +1300
        insertTimestamp.setInt(1,seq++);
        insertTimestamp.setTime(2,tGMT13,cGMT13); // 15:00:00
        insertTimestamp.setTime(3,tGMT13,cGMT13); // 15:00:00+13
        insertTimestamp.executeUpdate();
        
        insertTimestamp.close();

        // check that insert went correctly by parsing the raw contents in UTC
        checkDatabaseContents("SELECT seq::text,t::text,tz::text from testtimezone ORDER BY seq",
                new String[][]{new String[]{"1", "15:00:00", "15:00:00+01",},
                               new String[]{"2", "15:00:00", "15:00:00+00",},
                               new String[]{"3", "15:00:00", "15:00:00+03",},
                               new String[]{"4", "15:00:00", "15:00:00-05",},
                               new String[]{"5", "15:00:00", "15:00:00+13",}});

        //
        // check results
        //

        seq = 1;
        ResultSet rs = con.createStatement().executeQuery("SELECT seq,t,tz FROM testtimezone ORDER BY seq");

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(tJVM, rs.getTime(2));
        assertEquals(tJVM, rs.getTime(3));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(tUTC, rs.getTime(2, cUTC));
        assertEquals(tUTC, rs.getTime(2, cUTC));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(tGMT03, rs.getTime(2, cGMT03));
        assertEquals(tGMT03, rs.getTime(2, cGMT03));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(tGMT05, rs.getTime(2, cGMT05));
        assertEquals(tGMT05, rs.getTime(2, cGMT05));

        assertTrue(rs.next());
        assertEquals(seq++, rs.getInt(1));
        assertEquals(tGMT13, rs.getTime(2, cGMT13));
        assertEquals(tGMT13, rs.getTime(2, cGMT13));

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

        assertEquals(-1577929192000L, rs.getTimestamp(1).getTime());
    }

    /**
     * Does a query in UTC time zone to database to check that the 
     * inserted values are correct.
     *
     * @param query The query to run.
     * @param correct The correct answers in UTC time zone as formatted by backend.
     */
    private void checkDatabaseContents(String query, String[] correct) throws Exception {
        checkDatabaseContents(query, new String[][]{correct});
    }

    private void checkDatabaseContents(String query, String[][] correct) throws Exception {
        Connection con2 = TestUtil.openDB();
        Statement s = con2.createStatement();
        assertFalse(s.execute("set time zone 'UTC'"));
        assertTrue(s.execute(query));
        ResultSet rs = s.getResultSet();
        for (int j = 0; j < correct.length; ++j) {
            assertTrue(rs.next());
            for (int i = 0; i < correct[j].length; ++i) {
                assertEquals("On row " + (j+1), correct[j][i], rs.getString(i+1));
            }
        }
        assertFalse(rs.next());
        rs.close();
        s.close();
        con2.close();
    }

    /**
     * Converts the given time
     *
     * @param t The time of day. Must be within -24 and + 24 hours of epoc.
     * @param tz The timezone to normalize to.
     * @return the Time nomralized to 0 to 24 hours of epoc adjusted with given timezone.
     */
    private Timestamp normalizeTimeOfDayPart(Timestamp t, Calendar tz) {
        return new Timestamp(normalizeTimeOfDayPart(t.getTime(), tz.getTimeZone()));
    }

    private long normalizeTimeOfDayPart(long t, TimeZone tz) {
        long millis = t;
        long low = - tz.getOffset(millis);
        long high = low + DAY;
        if (millis < low) {
            do { millis += DAY; } while (millis < low);
        } else if (millis >= high) {
            do { millis -= DAY; } while (millis > high);
        }
        return millis;
    }
}
