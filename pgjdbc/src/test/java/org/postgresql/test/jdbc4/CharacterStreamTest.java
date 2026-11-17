/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.stream.Stream;

@ParameterizedClass
@MethodSource("data")
public class CharacterStreamTest extends BaseTest4 {

  public CharacterStreamTest(PreferQueryMode preferQueryMode) {
    setPreferQueryMode(preferQueryMode);
  }

  // setCharacterStream(int, Reader) takes a separate path in simple query mode
  public static Iterable<Object[]> data() {
    return Arrays.asList(
        new Object[]{PreferQueryMode.EXTENDED},
        new Object[]{PreferQueryMode.SIMPLE});
  }

  private static final String TEST_TABLE_NAME = "charstream";
  private static final String TEST_COLUMN_NAME = "cs";

  private static final String _insert;
  private static final String _select;

  static {
    _insert = String.format("INSERT INTO %s (%s) VALUES (?)", TEST_TABLE_NAME, TEST_COLUMN_NAME);
    _select = String.format("SELECT %s FROM %s", TEST_COLUMN_NAME, TEST_TABLE_NAME);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTempTable(con, TEST_TABLE_NAME, "cs text");
  }

  private void insertStreamKnownIntLength(String data) throws Exception {
    PreparedStatement insertPS = con.prepareStatement(_insert);
    try {
      Reader reader = data != null ? new StringReader(data) : null;
      int length = data != null ? data.length() : 0;
      insertPS.setCharacterStream(1, reader, length);
      insertPS.executeUpdate();
    } finally {
      TestUtil.closeQuietly(insertPS);
    }
  }

  private void insertStreamKnownLongLength(String data) throws Exception {
    PreparedStatement insertPS = con.prepareStatement(_insert);
    try {
      Reader reader = data != null ? new StringReader(data) : null;
      long length = data != null ? data.length() : 0;
      insertPS.setCharacterStream(1, reader, length);
      insertPS.executeUpdate();
    } finally {
      TestUtil.closeQuietly(insertPS);
    }
  }

  private void insertStreamUnknownLength(String data) throws Exception {
    PreparedStatement insertPS = con.prepareStatement(_insert);
    try {
      Reader reader = data != null ? new StringReader(data) : null;
      insertPS.setCharacterStream(1, reader);
      insertPS.executeUpdate();
    } finally {
      TestUtil.closeQuietly(insertPS);
    }
  }

  private void validateContent(String data) throws Exception {
    String actualData = TestUtil.queryForString(con, _select);
    assertEquals(data, actualData, "Sent and received data are not the same");
  }

  private static String getTestData(int size) {
    StringBuilder buf = new StringBuilder(size);
    String s = "This is a test string.\n";
    int slen = s.length();
    int len = 0;

    while ((len + slen) < size) {
      buf.append(s);
      len += slen;
    }

    while (len < size) {
      buf.append('.');
      len++;
    }

    return buf.toString();
  }

