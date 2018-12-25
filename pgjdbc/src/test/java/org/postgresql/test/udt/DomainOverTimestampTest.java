/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import org.junit.Assert;
import org.junit.Test;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tests a user-defined data type mapping to a <a href="https://www.postgresql.org/docs/current/sql-createdomain.html">DOMAIN</a>
 * over a timestamp type.  This tests the type inference because the server sends back the oid of the base type for domains.
 */
public class DomainOverTimestampTest extends DomainTest {

  @Test(expected = SQLException.class)
  public void testInsertInvalidTimestamp() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO sales VALUES (?)");
    try {
      pstmt.setTimestamp(1, TS_INVALID);
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  @Test(expected = SQLException.class)
  public void testInsertInvalidUDT() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO sales VALUES (?)");
    try {
      pstmt.setObject(1, new SaleDate(TS_INVALID));
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test
  public void testGetObjectUDT() throws Exception {
    Set<SaleDate> dates = new HashSet<SaleDate>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM sales");
      try {
        while (result.next()) {
          dates.add(result.getObject(1, SaleDate.class));
        }
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }

    Assert.assertEquals(
        new HashSet<SaleDate>(Arrays.asList(new SaleDate(TS3), new SaleDate(TS1), new SaleDate(TS2))),
        dates
    );
  }
  //#endif

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test
  public void testNullRow() throws Exception {
    Statement stmt = con.createStatement();
    try {
      stmt.executeUpdate("INSERT INTO sales VALUES (NULL)");
      ResultSet result = stmt.executeQuery("SELECT * FROM sales WHERE date IS NULL");
      try {
        while (result.next()) {
          Assert.assertNull(result.getObject("date", SaleDate.class));
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
  public void testOverrideBaseType() throws Exception {
    // Add base type from "timestamptz" to go to SaleDate
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("timestamptz", SaleDate.class);
    con.setTypeMap(typemap);

    Set<SaleDate> dates = new HashSet<SaleDate>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM sales");
      try {
        while (result.next()) {
          dates.add((SaleDate)result.getObject(1));
        }
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }

    Assert.assertEquals(
        new HashSet<SaleDate>(Arrays.asList(new SaleDate(TS3), new SaleDate(TS1), new SaleDate(TS2))),
        dates
    );
  }

  @Test
  public void testParamMapOverridesConnectionMap() throws Exception {
    // Add base type from "timestamptz" to go to SaleDate
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("timestamptz", SaleDate.class);
    con.setTypeMap(typemap);

    Set<Timestamp> dates = new HashSet<Timestamp>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM sales");
      try {
        while (result.next()) {
          dates.add((Timestamp)result.getObject(1, Collections.<String, Class<?>>emptyMap()));
        }
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }

    Assert.assertEquals(
        new HashSet<Timestamp>(Arrays.asList(TS3, TS1, TS2)),
        dates
    );
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test(expected = SQLException.class)
  public void testTypeUnassignable() throws Exception {
    // Add base type from "timestamptz" to go to SaleDate
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("timestamptz", SaleDate.class);
    con.setTypeMap(typemap);

    Set<Port> ports = new HashSet<Port>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM sales");
      try {
        while (result.next()) {
          ports.add(result.getObject("date", Port.class));
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
      ResultSet result = stmt.executeQuery("SELECT ARRAY(SELECT date::timestamp with time zone FROM sales ORDER BY date) AS dates");
      try {
        Assert.assertTrue(result.next());
        Array array = result.getArray(1);
        // Get the array as base type
        Timestamp[] timestamps = (Timestamp[])array.getArray();
        Assert.assertArrayEquals(new Timestamp[] {TS1, TS2, TS3}, timestamps);
        // Get the array as domain type
        SaleDate[] dates = (SaleDate[])array.getArray(Collections.<String, Class<?>>singletonMap("timestamptz", SaleDate.class));
        Assert.assertArrayEquals(new SaleDate[] {new SaleDate(TS1), new SaleDate(TS2), new SaleDate(TS3)}, dates);
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
      ResultSet result = stmt.executeQuery("SELECT ARRAY[(SELECT ARRAY(SELECT date::timestamptz FROM sales ORDER BY date)), (SELECT ARRAY(SELECT date::timestamptz FROM sales ORDER BY date)), (SELECT ARRAY(SELECT date::timestamptz FROM sales ORDER BY date))] AS dates");
      try {
        Assert.assertTrue(result.next());
        Array array = result.getArray(1);
        // Get the array as base type
        Timestamp[][] timestamps = (Timestamp[][])array.getArray();
        Assert.assertEquals(3, timestamps.length);
        Assert.assertArrayEquals(new Timestamp[] {TS1, TS2, TS3}, timestamps[0]);
        Assert.assertArrayEquals(new Timestamp[] {TS1, TS2, TS3}, timestamps[1]);
        Assert.assertArrayEquals(new Timestamp[] {TS1, TS2, TS3}, timestamps[2]);
        // Get the array as domain type
        SaleDate[][] dates = (SaleDate[][])array.getArray(Collections.<String, Class<?>>singletonMap("timestamptz", SaleDate.class));
        Assert.assertEquals(3, dates.length);
        Assert.assertArrayEquals(new SaleDate[] {new SaleDate(TS1), new SaleDate(TS2), new SaleDate(TS3)}, dates[0]);
        Assert.assertArrayEquals(new SaleDate[] {new SaleDate(TS1), new SaleDate(TS2), new SaleDate(TS3)}, dates[1]);
        Assert.assertArrayEquals(new SaleDate[] {new SaleDate(TS1), new SaleDate(TS2), new SaleDate(TS3)}, dates[2]);
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

  // TODO: Benchmark large arrays: select array(select * from generate_series(1, 1000));
}
