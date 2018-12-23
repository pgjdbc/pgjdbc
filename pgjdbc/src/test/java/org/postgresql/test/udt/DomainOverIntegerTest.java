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
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tests a user-defined data type mapping to a <a href="https://www.postgresql.org/docs/current/sql-createdomain.html">DOMAIN</a>
 * over an integer type.  This tests the type inference because the server sends back the oid of the base type for domains.
 */
public class DomainOverIntegerTest {

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

  // TODO: Test arrays
}
