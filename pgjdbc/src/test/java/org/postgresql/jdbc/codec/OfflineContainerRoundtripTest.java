/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.io.IOException;
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

  private static PgType anonymousRecord(PgField... fields) {
    return new PgType(new ObjectName("pg_catalog", "record"), "record", Oid.RECORD, 'c', 'C',
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
  void nestedAnonymousRecordRoundtripsOffline() throws SQLException {
    // An anonymous record (OID 2249) whose second field is itself an anonymous record. The wire
    // reports OID 2249 for the nested field, which resolves to the fieldless record pseudo-type, so
    // the binary encoder must fall back to the fields the nested struct carries. Before the fix a
    // nested record could be decoded but not re-encoded, forcing callers onto named composites.
    PgType inner = anonymousRecord(field("g1", Oid.INT4, 1), field("g2", Oid.INT4, 2));
    PgType outer = anonymousRecord(field("f1", Oid.INT4, 1), field("f2", Oid.RECORD, 2));
    // One record descriptor lets the context route OID 2249 to the composite codec; the per-node
    // fields come from each struct's own carried type on encode and from the wire on decode.
    CodecContext ctx = PgCodecContext.offlineBuilder().type(outer).build();

    PgStruct nested = new PgStruct(inner, new Object[]{2, 3}, null);
    PgStruct value = new PgStruct(outer, new Object[]{1, nested}, null);

    RawValue raw = Codecs.encode(value, outer, ctx, Format.BINARY);
    PgStruct decoded = (PgStruct) Codecs.decode(raw, outer, ctx, Struct.class);
    assertNotNull(decoded, "nested anonymous record");
    Object[] attributes = decoded.getAttributes();
    assertEquals(1, attributes[0], "outer scalar field");
    Struct decodedNested = assertInstanceOf(Struct.class, attributes[1], "nested record field");
    assertArrayEquals(new Object[]{2, 3}, decodedNested.getAttributes(), "nested record attributes");

    // getValue() rebuilds the record_out literal recursively; record_out quotes the nested record
    // because it contains commas and parentheses.
    assertEquals("(1,\"(2,3)\")", decoded.getValue(), "rebuilt nested record literal");
  }

  @Test
  void deeplyNestedAnonymousRecordRoundtripsOffline() throws SQLException {
    // record(record(record(int4))): each level recurses through the child struct's own fields, so
    // the encoder compounds the nesting exactly as record_send would on the server.
    PgType level3 = anonymousRecord(field("h1", Oid.INT4, 1));
    PgType level2 = anonymousRecord(field("g1", Oid.RECORD, 1));
    PgType level1 = anonymousRecord(field("f1", Oid.RECORD, 1));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(level1).build();

    PgStruct value = new PgStruct(level1, new Object[]{
        new PgStruct(level2, new Object[]{
            new PgStruct(level3, new Object[]{1}, null)}, null)}, null);

    RawValue raw = Codecs.encode(value, level1, ctx, Format.BINARY);
    PgStruct decoded = (PgStruct) Codecs.decode(raw, level1, ctx, Struct.class);
    Struct l2 = assertInstanceOf(Struct.class, decoded.getAttributes()[0], "level 2 record");
    Struct l3 = assertInstanceOf(Struct.class, l2.getAttributes()[0], "level 3 record");
    assertArrayEquals(new Object[]{1}, l3.getAttributes(), "leaf record attributes");

    // record_out doubles the embedded quotes at each level, so the escaping compounds with depth.
    assertEquals("(\"(\"\"(1)\"\")\")", decoded.getValue(), "rebuilt deeply nested record literal");
  }

  @Test
  void nestedAnonymousRecordResolvesFromBuiltinCatalogOffline() throws SQLException {
    // Regression for a coverage-guided fuzzer finding (NestedCodecFuzzTest.structRoundTrip): a named
    // composite with a nested anonymous RECORD field, encoded offline without registering the record
    // pseudo-type. The nested field's OID (2249) is resolved by OID during encode, so the record
    // pseudo-type must resolve to CompositeCodec from the built-in catalog alone -- otherwise it fell
    // through to FallbackCodec and failed with "Cannot convert PgStruct to record". The fix is the
    // built-in record/record[] entries in BaseTypes; the context registers only the named outer type.
    PgType inner = anonymousRecord(field("g1", Oid.INT4, 1), field("g2", Oid.INT4, 2));
    PgType outer = composite("outer_rec", POINT_OID,
        field("f1", Oid.INT4, 1), field("f2", Oid.RECORD, 2));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(outer).build();

    PgStruct value = new PgStruct(outer,
        new Object[]{1, new PgStruct(inner, new Object[]{2, 3}, null)}, null);

    RawValue raw = Codecs.encode(value, outer, ctx, Format.BINARY);
    PgStruct decoded = (PgStruct) Codecs.decode(raw, outer, ctx, Struct.class);
    assertNotNull(decoded, "named record with nested anonymous record");
    Object[] attributes = decoded.getAttributes();
    assertEquals(1, attributes[0], "outer scalar field");
    Struct nested = assertInstanceOf(Struct.class, attributes[1], "nested record field");
    assertArrayEquals(new Object[]{2, 3}, nested.getAttributes(), "nested record attributes");
    assertEquals("(1,\"(2,3)\")", decoded.getValue(), "rebuilt nested record literal");
  }

  @Test
  void structFastPathStreamingMatchesMaterializedBytesOffline() throws SQLException, IOException {
    // The Struct fast path streams each attribute straight into a BackpatchingBinarySink,
    // back-patching per-field length prefixes. Assert it produces the SAME bytes as the
    // materializing byte[] path. This proves output equivalence only — not the absence of an
    // intermediate byte[] (that would need an allocation-counting sink).
    PgType type = composite("pt", POINT_OID,
        field("x", Oid.INT4, 1), field("y", Oid.INT4, 2), field("label", Oid.TEXT, 3));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    PgStruct value = new PgStruct(type, new Object[]{10, 20, "hello, struct"}, null);

    byte[] materialized = CompositeCodec.INSTANCE.encodeBinary(value, type, ctx);
    BackpatchByteArrayOutputStream streamed = new BackpatchByteArrayOutputStream();
    CompositeCodec.INSTANCE.encodeBinary(value, type, ctx, streamed);
    assertArrayEquals(materialized, streamed.toByteArray(), "Struct fast path");
  }

  @Test
  void sqlDataStreamingFallbackMatchesMaterializedBytesOffline() throws SQLException, IOException {
    // An SQLData value does not take the Struct fast path: streaming encodeBinary falls back to
    // out.write(encodeBinary(...)), a length-correct materialize-then-copy. Assert the fallback
    // writes the same bytes as the byte[] path. (A plain PGobject is never binary-encoded here —
    // canEncodeBinary() gates it to text — so SQLData is the value that actually exercises the
    // fallback branch.)
    PgType type = composite("point_t", POINT_OID,
        field("x", Oid.INT4, 1), field("y", Oid.INT4, 2), field("label", Oid.TEXT, 3));
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type).build();
    Point value = point(3, 4, "corner");

    byte[] materialized = CompositeCodec.INSTANCE.encodeBinary(value, type, ctx);
    BackpatchByteArrayOutputStream streamed = new BackpatchByteArrayOutputStream();
    CompositeCodec.INSTANCE.encodeBinary(value, type, ctx, streamed);
    assertArrayEquals(materialized, streamed.toByteArray(), "SQLData fallback");
  }

  // A delegate codec that forwards to a streaming inner codec must emit the same bytes through its
  // streaming form as through its materialising byte[] form.
  private static void assertStreamMatchesMaterialized(BinaryCodec codec, PgType type, Object value,
      CodecContext ctx) throws SQLException, IOException {
    byte[] materialized = codec.encodeBinary(value, type, ctx);
    BackpatchByteArrayOutputStream sink = new BackpatchByteArrayOutputStream();
    ((StreamingBinaryCodec) codec).encodeBinary(value, type, ctx, sink);
    assertArrayEquals(materialized, sink.toByteArray());
  }

  @Test
  void pgobjectDelegateStreamingMatchesMaterializedOffline() throws SQLException, IOException {
    // PGobjectCodec forwards to its delegate; with a streaming delegate (int4) the sink path must
    // match the materialised bytes.
    CodecContext ctx = PgCodecContext.offlineBuilder().build();
    PgType int4 = new PgType(new ObjectName("pg_catalog", "int4"), "int4", Oid.INT4, 'b', 'N', -1,
        0, 0, 0);
    PGobjectCodec codec = new PGobjectCodec(PGobject.class, Int4Codec.INSTANCE);
    assertStreamMatchesMaterialized(codec, int4, 42, ctx);
  }

  @Test
  void domainDelegateStreamingMatchesMaterializedOffline() throws SQLException, IOException {
    // DomainCodec resolves its base type (int4, a streaming codec) and forwards to it.
    PgType domain = new PgType(new ObjectName("public", "dom_int"), "public.dom_int", 90_010,
        'd', 'N', -1, 0, 0, Oid.INT4);
    CodecContext ctx = PgCodecContext.offlineBuilder().type(domain).build();
    assertStreamMatchesMaterialized(DomainCodec.INSTANCE, domain, 7, ctx);
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
