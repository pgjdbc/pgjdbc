/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tests a user-defined data type mapping to a <a href="https://www.postgresql.org/docs/current/sql-createdomain.html">DOMAIN</a>
 * over an integer type.  This tests the type inference because the server sends back the oid of the base type for domains.
 */
public class DomainOverIntegerTest {
  private Connection con;

  // Set up the fixture for this testcase: the tables for this test.
  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();

    TestUtil.createDomain(con, PortImpl.SQL_TYPE, "integer", "value >= 1 and value <= 65535");
    TestUtil.createTable(con, "testport", "port " + PortImpl.SQL_TYPE + " primary key");

    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put(PortImpl.SQL_TYPE, PortImpl.class);
    con.setTypeMap(typeMap);

    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testport VALUES (?)");
    try {
      // Can add as the base type
      pstmt.setInt(1, 1024);
      pstmt.executeUpdate();
      // Can also insert as Port object
      pstmt.setObject(1, new PortImpl(16384));
      pstmt.executeUpdate();
      pstmt.setObject(1, new PortImpl(1337));
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
    TestUtil.dropDomain(con, PortImpl.SQL_TYPE);

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

  @Test
  public void testGetObjectUDTDirect() throws Exception {
    Set<Port> ports = new HashSet<Port>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM testport");
      try {
        while (result.next()) {
          ports.add(result.getObject(1, PortImpl.class));
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
}
