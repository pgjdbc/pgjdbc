/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import legacy.org.postgresql.TestUtil;
import legacy.org.postgresql.core.Encoding;
import junit.framework.TestCase;
import java.sql.*;
import java.io.IOException;
import java.util.Arrays;

/*
 * Test case for various encoding problems.
 *
 * Ensure that we can do a round-trip of all server-supported unicode
 * values without trashing them, and that bad encodings are
 * detected.
 */
public class DatabaseEncodingTest extends TestCase
{
    private Connection con;

    public DatabaseEncodingTest(String name)
    {
        super(name);
    }

    private static final int STEP = 100;

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
        // Check that we have a UTF8 server encoding, or we must skip this test.
        Statement stmt = con.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT getdatabaseencoding()");
        assertTrue(rs.next());

        String dbEncoding = rs.getString(1);
        if (!dbEncoding.equals("UTF8"))
        {
            System.err.println("DatabaseEncodingTest: Skipping UTF8 database tests as test database encoding is " + dbEncoding);
            rs.close();
            return ; // not a UTF8 database.
        }

        rs.close();

        boolean testHighUnicode = TestUtil.haveMinimumServerVersion(con, "8.1");

        // Create data.
        // NB: we avoid d800-dfff as those are reserved for surrogates in UTF-16
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

        for (int i = 0xe000; i < 0x10000; i += STEP)
        {
            int count = (i + STEP) > 0x10000 ? 0x10000 - i : STEP;
            char[] testChars = new char[count];
            for (int j = 0; j < count; ++j)
                testChars[j] = (char)(i + j);

            String testString = new String(testChars);

            insert.setInt(1, i);
            insert.setString(2, testString);
            assertEquals(1, insert.executeUpdate());
        }

        if (testHighUnicode) {
            for (int i = 0x10000; i < 0x110000; i += STEP)
            {
                int count = (i + STEP) > 0x110000 ? 0x110000 - i : STEP;
                char[] testChars = new char[count*2];
                for (int j = 0; j < count; ++j) {
                    testChars[j*2]   = (char)(0xd800 + ((i + j - 0x10000) >> 10));
                    testChars[j*2+1] = (char)(0xdc00 + ((i + j - 0x10000) & 0x3ff));
                }
                
                String testString = new String(testChars);
                
                insert.setInt(1, i);
                insert.setString(2, testString);
                
                //System.err.println("Inserting: " + dumpString(testString));

                assertEquals(1, insert.executeUpdate());
            }
        }            

        con.commit();

        // Check data.
        stmt.setFetchSize(1);
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

