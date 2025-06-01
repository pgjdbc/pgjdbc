/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;

@ParameterizedClass(name = "{index}: fetch(autoCommit={0}, fetchSize={1}, binaryMode={2})")
@MethodSource("data")
public class ConcurrentStatementFetchTest extends BaseTest4 {

  private final AutoCommit autoCommit;
  private final int fetchSize;

  public ConcurrentStatementFetchTest(AutoCommit autoCommit, int fetchSize, BinaryMode binaryMode) {
    this.autoCommit = autoCommit;
    this.fetchSize = fetchSize;
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
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
    assumeTrue(autoCommit == AutoCommit.YES
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
        assertTrue(rs1.next());
        assertTrue(rs2.next());
        assertEquals(i, rs1.getInt(1), "Row#" + i + ", resultset 1");
        assertEquals(i + 10, rs2.getInt(1), "Row#" + i + ", resultset 2");
      }
      assertFalse(rs1.next());
      assertFalse(rs2.next());
    } finally {
      TestUtil.closeQuietly(ps1);
      TestUtil.closeQuietly(ps2);
    }
  }
}
