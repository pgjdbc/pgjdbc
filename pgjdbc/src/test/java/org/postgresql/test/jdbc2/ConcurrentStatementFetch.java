/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class ConcurrentStatementFetch extends BaseTest4 {

  private final AutoCommit autoCommit;
  private final int fetchSize;

  public ConcurrentStatementFetch(AutoCommit autoCommit, int fetchSize, BinaryMode binaryMode) {
    this.autoCommit = autoCommit;
    this.fetchSize = fetchSize;
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "{index}: fetch(autoCommit={0}, fetchSize={1}, binaryMode={2})")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (AutoCommit autoCommit : AutoCommit.values()) {
      for (int fetchSize : new int[]{1, 2, 20}) {
        for (BinaryMode binaryMode : BinaryMode.values()) {
          ids.add(new Object[]{autoCommit, fetchSize, binaryMode});
        }
      }
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    con.setAutoCommit(autoCommit == AutoCommit.YES);
  }

  @Test
  public void testFetchTwoStatements() throws Exception {
    // This test definitely fails at 8.2 in autocommit=false, and works with 8.4+
    Assume.assumeTrue(autoCommit == AutoCommit.YES
        || TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4));
    PreparedStatement ps1 = null;
    PreparedStatement ps2 = null;
    try {
      ps1 = con.prepareStatement("select * from generate_series(0, 9)");
      ps1.setFetchSize(fetchSize);
      ResultSet rs1 = ps1.executeQuery();
      ps2 = con.prepareStatement("select * from generate_series(10, 19)");
      ps2.setFetchSize(fetchSize);
      ResultSet rs2 = ps2.executeQuery();

      for (int i = 0; i < 10; i++) {
        Assert.assertTrue(rs1.next());
        Assert.assertTrue(rs2.next());
        Assert.assertEquals("Row#" + i + ", resultset 1", i, rs1.getInt(1));
        Assert.assertEquals("Row#" + i + ", resultset 2", i + 10, rs2.getInt(1));
      }
      Assert.assertFalse(rs1.next());
      Assert.assertFalse(rs2.next());
    } finally {
      TestUtil.closeQuietly(ps1);
      TestUtil.closeQuietly(ps2);
    }
  }
}
