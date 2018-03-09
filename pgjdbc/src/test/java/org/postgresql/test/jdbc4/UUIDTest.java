/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

@RunWith(Parameterized.class)
public class UUIDTest extends BaseTest4 {

  public UUIDTest(BinaryMode binaryMode, StringType stringType) {
    setBinaryMode(binaryMode);
    setStringType(stringType);
  }

  @Parameterized.Parameters(name = "binary={0}, stringType={1}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      for (StringType stringType : StringType.values()) {
        ids.add(new Object[]{binaryMode, stringType});
      }
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeMinimumServerVersion(ServerVersion.v8_3);

    Statement stmt = con.createStatement();
    stmt.execute("CREATE TEMP TABLE uuidtest(id uuid)");
    stmt.close();
  }

  @Override
  public void tearDown() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("DROP TABLE IF EXISTS uuidtest");
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

  @Test
  public void testUUIDString() throws SQLException {
    String uuid = "0dcdf03a-058c-4fa3-b210-8385cb6810d5";
    PreparedStatement ps = con.prepareStatement("INSERT INTO uuidtest VALUES (?)");
    ps.setString(1, uuid);
    try {
      ps.executeUpdate();
      if (getStringType() == StringType.VARCHAR) {
        Assert.fail(
            "setString(, uuid) should fail to insert value into UUID column when stringType=varchar."
                + " Expecting error <<column \"id\" is of type uuid but expression is of type character varying>>");
      }
    } catch (SQLException e) {
      if (getStringType() == StringType.VARCHAR
          && PSQLState.DATATYPE_MISMATCH.getState().equals(e.getSQLState())) {
        // The following error is expected in stringType=varchar mode
        // ERROR: column "id" is of type uuid but expression is of type character varying
        return;
      }
      throw e;
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }

}

