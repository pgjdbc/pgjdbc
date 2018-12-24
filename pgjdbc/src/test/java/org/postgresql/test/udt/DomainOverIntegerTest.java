/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import java.math.BigDecimal;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tests a user-defined data type mapping to a <a href="https://www.postgresql.org/docs/current/sql-createdomain.html">DOMAIN</a>
 * over an integer type.  This tests the type inference because the server sends back the oid of the base type for domains.
 */
public class DomainOverIntegerTest {

  private static final Logger LOGGER = Logger.getLogger(DomainOverIntegerTest.class.getName());

  private static final String EMAIL_SQL_TYPE = "public.\"Email\"";
  private static final String PORT_SQL_TYPE = "\"port\"";

  private Connection con;

  // Set up the fixture for this testcase: the tables for this test.
  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();

    TestUtil.createDomain(con, PORT_SQL_TYPE, "integer", "value >= 1 and value <= 65535");
    TestUtil.createTable(con, "testport", "port " + PORT_SQL_TYPE + " primary key");

    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put(PORT_SQL_TYPE, PortImpl.class);
    typeMap.put(EMAIL_SQL_TYPE, Email.class);
    con.setTypeMap(typeMap);

    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testport VALUES (?)");
    try {
      // Can add as the base type
      pstmt.setInt(1, 1024);
      pstmt.executeUpdate();
      // Can also insert as Port object
      pstmt.setObject(1, new PortImpl(16384));
      pstmt.executeUpdate();
      pstmt.setObject(1, new PortImpl(1337), Types.OTHER);
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  // Tear down the fixture for this test case.
  @After
  public void tearDown() throws Exception {
    TestUtil.closeDB(con);

    con = TestUtil.openDB();

    TestUtil.dropTable(con, "testport");
    TestUtil.dropDomain(con, PORT_SQL_TYPE);

    TestUtil.closeDB(con);
  }

  @Test(expected = SQLException.class)
  public void testInsertPortTooLowInteger() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testport VALUES (?)");
    try {
      pstmt.setInt(1, 0);
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  @Test(expected = SQLException.class)
  public void testInsertPortTooLowUDT() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testport VALUES (?)");
    try {
      pstmt.setObject(1, new PortImpl(0));
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  @Test
  public void testInsertMinimumPortInteger() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testport VALUES (?)");
    try {
      pstmt.setInt(1, 1);
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  @Test
  public void testInsertMinimumPortUDT() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testport VALUES (?)");
    try {
      pstmt.setObject(1, new PortImpl(1));
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  @Test
  public void testInsertMaximumPortInteger() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testport VALUES (?)");
    try {
      pstmt.setInt(1, 65535);
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  @Test
  public void testInsertMaximumPortUDT() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testport VALUES (?)");
    try {
      pstmt.setObject(1, new PortImpl(65535));
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  @Test(expected = SQLException.class)
  public void testInsertPortTooHighInteger() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testport VALUES (?)");
    try {
      pstmt.setInt(1, 65536);
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  @Test(expected = SQLException.class)
  public void testInsertPortTooHighUDT() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testport VALUES (?)");
    try {
      pstmt.setObject(1, new PortImpl(65536));
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test
  public void testGetObjectUDTDirect() throws Exception {
    Set<Port> ports = new HashSet<Port>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM testport");
      try {
        while (result.next()) {
          ports.add(result.getObject("port", PortImpl.class));
        }
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }

    Assert.assertEquals(
        new HashSet<Port>(Arrays.asList(new PortImpl(1024), new PortImpl(1337), new PortImpl(16384))),
        ports
    );
  }
  //#endif

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test
  public void testGetObjectUDTInherited() throws Exception {
    Set<Port> ports = new HashSet<Port>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM testport");
      try {
        while (result.next()) {
          ports.add(result.getObject(1, Port.class));
        }
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }

    Assert.assertEquals(
        new HashSet<Port>(Arrays.asList(new PortImpl(1024), new PortImpl(1337), new PortImpl(16384))),
        ports
    );
  }
  //#endif

  @Test
  public void testOverrideBaseType() throws Exception {
    // Add base type from "int4" to go to PortImpl
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("int4", PortImpl.class);
    con.setTypeMap(typemap);

    Set<Port> ports = new HashSet<Port>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM testport");
      try {
        while (result.next()) {
          ports.add((Port)result.getObject("port"));
        }
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }

    Assert.assertEquals(
        new HashSet<Port>(Arrays.asList(new PortImpl(1024), new PortImpl(1337), new PortImpl(16384))),
        ports
    );
  }

  @Test
  public void testParamMapOverridesConnectionMap() throws Exception {
    // Add base type from "int4" to go to PortImpl
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("int4", PortImpl.class);
    con.setTypeMap(typemap);

    Set<Integer> ports = new HashSet<Integer>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM testport");
      try {
        while (result.next()) {
          ports.add((Integer)result.getObject("port", Collections.<String, Class<?>>emptyMap()));
        }
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }

    Assert.assertEquals(
        new HashSet<Integer>(Arrays.asList(1024, 1337, 16384)),
        ports
    );
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test(expected = SQLException.class)
  public void testTypeUnassignable() throws Exception {
    // Add base type from "int4" to go to PortImpl
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("int4", PortImpl.class);
    con.setTypeMap(typemap);

    Set<Email> emails = new HashSet<Email>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM testport");
      try {
        while (result.next()) {
          emails.add(result.getObject(1, Email.class));
        }
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }
  }
  //#endif

  @Test
  public void testGetArray() throws Exception {
    Statement stmt = con.createStatement();
    try {
      // PostgreSQL doesn't support arrays of domains, use base type
      ResultSet result = stmt.executeQuery("SELECT ARRAY(SELECT port::int4 FROM testport ORDER BY port) AS ports");
      try {
        Assert.assertTrue(result.next());
        Array array = result.getArray("ports");
        // Get the array as base type
        Integer[] ints = (Integer[])array.getArray();
        Assert.assertArrayEquals(new Integer[] {1024, 1337, 16384}, ints);
        // Get the array as domain type
        PortImpl[] ports = (PortImpl[])array.getArray(Collections.singletonMap("int4", PortImpl.class));
        Assert.assertArrayEquals(new PortImpl[] {new PortImpl(1024), new PortImpl(1337), new PortImpl(16384)}, ports);
        // TODO: ResultSet variations
        // Must have only been one row
        Assert.assertFalse(result.next());
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }
  }

  @Test
  public void testGetArray2D() throws Exception {
    Statement stmt = con.createStatement();
    try {
      // PostgreSQL doesn't support arrays of domains, use base type
      ResultSet result = stmt.executeQuery("SELECT ARRAY[(SELECT ARRAY(SELECT port::int4 FROM testport ORDER BY port)), (SELECT ARRAY(SELECT port::int4 FROM testport ORDER BY port)), (SELECT ARRAY(SELECT port::int4 FROM testport ORDER BY port))] AS ports");
      try {
        Assert.assertTrue(result.next());
        Array array = result.getArray(1);
        // Get the array as base type
        Integer[][] ints = (Integer[][])array.getArray();
        Assert.assertEquals(3, ints.length);
        Assert.assertArrayEquals(new Integer[] {1024, 1337, 16384}, ints[0]);
        Assert.assertArrayEquals(new Integer[] {1024, 1337, 16384}, ints[1]);
        Assert.assertArrayEquals(new Integer[] {1024, 1337, 16384}, ints[2]);
        // Get the array as domain type
        PortImpl[][] ports = (PortImpl[][])array.getArray(Collections.singletonMap("int4", PortImpl.class));
        Assert.assertEquals(3, ports.length);
        Assert.assertArrayEquals(new PortImpl[] {new PortImpl(1024), new PortImpl(1337), new PortImpl(16384)}, ports[0]);
        Assert.assertArrayEquals(new PortImpl[] {new PortImpl(1024), new PortImpl(1337), new PortImpl(16384)}, ports[1]);
        Assert.assertArrayEquals(new PortImpl[] {new PortImpl(1024), new PortImpl(1337), new PortImpl(16384)}, ports[2]);
        // TODO: ResultSet variations
        // TODO: Test with inference, too via resultset
        // Must have only been one row
        Assert.assertFalse(result.next());
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }
  }


  @Test
  public void testLargeArray() throws Exception {
    for (int iter = 0; iter < 10; iter++) {
      Statement stmt = con.createStatement();
      try {
        // PostgreSQL doesn't support arrays of domains, use base type
        ResultSet result = stmt.executeQuery("SELECT ARRAY(SELECT * FROM generate_series(1, 65535)) AS ports");
        try {
          Assert.assertTrue(result.next());
          Array array = result.getArray(1);

          // Get the array as base type
          long startNanos = System.nanoTime();
          Integer[] ints = (Integer[])array.getArray();
          long endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "array.getArray() of int[65535] in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));
          startNanos = System.nanoTime();
          Assert.assertEquals(65535, ints.length);
          for (int i = 1; i <= 65535; i++) {
            Assert.assertEquals((Integer)i, ints[i - 1]);
          }
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "Tested Array(int[65535]) in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));

          // Get the array as domain type
          startNanos = System.nanoTime();
          PortImpl[] ports = (PortImpl[])array.getArray(Collections.singletonMap("int4", PortImpl.class));
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "array.getArray() of PortImpl[65535] in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));
          startNanos = System.nanoTime();
          Assert.assertEquals(65535, ports.length);
          for (int i = 1; i <= 65535; i++) {
            Assert.assertEquals(new PortImpl(i), ports[i - 1]);
          }
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "Tested Array(PortImpl[65535]) in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));

          // Get the result set as base type
          startNanos = System.nanoTime();
          ResultSet arrayResult = array.getResultSet();
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "array.getResultSet() of int[65535] in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));
          startNanos = System.nanoTime();
          for (int i = 1; i <= 65535; i++) {
            Assert.assertTrue(arrayResult.next());
            Assert.assertEquals(i, arrayResult.getInt(2));
            Assert.assertFalse(arrayResult.wasNull());
          }
          Assert.assertFalse(arrayResult.next());
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "Tested ResultSet(int[65535]) in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));

          // Get the result set as domain type
          startNanos = System.nanoTime();
          arrayResult = array.getResultSet(Collections.singletonMap("int4", PortImpl.class));
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "array.getResultSet() of PortImpl[65535] in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));
          startNanos = System.nanoTime();
          for (int i = 1; i <= 65535; i++) {
            Assert.assertTrue(arrayResult.next());
            Assert.assertEquals(new PortImpl(i), arrayResult.getObject(2));
            Assert.assertFalse(arrayResult.wasNull());
          }
          Assert.assertFalse(arrayResult.next());
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "Tested ResultSet(PortImpl[65535]) in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));

          // TODO: Time iteration of results, too
          // TODO: Test with inference, too via resultset
          // Must have only been one row
          Assert.assertFalse(result.next());
        } finally {
          result.close();
        }
      } finally {
        stmt.close();
      }
    }
  }
}
