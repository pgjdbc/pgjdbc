/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc2/DatabaseEncodingTest.java,v 1.3 2004/11/07 22:16:44 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import junit.framework.TestCase;
import java.sql.*;

/*
 * Test case for Dario's encoding problems.
 * Ensure the driver's own utf-8 decode method works.
 */
public class DatabaseEncodingTest extends TestCase
{
    private Connection con;

    public DatabaseEncodingTest(String name)
    {
        super(name);
    }

    private static final int STEP = 300;

    // Set up the fixture for this testcase: a connection to a database with
    // a table for this test.
    protected void setUp() throws Exception
    {
        con = TestUtil.openDB();
        TestUtil.createTable(con,
                             "testdbencoding",
                             "unicode_ordinal integer primary key not null, unicode_string varchar(" + STEP + ")");
        // disabling auto commit makes the test run faster
        // by not committing each insert individually.
        con.setAutoCommit(false);
    }

    // Tear down the fixture for this test case.
    protected void tearDown() throws Exception
    {
        con.setAutoCommit(true);
        TestUtil.dropTable(con, "testdbencoding");
        TestUtil.closeDB(con);
    }

    private static String dumpString(String s) {
        StringBuffer sb = new StringBuffer(s.length() * 6);
        for (int i = 0; i < s.length(); ++i)
        {
            sb.append("\\u");
            char c = s.charAt(i);
            sb.append(Integer.toHexString((c >> 12)&15));
            sb.append(Integer.toHexString((c >> 8)&15));
            sb.append(Integer.toHexString((c >> 4)&15));
            sb.append(Integer.toHexString(c&15));
        }
        return sb.toString();
    }

    public void testEncoding() throws Exception {
        // Check that we have a UNICODE server encoding, or we must skip this test.
        Statement stmt = con.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT getdatabaseencoding()");
        assertTrue(rs.next());
        if (!"UNICODE".equals(rs.getString(1)))
        {
            rs.close();
            return ; // not a UNICODE database.
        }

        rs.close();

        // Create data.
        // NB: we only test up to d800 as code points above that are
        // reserved for surrogates in UTF-16
        PreparedStatement insert = con.prepareStatement("INSERT INTO testdbencoding(unicode_ordinal, unicode_string) VALUES (?,?)");
        for (int i = 1; i < 0xd800; i += STEP)
        {
            int count = (i + STEP) > 0xd800 ? 0xd800 - i : STEP;
            char[] testChars = new char[count];
            for (int j = 0; j < count; ++j)
                testChars[j] = (char)(i + j);

            String testString = new String(testChars);

            insert.setInt(1, i);
            insert.setString(2, testString);
            assertEquals(1, insert.executeUpdate());
        }

        con.commit();

        // Check data.
        rs = stmt.executeQuery("SELECT unicode_ordinal, unicode_string FROM testdbencoding ORDER BY unicode_ordinal");
        for (int i = 1; i < 0xd800; i += STEP)
        {
            assertTrue(rs.next());
            assertEquals(i, rs.getInt(1));

            int count = (i + STEP) > 0xd800 ? 0xd800 - i : STEP;
            char[] testChars = new char[count];
            for (int j = 0; j < count; ++j)
                testChars[j] = (char)(i + j);

            String testString = new String(testChars);

            assertEquals(dumpString(testString), dumpString(rs.getString(2)));
        }
    }
}
