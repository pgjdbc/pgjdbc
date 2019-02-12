/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
public class DomainOverIntegerTest extends DomainTest {

  private static final Logger LOGGER = Logger.getLogger(DomainOverIntegerTest.class.getName());

  /**
   * A higher number of iterations is only meaningful when actively benchmarking the implementation performance.
   * Try to save build time, especially if the build is doing extensive logging.
   */
  private static final int BENCHMARK_ITERATIONS = 1;

  /**
   * The number of array elements to benchmark.
   * Maximum: 65535
   */
  private static final int BENCHMARK_ELEMENTS = 512;

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
        new HashSet<Port>(Arrays.asList(new PortImpl(PORT1), new PortImpl(PORT2), new PortImpl(PORT3))),
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
        new HashSet<Port>(Arrays.asList(new PortImpl(PORT1), new PortImpl(PORT2), new PortImpl(PORT3))),
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
        new HashSet<Port>(Arrays.asList(new PortImpl(PORT1), new PortImpl(PORT2), new PortImpl(PORT3))),
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
        new HashSet<Integer>(Arrays.asList(PORT1, PORT2, PORT3)),
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
          Assert.fail();
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
        Assert.assertArrayEquals(new Integer[] {PORT1, PORT2, PORT3}, ints);
        // Get the array as domain type
        PortImpl[] ports = (PortImpl[])array.getArray(Collections.<String, Class<?>>singletonMap("int4", PortImpl.class));
        Assert.assertArrayEquals(new PortImpl[] {new PortImpl(PORT1), new PortImpl(PORT2), new PortImpl(PORT3)}, ports);
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
        Assert.assertArrayEquals(new Integer[] {PORT1, PORT2, PORT3}, ints[0]);
        Assert.assertArrayEquals(new Integer[] {PORT1, PORT2, PORT3}, ints[1]);
        Assert.assertArrayEquals(new Integer[] {PORT1, PORT2, PORT3}, ints[2]);
        // Get the array as domain type
        PortImpl[][] ports = (PortImpl[][])array.getArray(Collections.<String, Class<?>>singletonMap("int4", PortImpl.class));
        Assert.assertEquals(3, ports.length);
        Assert.assertArrayEquals(new PortImpl[] {new PortImpl(PORT1), new PortImpl(PORT2), new PortImpl(PORT3)}, ports[0]);
        Assert.assertArrayEquals(new PortImpl[] {new PortImpl(PORT1), new PortImpl(PORT2), new PortImpl(PORT3)}, ports[1]);
        Assert.assertArrayEquals(new PortImpl[] {new PortImpl(PORT1), new PortImpl(PORT2), new PortImpl(PORT3)}, ports[2]);
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
    for (int iter = 0; iter < BENCHMARK_ITERATIONS; iter++) {
      Statement stmt = con.createStatement();
      try {
        // PostgreSQL doesn't support arrays of domains, use base type
        long startNanos = System.nanoTime();
        ResultSet result = stmt.executeQuery("SELECT ARRAY(SELECT * FROM generate_series(1, " + BENCHMARK_ELEMENTS + ")) AS ports");
        try {
          long endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "Query large array in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));

          startNanos = System.nanoTime();
          Assert.assertTrue(result.next());
          Array array = result.getArray(1);
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "ResultSet.getArray(int) in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));

          // Get the array as base type
          startNanos = System.nanoTime();
          Integer[] ints = (Integer[])array.getArray();
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "array.getArray() #1 of int[" + BENCHMARK_ELEMENTS + "] in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));
          startNanos = System.nanoTime();
          ints = (Integer[])array.getArray();
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "array.getArray() #2 of int[" + BENCHMARK_ELEMENTS + "] in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));
          startNanos = System.nanoTime();
          Assert.assertEquals(BENCHMARK_ELEMENTS, ints.length);
          for (int i = 1; i <= BENCHMARK_ELEMENTS; i++) {
            Assert.assertEquals((Integer)i, ints[i - 1]);
          }
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "Tested Array(int[" + BENCHMARK_ELEMENTS + "]) in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));

          // Get the array as domain type
          startNanos = System.nanoTime();
          PortImpl[] ports = (PortImpl[])array.getArray(Collections.<String, Class<?>>singletonMap("int4", PortImpl.class));
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "array.getArray() #1 of PortImpl[" + BENCHMARK_ELEMENTS + "] in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));
          startNanos = System.nanoTime();
          ports = (PortImpl[])array.getArray(Collections.<String, Class<?>>singletonMap("int4", PortImpl.class));
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "array.getArray() #2 of PortImpl[" + BENCHMARK_ELEMENTS + "] in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));
          startNanos = System.nanoTime();
          Assert.assertEquals(BENCHMARK_ELEMENTS, ports.length);
          for (int i = 1; i <= BENCHMARK_ELEMENTS; i++) {
            Assert.assertEquals(new PortImpl(i), ports[i - 1]);
          }
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "Tested Array(PortImpl[" + BENCHMARK_ELEMENTS + "]) in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));

          // Get the result set as base type
          startNanos = System.nanoTime();
          ResultSet arrayResult = array.getResultSet();
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "array.getResultSet() #1 of int[" + BENCHMARK_ELEMENTS + "] in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));
          arrayResult.close();
          startNanos = System.nanoTime();
          arrayResult = array.getResultSet();
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "array.getResultSet() #2 of int[" + BENCHMARK_ELEMENTS + "] in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));
          startNanos = System.nanoTime();
          for (int i = 1; i <= BENCHMARK_ELEMENTS; i++) {
            Assert.assertTrue(arrayResult.next());
            Assert.assertEquals(i, arrayResult.getInt(2));
            Assert.assertFalse(arrayResult.wasNull());
          }
          Assert.assertFalse(arrayResult.next());
          arrayResult.close();
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "Tested ResultSet(int[" + BENCHMARK_ELEMENTS + "]) in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));

          // Get the result set as domain type
          startNanos = System.nanoTime();
          arrayResult = array.getResultSet(Collections.<String, Class<?>>singletonMap("int4", PortImpl.class));
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "array.getResultSet() #1 of PortImpl[" + BENCHMARK_ELEMENTS + "] in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));
          arrayResult.close();
          startNanos = System.nanoTime();
          arrayResult = array.getResultSet(Collections.<String, Class<?>>singletonMap("int4", PortImpl.class));
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "array.getResultSet() #2 of PortImpl[" + BENCHMARK_ELEMENTS + "] in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));
          startNanos = System.nanoTime();
          for (int i = 1; i <= BENCHMARK_ELEMENTS; i++) {
            Assert.assertTrue(arrayResult.next());
            Assert.assertEquals(new PortImpl(i), arrayResult.getObject(2));
            Assert.assertFalse(arrayResult.wasNull());
          }
          Assert.assertFalse(arrayResult.next());
          arrayResult.close();
          endNanos = System.nanoTime();
          LOGGER.log(Level.INFO, "Tested ResultSet(PortImpl[" + BENCHMARK_ELEMENTS + "]) in {0} ms", BigDecimal.valueOf(endNanos - startNanos, 6));

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
