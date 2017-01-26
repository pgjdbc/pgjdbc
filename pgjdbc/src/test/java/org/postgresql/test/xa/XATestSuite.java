/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.xa;

import org.postgresql.test.TestUtil;

import junit.framework.JUnit4TestAdapter;
import junit.framework.TestSuite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class XATestSuite extends TestSuite {
  public static TestSuite suite() throws Exception {
    TestSuite suite = new TestSuite();
    Connection connection = TestUtil.openDB();

    try {
      Statement stmt = connection.createStatement();
      ResultSet rs = stmt.executeQuery("SHOW max_prepared_transactions");
      rs.next();
      int mpt = rs.getInt(1);
      if (mpt > 0) {
        suite.addTest(new JUnit4TestAdapter(XADataSourceTest.class));
      } else {
        System.out.println("Skipping XA tests because max_prepared_transactions = 0.");
      }
      rs.close();
      stmt.close();
    } finally {
      connection.close();
    }
    return suite;
  }
}
