/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class ClobTest extends BaseTest4 {
  Clob clob;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "testclob", "id int4,lo oid");
    con.setAutoCommit(false);
    clob = con.createClob();
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testclob");
    super.tearDown();
  }

  private void insertClob(int id, Clob clob) throws SQLException {
    PreparedStatement insertClob =
        con.prepareStatement("insert into testclob(id, lo) values(?, ?)");
    insertClob.setInt(1, id);
    insertClob.setClob(2, clob);
    insertClob.execute();
    TestUtil.closeQuietly(insertClob);
  }

  private void checkClobContents(String value, Clob clob) throws SQLException {
    int id = 42;
    insertClob(id, clob);
    clob.free();

    PreparedStatement ps = con.prepareStatement("select lo from testclob where id=?");
    ps.setInt(1, id);
    ResultSet rs = ps.executeQuery();
    rs.next();
    Clob dbClob = rs.getClob(1);
    String dbValue = dbClob.getSubString(1, (int) dbClob.length());
    dbClob.free();
    Assert.assertEquals(value, dbValue);
  }

  @Test
  public void setString() throws SQLException {
    String value = "Привет, clob";
    clob.setString(1, value);

    checkClobContents(value, clob);
  }

  @Test
  public void setStringOffsLen() throws SQLException {
    String value = "Привет, clob";
    clob.setString(1, value, 1, 3);

    checkClobContents(value.substring(1, 1 + 3), clob);
  }

  @Test
  public void setAsciiStream() throws SQLException, IOException {
    String value = "Привет, clob";
    OutputStream os = clob.setAsciiStream(1); // index is 1-based
    os.write(value.getBytes("UTF-8"));
    os.close();
    checkClobContents(value, clob);
  }

  @Test
  public void setAsciiStreamOffs() throws SQLException, IOException {
    String prefix = "Привет, ";
    byte[] prefixBytes = prefix.getBytes("UTF-8");
    OutputStream os = clob.setAsciiStream(1);
    os.write((prefix + ", clob").getBytes("UTF-8"));
    os.close();

    // Append value to the tail
    String suffix = "клоб";
    byte[] suffixBytes = suffix.getBytes("UTF-8");
    os = clob.setAsciiStream(prefixBytes.length + 1); // index is 1-based
    os.write(suffixBytes);
    os.close();

    checkClobContents(prefix + suffix, clob);
  }
}
