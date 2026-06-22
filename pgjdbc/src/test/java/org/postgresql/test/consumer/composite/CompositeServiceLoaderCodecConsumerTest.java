/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Execution(ExecutionMode.SAME_THREAD)
class CompositeServiceLoaderCodecConsumerTest {
  @BeforeAll
  static void createObjects() throws SQLException {
    try (Connection conn = TestUtil.openDB();
         Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS consumer_service_loader_events");
      stmt.execute("DROP TYPE IF EXISTS consumer_service_loader_payload CASCADE");
      stmt.execute("DROP DOMAIN IF EXISTS consumer_service_loader_service_point");
      // Custom domain over point so the ServiceLoader-registered codec
      // does not collide with the built-in "point" type that other
      // tests (e.g. ParameterMetaDataTest) rely on.
      stmt.execute("CREATE DOMAIN consumer_service_loader_service_point AS point");
      stmt.execute("CREATE TYPE consumer_service_loader_payload AS "
          + "(name text, location consumer_service_loader_service_point)");
      stmt.execute("CREATE TABLE consumer_service_loader_events (id int primary key, payload consumer_service_loader_payload)");
      stmt.execute("INSERT INTO consumer_service_loader_events VALUES "
          + "(1, ROW('dock', point(12.5, 48.0))::consumer_service_loader_payload)");
    }
  }

  @AfterAll
  static void dropObjects() throws SQLException {
    try (Connection conn = TestUtil.openDB();
         Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS consumer_service_loader_events");
      stmt.execute("DROP TYPE IF EXISTS consumer_service_loader_payload CASCADE");
      stmt.execute("DROP DOMAIN IF EXISTS consumer_service_loader_service_point");
    }
  }

  @Test
  void serviceLoaderCodecFromTestClasspathDecodesCompositeField() throws SQLException {
    Properties props = new Properties();
    PGProperty.PREFER_QUERY_MODE.set(props, "simple");

    try (Connection conn = TestUtil.openDB(props);
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT payload FROM consumer_service_loader_events WHERE id = 1")) {
      assertTrue(rs.next());
      Struct payload = rs.getObject(1, Struct.class);
      Object[] attrs = payload.getAttributes();
      assertEquals("dock", attrs[0]);

      ServicePoint point = assertInstanceOf(ServicePoint.class, attrs[1]);
      assertEquals(12.5d, point.x);
      assertEquals(48.0d, point.y);
    }
  }

  @Test
  void serviceLoaderCodecAlsoFlowsThroughSqlDataReadObject() throws SQLException {
    Properties props = new Properties();
    PGProperty.PREFER_QUERY_MODE.set(props, "simple");

    try (Connection conn = TestUtil.openDB(props);
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT payload FROM consumer_service_loader_events WHERE id = 1")) {
      Map<String, Class<?>> typeMap = new HashMap<>();
      typeMap.put("consumer_service_loader_payload", PayloadWithServicePoint.class);

      assertTrue(rs.next());
      PayloadWithServicePoint payload =
          (PayloadWithServicePoint) rs.getObject(1, typeMap);
      assertEquals("dock", payload.name);
      assertEquals(12.5d, payload.location.x);
      assertEquals(48.0d, payload.location.y);
    }
  }

  public static final class PayloadWithServicePoint implements SQLData {
    String name;
    ServicePoint location;

    @Override
    public String getSQLTypeName() {
      return "consumer_service_loader_payload";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      name = stream.readString();
      location = (ServicePoint) stream.readObject();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(name);
      throw new UnsupportedOperationException("write path is not exercised in this regression test");
    }
  }
}
