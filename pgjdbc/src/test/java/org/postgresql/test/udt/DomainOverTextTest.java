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
 * over a text type.  This tests the type inference because the server sends back the oid of the base type for domains.
 */
public class DomainOverTextTest {
  private Connection con;

  // Set up the fixture for this testcase: the tables for this test.
  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();

    TestUtil.createDomain(con, Email.SQL_TYPE, "text", "value like '%_@_%'"); // Not a true email validation - just for testing
    TestUtil.createTable(con, "testemail", "email " + Email.SQL_TYPE);

    Map<String, Class<?>> typeMap = con.getTypeMap();
    typeMap.put(Email.SQL_TYPE, Email.class);
    typeMap.put(PortImpl.SQL_TYPE, Port.class);
    con.setTypeMap(typeMap);

    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testemail VALUES (?)");
    try {
      // Can add as the base type
      pstmt.setString(1, "test@example.com");
      pstmt.executeUpdate();
      // Can also insert as Email object
      pstmt.setObject(1, new Email("test2@example.com"));
      pstmt.executeUpdate();
      pstmt.setObject(1, new Email("foo@bar.baz"));
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

    TestUtil.dropTable(con, "testemail");
    TestUtil.dropDomain(con, Email.SQL_TYPE);

    TestUtil.closeDB(con);
  }

  @Test(expected = SQLException.class)
  public void testInsertInvalidEmailString() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testemail VALUES (?)");
    try {
      pstmt.setString(1, "invalidemail");
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  @Test(expected = SQLException.class)
  public void testInsertInvalidEmailUDT() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testemail VALUES (?)");
    try {
      pstmt.setObject(1, new Email("invalidUDT"));
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
  }

  @Test
  public void testGetObjectUDT() throws Exception {
    Set<Email> emails = new HashSet<Email>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM testemail");
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

    Assert.assertEquals(
        new HashSet<Email>(Arrays.asList(new Email("test@example.com"), new Email("foo@bar.baz"), new Email("test2@example.com"))),
        emails
    );
  }

  @Test
  public void testNullRow() throws Exception {
    Statement stmt = con.createStatement();
    try {
      stmt.executeUpdate("INSERT INTO testemail VALUES (NULL)");
      ResultSet result = stmt.executeQuery("SELECT * FROM testemail WHERE email IS NULL");
      try {
        while (result.next()) {
          Assert.assertNull(result.getObject("email", Email.class));
        }
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }
  }

  @Test
  public void testOverrideBaseType() throws Exception {
    // Add base type from "text" to go to Email
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("text", Email.class);
    con.setTypeMap(typemap);

    Set<Email> emails = new HashSet<Email>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM testemail");
      try {
        while (result.next()) {
          emails.add((Email)result.getObject(1));
        }
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }

    Assert.assertEquals(
        new HashSet<Email>(Arrays.asList(new Email("test@example.com"), new Email("foo@bar.baz"), new Email("test2@example.com"))),
        emails
    );
  }

  @Test(expected = SQLException.class)
  public void testTypeUnassignable() throws Exception {
    // Add base type from "text" to go to Email
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("text", Email.class);
    con.setTypeMap(typemap);

    Set<Port> ports = new HashSet<Port>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM testemail");
      try {
        while (result.next()) {
          ports.add(result.getObject("email", Port.class));
        }
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }
  }
}
