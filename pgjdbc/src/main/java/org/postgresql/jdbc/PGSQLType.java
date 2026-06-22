/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;

import java.sql.SQLType;

/**
 * PostgreSQL-specific SQLType implementation for JDBC 4.2+.
 *
 * <p>Provides built-in PostgreSQL types as SQLType constants for use with
 * {@link java.sql.PreparedStatement#setObject(int, Object, SQLType)} and
 * {@link java.sql.CallableStatement#registerOutParameter(int, SQLType)}.</p>
 *
 * @since 42.8.0
 */
public enum PGSQLType implements SQLType {

  INT2(Oid.INT2, "int2"),
  INT4(Oid.INT4, "int4"),
  INT8(Oid.INT8, "int8"),
  FLOAT4(Oid.FLOAT4, "float4"),
  FLOAT8(Oid.FLOAT8, "float8"),
  NUMERIC(Oid.NUMERIC, "numeric"),
  MONEY(Oid.MONEY, "money"),
  BOOL(Oid.BOOL, "bool"),
  TEXT(Oid.TEXT, "text"),
  VARCHAR(Oid.VARCHAR, "varchar"),
  BPCHAR(Oid.BPCHAR, "bpchar"),
  NAME(Oid.NAME, "name"),
  BYTEA(Oid.BYTEA, "bytea"),
  DATE(Oid.DATE, "date"),
  TIME(Oid.TIME, "time"),
  TIMETZ(Oid.TIMETZ, "timetz"),
  TIMESTAMP(Oid.TIMESTAMP, "timestamp"),
  TIMESTAMPTZ(Oid.TIMESTAMPTZ, "timestamptz"),
  INTERVAL(Oid.INTERVAL, "interval"),
  UUID(Oid.UUID, "uuid"),
  JSON(Oid.JSON, "json"),
  JSONB(Oid.JSONB, "jsonb"),
  XML(Oid.XML, "xml"),
  BIT(Oid.BIT, "bit"),
  VARBIT(Oid.VARBIT, "varbit"),
  POINT(Oid.POINT, "point"),
  LINE(Oid.LINE, "line"),
  LSEG(Oid.LSEG, "lseg"),
  BOX(Oid.BOX, "box"),
  PATH(Oid.PATH, "path"),
  POLYGON(Oid.POLYGON, "polygon"),
  CIRCLE(Oid.CIRCLE, "circle"),
  CIDR(Oid.CIDR, "cidr"),
  INET(Oid.INET, "inet"),
  MACADDR(Oid.MACADDR, "macaddr"),
  MACADDR8(Oid.MACADDR8, "macaddr8"),
  TSVECTOR(Oid.TSVECTOR, "tsvector"),
  TSQUERY(Oid.TSQUERY, "tsquery"),
  OID(Oid.OID, "oid"),
  REFCURSOR(Oid.REFCURSOR, "refcursor"),
  VOID(Oid.VOID, "void"),
  ;

  private static final String VENDOR = "org.postgresql";

  private final int oid;
  private final String name;

  PGSQLType(int oid, String name) {
    this.oid = oid;
    this.name = name;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getVendor() {
    return VENDOR;
  }

  @Override
  public Integer getVendorTypeNumber() {
    return oid;
  }

  public int getOid() {
    return oid;
  }
}
