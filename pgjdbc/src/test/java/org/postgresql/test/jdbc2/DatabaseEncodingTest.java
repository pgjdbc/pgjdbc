/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.Encoding;
import org.postgresql.test.SlowTests;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

/*
 * Test case for various encoding problems.
 *
 * Ensure that we can do a round-trip of all server-supported unicode values without trashing them,
 * and that bad encodings are detected.
 */
public class DatabaseEncodingTest {
  private static final int STEP = 100;

  private Connection con;

  // Set up the fixture for this testcase: a connection to a database with
  // a table for this test.
  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTempTable(con, "testdbencoding",
        "unicode_ordinal integer primary key not null, unicode_string varchar(" + STEP + ")");
    // disabling auto commit makes the test run faster
    // by not committing each insert individually.
    con.setAutoCommit(false);
  }

  // Tear down the fixture for this test case.
  @After
  public void tearDown() throws Exception {
    con.setAutoCommit(true);
    TestUtil.closeDB(con);
  }

  private static String dumpString(String s) {
    StringBuffer sb = new StringBuffer(s.length() * 6);
    for (int i = 0; i < s.length(); ++i) {
      sb.append("\\u");
      char c = s.charAt(i);
      sb.append(Integer.toHexString((c >> 12) & 15));
      sb.append(Integer.toHexString((c >> 8) & 15));
      sb.append(Integer.toHexString((c >> 4) & 15));
      sb.append(Integer.toHexString(c & 15));
    }
    return sb.toString();
  }

  @Test
  @Category(SlowTests.class)
  public void testEncoding() throws Exception {
    String databaseEncoding = TestUtil.queryForString(con, "SELECT getdatabaseencoding()");
    Assume.assumeTrue("Database encoding must be UTF8", databaseEncoding.equals("UTF8"));

    boolean testHighUnicode = true;

    // Create data.
    // NB: we avoid d800-dfff as those are reserved for surrogates in UTF-16
    PreparedStatement insert = con.prepareStatement(
        "INSERT INTO testdbencoding(unicode_ordinal, unicode_string) VALUES (?,?)");
    for (int i = 1; i < 0xd800; i += STEP) {
      int count = (i + STEP) > 0xd800 ? 0xd800 - i : STEP;
      char[] testChars = new char[count];
      for (int j = 0; j < count; ++j) {
        testChars[j] = (char) (i + j);
      }

      String testString = new String(testChars);

      insert.setInt(1, i);
      insert.setString(2, testString);
      assertEquals(1, insert.executeUpdate());
    }

    for (int i = 0xe000; i < 0x10000; i += STEP) {
      int count = (i + STEP) > 0x10000 ? 0x10000 - i : STEP;
      char[] testChars = new char[count];
      for (int j = 0; j < count; ++j) {
        testChars[j] = (char) (i + j);
      }

      String testString = new String(testChars);

      insert.setInt(1, i);
      insert.setString(2, testString);
      assertEquals(1, insert.executeUpdate());
    }

    if (testHighUnicode) {
      for (int i = 0x10000; i < 0x110000; i += STEP) {
        int count = (i + STEP) > 0x110000 ? 0x110000 - i : STEP;
        char[] testChars = new char[count * 2];
        for (int j = 0; j < count; ++j) {
          testChars[j * 2] = (char) (0xd800 + ((i + j - 0x10000) >> 10));
          testChars[j * 2 + 1] = (char) (0xdc00 + ((i + j - 0x10000) & 0x3ff));
        }

        String testString = new String(testChars);

        insert.setInt(1, i);
        insert.setString(2, testString);

        // System.err.println("Inserting: " + dumpString(testString));

        assertEquals(1, insert.executeUpdate());
      }
    }

    con.commit();

    // Check data.
    Statement stmt = con.createStatement();
    stmt.setFetchSize(1);
    ResultSet rs = stmt.executeQuery(
        "SELECT unicode_ordinal, unicode_string FROM testdbencoding ORDER BY unicode_ordinal");
    for (int i = 1; i < 0xd800; i += STEP) {
      assertTrue(rs.next());
      assertEquals(i, rs.getInt(1));

      int count = (i + STEP) > 0xd800 ? 0xd800 - i : STEP;
      char[] testChars = new char[count];
      for (int j = 0; j < count; ++j) {
        testChars[j] = (char) (i + j);
      }

      String testString = new String(testChars);

      assertEquals("Test string: " + dumpString(testString), dumpString(testString),
          dumpString(rs.getString(2)));
    }

    for (int i = 0xe000; i < 0x10000; i += STEP) {
      assertTrue(rs.next());
      assertEquals(i, rs.getInt(1));

      int count = (i + STEP) > 0x10000 ? 0x10000 - i : STEP;
      char[] testChars = new char[count];
      for (int j = 0; j < count; ++j) {
        testChars[j] = (char) (i + j);
      }

      String testString = new String(testChars);

      assertEquals("Test string: " + dumpString(testString), dumpString(testString),
          dumpString(rs.getString(2)));
    }

    if (testHighUnicode) {
      for (int i = 0x10000; i < 0x110000; i += STEP) {
        assertTrue(rs.next());
        assertEquals(i, rs.getInt(1));

        int count = (i + STEP) > 0x110000 ? 0x110000 - i : STEP;
        char[] testChars = new char[count * 2];
        for (int j = 0; j < count; ++j) {
          testChars[j * 2] = (char) (0xd800 + ((i + j - 0x10000) >> 10));
          testChars[j * 2 + 1] = (char) (0xdc00 + ((i + j - 0x10000) & 0x3ff));
        }

        String testString = new String(testChars);

        assertEquals("Test string: " + dumpString(testString), dumpString(testString),
            dumpString(rs.getString(2)));
      }
    }
  }

  @Test
  public void testUTF8Decode() throws Exception {
    // Tests for our custom UTF-8 decoder.

    Encoding utf8Encoding = Encoding.getJVMEncoding("UTF-8");

    for (int ch = 0; ch < 0x110000; ++ch) {
      if (ch >= 0xd800 && ch < 0xe000) {
        continue; // Surrogate range.
      }

      String testString;
      if (ch >= 0x10000) {
        testString = new String(new char[]{(char) (0xd800 + ((ch - 0x10000) >> 10)),
            (char) (0xdc00 + ((ch - 0x10000) & 0x3ff))});
      } else {
        testString = new String(new char[]{(char) ch});
      }

      byte[] jvmEncoding = testString.getBytes("UTF-8");
      String jvmDecoding = new String(jvmEncoding, 0, jvmEncoding.length, "UTF-8");
      String ourDecoding = utf8Encoding.decode(jvmEncoding, 0, jvmEncoding.length);

      assertEquals("Test string: " + dumpString(testString), dumpString(testString),
          dumpString(jvmDecoding));
      assertEquals("Test string: " + dumpString(testString), dumpString(testString),
          dumpString(ourDecoding));
    }
  }

  /**
   * Tests that invalid utf-8 values are replaced with the unicode replacement chart.
   */
  @Test
  public void testTruncatedUTF8Decode() throws Exception {
    Encoding utf8Encoding = Encoding.getJVMEncoding("UTF-8");

    byte[][] shortSequences = new byte[][]{{(byte) 0xc0}, // Second byte must be present

        {(byte) 0xe0}, // Second byte must be present
        {(byte) 0xe0, (byte) 0x80}, // Third byte must be present

        {(byte) 0xf0}, // Second byte must be present
        {(byte) 0xf0, (byte) 0x80}, // Third byte must be present
        {(byte) 0xf0, (byte) 0x80, (byte) 0x80}, // Fourth byte must be present
    };

    byte[] paddedSequence = new byte[32];
    for (int i = 0; i < shortSequences.length; ++i) {
      byte[] sequence = shortSequences[i];
      String expected = "\uFFFD";
      for (int j = 1; j < sequence.length; ++j) {
        expected += "\uFFFD";
      }

      String str = utf8Encoding.decode(sequence, 0, sequence.length);
      assertEquals("itr:" + i, expected, str);

      // Try it with padding and a truncated length.
      Arrays.fill(paddedSequence, (byte) 0);
      System.arraycopy(sequence, 0, paddedSequence, 0, sequence.length);

      str = utf8Encoding.decode(paddedSequence, 0, sequence.length);
      assertEquals("itr:" + i, expected, str);
    }
  }
}
