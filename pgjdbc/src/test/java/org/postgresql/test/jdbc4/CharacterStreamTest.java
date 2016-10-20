/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;

public class CharacterStreamTest extends BaseTest4 {

  private static final String TEST_TABLE_NAME = "charstream";
  private static final String TEST_COLUMN_NAME = "cs";

  private static final String _delete;
  private static final String _insert;
  private static final String _select;

  static {
    _delete = String.format("DELETE FROM %s", TEST_TABLE_NAME);
    _insert = String.format("INSERT INTO %s (%s) VALUES (?)", TEST_TABLE_NAME, TEST_COLUMN_NAME);
    _select = String.format("SELECT %s FROM %s", TEST_COLUMN_NAME, TEST_TABLE_NAME);
  }

  @BeforeClass
  public static void classSetup() throws Exception {
    Properties props = new Properties();
    Connection con = TestUtil.openDB(props);
    TestUtil.createTable(con, TEST_TABLE_NAME, "cs text");
    TestUtil.closeDB(con);
  }

  @AfterClass
  public static void classTeardown() throws Exception {
    Properties props = new Properties();
    Connection con = TestUtil.openDB(props);
    TestUtil.dropTable(con, TEST_TABLE_NAME);
    TestUtil.closeDB(con);
  }

  private void deleteData() throws Exception {
    PreparedStatement deletePS = con.prepareStatement(_delete);
    try {
      deletePS.executeUpdate();
    } finally {
      deletePS.close();
    }
  }

  private void insertStreamKnownIntLength(String data) throws Exception {
    PreparedStatement insertPS = con.prepareStatement(_insert);
    try {
      Reader reader = (data != null) ? new StringReader(data) : null;
      int length = (data != null) ? data.length() : 0;
      insertPS.setCharacterStream(1, reader, length);
      insertPS.executeUpdate();
    } finally {
      insertPS.close();
    }
  }

  private void insertStreamKnownLongLength(String data) throws Exception {
    PreparedStatement insertPS = con.prepareStatement(_insert);
    try {
      Reader reader = (data != null) ? new StringReader(data) : null;
      long length = (data != null) ? data.length() : 0;
      insertPS.setCharacterStream(1, reader, length);
      insertPS.executeUpdate();
    } finally {
      insertPS.close();
    }
  }

  private void insertStreamUnknownLength(String data) throws Exception {
    PreparedStatement insertPS = con.prepareStatement(_insert);
    try {
      Reader reader = (data != null) ? new StringReader(data) : null;
      insertPS.setCharacterStream(1, reader);
      insertPS.executeUpdate();
    } finally {
      insertPS.close();
    }
  }

  private void validateContent(String data) throws Exception {
    PreparedStatement selectPS = con.prepareStatement(_select);
    try {
      ResultSet rs = selectPS.executeQuery();
      try {
        if (rs.next()) {
          String actualData = rs.getString(1);
          Assert.assertEquals("Sent and received data are not the same", data, actualData);
        } else {
          Assert.fail("query returned zero rows");
        }
      } finally {
        rs.close();
      }
    } finally {
      selectPS.close();
    }
  }

  private String getTestData(int size) {
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
    deleteData();
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test(expected = SQLFeatureNotSupportedException.class)
  public void testKnownLongLengthNull() throws Exception {
    String data = null;
    deleteData();
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLengthNull() throws Exception {
    String data = null;
    deleteData();
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLengthEmpty() throws Exception {
    String data = "";
    deleteData();
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test(expected = SQLFeatureNotSupportedException.class)
  public void testKnownLongLengthEmpty() throws Exception {
    String data = "";
    deleteData();
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLengthEmpty() throws Exception {
    String data = "";
    deleteData();
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLength2Kb() throws Exception {
    String data = getTestData(2 * 1024);
    deleteData();
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test(expected = SQLFeatureNotSupportedException.class)
  public void testKnownLongLength2Kb() throws Exception {
    String data = getTestData(2 * 1024);
    deleteData();
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength2Kb() throws Exception {
    String data = getTestData(2 * 1024);
    deleteData();
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLength10Kb() throws Exception {
    String data = getTestData(10 * 1024);
    deleteData();
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test(expected = SQLFeatureNotSupportedException.class)
  public void testKnownLongLength10Kb() throws Exception {
    String data = getTestData(10 * 1024);
    deleteData();
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength10Kb() throws Exception {
    String data = getTestData(10 * 1024);
    deleteData();
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLength100Kb() throws Exception {
    String data = getTestData(100 * 1024);
    deleteData();
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test(expected = SQLFeatureNotSupportedException.class)
  public void testKnownLongLength100Kb() throws Exception {
    String data = getTestData(100 * 1024);
    deleteData();
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength100Kb() throws Exception {
    String data = getTestData(100 * 1024);
    deleteData();
    insertStreamUnknownLength(data);
    validateContent(data);
  }

  @Test
  public void testKnownIntLength200Kb() throws Exception {
    String data = getTestData(200 * 1024);
    deleteData();
    insertStreamKnownIntLength(data);
    validateContent(data);
  }

  @Test(expected = SQLFeatureNotSupportedException.class)
  public void testKnownLongLength200Kb() throws Exception {
    String data = getTestData(200 * 1024);
    deleteData();
    insertStreamKnownLongLength(data);
    validateContent(data);
  }

  @Test
  public void testUnknownLength200Kb() throws Exception {
    String data = getTestData(200 * 1024);
    deleteData();
    insertStreamUnknownLength(data);
    validateContent(data);
  }
}