  @Test
  public void testKnownIntLengthNull() throws Exception {
    String data = null;
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLongLengthNull() throws Exception {
    String data = null;
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLengthNull() throws Exception {
    String data = null;
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLengthEmpty() throws Exception {
    String data = "";
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLongLengthEmpty() throws Exception {
    String data = "";
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLengthEmpty() throws Exception {
    String data = "";
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLength2Kb() throws Exception {
    String data = getTestData(2 * 1024);
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLongLength2Kb() throws Exception {
    String data = getTestData(2 * 1024);
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength2Kb() throws Exception {
    String data = getTestData(2 * 1024);
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLength10Kb() throws Exception {
    String data = getTestData(10 * 1024);
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLongLength10Kb() throws Exception {
    String data = getTestData(10 * 1024);
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength10Kb() throws Exception {
    String data = getTestData(10 * 1024);
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLength100Kb() throws Exception {
    String data = getTestData(100 * 1024);
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLongLength100Kb() throws Exception {
    String data = getTestData(100 * 1024);
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength100Kb() throws Exception {
    String data = getTestData(100 * 1024);
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLength200Kb() throws Exception {
    String data = getTestData(200 * 1024);
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownLongLength200Kb() throws Exception {
    String data = getTestData(200 * 1024);
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength200Kb() throws Exception {
    String data = getTestData(200 * 1024);
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  private void insertWithLongLength(String data, long length) throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(_insert)) {
      ps.setCharacterStream(1, new StringReader(data), length);
      ps.executeUpdate();
    }
  }

  @Test
  public void longLengthShorterThanTheReaderBindsTheFirstLengthChars() throws Exception {
    insertWithLongLength("abcdef", 3L);
    validateContent("abc");
  }

  @Test
  public void longLengthLongerThanTheReaderBindsTheWholeReader() throws Exception {
    insertWithLongLength("abc", 10L);
    validateContent("abc");
  }

  @Test
  public void longLengthZeroBindsAnEmptyString() throws Exception {
    insertWithLongLength("abc", 0L);
    validateContent("");
  }

  @Test
  public void longLengthIntegerMaxValueIsAccepted() throws Exception {
    insertWithLongLength("abc", Integer.MAX_VALUE);
    validateContent("abc");
  }

  // U+1F600 is two chars in UTF-16, and length counts chars, not code points
  @Test
  public void longLengthCountsUtf16Chars() throws Exception {
    insertWithLongLength("😀xyz", 3L);
    validateContent("😀x");
  }

  @ParameterizedTest
  @ValueSource(longs = {Integer.MAX_VALUE + 1L, Long.MAX_VALUE})
  public void longLengthAboveIntegerMaxValueIsRejected(long length) throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(_insert)) {
      SQLException e = assertThrows(SQLException.class,
          () -> ps.setCharacterStream(1, new StringReader("abc"), length));
      assertEquals(PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE.getState(), e.getSQLState(),
          () -> "setCharacterStream(1, reader, " + length + "L)");
    }
  }

  // (int) Long.MIN_VALUE is 0, so a sign check made after the narrowing cast would accept it, and
  // MessageFormat would print its digits grouped
  @ParameterizedTest
  @ValueSource(longs = {-1L, Long.MIN_VALUE})
  public void negativeLongLengthIsRejectedAndReportedWithUngroupedDigits(long length)
      throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(_insert)) {
      SQLException e = assertThrows(SQLException.class,
          () -> ps.setCharacterStream(1, new StringReader("abc"), length));
      String call = "setCharacterStream(1, reader, " + length + "L)";
      String digits = Long.toString(length);
      String message = String.valueOf(e.getMessage());
      assertAll(
          () -> assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState(),
              call + ", SQLState"),
          () -> assertTrue(message.contains(digits),
              () -> call + ", message: expected to contain <" + digits + "> but was <" + message
                  + ">"));
    }
  }

  @Test
  public void intLengthShorterThanTheReaderBindsTheFirstLengthChars() throws Exception {
    try (PreparedStatement ps = con.prepareStatement(_insert)) {
      ps.setCharacterStream(1, new StringReader("abcdef"), 3);
      ps.executeUpdate();
    }
    validateContent("abc");
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, Integer.MIN_VALUE})
  public void negativeIntLengthIsRejected(int length) throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(_insert)) {
      SQLException e = assertThrows(SQLException.class,
          () -> ps.setCharacterStream(1, new StringReader("abc"), length));
      assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState(),
          () -> "setCharacterStream(1, reader, " + length + ")");
    }
  }

  interface CharacterStreamSetter {
    void set(PreparedStatement ps, Reader reader) throws SQLException;
  }

  static Stream<Arguments> characterStreamSetters() {
    return Stream.of(
        argumentSet("setCharacterStream(int, Reader, long)",
            (CharacterStreamSetter) (ps, reader) -> ps.setCharacterStream(1, reader, 3L)),
        argumentSet("setCharacterStream(int, Reader, int)",
            (CharacterStreamSetter) (ps, reader) -> ps.setCharacterStream(1, reader, 3)),
        argumentSet("setCharacterStream(int, Reader)",
            (CharacterStreamSetter) (ps, reader) -> ps.setCharacterStream(1, reader)));
  }

  /**
   * Counts the calls to {@code read}, so a test can check that the driver never read from it.
   */
  private static final class ReadCountingReader extends StringReader {
    int reads;

    ReadCountingReader(String s) {
      super(s);
    }

    @Override
    public int read() throws IOException {
      reads++;
      return super.read();
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
      reads++;
      return super.read(cbuf, off, len);
    }
  }

  @ParameterizedTest
  @MethodSource("characterStreamSetters")
  public void closedStatementIsRejectedBeforeTheReaderIsRead(CharacterStreamSetter setter)
      throws SQLException {
    PreparedStatement ps = con.prepareStatement(_insert);
    ps.close();
    ReadCountingReader reader = new ReadCountingReader("abc");
    SQLException e = assertThrows(SQLException.class, () -> setter.set(ps, reader));
    assertAll(
        () -> assertEquals(PSQLState.OBJECT_NOT_IN_STATE.getState(), e.getSQLState(),
            "SQLState"),
        () -> assertEquals(0, reader.reads, "calls to Reader.read"));
  }
}
