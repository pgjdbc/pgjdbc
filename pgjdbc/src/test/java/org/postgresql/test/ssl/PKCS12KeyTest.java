/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.Assert;
import org.junit.Test;

import java.sql.Connection;
import java.util.Properties;

public class PKCS12KeyTest {
  @Test
  public void TestGoodClientP12() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    props.put(TestUtil.DATABASE_PROP, "hostssldb");
    PGProperty.SSL_MODE.set(props, "prefer");
    PGProperty.SSL_KEY.set(props, TestUtil.getSslTestCertPath("goodclient.p12"));

    try (Connection conn = TestUtil.openDB(props)) {
      boolean sslUsed = TestUtil.queryForBoolean(conn, "SELECT ssl_is_used()");
      Assert.assertTrue("SSL should be in use", sslUsed);
    }
  }
}
