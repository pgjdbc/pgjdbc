/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2.optional;

import static org.junit.Assert.assertEquals;

import org.postgresql.ds.common.BaseDataSource;

import org.junit.Test;

import java.io.IOException;
import javax.naming.NamingException;

public class BaseDataSourceFailoverUrlsTest {

  /**
  * tests that failover urls survive the parse/rebuild roundtrip with and without specific ports
  */
  @Test
  public void testFailoverUrl() throws NamingException, ClassNotFoundException, IOException {
    String[][] tests = new String[][] {
      new String[] { "jdbc:postgresql://server/database", "jdbc:postgresql://server:5432/database" },
      new String[] { "jdbc:postgresql://server1,server2/database", "jdbc:postgresql://server1:5432,server2:5432/database"},
      new String[] { "jdbc:postgresql://server1:1234,server2:2345/database", "jdbc:postgresql://server1:1234,server2:2345/database"},
      new String[] { "jdbc:postgresql://server1,server2:2345/database", "jdbc:postgresql://server1:5432,server2:2345/database"},
      new String[] { "jdbc:postgresql://server1:2345,server2/database", "jdbc:postgresql://server1:2345,server2:5432/database"},
    };

    for (String[] test : tests) {
      BaseDataSource bds = new BaseDataSource() {
        @Override
        public String getDescription() {
          return "BaseDataSourceFailoverUrlsTest-DS";
        }
      };

      bds.setUrl(test[0]);
      assertEquals(test[0], test[1], bds.getURL().replaceAll("\\?.*$", ""));

      bds.setFromReference(bds.getReference());
      assertEquals(test[0], test[1], bds.getURL().replaceAll("\\?.*$", ""));

      bds.initializeFrom(bds);
      assertEquals(test[0], test[1], bds.getURL().replaceAll("\\?.*$", ""));
    }
  }
}
