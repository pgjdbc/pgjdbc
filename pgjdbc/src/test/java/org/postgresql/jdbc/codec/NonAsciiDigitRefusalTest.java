/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pins one invariant across every path that turns a number literal into a value: a digit outside ASCII
 * is refused.
 *
 * <p>{@code Integer.parseInt}, {@code Long.parseUnsignedLong} and the {@link java.math.BigDecimal}
 * string constructor all read through {@code Character.digit}, so a fullwidth or Arabic-Indic digit
 * string parses to a value. PostgreSQL's {@code int4in} / {@code numeric_in} read ASCII only and reject
 * it, so accepting it invents a value the server can never have sent. {@code
 * NumberDecoders.requireAsciiLiteral} is the shared screen; this test is what keeps it wired into every
 * entry point rather than the one a fuzzer happened to reach.</p>
 *
 * <p>Server output is ASCII, so none of this is reachable from a normal query. It bites an offline
 * decode of a caller-supplied literal, a text COPY stream, and {@code setObject} with a {@link String}.
 * The refusal is also pinned end-to-end, against the server's own verdict, by the fuzzkit's
 * {@code MalformedLiteralRefusalTest}.</p>
 */
class NonAsciiDigitRefusalTest {

  // U+FF11 U+FF12 U+FF13, fullwidth one, two, three.
  private static final String FULLWIDTH = "１２３";
  // U+0661 U+0662 U+0663, Arabic-Indic one, two, three.
  private static final String ARABIC_INDIC = "١٢٣";
  private static final String ASCII = "123";

  private static final CodecContext CTX = TestCodecContext.create();

  private static final PgType INT2 = numeric("int2", "smallint", Oid.INT2);
  private static final PgType INT4 = numeric("int4", "integer", Oid.INT4);
  private static final PgType INT8 = numeric("int8", "bigint", Oid.INT8);
  private static final PgType OID = numeric("oid", "oid", Oid.OID);
  private static final PgType OID8 = numeric("oid8", "oid8", Oid.OID8);
  private static final PgType XID8 = numeric("xid8", "xid8", Oid.XID8);
  private static final PgType NUMERIC = numeric("numeric", "numeric", Oid.NUMERIC);
  private static final PgType TEXT =
      new PgType(new ObjectName("pg_catalog", "text"), "text", Oid.TEXT, 'b', 'S', -1, 0, 0, 0);
  private static final PgType VARCHAR = new PgType(
      new ObjectName("pg_catalog", "varchar"), "character varying", Oid.VARCHAR,
      'b', 'S', -1, 0, 0, 0);
  private static final PgType UNKNOWN = new PgType(
      new ObjectName("pg_catalog", "unknown_type"), "unknown_type", 99999, 'b', 'X', -1, 0, 0, 0);

  private static PgType numeric(String name, String fullName, int oid) {
    return new PgType(new ObjectName("pg_catalog", name), fullName, oid, 'b', 'N', -1, 0, 0, 0);
  }

  /** One place a literal enters, driven with whichever digits the test supplies. */
  @FunctionalInterface
  private interface Path {
    void parse(String digits) throws SQLException;
  }

