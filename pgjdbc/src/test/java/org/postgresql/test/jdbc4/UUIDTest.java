/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.UUID;

public class UUIDTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    Statement stmt = con.createStatement();
    stmt.execute("CREATE TEMP TABLE uuidtest(id uuid)");
    stmt.close();
  }

  @Override
  public void tearDown() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("DROP TABLE uuidtest");
    stmt.close();
    super.tearDown();
  }

  @Test
  public void testUUID() throws SQLException {
    UUID uuid = UUID.randomUUID();
    PreparedStatement ps = con.prepareStatement("INSERT INTO uuidtest VALUES (?)");
    ps.setObject(1, uuid, Types.OTHER);
    ps.executeUpdate();
    ps.close();

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT id FROM uuidtest");
    assertTrue(rs.next());

    UUID uuid2 = (UUID) rs.getObject(1);
    assertEquals(uuid, rs.getObject(1));
    assertEquals(uuid.toString(), rs.getString(1));

    rs.close();
    stmt.close();
  }

}

