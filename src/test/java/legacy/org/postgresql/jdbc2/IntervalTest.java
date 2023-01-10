/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.sql.*;

import junit.framework.TestCase;

import legacy.org.postgresql.TestUtil;
import legacy.org.postgresql.util.PGInterval;

public class IntervalTest extends TestCase
{

    private Connection _conn;

    public IntervalTest(String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        _conn = TestUtil.openDB();
        TestUtil.createTable(_conn, "testinterval", "v interval");
    }

    protected void tearDown() throws Exception
    {
        TestUtil.dropTable(_conn, "testinterval");
        TestUtil.closeDB(_conn);
    }

    public void testOnlineTests() throws SQLException
    {
        PreparedStatement pstmt = _conn.prepareStatement("INSERT INTO testinterval VALUES (?)");
        pstmt.setObject(1, new PGInterval(2004, 13, 28, 0, 0, 43000.9013));
        pstmt.executeUpdate();
        pstmt.close();

        Statement stmt = _conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT v FROM testinterval");
        assertTrue(rs.next());
        PGInterval pgi = (PGInterval)rs.getObject(1);
        assertEquals(2005, pgi.getYears());
        assertEquals(1, pgi.getMonths());
        assertEquals(28, pgi.getDays());
        assertEquals(11, pgi.getHours());
        assertEquals(56, pgi.getMinutes());
        assertEquals(40.9013, pgi.getSeconds(), 0.000001);
        assertTrue(!rs.next());
        rs.close();
        stmt.close();
    }

