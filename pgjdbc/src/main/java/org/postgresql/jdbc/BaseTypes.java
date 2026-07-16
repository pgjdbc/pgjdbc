/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;

/**
 * The driver's static catalog of built-in {@code pg_catalog} types, seeded into every
 * {@link TypeInfoCache} and used by the connectionless (offline) codec context to resolve built-in
 * types without a live type cache.
 *
 * <p><strong>Generated file — do not edit by hand.</strong> Regenerate it from a live server with:</p>
 *
 * <pre>{@code
 * ./gradlew :postgresql:test --tests '*TypeInfoCacheTest.generateBaseTypes' \
 *     -Ppgjdbc.test.TypeInfoCacheTest.generateBaseTypes=pgjdbc/src/main/java/org/postgresql/jdbc/BaseTypes.java
 * }</pre>
 *
 * <p>The generator ({@code org.postgresql.jdbc.TypeInfoCacheTest.generateBaseTypes}) emits this whole
 * file; overwrite it rather than editing {@link #BASE_TYPES} in place. {@link TypeInfoCache} seeds its
 * offline catalog from that array, and that test also documents the {@code pg_type} column list the
 * type name set is drawn from.</p>
 */
final class BaseTypes {

  private BaseTypes() {
  }

  // Constructor: PgType(typeName, fullName, oid, typtype, typcategory, typtypmod, typsend, typreceive, typelem, arrayOid, typbasetype)
  static final PgType[] BASE_TYPES = {
      new PgType(new ObjectName("pg_catalog", "bit"), "bit", Oid.BIT, 'b', 'V', -1, "bit_send", "bit_recv", Oid.UNSPECIFIED, Oid.BIT_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_bit"), "bit[]", Oid.BIT_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.BIT, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "bool"), "boolean", Oid.BOOL, 'b', 'B', -1, "boolsend", "boolrecv", Oid.UNSPECIFIED, Oid.BOOL_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_bool"), "boolean[]", Oid.BOOL_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.BOOL, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "box"), "box", Oid.BOX, 'b', 'G', -1, "box_send", "box_recv", Oid.POINT, Oid.BOX_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_box"), "box[]", Oid.BOX_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.BOX, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "bpchar"), "character", Oid.BPCHAR, 'b', 'S', -1, "bpcharsend", "bpcharrecv", Oid.UNSPECIFIED, Oid.BPCHAR_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_bpchar"), "character[]", Oid.BPCHAR_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.BPCHAR, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "bytea"), "bytea", Oid.BYTEA, 'b', 'U', -1, "byteasend", "bytearecv", Oid.UNSPECIFIED, Oid.BYTEA_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_bytea"), "bytea[]", Oid.BYTEA_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.BYTEA, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "char"), "\"char\"", Oid.CHAR, 'b', 'Z', -1, "charsend", "charrecv", Oid.UNSPECIFIED, Oid.CHAR_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_char"), "\"char\"[]", Oid.CHAR_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.CHAR, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "circle"), "circle", Oid.CIRCLE, 'b', 'G', -1, "circle_send", "circle_recv", Oid.UNSPECIFIED, Oid.CIRCLE_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_circle"), "circle[]", Oid.CIRCLE_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.CIRCLE, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "date"), "date", Oid.DATE, 'b', 'D', -1, "date_send", "date_recv", Oid.UNSPECIFIED, Oid.DATE_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_date"), "date[]", Oid.DATE_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.DATE, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "float4"), "real", Oid.FLOAT4, 'b', 'N', -1, "float4send", "float4recv", Oid.UNSPECIFIED, Oid.FLOAT4_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_float4"), "real[]", Oid.FLOAT4_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.FLOAT4, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "float8"), "double precision", Oid.FLOAT8, 'b', 'N', -1, "float8send", "float8recv", Oid.UNSPECIFIED, Oid.FLOAT8_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_float8"), "double precision[]", Oid.FLOAT8_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.FLOAT8, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "int2"), "smallint", Oid.INT2, 'b', 'N', -1, "int2send", "int2recv", Oid.UNSPECIFIED, Oid.INT2_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_int2"), "smallint[]", Oid.INT2_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.INT2, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "int4"), "integer", Oid.INT4, 'b', 'N', -1, "int4send", "int4recv", Oid.UNSPECIFIED, Oid.INT4_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_int4"), "integer[]", Oid.INT4_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.INT4, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "int8"), "bigint", Oid.INT8, 'b', 'N', -1, "int8send", "int8recv", Oid.UNSPECIFIED, Oid.INT8_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_int8"), "bigint[]", Oid.INT8_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.INT8, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "interval"), "interval", Oid.INTERVAL, 'b', 'T', -1, "interval_send", "interval_recv", Oid.UNSPECIFIED, Oid.INTERVAL_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_interval"), "interval[]", Oid.INTERVAL_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.INTERVAL, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "json"), "json", Oid.JSON, 'b', 'U', -1, "json_send", "json_recv", Oid.UNSPECIFIED, Oid.JSON_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_json"), "json[]", Oid.JSON_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.JSON, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "jsonb"), "jsonb", Oid.JSONB, 'b', 'U', -1, "jsonb_send", "jsonb_recv", Oid.UNSPECIFIED, Oid.JSONB_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_jsonb"), "jsonb[]", Oid.JSONB_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.JSONB, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "line"), "line", Oid.LINE, 'b', 'G', -1, "line_send", "line_recv", Oid.FLOAT8, Oid.LINE_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_line"), "line[]", Oid.LINE_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.LINE, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "lseg"), "lseg", Oid.LSEG, 'b', 'G', -1, "lseg_send", "lseg_recv", Oid.POINT, Oid.LSEG_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_lseg"), "lseg[]", Oid.LSEG_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.LSEG, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "money"), "money", Oid.MONEY, 'b', 'N', -1, "cash_send", "cash_recv", Oid.UNSPECIFIED, Oid.MONEY_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_money"), "money[]", Oid.MONEY_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.MONEY, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "name"), "name", Oid.NAME, 'b', 'S', -1, "namesend", "namerecv", Oid.CHAR, Oid.NAME_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_name"), "name[]", Oid.NAME_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.NAME, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "numeric"), "numeric", Oid.NUMERIC, 'b', 'N', -1, "numeric_send", "numeric_recv", Oid.UNSPECIFIED, Oid.NUMERIC_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_numeric"), "numeric[]", Oid.NUMERIC_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.NUMERIC, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "oid"), "oid", Oid.OID, 'b', 'N', -1, "oidsend", "oidrecv", Oid.UNSPECIFIED, Oid.OID_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_oid"), "oid[]", Oid.OID_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.OID, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "path"), "path", Oid.PATH, 'b', 'G', -1, "path_send", "path_recv", Oid.UNSPECIFIED, Oid.PATH_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_path"), "path[]", Oid.PATH_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.PATH, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "point"), "point", Oid.POINT, 'b', 'G', -1, "point_send", "point_recv", Oid.FLOAT8, Oid.POINT_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_point"), "point[]", Oid.POINT_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.POINT, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "polygon"), "polygon", Oid.POLYGON, 'b', 'G', -1, "poly_send", "poly_recv", Oid.UNSPECIFIED, Oid.POLYGON_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_polygon"), "polygon[]", Oid.POLYGON_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.POLYGON, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "record"), "record", Oid.RECORD, 'p', 'P', -1, "record_send", "record_recv", Oid.UNSPECIFIED, Oid.RECORD_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_record"), "record[]", Oid.RECORD_ARRAY, 'p', 'P', -1, "array_send", "array_recv", Oid.RECORD, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "refcursor"), "refcursor", Oid.REFCURSOR, 'b', 'U', -1, "textsend", "textrecv", Oid.UNSPECIFIED, Oid.REFCURSOR_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_refcursor"), "refcursor[]", Oid.REFCURSOR_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.REFCURSOR, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "text"), "text", Oid.TEXT, 'b', 'S', -1, "textsend", "textrecv", Oid.UNSPECIFIED, Oid.TEXT_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_text"), "text[]", Oid.TEXT_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.TEXT, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "time"), "time without time zone", Oid.TIME, 'b', 'D', -1, "time_send", "time_recv", Oid.UNSPECIFIED, Oid.TIME_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_time"), "time without time zone[]", Oid.TIME_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.TIME, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "timestamp"), "timestamp without time zone", Oid.TIMESTAMP, 'b', 'D', -1, "timestamp_send", "timestamp_recv", Oid.UNSPECIFIED, Oid.TIMESTAMP_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_timestamp"), "timestamp without time zone[]", Oid.TIMESTAMP_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.TIMESTAMP, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "timestamptz"), "timestamp with time zone", Oid.TIMESTAMPTZ, 'b', 'D', -1, "timestamptz_send", "timestamptz_recv", Oid.UNSPECIFIED, Oid.TIMESTAMPTZ_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_timestamptz"), "timestamp with time zone[]", Oid.TIMESTAMPTZ_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.TIMESTAMPTZ, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "timetz"), "time with time zone", Oid.TIMETZ, 'b', 'D', -1, "timetz_send", "timetz_recv", Oid.UNSPECIFIED, Oid.TIMETZ_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_timetz"), "time with time zone[]", Oid.TIMETZ_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.TIMETZ, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "uuid"), "uuid", Oid.UUID, 'b', 'U', -1, "uuid_send", "uuid_recv", Oid.UNSPECIFIED, Oid.UUID_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_uuid"), "uuid[]", Oid.UUID_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.UUID, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "varbit"), "bit varying", Oid.VARBIT, 'b', 'V', -1, "varbit_send", "varbit_recv", Oid.UNSPECIFIED, Oid.VARBIT_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_varbit"), "bit varying[]", Oid.VARBIT_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.VARBIT, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "varchar"), "character varying", Oid.VARCHAR, 'b', 'S', -1, "varcharsend", "varcharrecv", Oid.UNSPECIFIED, Oid.VARCHAR_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_varchar"), "character varying[]", Oid.VARCHAR_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.VARCHAR, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "xml"), "xml", Oid.XML, 'b', 'U', -1, "xml_send", "xml_recv", Oid.UNSPECIFIED, Oid.XML_ARRAY, Oid.UNSPECIFIED),
      new PgType(new ObjectName("pg_catalog", "_xml"), "xml[]", Oid.XML_ARRAY, 'b', 'A', -1, "array_send", "array_recv", Oid.XML, Oid.UNSPECIFIED, Oid.UNSPECIFIED),
  };
}
