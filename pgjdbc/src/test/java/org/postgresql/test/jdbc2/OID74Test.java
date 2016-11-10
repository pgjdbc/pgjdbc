/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Properties;

/**
 * User: alexei
 */
public class OID74Test {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    // set up conection here
    Properties props = new Properties();
    props.setProperty("compatible", "7.1");
    conn = TestUtil.openDB(props);

    TestUtil.createTable(conn, "temp", "col oid");
    conn.setAutoCommit(false);
  }

  @After
  public void tearDown() throws Exception {
    conn.setAutoCommit(true);
    TestUtil.dropTable(conn, "temp");
    TestUtil.closeDB(conn);
  }

  @Test
  public void testSetNull() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO temp VALUES (?)");
    pstmt.setNull(1, Types.VARBINARY);
    pstmt.executeUpdate();
    pstmt.setNull(1, Types.BLOB);
    pstmt.executeUpdate();
    pstmt.setNull(1, Types.CLOB);
    pstmt.executeUpdate();
    pstmt.close();
  }

  @Test
  public void testBinaryStream() throws Exception {
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO temp VALUES (?)");
    pstmt.setBinaryStream(1, new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5}), 5);
    assertTrue((pstmt.executeUpdate() == 1));
    pstmt.close();

    pstmt = conn.prepareStatement("SELECT col FROM temp LIMIT 1");
    ResultSet rs = pstmt.executeQuery();

    assertTrue("No results from query", rs.next());

    InputStream in = rs.getBinaryStream(1);
    int data;
    int i = 1;
    while ((data = in.read()) != -1) {
      assertEquals(i++, data);
    }
    rs.close();
    pstmt.close();
  }
}
