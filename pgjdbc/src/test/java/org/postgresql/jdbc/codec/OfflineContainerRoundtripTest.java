/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Struct;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Round-trips container values — composites, SQLData objects, arrays and hstore — through the public
 * offline (connectionless) codec surface. These exercise the slice-3b work that lifted the container
 * codecs and SQLData adapters off a live {@link java.sql.Connection}: built-in element and field
 * types resolve through the driver's catalog, the encoding readers run from a charset-derived
 * encoding, and a genuinely connection-bound result (a {@code java.sql.Array} inside a struct) still
 * reports a clear error.
 */
class OfflineContainerRoundtripTest {

  private static final int POINT_OID = 90_001;
  private static final int HAS_ARRAY_OID = 90_002;
  private static final int HSTORE_OID = 90_003;
  private static final int POINT_ARRAY_OID = 90_004;

  private static final PgType TEXT_ARRAY = new PgType(
      new ObjectName("pg_catalog", "_text"), "text[]", Oid.TEXT_ARRAY, 'b', 'A', -1,
      Oid.TEXT, 0, 0);

  private static PgField field(String name, int oid, int position) {
    return new PgField(name, oid, position, -1);
  }

  private static PgType composite(String simpleName, int oid, PgField... fields) {
    return new PgType(new ObjectName("public", simpleName), "public." + simpleName, oid, 'c', 'C',
        -1, 0, 0, 0, ',', Arrays.asList(fields));
  }

  private static Point point(int x, int y, String label) {
    Point p = new Point();
    p.x = x;
    p.y = y;
    p.label = label;
    return p;
  }

  @Test
  void compositeStructRoundtripsOffline() throws SQLException {
    PgType type = composite("pt", POINT_OID,
        field("x", Oid.INT4, 1), field("y", Oid.INT4, 2), field("label", Oid.TEXT, 3));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    Object[] attributes = {10, 20, "hello, struct"};

    for (Format format : Format.values()) {
      // Built-in field types (int4, text) resolve through the driver catalog with no registration.
      RawValue raw = Codecs.encode(new PgStruct(type, attributes, null), type, ctx, format);
      Struct decoded = Codecs.decode(raw, type, ctx, Struct.class);
      assertNotNull(decoded, "composite " + format);
      assertArrayEquals(attributes, decoded.getAttributes(), "composite " + format);
    }
  }

  @Test
  void compositeStructGetValueRebuiltOffline() throws SQLException {
    // The bug: offline binary (and text -> Struct.class) decode left PgStruct.getValue() null,
    // because the struct reached its codec context only through a connection. With the context
    // carried directly, getValue() and toString() rebuild the record_out literal from the
    // attributes with no connection.
    PgType type = composite("pt", POINT_OID,
        field("x", Oid.INT4, 1), field("y", Oid.INT4, 2), field("label", Oid.TEXT, 3));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    Object[] attributes = {10, 20, "hello"};

    String canonical = null;
    for (Format format : Format.values()) {
      // Struct.class decode never records the raw literal, so getValue() must rebuild it from the
      // attributes through the carried offline context.
      RawValue raw = Codecs.encode(new PgStruct(type, attributes, null), type, ctx, format);
      PgStruct decoded = (PgStruct) Codecs.decode(raw, type, ctx, Struct.class);
      assertNotNull(decoded, "struct " + format);

      String literal = decoded.getValue();
      assertNotNull(literal, "getValue offline " + format);
      assertEquals(literal, decoded.toString(), "toString offline " + format);

      // Binary and text decode must rebuild the identical canonical literal.
      if (canonical == null) {
        canonical = literal;
      } else {
        assertEquals(canonical, literal, "canonical literal " + format);
      }
    }
    // The text codec quotes the string field unconditionally; the literal round-trips either way.
    assertEquals("(10,20,\"hello\")", canonical, "literal form");
  }

