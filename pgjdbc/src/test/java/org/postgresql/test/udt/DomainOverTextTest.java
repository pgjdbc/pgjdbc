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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tests a user-defined data type mapping to a <a href="https://www.postgresql.org/docs/current/sql-createdomain.html">DOMAIN</a>
 * over a text type.  This tests the type inference because the server sends back the oid of the base type for domains.
 */
// TODO: Use fail() on the exact statement after the one where SQLException is expected, here and other tests
public class DomainOverTextTest extends DomainTest {

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

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
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
        new HashSet<Email>(Arrays.asList(new Email(EMAIL3), new Email(EMAIL1), new Email(EMAIL2))),
        emails
    );
  }
  //#endif

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
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
  //#endif

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
        new HashSet<Email>(Arrays.asList(new Email(EMAIL3), new Email(EMAIL1), new Email(EMAIL2))),
        emails
    );
  }

  @Test
  public void testParamMapOverridesConnectionMap() throws Exception {
    // Add base type from "text" to go to Email
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("text", Email.class);
    con.setTypeMap(typemap);

    Set<String> emails = new HashSet<String>();
    Statement stmt = con.createStatement();
    try {
      ResultSet result = stmt.executeQuery("SELECT * FROM testemail");
      try {
        while (result.next()) {
          emails.add((String)result.getObject(1, Collections.<String, Class<?>>emptyMap()));
        }
      } finally {
        result.close();
      }
    } finally {
      stmt.close();
    }

    Assert.assertEquals(
        new HashSet<String>(Arrays.asList(EMAIL3, EMAIL1, EMAIL2)),
        emails
    );
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
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
      ResultSet result = stmt.executeQuery("SELECT ARRAY(SELECT email::text FROM testemail ORDER BY email) AS emails");
      try {
        Assert.assertTrue(result.next());
        Array array = result.getArray(1);
        // Get the array as base type
        String[] strings = (String[])array.getArray();
        Assert.assertArrayEquals(new String[] {EMAIL1, EMAIL2, EMAIL3}, strings);
        // Get the array as domain type
        Email[] emails = (Email[])array.getArray(Collections.<String, Class<?>>singletonMap("text", Email.class));
        Assert.assertArrayEquals(new Email[] {new Email(EMAIL1), new Email(EMAIL2), new Email(EMAIL3)}, emails);
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
      ResultSet result = stmt.executeQuery("SELECT ARRAY[(SELECT ARRAY(SELECT email::text FROM testemail ORDER BY email)), (SELECT ARRAY(SELECT email::text FROM testemail ORDER BY email)), (SELECT ARRAY(SELECT email::text FROM testemail ORDER BY email))] AS emails");
      try {
        Assert.assertTrue(result.next());
        Array array = result.getArray(1);
        // Get the array as base type
        String[][] strings = (String[][])array.getArray();
        Assert.assertEquals(3, strings.length);
        Assert.assertArrayEquals(new String[] {EMAIL1, EMAIL2, EMAIL3}, strings[0]);
        Assert.assertArrayEquals(new String[] {EMAIL1, EMAIL2, EMAIL3}, strings[1]);
        Assert.assertArrayEquals(new String[] {EMAIL1, EMAIL2, EMAIL3}, strings[2]);
        // Get the array as domain type
        Email[][] emails = (Email[][])array.getArray(Collections.<String, Class<?>>singletonMap("text", Email.class));
        Assert.assertEquals(3, emails.length);
        Assert.assertArrayEquals(new Email[] {new Email(EMAIL1), new Email(EMAIL2), new Email(EMAIL3)}, emails[0]);
        Assert.assertArrayEquals(new Email[] {new Email(EMAIL1), new Email(EMAIL2), new Email(EMAIL3)}, emails[1]);
        Assert.assertArrayEquals(new Email[] {new Email(EMAIL1), new Email(EMAIL2), new Email(EMAIL3)}, emails[2]);
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