    public void testDaysHours() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT '101:12:00'::interval");
        assertTrue(rs.next());
        PGInterval i = (PGInterval)rs.getObject(1);
        // 8.1 servers store hours and days separately.
        if (TestUtil.haveMinimumServerVersion(_conn, "8.1")) {
            assertEquals(0, i.getDays());
            assertEquals(101, i.getHours());
        } else {
            assertEquals(4, i.getDays());
            assertEquals(5, i.getHours());
        }
        assertEquals(12, i.getMinutes());
    }

    public void testAddRounding() {
        PGInterval pgi = new PGInterval(0, 0, 0, 0, 0, 0.6006);
        Calendar cal = Calendar.getInstance();
        long origTime = cal.getTime().getTime();
        pgi.add(cal);
        long newTime = cal.getTime().getTime();
        assertEquals(601, newTime - origTime);
        pgi.setSeconds(-0.6006);
        pgi.add(cal);
        assertEquals(origTime, cal.getTime().getTime());
    }

    public void testOfflineTests()
    throws Exception
    {
        PGInterval pgi = new PGInterval(2004, 4, 20, 15, 57, 12.1);

        assertEquals(2004, pgi.getYears());
        assertEquals(4, pgi.getMonths());
        assertEquals(20, pgi.getDays());
        assertEquals(15, pgi.getHours());
        assertEquals(57, pgi.getMinutes());
        assertEquals(12.1, pgi.getSeconds(), 0);

        PGInterval pgi2 = new PGInterval("@ 2004 years 4 mons 20 days 15 hours 57 mins 12.1 secs");
        assertEquals(pgi, pgi2);

        // Singular units
        PGInterval pgi3 = new PGInterval("@ 2004 year 4 mon 20 day 15 hour 57 min 12.1 sec");
        assertEquals(pgi, pgi3);

        PGInterval pgi4 = new PGInterval("2004 years 4 mons 20 days 15:57:12.1");
        assertEquals(pgi, pgi4);

        // Ago test
        pgi = new PGInterval("@ 2004 years 4 mons 20 days 15 hours 57 mins 12.1 secs ago");
        assertEquals(-2004, pgi.getYears());
        assertEquals(-4, pgi.getMonths());
        assertEquals(-20, pgi.getDays());
        assertEquals(-15, pgi.getHours());
        assertEquals(-57, pgi.getMinutes());
        assertEquals(-12.1, pgi.getSeconds(), 0);

        // Char test
        pgi = new PGInterval("@ +2004 years -4 mons +20 days -15 hours +57 mins -12.1 secs");
        assertEquals(2004, pgi.getYears());
        assertEquals(-4, pgi.getMonths());
        assertEquals(20, pgi.getDays());
        assertEquals(-15, pgi.getHours());
        assertEquals(57, pgi.getMinutes());
        assertEquals(-12.1, pgi.getSeconds(), 0);
    }

    Calendar getStartCalendar()
    {
        Calendar cal = new GregorianCalendar();
        cal.set(Calendar.YEAR, 2005);
        cal.set(Calendar.MONTH, 4);
        cal.set(Calendar.DAY_OF_MONTH, 29);
        cal.set(Calendar.HOUR_OF_DAY, 15);
        cal.set(Calendar.MINUTE, 35);
        cal.set(Calendar.SECOND, 42);
        cal.set(Calendar.MILLISECOND, 100);

        return cal;
    }

    public void testCalendar()
    throws Exception
    {
        Calendar cal = getStartCalendar();

        PGInterval pgi = new PGInterval("@ 1 year 1 mon 1 day 1 hour 1 minute 1 secs");
        pgi.add(cal);

        assertEquals(2006, cal.get(Calendar.YEAR));
        assertEquals(5, cal.get(Calendar.MONTH));
        assertEquals(30, cal.get(Calendar.DAY_OF_MONTH));
        assertEquals(16, cal.get(Calendar.HOUR_OF_DAY));
        assertEquals(36, cal.get(Calendar.MINUTE));
        assertEquals(43, cal.get(Calendar.SECOND));
        assertEquals(100, cal.get(Calendar.MILLISECOND));

        pgi = new PGInterval("@ 1 year 1 mon 1 day 1 hour 1 minute 1 secs ago");
        pgi.add(cal);

        assertEquals(2005, cal.get(Calendar.YEAR));
        assertEquals(4, cal.get(Calendar.MONTH));
        assertEquals(29, cal.get(Calendar.DAY_OF_MONTH));
        assertEquals(15, cal.get(Calendar.HOUR_OF_DAY));
        assertEquals(35, cal.get(Calendar.MINUTE));
        assertEquals(42, cal.get(Calendar.SECOND));
        assertEquals(100, cal.get(Calendar.MILLISECOND));

        cal = getStartCalendar();

        pgi = new PGInterval("@ 1 year -23 hours -3 mins -3.30 secs");
        pgi.add(cal);

        assertEquals(2006, cal.get(Calendar.YEAR));
        assertEquals(4, cal.get(Calendar.MONTH));
        assertEquals(28, cal.get(Calendar.DAY_OF_MONTH));
        assertEquals(16, cal.get(Calendar.HOUR_OF_DAY));
        assertEquals(32, cal.get(Calendar.MINUTE));
        assertEquals(38, cal.get(Calendar.SECOND));
        assertEquals(800, cal.get(Calendar.MILLISECOND));

        pgi = new PGInterval("@ 1 year -23 hours -3 mins -3.30 secs ago");
        pgi.add(cal);

        assertEquals(2005, cal.get(Calendar.YEAR));
        assertEquals(4, cal.get(Calendar.MONTH));
        assertEquals(29, cal.get(Calendar.DAY_OF_MONTH));
        assertEquals(15, cal.get(Calendar.HOUR_OF_DAY));
        assertEquals(35, cal.get(Calendar.MINUTE));
        assertEquals(42, cal.get(Calendar.SECOND));
        assertEquals(100, cal.get(Calendar.MILLISECOND));
    }

    public void testDate()
    throws Exception
    {
        Date date  = getStartCalendar().getTime();
        Date date2 = getStartCalendar().getTime();

        PGInterval pgi = new PGInterval("@ +2004 years -4 mons +20 days -15 hours +57 mins -12.1 secs");
        pgi.add(date);

        PGInterval pgi2 = new PGInterval("@ +2004 years -4 mons +20 days -15 hours +57 mins -12.1 secs ago");
        pgi2.add(date);

        assertEquals(date2, date);
    }

    public void testISODate()
    throws Exception
    {
        Date date  = getStartCalendar().getTime();
        Date date2 = getStartCalendar().getTime();

        PGInterval pgi = new PGInterval("+2004 years -4 mons +20 days -15:57:12.1");
        pgi.add(date);

        PGInterval pgi2 = new PGInterval("-2004 years 4 mons -20 days 15:57:12.1");
        pgi2.add(date);

        assertEquals(date2, date);
    }

}