  @Test
  void sqlDataRoundtripsOffline() throws SQLException {
    PgType type = composite("point_t", POINT_OID,
        field("x", Oid.INT4, 1), field("y", Oid.INT4, 2), field("label", Oid.TEXT, 3));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    Point corner = point(3, 4, "corner");

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(corner, type, ctx, format);
      Point back = Codecs.decode(raw, type, ctx, Point.class);
      assertEquals(corner, back, "SQLData " + format);
    }
  }

  @Test
  void hstoreRoundtripsOffline() throws SQLException {
    // hstore decodes from bytes through the wire encoding; offline that encoding is derived from the
    // context charset, so the binary path works without a connection.
    PgType type = new PgType(new ObjectName("public", "hstore"), "hstore", HSTORE_OID, 'b', 'U', -1,
        0, 0, 0);
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    Map<String, String> value = new LinkedHashMap<>();
    value.put("one", "1");
    value.put("two", "2");

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, type, ctx, format);
      Object back = Codecs.decode(raw, type, ctx, Map.class);
      assertEquals(value, back, "hstore " + format);
    }
  }

  @Test
  void textArrayRoundtripsToStringArrayOffline() throws SQLException {
    CodecContext ctx = PgCodecContext.offlineBuilder().build();
    String[] value = {"a", "b,c", "d e"};

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, TEXT_ARRAY, ctx, format);
      assertArrayEquals(value, Codecs.decode(raw, TEXT_ARRAY, ctx, String[].class),
          "text[] " + format);
    }
  }

  @Test
  void compositeArrayDecodesToStructsOffline() throws SQLException {
    PgType element = composite("pt2", POINT_OID, field("x", Oid.INT4, 1), field("y", Oid.INT4, 2));
    PgType arrayType = new PgType(new ObjectName("public", "_pt2"), "public.pt2[]", POINT_ARRAY_OID,
        'b', 'A', -1, POINT_OID, 0, 0);
    CodecContext ctx = PgCodecContext.offlineBuilder().type(element).type(arrayType).build();
    PgStruct[] structs = {
        new PgStruct(element, new Object[]{1, 2}, null),
        new PgStruct(element, new Object[]{3, 4}, null),
    };

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(structs, arrayType, ctx, format);
      // An array of composites decodes offline to an Object[] of connectionless structs.
      Object[] decoded = (Object[]) Codecs.decode(raw, arrayType, ctx, Object.class);
      assertNotNull(decoded, "array-of-struct " + format);
      assertEquals(2, decoded.length, "array-of-struct " + format);
      assertArrayEquals(new Object[]{1, 2}, ((Struct) decoded[0]).getAttributes(),
          "array-of-struct " + format);
      assertArrayEquals(new Object[]{3, 4}, ((Struct) decoded[1]).getAttributes(),
          "array-of-struct " + format);
    }
  }

  @Test
  void sqlDataArrayRoundtripsOffline() throws SQLException {
    PgType element = composite("point_t", POINT_OID,
        field("x", Oid.INT4, 1), field("y", Oid.INT4, 2), field("label", Oid.TEXT, 3));
    PgType arrayType = new PgType(new ObjectName("public", "_point_t"), "public.point_t[]",
        POINT_ARRAY_OID, 'b', 'A', -1, POINT_OID, 0, 0);
    CodecContext ctx = PgCodecContext.offlineBuilder().type(element).type(arrayType).build();
    Point[] points = {point(1, 2, "a"), point(3, 4, "b,c")};

    for (Format format : Format.values()) {
      // A typed CustomDto[] target decodes each element to the SQLData class — no connection.
      RawValue raw = Codecs.encode(points, arrayType, ctx, format);
      Point[] back = Codecs.decode(raw, arrayType, ctx, Point[].class);
      assertArrayEquals(points, back, "CustomDto[] " + format);
    }
  }

  @Test
  void nestedArrayInSqlDataReportsClearErrorOffline() throws SQLException {
    PgType type = composite("has_array", HAS_ARRAY_OID, field("arr", Oid.INT4_ARRAY, 1));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    // Encode a struct whose only attribute is an int4[]; the binary wire form is connectionless.
    RawValue raw = Codecs.encode(
        new PgStruct(type, new Object[]{new Integer[]{1, 2}}, null), type, ctx, Format.BINARY);

    // Decoding it as an SQLData object that calls readArray() needs a connection-bound PgArray.
    PSQLException ex = assertThrows(PSQLException.class,
        () -> Codecs.decode(raw, type, ctx, ArrayHolder.class));
    assertEquals(PSQLState.NOT_IMPLEMENTED.getState(), ex.getSQLState());
  }

  @Test
  void offlineCompositeWithoutFieldsReportsClearError() {
    // A composite registered without its attributes cannot be materialized as a struct offline.
    PgType fieldless = new PgType(new ObjectName("public", "bare"), "public.bare", POINT_OID, 'c',
        'C', -1, 0, 0, 0);
    CodecContext ctx = PgCodecContext.offlineBuilder().type(fieldless).build();

    PSQLException ex = assertThrows(PSQLException.class,
        () -> Codecs.decode(RawValue.binary(new byte[]{0, 0, 0, 0}), fieldless, ctx, Point.class));
    assertEquals(PSQLState.INVALID_PARAMETER_TYPE.getState(), ex.getSQLState());
  }

  /** A simple {@link SQLData} value object whose attributes are all built-in scalars. */
  public static final class Point implements SQLData {
    private int x;
    private int y;
    private String label = "";

    @Override
    public String getSQLTypeName() {
      return "public.point_t";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.x = stream.readInt();
      this.y = stream.readInt();
      this.label = Objects.requireNonNull(stream.readString());
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(x);
      stream.writeInt(y);
      stream.writeString(label);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Point)) {
        return false;
      }
      Point point = (Point) o;
      return x == point.x && y == point.y && label.equals(point.label);
    }

    @Override
    public int hashCode() {
      return Objects.hash(x, y, label);
    }
  }

  /** An {@link SQLData} value whose single attribute is an array, used for the nested-array case. */
  public static final class ArrayHolder implements SQLData {
    private Array array;

    @Override
    public String getSQLTypeName() {
      return "public.has_array";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.array = Objects.requireNonNull(stream.readArray());
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeArray(array);
    }
  }
}