  static List<Arguments> paths() {
    List<Arguments> out = new ArrayList<>();

    // Scalar text decode, the CharSequence form.
    add(out, "int2", d -> Int2Codec.INSTANCE.decodeAsInt(d, INT2, CTX));
    add(out, "int4", d -> Int4Codec.INSTANCE.decodeAsInt(d, INT4, CTX));
    add(out, "int8", d -> Int8Codec.INSTANCE.decodeAsLong(d, INT8, CTX));
    add(out, "oid", d -> OidCodec.INSTANCE.decodeAsLong(d, OID, CTX));
    add(out, "oid8", d -> Oid8Codec.INSTANCE.decodeAsLong(d, OID8, CTX));
    add(out, "xid8", d -> Xid8Codec.INSTANCE.decodeAsLong(d, XID8, CTX));
    add(out, "numeric", d -> NumericCodec.INSTANCE.decodeAsBigDecimal(d, NUMERIC, CTX));

    // The char[] slice overload must agree with the String form.
    add(out, "int2/chars", d -> Int2Codec.INSTANCE.decodeText(d.toCharArray(), 0, d.length(), INT2, CTX));
    add(out, "int4/chars", d -> Int4Codec.INSTANCE.decodeText(d.toCharArray(), 0, d.length(), INT4, CTX));
    add(out, "int8/chars", d -> Int8Codec.INSTANCE.decodeText(d.toCharArray(), 0, d.length(), INT8, CTX));
    add(out, "oid/chars", d -> OidCodec.INSTANCE.decodeText(d.toCharArray(), 0, d.length(), OID, CTX));

    // Array text literals, decoded through the fast leaves.
    add(out, "_int2", d -> leaf(d, short.class, Int2ArrayLeafCodec.INSTANCE));
    add(out, "_int4", d -> leaf(d, int.class, Int4ArrayLeafCodec.INSTANCE));
    add(out, "_int8", d -> leaf(d, long.class, Int8ArrayLeafCodec.INSTANCE));
    add(out, "_oid", d -> leaf(d, long.class, OidArrayLeafCodec.INSTANCE));
    add(out, "_oid8", d -> leaf(d, long.class, Oid8ArrayLeafCodec.INSTANCE));
    add(out, "_xid8", d -> leaf(d, long.class, Xid8ArrayLeafCodec.INSTANCE));

    // getInt / getLong / getBigDecimal on a text-like column, and on an unknown type.
    add(out, "text/int", d -> TextCodec.INSTANCE.decodeAsInt(d, TEXT, CTX));
    add(out, "text/long", d -> TextCodec.INSTANCE.decodeAsLong(d, TEXT, CTX));
    add(out, "text/numeric", d -> TextCodec.INSTANCE.decodeAsBigDecimal(d, TEXT, CTX));
    add(out, "varchar/int", d -> VarcharCodec.INSTANCE.decodeAsInt(d, VARCHAR, CTX));
    add(out, "unknown/int", d -> FallbackCodec.INSTANCE.decodeAsInt(d, UNKNOWN, CTX));
    add(out, "unknown/long", d -> FallbackCodec.INSTANCE.decodeAsLong(d, UNKNOWN, CTX));

    // Encode side: a String bound to a numeric parameter.
    add(out, "int2/encode", d -> Int2Codec.INSTANCE.encodeText(d, INT2, CTX));
    add(out, "int4/encode", d -> Int4Codec.INSTANCE.encodeText(d, INT4, CTX));
    add(out, "int8/encode", d -> Int8Codec.INSTANCE.encodeText(d, INT8, CTX));
    add(out, "oid/encode", d -> OidCodec.INSTANCE.encodeText(d, OID, CTX));
    add(out, "oid8/encode", d -> Oid8Codec.INSTANCE.encodeText(d, OID8, CTX));
    add(out, "xid8/encode", d -> Xid8Codec.INSTANCE.encodeText(d, XID8, CTX));
    add(out, "numeric/encode", d -> NumericCodec.INSTANCE.encodeText(d, NUMERIC, CTX));

    return out;
  }

  private static void add(List<Arguments> out, String label, Path path) {
    out.add(Arguments.of(label, path));
  }

  private static void leaf(String digits, Class<?> component, ArrayLeafCodec codec)
      throws SQLException {
    MultiDimArrayText.decode("{" + digits + "}", component, ',', null, codec);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("paths")
  void refusesFullwidthDigits(String label, Path path) {
    assertThrows(SQLException.class, () -> path.parse(FULLWIDTH),
        () -> label + " accepted fullwidth digits; int4in and numeric_in read ASCII only");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("paths")
  void refusesArabicIndicDigits(String label, Path path) {
    assertThrows(SQLException.class, () -> path.parse(ARABIC_INDIC),
        () -> label + " accepted Arabic-Indic digits; int4in and numeric_in read ASCII only");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("paths")
  void stillAcceptsAsciiDigits(String label, Path path) {
    assertDoesNotThrow(() -> path.parse(ASCII),
        () -> label + " refused a plain ASCII literal");
  }
}
