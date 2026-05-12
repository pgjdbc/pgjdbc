/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.Map;

/**
 * Integration tests for composite types, Struct, and SQLData support.
 */
public class StructTest {

  private static Connection conn;

  @BeforeAll
  public static void setUp() throws Exception {
    conn = TestUtil.openDB();
    try (Statement stmt = conn.createStatement()) {
      // Create test composite types
      stmt.execute("DROP TYPE IF EXISTS test_person CASCADE");
      stmt.execute("DROP TYPE IF EXISTS test_address CASCADE");
      stmt.execute("DROP TYPE IF EXISTS test_nested CASCADE");

      stmt.execute("CREATE TYPE test_address AS (street text, city text, zip text)");
      stmt.execute("CREATE TYPE test_person AS (name text, age int, address test_address)");
      stmt.execute("CREATE TYPE test_nested AS (id int, person test_person)");

      // Create test table
      stmt.execute("DROP TABLE IF EXISTS struct_test");
      stmt.execute("CREATE TABLE struct_test (id serial PRIMARY KEY, person test_person)");
    }
  }

  @AfterAll
  public static void tearDown() throws SQLException {
    if (conn != null) {
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS struct_test");
        stmt.execute("DROP TYPE IF EXISTS test_nested CASCADE");
        stmt.execute("DROP TYPE IF EXISTS test_person CASCADE");
        stmt.execute("DROP TYPE IF EXISTS test_address CASCADE");
      }
      conn.close();
    }
  }

  @Test
  public void testCreateStruct() throws SQLException {
    Struct struct = conn.createStruct("test_address", new Object[]{"123 Main St", "Springfield", "12345"});

    assertNotNull(struct);
    assertEquals("test_address", struct.getSQLTypeName());

    Object[] attrs = struct.getAttributes();
    assertEquals(3, attrs.length);
    assertEquals("123 Main St", attrs[0]);
    assertEquals("Springfield", attrs[1]);
    assertEquals("12345", attrs[2]);
  }

  @Test
  public void testCreateNestedStruct() throws SQLException {
    Struct address = conn.createStruct("test_address", new Object[]{"456 Oak Ave", "Portland", "97201"});
    Struct person = conn.createStruct("test_person", new Object[]{"John Doe", 30, address});

    assertNotNull(person);
    assertEquals("test_person", person.getSQLTypeName());

    Object[] attrs = person.getAttributes();
    assertEquals(3, attrs.length);
    assertEquals("John Doe", attrs[0]);
    assertEquals(30, attrs[1]);
    assertTrue(attrs[2] instanceof Struct);
  }

  @Test
  public void testInsertAndSelectStruct() throws SQLException {
    Struct address = conn.createStruct("test_address", new Object[]{"789 Elm St", "Seattle", "98101"});
    Struct person = conn.createStruct("test_person", new Object[]{"Jane Smith", 25, address});

    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO struct_test (person) VALUES (?) RETURNING id")) {
      ps.setObject(1, person);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        int id = rs.getInt(1);

        try (PreparedStatement selectPs = conn.prepareStatement(
            "SELECT person FROM struct_test WHERE id = ?")) {
          selectPs.setInt(1, id);
          try (ResultSet selectRs = selectPs.executeQuery()) {
            assertTrue(selectRs.next());
            Object obj = selectRs.getObject(1);
            assertNotNull(obj);
            // The returned object is a PGobject containing the composite text
          }
        }
      }
    }
  }

  @Test
  public void testSQLDataWrite() throws SQLException {
    TestAddress address = new TestAddress("100 Pine St", "Denver", "80202");
    TestPerson person = new TestPerson("Bob Wilson", 40, address);

    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO struct_test (person) VALUES (?) RETURNING id")) {
      ps.setObject(1, person);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        int id = rs.getInt(1);

        // Verify the data was inserted
        try (PreparedStatement selectPs = conn.prepareStatement(
            "SELECT (person).name, (person).age FROM struct_test WHERE id = ?")) {
          selectPs.setInt(1, id);
          try (ResultSet selectRs = selectPs.executeQuery()) {
            assertTrue(selectRs.next());
            assertEquals("Bob Wilson", selectRs.getString(1));
            assertEquals(40, selectRs.getInt(2));
          }
        }
      }
    }
  }

  @Test
  public void testSQLDataRead() throws SQLException {
    // Insert data using text literal
    int id;
    try (Statement stmt = conn.createStatement()) {
      // The nested composite ("200 Maple Dr",...) must be quoted *and* its
      // inner double-quotes escaped, otherwise PostgreSQL parses it as two
      // top-level attributes (and rejects the literal).
      try (ResultSet rs = stmt.executeQuery(
          "INSERT INTO struct_test (person) VALUES "
              + "('(\"Alice Brown\",35,\"(\\\"200 Maple Dr\\\",\\\"Austin\\\",\\\"73301\\\")\")'::test_person) "
              + "RETURNING id")) {
        assertTrue(rs.next());
        id = rs.getInt(1);
      }
    }

    // Read as SQLData using type map. The map must be installed on the
    // connection so SQLData.readSQL — which calls SQLInput.readObject() on the
    // nested address field — can resolve the nested type to TestAddress.
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("test_person", TestPerson.class);
    typeMap.put("test_address", TestAddress.class);
    conn.setTypeMap(typeMap);
    try {
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT person FROM struct_test WHERE id = ?")) {
        ps.setInt(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          TestPerson person = rs.getObject(1, TestPerson.class);
          assertNotNull(person);
          assertEquals("Alice Brown", person.name);
          assertEquals(35, person.age);
          assertNotNull(person.address);
          assertEquals("200 Maple Dr", person.address.street);
          assertEquals("Austin", person.address.city);
          assertEquals("73301", person.address.zip);
        }
      }
    } finally {
      conn.setTypeMap(new HashMap<>());
    }
  }

  @Test
  public void testNullFields() throws SQLException {
    Struct address = conn.createStruct("test_address", new Object[]{null, "Boston", null});
    Struct person = conn.createStruct("test_person", new Object[]{"Charlie", null, address});

    Object[] personAttrs = person.getAttributes();
    assertEquals("Charlie", personAttrs[0]);
    assertNull(personAttrs[1]);

    Struct addrStruct = (Struct) personAttrs[2];
    Object[] addrAttrs = addrStruct.getAttributes();
    assertNull(addrAttrs[0]);
    assertEquals("Boston", addrAttrs[1]);
    assertNull(addrAttrs[2]);
  }

  // Test SQLData implementations

  public static class TestAddress implements SQLData {
    String street;
    String city;
    String zip;

    public TestAddress() {
    }

    public TestAddress(String street, String city, String zip) {
      this.street = street;
      this.city = city;
      this.zip = zip;
    }

    @Override
    public String getSQLTypeName() {
      return "test_address";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      street = stream.readString();
      city = stream.readString();
      zip = stream.readString();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(street);
      stream.writeString(city);
      stream.writeString(zip);
    }
  }

  public static class TestPerson implements SQLData {
    String name;
    int age;
    TestAddress address;

    public TestPerson() {
    }

    public TestPerson(String name, int age, TestAddress address) {
      this.name = name;
      this.age = age;
      this.address = address;
    }

    @Override
    public String getSQLTypeName() {
      return "test_person";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      name = stream.readString();
      age = stream.readInt();
      // For nested types, we'd need a type map
      Object addr = stream.readObject();
      if (addr instanceof TestAddress) {
        address = (TestAddress) addr;
      }
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(name);
      stream.writeInt(age);
      stream.writeObject(address);
    }
  }
}