            assertEquals("Test string: " + dumpString(testString), dumpString(testString), dumpString(rs.getString(2)));
        }

        for (int i = 0xe000; i < 0x10000; i += STEP)
        {
            assertTrue(rs.next());
            assertEquals(i, rs.getInt(1));

            int count = (i + STEP) > 0x10000 ? 0x10000 - i : STEP;
            char[] testChars = new char[count];
            for (int j = 0; j < count; ++j)
                testChars[j] = (char)(i + j);

            String testString = new String(testChars);

            assertEquals("Test string: " + dumpString(testString), dumpString(testString), dumpString(rs.getString(2)));
        }

        if (testHighUnicode) {
            for (int i = 0x10000; i < 0x110000; i += STEP)
            {
                assertTrue(rs.next());
                assertEquals(i, rs.getInt(1));

                int count = (i + STEP) > 0x110000 ? 0x110000 - i : STEP;
                char[] testChars = new char[count*2];
                for (int j = 0; j < count; ++j) {
                    testChars[j*2]   = (char)(0xd800 + ((i + j - 0x10000) >> 10));
                    testChars[j*2+1] = (char)(0xdc00 + ((i + j - 0x10000) & 0x3ff));
                }
                
                String testString = new String(testChars);
                
                assertEquals("Test string: " + dumpString(testString), dumpString(testString), dumpString(rs.getString(2)));
            }
        }
    }

    public void testUTF8Decode() throws Exception {
        // Tests for our custom UTF-8 decoder.

        Encoding utf8Encoding = Encoding.getJVMEncoding("UTF-8");
        
        for (int ch = 0; ch < 0x110000; ++ch) {
            if (ch >= 0xd800 && ch < 0xe000)
                continue; // Surrogate range.
            
            String testString;
            if (ch >= 0x10000) {
                testString = new String(new char[] {
                    (char) (0xd800 + ((ch-0x10000) >> 10)),
                    (char) (0xdc00 + ((ch-0x10000) & 0x3ff)) });
            } else {
                testString = new String(new char[] { (char)ch });
            }
            
            byte[] jvmEncoding = testString.getBytes("UTF-8");
            String jvmDecoding = new String(jvmEncoding, 0, jvmEncoding.length, "UTF-8");
            String ourDecoding = utf8Encoding.decode(jvmEncoding, 0, jvmEncoding.length);
            
            assertEquals("Test string: " + dumpString(testString), dumpString(testString), dumpString(jvmDecoding));
            assertEquals("Test string: " + dumpString(testString), dumpString(testString), dumpString(ourDecoding));
        }
    }
 
    public void testBadUTF8Decode() throws Exception {
        Encoding utf8Encoding = Encoding.getJVMEncoding("UTF-8");

        byte[][] badSequences = new byte[][] {
            // One-byte illegal sequences
            { (byte)0x80 }, // First byte may not be 10xxxxxx
            
            // Two-byte illegal sequences
            { (byte)0xc0, (byte)0x00 },  // Second byte must be 10xxxxxx
            { (byte)0xc0, (byte)0x80 },  // Can't represent a value < 0x80
            
            // Three-byte illegal sequences
            { (byte)0xe0, (byte)0x00 },  // Second byte must be 10xxxxxx
            { (byte)0xe0, (byte)0x80, (byte)0x00 },  // Third byte must be 10xxxxxx
            { (byte)0xe0, (byte)0x80, (byte)0x80 },  // Can't represent a value < 0x800
            { (byte)0xed, (byte)0xa0, (byte)0x80 },  // Not allowed to encode the range d800..dfff
            
            // Four-byte illegal sequences
            { (byte)0xf0, (byte)0x00 },  // Second byte must be 10xxxxxx
            { (byte)0xf0, (byte)0x80, (byte)0x00 },  // Third byte must be 10xxxxxx
            { (byte)0xf0, (byte)0x80, (byte)0x80, (byte)0x00 },  // Fourth byte must be 10xxxxxx
            { (byte)0xf0, (byte)0x80, (byte)0x80, (byte)0x80 },  // Can't represent a value < 0x10000
            
            // Five-byte illegal sequences
            { (byte)0xf8 }, // Can't have a five-byte sequence.

            // Six-byte illegal sequences
            { (byte)0xfc }, // Can't have a six-byte sequence.

            // Seven-byte illegal sequences
            { (byte)0xfe }, // Can't have a seven-byte sequence.
            
            // Eigth-byte illegal sequences
            { (byte)0xff }, // Can't have an eight-byte sequence.
        }; 

        byte[] paddedSequence = new byte[32];
        for (int i = 0; i < badSequences.length; ++i) {
            byte[] sequence = badSequences[i];
            
            try {
                String str = utf8Encoding.decode(sequence, 0, sequence.length);
                fail("Expected an IOException on sequence " + i + ", but decoded to <" + str + ">");
            } catch (IOException ioe) {
                // Expected exception.
            }
            
            // Try it with padding.
            Arrays.fill(paddedSequence, (byte)0);
            System.arraycopy(sequence, 0, paddedSequence, 0, sequence.length);
            
            try {
                String str = utf8Encoding.decode(paddedSequence, 0, paddedSequence.length);
                fail("Expected an IOException on sequence " + i + ", but decoded to <" + str + ">");
            } catch (IOException ioe) {
                // Expected exception.
            }
        }
    }

    public void testTruncatedUTF8Decode() throws Exception {
        Encoding utf8Encoding = Encoding.getJVMEncoding("UTF-8");

        byte[][] shortSequences = new byte[][] {
            { (byte)0xc0 },              // Second byte must be present
            
            { (byte)0xe0 },              // Second byte must be present
            { (byte)0xe0, (byte)0x80 },  // Third byte must be present
            
            { (byte)0xf0 },              // Second byte must be present
            { (byte)0xf0, (byte)0x80 },  // Third byte must be present
            { (byte)0xf0, (byte)0x80, (byte)0x80 },  // Fourth byte must be present
        };
        
        byte[] paddedSequence = new byte[32];
        for (int i = 0; i < shortSequences.length; ++i) {
            byte[] sequence = shortSequences[i];
            
            try {
                String str = utf8Encoding.decode(sequence, 0, sequence.length);
                fail("Expected an IOException on sequence " + i + ", but decoded to <" + str + ">");
            } catch (IOException ioe) {
                // Expected exception.
            }
            
            
            // Try it with padding and a truncated length.
            Arrays.fill(paddedSequence, (byte)0);
            System.arraycopy(sequence, 0, paddedSequence, 0, sequence.length);
            
            try {
                String str = utf8Encoding.decode(paddedSequence, 0, sequence.length);
                fail("Expected an IOException on sequence " + i + ", but decoded to <" + str + ">");
            } catch (IOException ioe) {
                // Expected exception.
            }
        }
    }
}
