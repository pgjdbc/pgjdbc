/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PropertyLoader;

import org.junit.Test;

import java.util.Properties;

public class PropertyLoaderTest {

  /*
   * This tests the acceptsURL() method with a couple of well and poorly formed jdbc urls.
   */
  @Test
  public void testAcceptsURL() throws Exception {
    TestUtil.initDriver(); // Set up log levels, etc.

    // Load the driver (note clients should never do it this way!)
    PropertyLoader ldr = new PropertyLoader();
    assertNotNull(ldr);

    // These are always correct
    verifyUrl(ldr, "jdbc:postgresql:test", null, null, "test");
    verifyUrl(ldr, "jdbc:postgresql://localhost/test", "localhost", "5432", "test");
    verifyUrl(ldr, "jdbc:postgresql://localhost:5432/test", "localhost", "5432", "test");
    verifyUrl(ldr, "jdbc:postgresql://127.0.0.1/anydbname", "127.0.0.1", "5432", "anydbname");
    verifyUrl(ldr, "jdbc:postgresql://127.0.0.1:5433/hidden", "127.0.0.1", "5433", "hidden");
    verifyUrl(ldr, "jdbc:postgresql://[::1]:5740/db", "[::1]", "5740", "db");

    // failover urls
    verifyUrl(ldr, "jdbc:postgresql://localhost,127.0.0.1:5432/test", "localhost,127.0.0.1",
        "5432,5432", "test");
    verifyUrl(ldr, "jdbc:postgresql://localhost:5433,127.0.0.1:5432/test", "localhost,127.0.0.1",
        "5433,5432", "test");
    verifyUrl(ldr, "jdbc:postgresql://[::1],[::1]:5432/db", "[::1],[::1]", "5432,5432", "db");
    verifyUrl(ldr, "jdbc:postgresql://[::1]:5740,127.0.0.1:5432/db", "[::1],127.0.0.1", "5740,5432",
        "db");
  }

  private void verifyUrl(PropertyLoader ldr, String url, String hosts, String ports, String dbName) throws Exception {
    Properties p = PropertyLoader.parseURL(url);
    assertTrue(url != null);
    assertEquals(url, dbName, p.getProperty(PGProperty.PG_DBNAME.getName()));
    assertEquals(url, hosts, p.getProperty(PGProperty.PG_HOST.getName()));
    assertEquals(url, ports, p.getProperty(PGProperty.PG_PORT.getName()));
  }
}
