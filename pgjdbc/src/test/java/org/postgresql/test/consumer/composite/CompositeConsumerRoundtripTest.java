/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Execution(ExecutionMode.SAME_THREAD)
@ParameterizedClass
@MethodSource("data")
public class CompositeConsumerRoundtripTest extends BaseTest4 {
  private static final String QUOTED_SCHEMA = "\"ConsumerCaseSchema\"";
  private static final String QUOTED_ADDRESS_TYPE = QUOTED_SCHEMA + ".\"PostalAddress\"";
  private static final String QUOTED_TABLE = QUOTED_SCHEMA + ".\"QuotedOrders\"";

  CompositeConsumerRoundtripTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @BeforeAll
  static void setUpSchema() throws SQLException {
    try (Connection conn = TestUtil.openDB();
         Statement stmt = conn.createStatement()) {
      cleanup(stmt);

      stmt.execute("CREATE SCHEMA " + QUOTED_SCHEMA);
      stmt.execute("CREATE TYPE " + QUOTED_ADDRESS_TYPE
          + " AS (\"streetLine\" text, \"postalCode\" text)");
      stmt.execute("CREATE TABLE " + QUOTED_TABLE
          + " (id int primary key, \"shippingAddress\" " + QUOTED_ADDRESS_TYPE + ")");

      stmt.execute("CREATE TYPE consumer_person_name AS (first_name text, last_name text)");
      stmt.execute("CREATE TYPE consumer_customer_record AS (customer_id int, full_name consumer_person_name, vip boolean)");
      stmt.execute("CREATE TABLE consumer_customer_records (id int primary key, payload consumer_customer_record)");

      stmt.execute("CREATE TYPE consumer_order_line AS (sku text, quantity int)");
      stmt.execute("CREATE TABLE consumer_baskets (id int primary key, items consumer_order_line[])");
      // CREATE PROCEDURE is PG 11+. The procedure is used only by
      // callableStatement_inoutCompositeRoundTripsAsSqlData, which skips
      // itself on earlier servers via assumeMinimumServerVersion.
      if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11)) {
        stmt.execute("CREATE OR REPLACE PROCEDURE consumer_transform_order_line(INOUT line consumer_order_line) "
            + "LANGUAGE plpgsql AS $$ "
            + "BEGIN "
            + "  line := ROW((line).sku || '-done', (line).quantity + 10)::consumer_order_line; "
            + "END "
            + "$$");
      }

      stmt.execute("CREATE TYPE consumer_batch_customer AS (email text, loyalty_tier int)");
      stmt.execute("CREATE TABLE consumer_batch_events (id int primary key, customer consumer_batch_customer)");

      stmt.execute("CREATE TYPE consumer_nullable_customer AS (customer_id int, nickname text, full_name consumer_person_name)");
      stmt.execute("CREATE TABLE consumer_nullable_customers (id int primary key, payload consumer_nullable_customer)");

