/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.Oid;

import org.junit.jupiter.api.Test;

import java.sql.Types;

/**
 * Unit tests for {@link PgType}.
 */
public class PgTypeTest {

  @Test
  void testToJdbcSqlTypeByOid() {
    // Numeric types
    assertEquals(Types.SMALLINT, PgType.toJdbcSqlType(Oid.INT2, 'N', 'b'));
    assertEquals(Types.INTEGER, PgType.toJdbcSqlType(Oid.INT4, 'N', 'b'));
    assertEquals(Types.BIGINT, PgType.toJdbcSqlType(Oid.INT8, 'N', 'b'));
    assertEquals(Types.REAL, PgType.toJdbcSqlType(Oid.FLOAT4, 'N', 'b'));
    assertEquals(Types.DOUBLE, PgType.toJdbcSqlType(Oid.FLOAT8, 'N', 'b'));
    assertEquals(Types.NUMERIC, PgType.toJdbcSqlType(Oid.NUMERIC, 'N', 'b'));

    // String types
    assertEquals(Types.VARCHAR, PgType.toJdbcSqlType(Oid.VARCHAR, 'S', 'b'));
    assertEquals(Types.VARCHAR, PgType.toJdbcSqlType(Oid.TEXT, 'S', 'b'));
    assertEquals(Types.CHAR, PgType.toJdbcSqlType(Oid.BPCHAR, 'S', 'b'));

    // Date/time types
    assertEquals(Types.DATE, PgType.toJdbcSqlType(Oid.DATE, 'D', 'b'));
    assertEquals(Types.TIME, PgType.toJdbcSqlType(Oid.TIME, 'D', 'b'));
    assertEquals(Types.TIMESTAMP, PgType.toJdbcSqlType(Oid.TIMESTAMP, 'D', 'b'));
    assertEquals(Types.TIMESTAMP_WITH_TIMEZONE, PgType.toJdbcSqlType(Oid.TIMESTAMPTZ, 'D', 'b'));

    // Binary
    assertEquals(Types.BINARY, PgType.toJdbcSqlType(Oid.BYTEA, 'U', 'b'));

    // Boolean
    assertEquals(Types.BIT, PgType.toJdbcSqlType(Oid.BOOL, 'B', 'b'));

    // UUID
    assertEquals(Types.OTHER, PgType.toJdbcSqlType(Oid.UUID, 'U', 'b'));

    // Arrays
    assertEquals(Types.ARRAY, PgType.toJdbcSqlType(Oid.INT4_ARRAY, 'A', 'b'));
    assertEquals(Types.ARRAY, PgType.toJdbcSqlType(Oid.TEXT_ARRAY, 'A', 'b'));
  }

  @Test
  void testToJdbcSqlTypeByCategory() {
    // When OID is unknown, fall back to category/typtype
    int unknownOid = 99999;

    // Composite type (typtype='c')
    assertEquals(Types.STRUCT, PgType.toJdbcSqlType(unknownOid, 'C', 'c'));

    // Enum type (typtype='e')
    assertEquals(Types.VARCHAR, PgType.toJdbcSqlType(unknownOid, 'E', 'e'));

    // Domain type (typtype='d') - falls back to category
    assertEquals(Types.NUMERIC, PgType.toJdbcSqlType(unknownOid, 'N', 'd'));
    assertEquals(Types.VARCHAR, PgType.toJdbcSqlType(unknownOid, 'S', 'd'));

    // Array category
    assertEquals(Types.ARRAY, PgType.toJdbcSqlType(unknownOid, 'A', 'b'));

    // Boolean category
    assertEquals(Types.BOOLEAN, PgType.toJdbcSqlType(unknownOid, 'B', 'b'));

    // Numeric category
    assertEquals(Types.NUMERIC, PgType.toJdbcSqlType(unknownOid, 'N', 'b'));

    // String category
    assertEquals(Types.VARCHAR, PgType.toJdbcSqlType(unknownOid, 'S', 'b'));

    // Date/time category
    assertEquals(Types.TIMESTAMP, PgType.toJdbcSqlType(unknownOid, 'D', 'b'));
  }

  @Test
  void testPgTypeProperties() {
    PgType type = new PgType(
        new ObjectName("public", "my_type"),
        "public.my_type",
        12345,
        'c', // composite
        'C', // composite category
        -1,  // typmod
        0,   // typelem
        0,   // arrayOid
        0    // typbasetype
    );

    assertEquals("my_type", type.getTypeName().getName());
    assertEquals("public", type.getTypeName().getNamespace());
    assertEquals("public.my_type", type.getFullName());
    assertEquals(12345, type.getOid());
    assertTrue(type.isComposite());
    assertFalse(type.isArray());
    assertFalse(type.isDomain());
    assertFalse(type.isEnum());
    assertEquals(',', type.getDelimiter());
  }

  @Test
  void testPgTypeDomain() {
    PgType type = new PgType(
        new ObjectName("public", "positive_int"),
        "public.positive_int",
        12346,
        'd', // domain
        'N', // numeric category
        -1,
        0,
        0,
        Oid.INT4 // base type is int4
    );

    assertTrue(type.isDomain());
    assertFalse(type.isComposite());
    assertEquals(Oid.INT4, type.getTypbasetype());
  }

  @Test
  void testPgTypeArray() {
    PgType type = new PgType(
        new ObjectName("pg_catalog", "_int4"),
        "integer[]",
        Oid.INT4_ARRAY,
        'b', // base type
        'A', // array category
        -1,
        Oid.INT4, // element type
        0,
        0
    );

    assertTrue(type.isArray());
    assertFalse(type.isComposite());
    assertEquals(Oid.INT4, type.getTypelem());
  }

  @Test
  void testPgTypeWithCustomDelimiter() {
    // Box type uses semicolon as delimiter
    PgType boxType = new PgType(
        new ObjectName("pg_catalog", "box"),
        "box",
        Oid.BOX,
        'b',
        'G', // geometric
        -1,
        0,
        Oid.BOX_ARRAY,
        0
    );
    assertEquals(';', boxType.getDelimiter());

    // Other types use comma
    PgType intType = new PgType(
        new ObjectName("pg_catalog", "int4"),
        "integer",
        Oid.INT4,
        'b',
        'N',
        -1,
        0,
        Oid.INT4_ARRAY,
        0
    );
    assertEquals(',', intType.getDelimiter());

    // Custom delimiter from database
    PgType customType = new PgType(
        new ObjectName("public", "custom"),
        "public.custom",
        99999,
        'b',
        'U',
        -1,
        0,
        0,
        0,
        '|' // custom delimiter
    );
    assertEquals('|', customType.getDelimiter());
  }
}