      stmt.execute("CREATE SCHEMA consumer_shadow_a");
      stmt.execute("CREATE SCHEMA consumer_shadow_b");
      stmt.execute("CREATE TYPE consumer_shadow_a.shipping_state AS (value text)");
      stmt.execute("CREATE TYPE consumer_shadow_b.shipping_state AS (value int)");
      stmt.execute("CREATE TABLE consumer_shadow_a.orders (id int primary key, state consumer_shadow_a.shipping_state)");
      stmt.execute("CREATE TABLE consumer_shadow_b.orders (id int primary key, state consumer_shadow_b.shipping_state)");
    }
  }

  @AfterAll
  static void tearDownSchema() throws SQLException {
    try (Connection conn = TestUtil.openDB();
         Statement stmt = conn.createStatement()) {
      cleanup(stmt);
    }
  }

  private static void cleanup(Statement stmt) throws SQLException {
    // DROP TABLE IF EXISTS schema.table still errors on PG 9.1 when the
    // schema itself is missing, so let DROP SCHEMA ... CASCADE remove the
    // qualified tables along with the schema.
    stmt.execute("DROP SCHEMA IF EXISTS consumer_shadow_b CASCADE");
    stmt.execute("DROP SCHEMA IF EXISTS consumer_shadow_a CASCADE");
    stmt.execute("DROP TABLE IF EXISTS consumer_batch_events");
    stmt.execute("DROP TYPE IF EXISTS consumer_batch_customer CASCADE");
    stmt.execute("DROP TABLE IF EXISTS consumer_nullable_customers");
    stmt.execute("DROP TYPE IF EXISTS consumer_nullable_customer CASCADE");
    stmt.execute("DROP TABLE IF EXISTS consumer_baskets");
    stmt.execute("DROP TYPE IF EXISTS consumer_order_line CASCADE");
    stmt.execute("DROP TABLE IF EXISTS consumer_customer_records");
    stmt.execute("DROP TYPE IF EXISTS consumer_customer_record CASCADE");
    stmt.execute("DROP TYPE IF EXISTS consumer_person_name CASCADE");
    stmt.execute("DROP SCHEMA IF EXISTS " + QUOTED_SCHEMA + " CASCADE");
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    try (Statement stmt = con.createStatement()) {
      stmt.execute("TRUNCATE TABLE " + QUOTED_TABLE);
      stmt.execute("TRUNCATE TABLE consumer_customer_records");
      stmt.execute("TRUNCATE TABLE consumer_baskets");
      stmt.execute("TRUNCATE TABLE consumer_batch_events");
      stmt.execute("TRUNCATE TABLE consumer_nullable_customers");
      stmt.execute("TRUNCATE TABLE consumer_shadow_a.orders");
      stmt.execute("TRUNCATE TABLE consumer_shadow_b.orders");
      stmt.execute("RESET search_path");
    }
  }

  @Test
  void quotedIdentifiers_roundTripViaGetObjectMap() throws SQLException {
    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO " + QUOTED_TABLE + " (id, \"shippingAddress\") VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT \"shippingAddress\" FROM " + QUOTED_TABLE + " WHERE id = ?")) {
      Struct address = con.createStruct(QUOTED_ADDRESS_TYPE, new Object[]{"221B Baker Street", "NW1"});
      insert.setInt(1, 1);
      insert.setObject(2, address);
      assertEquals(1, insert.executeUpdate());

      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put(QUOTED_ADDRESS_TYPE, QuotedPostalAddress.class);

      select.setInt(1, 1);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        QuotedPostalAddress actual =
            (QuotedPostalAddress) rs.getObject(1, typeMap);
        assertEquals("221B Baker Street", actual.streetLine);
        assertEquals("NW1", actual.postalCode);
      }
    }
  }

  @Test
  void searchPathSwitch_updatesUnqualifiedCreateStructResolution() throws SQLException {
    try (Statement stmt = con.createStatement();
         PreparedStatement insertA = con.prepareStatement(
             "INSERT INTO consumer_shadow_a.orders (id, state) VALUES (?, ?)");
         PreparedStatement insertB = con.prepareStatement(
             "INSERT INTO consumer_shadow_b.orders (id, state) VALUES (?, ?)");
         PreparedStatement selectA = con.prepareStatement(
             "SELECT state FROM consumer_shadow_a.orders WHERE id = ?");
         PreparedStatement selectB = con.prepareStatement(
             "SELECT state FROM consumer_shadow_b.orders WHERE id = ?")) {
      stmt.execute("SET search_path TO consumer_shadow_a");
      Struct textState = con.createStruct("shipping_state", new Object[]{"ready"});
      insertA.setInt(1, 1);
      insertA.setObject(2, textState);
      assertEquals(1, insertA.executeUpdate());

      selectA.setInt(1, 1);
      try (ResultSet rs = selectA.executeQuery()) {
        assertTrue(rs.next());
        Struct actual = rs.getObject(1, Struct.class);
        assertEquals("ready", actual.getAttributes()[0]);
      }

      stmt.execute("SET search_path TO consumer_shadow_b");
      Struct intState = con.createStruct("shipping_state", new Object[]{7});
      insertB.setInt(1, 1);
      insertB.setObject(2, intState);
      assertEquals(1, insertB.executeUpdate());

      selectB.setInt(1, 1);
      try (ResultSet rs = selectB.executeQuery()) {
        assertTrue(rs.next());
        Struct actual = rs.getObject(1, Struct.class);
        assertEquals(7, actual.getAttributes()[0]);
      }
    } finally {
      try (Statement reset = con.createStatement()) {
        reset.execute("RESET search_path");
      }
    }
  }

  @Test
  void nestedComposite_getObjectMapBuildsNestedSqlData() throws SQLException {
    Struct fullName = con.createStruct("consumer_person_name", new Object[]{"Ada", "Lovelace"});
    Struct customer = con.createStruct("consumer_customer_record", new Object[]{42, fullName, true});

    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO consumer_customer_records (id, payload) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT payload FROM consumer_customer_records WHERE id = ?")) {
      insert.setInt(1, 42);
      insert.setObject(2, customer);
      assertEquals(1, insert.executeUpdate());

      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put("consumer_person_name", ConsumerPersonName.class);
      typeMap.put("consumer_customer_record", ConsumerCustomerRecord.class);

      select.setInt(1, 42);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        ConsumerCustomerRecord actual =
            (ConsumerCustomerRecord) rs.getObject(1, typeMap);
        assertEquals(42, actual.customerId);
        assertNotNull(actual.fullName);
        assertEquals("Ada", actual.fullName.firstName);
        assertEquals("Lovelace", actual.fullName.lastName);
        assertTrue(actual.vip);
      }
    }
  }

  @Test
  void arraysOfComposites_roundTripAsStructElements() throws SQLException {
    Struct first = con.createStruct("consumer_order_line", new Object[]{"sku-1", 2});
    Struct second = con.createStruct("consumer_order_line", new Object[]{"sku-2", 5});
    Array items = con.createArrayOf("consumer_order_line", new Object[]{first, second});

    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO consumer_baskets (id, items) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT items FROM consumer_baskets WHERE id = ?")) {
      insert.setInt(1, 1);
      insert.setArray(2, items);
      assertEquals(1, insert.executeUpdate());

      select.setInt(1, 1);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        Object[] actual = (Object[]) rs.getArray(1).getArray();
        assertEquals(2, actual.length);

        Struct actualFirst = assertInstanceOf(Struct.class, actual[0]);
        Struct actualSecond = assertInstanceOf(Struct.class, actual[1]);
        assertArrayEquals(new Object[]{"sku-1", 2}, actualFirst.getAttributes());
        assertArrayEquals(new Object[]{"sku-2", 5}, actualSecond.getAttributes());
      }
    }
  }

  @Test
  void arrayGetArrayMap_materializesCompositeSqlDataElements() throws SQLException {
    Struct first = con.createStruct("consumer_order_line", new Object[]{"sku-10", 1});
    Struct second = con.createStruct("consumer_order_line", new Object[]{"sku-20", 4});
    Array items = con.createArrayOf("consumer_order_line", new Object[]{first, second});

    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO consumer_baskets (id, items) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT items FROM consumer_baskets WHERE id = ?")) {
      insert.setInt(1, 2);
      insert.setArray(2, items);
      assertEquals(1, insert.executeUpdate());

      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put("consumer_order_line", ConsumerOrderLine.class);

      select.setInt(1, 2);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        Object[] actual = (Object[]) rs.getArray(1).getArray(typeMap);
        assertEquals(2, actual.length);

        ConsumerOrderLine firstLine = assertInstanceOf(ConsumerOrderLine.class, actual[0]);
        ConsumerOrderLine secondLine = assertInstanceOf(ConsumerOrderLine.class, actual[1]);
        assertEquals("sku-10", firstLine.sku);
        assertEquals(1, firstLine.quantity);
        assertEquals("sku-20", secondLine.sku);
        assertEquals(4, secondLine.quantity);
      }
    }
  }

  @Test
  void batchInsert_sqlDataValuesRemainReadable() throws SQLException {
    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO consumer_batch_events (id, customer) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT customer FROM consumer_batch_events WHERE id = ?")) {
      insert.setInt(1, 1);
      insert.setObject(2, new BatchCustomer("alpha@example.test", 1));
      insert.addBatch();

      insert.setInt(1, 2);
      insert.setObject(2, new BatchCustomer("beta@example.test", 3));
      insert.addBatch();

      assertBatchSucceeded(2, insert.executeBatch());

      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put("consumer_batch_customer", BatchCustomer.class);

      select.setInt(1, 2);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        BatchCustomer actual = (BatchCustomer) rs.getObject(1, typeMap);
        assertEquals("beta@example.test", actual.email);
        assertEquals(3, actual.loyaltyTier);
      }
    }
  }

  @Test
  void callableStatement_inoutCompositeRoundTripsAsSqlData() throws SQLException {
    assumeCallableStatementsSupported();
    // CREATE PROCEDURE / CALL syntax require PG 11+.
    assumeMinimumServerVersion("INOUT CALL requires PostgreSQL 11", ServerVersion.v11);

    try (CallableStatement call = con.prepareCall("call consumer_transform_order_line(?)")) {
      call.setObject(1, new ConsumerOrderLine("sku-call", 5));
      call.registerOutParameter(1, Types.STRUCT, "consumer_order_line");
      call.execute();

      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put("consumer_order_line", ConsumerOrderLine.class);
      ConsumerOrderLine actual = (ConsumerOrderLine) call.getObject(1, typeMap);
      assertEquals("sku-call-done", actual.sku);
      assertEquals(15, actual.quantity);
    }
  }

  @Test
  void rowTypeAfterAlterTable_structReflectsNewColumns() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS consumer_alterable_rowtype");
      stmt.execute("CREATE TABLE consumer_alterable_rowtype (id int primary key, status text)");
      stmt.execute("INSERT INTO consumer_alterable_rowtype VALUES (1, 'new')");

      try (PreparedStatement select = con.prepareStatement(
          "SELECT r FROM consumer_alterable_rowtype r WHERE id = ?")) {
        select.setInt(1, 1);
        try (ResultSet rs = select.executeQuery()) {
          assertTrue(rs.next());
          Struct before = rs.getObject(1, Struct.class);
          assertArrayEquals(new Object[]{1, "new"}, before.getAttributes());
        }

        stmt.execute("ALTER TABLE consumer_alterable_rowtype ADD COLUMN priority int");
        stmt.execute("UPDATE consumer_alterable_rowtype SET priority = 9 WHERE id = 1");

        select.setInt(1, 1);
        try (ResultSet rs = select.executeQuery()) {
          assertTrue(rs.next());
          Struct after = rs.getObject(1, Struct.class);
          assertArrayEquals(new Object[]{1, "new", 9}, after.getAttributes());
        }
      }
    } finally {
      try (Statement cleanup = con.createStatement()) {
        cleanup.execute("DROP TABLE IF EXISTS consumer_alterable_rowtype");
      }
    }
  }

  @Test
  void nullHeavyNestedCompositeCases_preserveNullsInStructAndSqlData() throws SQLException {
    Struct sparseName = con.createStruct("consumer_person_name", new Object[]{null, "Solo"});
    Struct payloadWithNestedNulls = con.createStruct("consumer_nullable_customer",
        new Object[]{77, null, sparseName});
    Struct payloadWithNullNestedComposite = con.createStruct("consumer_nullable_customer",
        new Object[]{78, "ghost", null});

    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO consumer_nullable_customers (id, payload) VALUES (?, ?)");
         PreparedStatement select = con.prepareStatement(
             "SELECT payload FROM consumer_nullable_customers WHERE id = ?")) {
      insert.setInt(1, 1);
      insert.setObject(2, payloadWithNestedNulls);
      assertEquals(1, insert.executeUpdate());

      insert.setInt(1, 2);
      insert.setObject(2, payloadWithNullNestedComposite);
      assertEquals(1, insert.executeUpdate());

      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put("consumer_person_name", ConsumerPersonName.class);
      typeMap.put("consumer_nullable_customer", NullableCustomer.class);

      select.setInt(1, 1);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        NullableCustomer actual = (NullableCustomer) rs.getObject(1, typeMap);
        assertEquals(Integer.valueOf(77), actual.customerId);
        assertEquals(null, actual.nickname);
        assertNotNull(actual.fullName);
        assertEquals(null, actual.fullName.firstName);
        assertEquals("Solo", actual.fullName.lastName);
      }

      select.setInt(1, 2);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        Struct actual = rs.getObject(1, Struct.class);
        Object[] attrs = actual.getAttributes();
        assertEquals(78, attrs[0]);
        assertEquals("ghost", attrs[1]);
        assertEquals(null, attrs[2]);
      }
    }
  }

  // Batch counts can be -2 (Statement.SUCCESS_NO_INFO) when
  // rewriteBatchedInserts collapses inserts into a single statement.
  private static void assertBatchSucceeded(int expectedLength, int[] counts) {
    assertEquals(expectedLength, counts.length);
    for (int i = 0; i < counts.length; i++) {
      int count = counts[i];
      assertTrue(count == 1 || count == Statement.SUCCESS_NO_INFO,
          "Batch update count at index " + i + " was " + count);
    }
  }

  public static final class QuotedPostalAddress implements SQLData {
    String streetLine;
    String postalCode;

    @Override
    public String getSQLTypeName() {
      return QUOTED_ADDRESS_TYPE;
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      streetLine = stream.readString();
      postalCode = stream.readString();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(streetLine);
      stream.writeString(postalCode);
    }
  }

  public static final class ConsumerPersonName implements SQLData {
    String firstName;
    String lastName;

    @Override
    public String getSQLTypeName() {
      return "consumer_person_name";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      firstName = stream.readString();
      lastName = stream.readString();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(firstName);
      stream.writeString(lastName);
    }
  }

  public static final class ConsumerCustomerRecord implements SQLData {
    int customerId;
    ConsumerPersonName fullName;
    boolean vip;

    @Override
    public String getSQLTypeName() {
      return "consumer_customer_record";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      customerId = stream.readInt();
      fullName = (ConsumerPersonName) stream.readObject();
      vip = stream.readBoolean();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(customerId);
      stream.writeObject(fullName);
      stream.writeBoolean(vip);
    }
  }

  public static class BatchCustomer implements SQLData {
    String email;
    int loyaltyTier;

    public BatchCustomer() {
    }

    BatchCustomer(String email, int loyaltyTier) {
      this.email = email;
      this.loyaltyTier = loyaltyTier;
    }

    @Override
    public String getSQLTypeName() {
      return "consumer_batch_customer";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      email = stream.readString();
      loyaltyTier = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(email);
      stream.writeInt(loyaltyTier);
    }
  }

  public static class ConsumerOrderLine implements SQLData {
    String sku;
    Integer quantity;

    public ConsumerOrderLine() {
    }

    ConsumerOrderLine(String sku, Integer quantity) {
      this.sku = sku;
      this.quantity = quantity;
    }

    @Override
    public String getSQLTypeName() {
      return "consumer_order_line";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      sku = stream.readString();
      int rawQuantity = stream.readInt();
      quantity = stream.wasNull() ? null : rawQuantity;
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(sku);
      stream.writeInt(quantity);
    }
  }

  public static final class NullableCustomer implements SQLData {
    Integer customerId;
    String nickname;
    ConsumerPersonName fullName;

    @Override
    public String getSQLTypeName() {
      return "consumer_nullable_customer";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      int rawCustomerId = stream.readInt();
      customerId = stream.wasNull() ? null : rawCustomerId;
      nickname = stream.readString();
      fullName = (ConsumerPersonName) stream.readObject();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      throw new UnsupportedOperationException("write path is not exercised in this regression test");
    }
  }
}
