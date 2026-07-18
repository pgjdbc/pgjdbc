/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CharArraySequence;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

class NumericCodecTest {

  private NumericCodec codec;
  private PgType numericType;

  @BeforeEach
  void setUp() {
    codec = NumericCodec.INSTANCE;
    numericType = new PgType(
        new ObjectName("pg_catalog", "numeric"),
        "numeric",
        Oid.NUMERIC,
        'b', 'N', -1, 0, 0, 0
    );
  }

  @Test
  void decodeText_integer() throws SQLException {
    Object result = codec.decodeText("42", numericType, null);
    assertEquals(new BigDecimal("42"), result);
  }

  @Test
  void decodeText_decimal() throws SQLException {
    Object result = codec.decodeText("3.14159", numericType, null);
    assertEquals(new BigDecimal("3.14159"), result);
  }

  @Test
  void decodeText_negative() throws SQLException {
    Object result = codec.decodeText("-123.456", numericType, null);
    assertEquals(new BigDecimal("-123.456"), result);
  }

  @Test
  void decodeText_zero() throws SQLException {
    Object result = codec.decodeText("0", numericType, null);
    assertEquals(new BigDecimal("0"), result);
  }

  @Test
  void decodeText_largeValue() throws SQLException {
    String largeNum = "99999999999999999999999999999999.999999";
    Object result = codec.decodeText(largeNum, numericType, null);
    assertEquals(new BigDecimal(largeNum), result);
  }

  @Test
  void encodeText_integer() throws SQLException {
    String result = codec.encodeText(new BigDecimal("42"), numericType, null);
    assertEquals("42", result);
  }

  @Test
  void encodeText_decimal() throws SQLException {
    String result = codec.encodeText(new BigDecimal("3.14159"), numericType, null);
    assertEquals("3.14159", result);
  }

  @Test
  void decodeAsBigDecimal_text() throws SQLException {
    BigDecimal result = codec.decodeAsBigDecimal("123.456", numericType, null);
    assertEquals(new BigDecimal("123.456"), result);
  }

  @Test
  void decodeAsInt_text() throws SQLException {
    int result = codec.decodeAsInt("42", numericType, null);
    assertEquals(42, result);
  }

  private int decodeAsIntTextAndBinary(String literal) throws SQLException {
    int fromText = codec.decodeAsInt(literal, numericType, null);
    byte[] binary = ByteConverter.numeric(new BigDecimal(literal));
    int fromBinary = codec.decodeAsInt(binary, 0, binary.length, numericType, null);
    assertEquals(fromText, fromBinary, () -> "text/binary getInt mismatch for " + literal);
    // getObject(Integer.class) shares bigDecimalToInt, so it must agree with the primitive path.
    assertEquals(Integer.valueOf(fromText),
        codec.decodeBinaryAs(binary, 0, binary.length, numericType, Integer.class, null),
        () -> "primitive/getObject getInt mismatch for " + literal);
    return fromText;
  }

  private void assertGetIntOverflows(String literal) {
    assertThrows(PSQLException.class,
        () -> codec.decodeAsInt(literal, numericType, null),
        () -> "text getInt should overflow for " + literal);
    byte[] binary = ByteConverter.numeric(new BigDecimal(literal));
    assertThrows(PSQLException.class,
        () -> codec.decodeAsInt(binary, 0, binary.length, numericType, null),
        () -> "binary getInt should overflow for " + literal);
  }

  @Test
  void decodeAsInt_boundaryFraction_roundsThenRangeChecks() throws SQLException {
    // A fraction just past the boundary that rounds half-away-from-zero back to the boundary fits,
    // matching PostgreSQL's numeric->int4 cast ('2147483647.4'::numeric::int4 = 2147483647).
    assertEquals(Integer.MAX_VALUE, decodeAsIntTextAndBinary("2147483647.4"));
    assertEquals(Integer.MIN_VALUE, decodeAsIntTextAndBinary("-2147483648.4"));
    // x.5 rounds past the boundary (server rounds half-away-from-zero), so it overflows.
    assertGetIntOverflows("2147483647.5");
    assertGetIntOverflows("-2147483648.5");
    // One whole step past the boundary always overflows.
    assertGetIntOverflows("2147483648");
    assertGetIntOverflows("-2147483649");
  }

  @Test
  void decodeAsInt_fraction_roundsHalfAwayFromZero() throws SQLException {
    // The returned value rounds half-away-from-zero like the server, not truncating toward zero:
    // '2147483646.5'::int4 = 2147483647, '-2.5'::int4 = -3, '2.5'::int4 = 3.
    assertEquals(2147483647, decodeAsIntTextAndBinary("2147483646.5"));
    assertEquals(-3, decodeAsIntTextAndBinary("-2.5"));
    assertEquals(3, decodeAsIntTextAndBinary("2.5"));
    // A fraction below .5 rounds back toward zero.
    assertEquals(2, decodeAsIntTextAndBinary("2.4"));
    assertEquals(-2, decodeAsIntTextAndBinary("-2.4"));
  }

  @Test
  void decodeAsLong_text() throws SQLException {
    long result = codec.decodeAsLong("9999999999", numericType, null);
    assertEquals(9999999999L, result);
  }

  @Test
  void decodeAsDouble_text() throws SQLException {
    double result = codec.decodeAsDouble("3.14", numericType, null);
    assertEquals(3.14, result, 0.001);
  }

  @Test
  void decodeAsFloat_text() throws SQLException {
    float result = codec.decodeAsFloat("3.14", numericType, null);
    assertEquals(3.14f, result, 0.001f);
  }

  private double decodeAsDoubleTextAndBinary(String literal) throws SQLException {
    double fromText = codec.decodeAsDouble(literal, numericType, null);
    byte[] binary = ByteConverter.numeric(new BigDecimal(literal));
    double fromBinary = codec.decodeAsDouble(binary, 0, binary.length, numericType, null);
    assertEquals(Double.doubleToRawLongBits(fromText), Double.doubleToRawLongBits(fromBinary), literal);
    return fromText;
  }

  private float decodeAsFloatTextAndBinary(String literal) throws SQLException {
    float fromText = codec.decodeAsFloat(literal, numericType, null);
    byte[] binary = ByteConverter.numeric(new BigDecimal(literal));
    float fromBinary = codec.decodeAsFloat(binary, 0, binary.length, numericType, null);
    assertEquals(Float.floatToRawIntBits(fromText), Float.floatToRawIntBits(fromBinary), literal);
    return fromText;
  }

  private void assertDoubleOutOfRange(String literal) {
    assertThrows(SQLException.class, () -> codec.decodeAsDouble(literal, numericType, null), literal);
    byte[] binary = ByteConverter.numeric(new BigDecimal(literal));
    assertThrows(SQLException.class,
        () -> codec.decodeAsDouble(binary, 0, binary.length, numericType, null), literal);
  }

  private void assertFloatOutOfRange(String literal) {
    assertThrows(SQLException.class, () -> codec.decodeAsFloat(literal, numericType, null), literal);
    byte[] binary = ByteConverter.numeric(new BigDecimal(literal));
    assertThrows(SQLException.class,
        () -> codec.decodeAsFloat(binary, 0, binary.length, numericType, null), literal);
  }

  // Regression: getFloat on a numeric outside float4's range must refuse, matching PostgreSQL's
  // numeric->float4 cast, rather than saturate a finite value to +/-Infinity (overflow) or a nonzero
  // value to 0 (underflow). A value inside the range still reads.
  @Test
  void decodeAsFloat_outOfRange_refuses() throws SQLException {
    assertFloatOutOfRange("1e39");
    assertFloatOutOfRange("-1e39");
    assertFloatOutOfRange("1e-46");
    assertFloatOutOfRange("-1e-46");
    assertEquals(1.5f, decodeAsFloatTextAndBinary("1.5"));
  }

  // Regression: getDouble on a numeric outside float8's range must refuse (overflow), matching
  // numeric->float8, rather than saturate to +/-Infinity. A value inside the range still reads.
  @Test
  void decodeAsDouble_outOfRange_refuses() throws SQLException {
    assertDoubleOutOfRange("1e309");
    assertDoubleOutOfRange("-1e309");
    assertEquals(1.5, decodeAsDoubleTextAndBinary("1.5"));
  }

  // A genuine numeric NaN / +/-Infinity is not an overflow: float and double represent it, so the
  // accessors return it rather than refusing.
  @Test
  void decodeAsFloatAndDouble_specialValues_passThrough() throws SQLException {
    assertEquals(Double.NaN, codec.decodeAsDouble("NaN", numericType, null));
    assertEquals(Double.POSITIVE_INFINITY, codec.decodeAsDouble("Infinity", numericType, null));
    assertEquals(Double.NEGATIVE_INFINITY, codec.decodeAsDouble("-Infinity", numericType, null));
    assertEquals(Float.NaN, codec.decodeAsFloat("NaN", numericType, null));
    assertEquals(Float.POSITIVE_INFINITY, codec.decodeAsFloat("Infinity", numericType, null));
    assertEquals(Float.NEGATIVE_INFINITY, codec.decodeAsFloat("-Infinity", numericType, null));
  }

  // Regression: the float/double text accessors go through Double.parseDouble, which keeps a signed
  // zero; the char[] overloads must agree with the String form rather than fall through to the
  // BigDecimal path, which has no signed zero and would read "-0.0" as +0.0.
  @Test
  void decodeAsDouble_negativeZero_charArrayMatchesString() throws SQLException {
    char[] chars = "-0.0".toCharArray();
    double viaChars = codec.decodeAsDouble(new CharArraySequence(chars, 0, chars.length), numericType, null);
    assertEquals(Double.doubleToRawLongBits(codec.decodeAsDouble("-0.0", numericType, null)),
        Double.doubleToRawLongBits(viaChars));
    assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(viaChars));
  }

  @Test
  void decodeAsFloat_negativeZero_charArrayMatchesString() throws SQLException {
    char[] chars = "-0.0".toCharArray();
    float viaChars = codec.decodeAsFloat(new CharArraySequence(chars, 0, chars.length), numericType, null);
    assertEquals(Float.floatToRawIntBits(codec.decodeAsFloat("-0.0", numericType, null)),
        Float.floatToRawIntBits(viaChars));
    assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(viaChars));
  }

  // Regression: a numeric can be NaN or +/-Infinity; the String form handles them, and the char[] form
  // (which used to route through BigDecimal and reject them) must produce the same value.
  @Test
  void decodeAsDouble_specialValues_charArrayMatchesString() throws SQLException {
    for (String special : new String[]{"NaN", "Infinity", "-Infinity"}) {
      char[] chars = special.toCharArray();
      assertEquals(Double.doubleToRawLongBits(codec.decodeAsDouble(special, numericType, null)),
          Double.doubleToRawLongBits(codec.decodeAsDouble(new CharArraySequence(chars, 0, chars.length), numericType, null)),
          special);
    }
  }

  // Regression: decodeAsInt/decodeAsLong round to nearest (PostgreSQL's numeric->int cast), so the
  // char[] form must round too rather than fall to the truncating boxToInt default -- ".9" rounds to 1,
  // "1.5" to 2, not truncate to 0 and 1.
  @Test
  void decodeAsInt_charArray_roundsLikeString() throws SQLException {
    char[] chars = ".9".toCharArray();
    assertEquals(1, codec.decodeAsInt(new CharArraySequence(chars, 0, chars.length), numericType, null));
    assertEquals(codec.decodeAsInt(".9", numericType, null),
        codec.decodeAsInt(new CharArraySequence(chars, 0, chars.length), numericType, null));
  }

  @Test
  void decodeAsLong_charArray_roundsLikeString() throws SQLException {
    char[] chars = "1.5".toCharArray();
    assertEquals(2L, codec.decodeAsLong(new CharArraySequence(chars, 0, chars.length), numericType, null));
    assertEquals(codec.decodeAsLong("1.5", numericType, null),
        codec.decodeAsLong(new CharArraySequence(chars, 0, chars.length), numericType, null));
  }

  // ==================== Text-as-bytes Decoding ====================

  // numeric overrides decodeTextBytesAsInt/Long: a plain integer parses straight off the wire bytes
  // with no String or BigDecimal, while a fractional or special value falls back to the BigDecimal
  // path, which rounds half-away-from-zero rather than truncating the way the byte fast path would.

  @Test
  void decodeTextBytesAsInt_plainInteger_fastPath() throws SQLException {
    CodecContext ctx = TestCodecContext.create();
    assertEquals(42, codec.decodeTextBytesAsInt("42".getBytes(StandardCharsets.US_ASCII), numericType, ctx));
    assertEquals(-42, codec.decodeTextBytesAsInt("-42".getBytes(StandardCharsets.US_ASCII), numericType, ctx));
    assertEquals(0, codec.decodeTextBytesAsInt("0".getBytes(StandardCharsets.US_ASCII), numericType, ctx));
  }

  @Test
  void decodeTextBytesAsLong_plainInteger_fastPath() throws SQLException {
    CodecContext ctx = TestCodecContext.create();
    assertEquals(9999999999L,
        codec.decodeTextBytesAsLong("9999999999".getBytes(StandardCharsets.US_ASCII), numericType, ctx));
  }

  @Test
  void decodeTextBytesAsInt_fraction_roundsNotTruncates() throws SQLException {
    // The byte fast path must not swallow the fraction: '2.5'::numeric::int4 = 3 (round
    // half-away-from-zero), not 2 (getFastLong truncation). The fractional value falls back to the
    // BigDecimal path, so it rounds like decodeAsInt.
    CodecContext ctx = TestCodecContext.create();
    assertEquals(3, codec.decodeTextBytesAsInt("2.5".getBytes(StandardCharsets.US_ASCII), numericType, ctx));
    assertEquals(-3, codec.decodeTextBytesAsInt("-2.5".getBytes(StandardCharsets.US_ASCII), numericType, ctx));
    assertEquals(2, codec.decodeTextBytesAsInt("2.4".getBytes(StandardCharsets.US_ASCII), numericType, ctx));
    assertEquals(1, codec.decodeTextBytesAsInt(".9".getBytes(StandardCharsets.US_ASCII), numericType, ctx));
  }

  @Test
  void decodeTextBytesAsLong_fraction_roundsNotTruncates() throws SQLException {
    CodecContext ctx = TestCodecContext.create();
    assertEquals(2L, codec.decodeTextBytesAsLong("1.5".getBytes(StandardCharsets.US_ASCII), numericType, ctx));
  }

  @Test
  void decodeTextBytesAsInt_overflow() {
    // A plain integer beyond int range must still be rejected through the byte fast path, falling
    // back to the BigDecimal path which throws the range error.
    CodecContext ctx = TestCodecContext.create();
    assertThrows(PSQLException.class,
        () -> codec.decodeTextBytesAsInt("2147483648".getBytes(StandardCharsets.US_ASCII), numericType, ctx));
  }

  @Test
  void decodeTextBytesAsInt_specialValue_refused() {
    // NaN/Infinity are not eligible for the fast path; they fall back and refuse int decoding.
    CodecContext ctx = TestCodecContext.create();
    assertThrows(PSQLException.class,
        () -> codec.decodeTextBytesAsInt("NaN".getBytes(StandardCharsets.US_ASCII), numericType, ctx));
  }

  @Test
  void decodeAsString_text() throws SQLException {
    String result = codec.decodeAsString("123.456", numericType, null);
    assertEquals("123.456", result);
  }

  // Regression: getString of a small value used BigDecimal.toString() and returned scientific
  // notation ("1E-20") for a magnitude below ~1e-6, where the server's ::text and the text protocol
  // print the plain form. Both wire formats must render the plain form.
  @Test
  void decodeAsString_smallValue_plainNotScientific() throws SQLException {
    String plain = "0.00000000000000000001";
    byte[] binary = ByteConverter.numeric(new BigDecimal(plain));
    assertEquals(plain, codec.decodeAsString(binary, 0, binary.length, numericType, null));
    assertEquals(plain, codec.decodeAsString(plain, numericType, null));
  }

  @Test
  void textRoundtrip_decimal() throws SQLException {
    BigDecimal original = new BigDecimal("12345.67890");
    String encoded = codec.encodeText(original, numericType, null);
    Object decoded = codec.decodeText(encoded, numericType, null);
    assertEquals(original, decoded);
  }

  // ==================== decodeTextAs / decodeBinaryAs parity ====================

  @Test
  void decodeTextAs_matchesBinaryAs() throws SQLException {
    // decodeTextAs decodes straight from the text form; it must still agree with the binary path,
    // which the earlier text->bytes->decodeBinaryAs round-trip guaranteed by construction.
    BigDecimal value = new BigDecimal("12.5");
    byte[] binary = ByteConverter.numeric(value);
    String text = value.toPlainString();
    Class<?>[] targets = {
        BigDecimal.class, Object.class, Double.class, Float.class, Long.class,
        Integer.class, Short.class, Byte.class, String.class, Boolean.class,
    };
    for (Class<?> target : targets) {
      assertEquals(
          codec.decodeBinaryAs(binary, 0, binary.length, numericType, target, null),
          codec.decodeTextAs(text, numericType, target, null),
          "text/binary mismatch for " + target.getSimpleName());
    }
  }

  @Test
  void decodeTextAs_specialValues_double() throws SQLException {
    // NaN / ±Infinity reach Double via the same path the binary decode uses, rather than being
    // rejected by an up-front BigDecimal conversion.
    assertEquals(Double.valueOf(Double.NaN),
        codec.decodeTextAs("NaN", numericType, Double.class, null));
    assertEquals(Double.valueOf(Double.POSITIVE_INFINITY),
        codec.decodeTextAs("Infinity", numericType, Double.class, null));
    assertEquals(Double.valueOf(Double.NEGATIVE_INFINITY),
        codec.decodeTextAs("-Infinity", numericType, Double.class, null));
  }

  @Test
  void decodeTextAs_specialValues_float() throws SQLException {
    assertEquals(Float.valueOf(Float.NaN),
        codec.decodeTextAs("NaN", numericType, Float.class, null));
    assertEquals(Float.valueOf(Float.POSITIVE_INFINITY),
        codec.decodeTextAs("Infinity", numericType, Float.class, null));
  }

  @Test
  void decodeTextAs_nanToBigDecimal_throws() {
    // BigDecimal cannot hold NaN, so this stays an error on the text path too.
    assertThrows(PSQLException.class,
        () -> codec.decodeTextAs("NaN", numericType, BigDecimal.class, null));
  }

  // ==================== encode NaN / ±Infinity (text + binary) ====================

  @Test
  void encodeBinary_nan_roundTrips() throws SQLException {
    // Regression: encodeBinary used to route NaN through BigDecimal.valueOf, which throws
    // NumberFormatException. It must instead emit the numeric special: len=0, weight=0,
    // sign=0xC000 (NUMERIC_NAN), dscale=0 — the exact bytes numeric_send produces.
    byte[] encoded = codec.encodeBinary(Double.NaN, numericType, null);
    assertArrayEquals(new byte[]{0, 0, 0, 0, (byte) 0xC0, 0, 0, 0}, encoded);
    assertEquals(Double.valueOf(Double.NaN), codec.decodeBinary(encoded, 0, encoded.length, numericType, null));
  }

  @Test
  void encodeBinary_positiveInfinity_roundTrips() throws SQLException {
    byte[] encoded = codec.encodeBinary(Double.POSITIVE_INFINITY, numericType, null);
    // sign=0xD000 (NUMERIC_PINF); dscale is discarded by the server on recv, so 0 is fine.
    assertArrayEquals(new byte[]{0, 0, 0, 0, (byte) 0xD0, 0, 0, 0}, encoded);
    assertEquals(Double.valueOf(Double.POSITIVE_INFINITY),
              codec.decodeBinary(encoded, 0, encoded.length, numericType, null));
  }

  @Test
  void encodeBinary_negativeInfinity_roundTrips() throws SQLException {
    byte[] encoded = codec.encodeBinary(Double.NEGATIVE_INFINITY, numericType, null);
    // sign=0xF000 (NUMERIC_NINF)
    assertArrayEquals(new byte[]{0, 0, 0, 0, (byte) 0xF0, 0, 0, 0}, encoded);
    assertEquals(Double.valueOf(Double.NEGATIVE_INFINITY),
              codec.decodeBinary(encoded, 0, encoded.length, numericType, null));
  }

  @Test
  void encodeBinary_floatSpecials_widenToDoubleSentinels() throws SQLException {
    byte[] data2 = codec.encodeBinary(Float.NaN, numericType, null);
    assertEquals(Double.valueOf(Double.NaN),
              codec.decodeBinary(data2, 0, data2.length, numericType, null));
    byte[] data1 = codec.encodeBinary(Float.POSITIVE_INFINITY, numericType, null);
    assertEquals(Double.valueOf(Double.POSITIVE_INFINITY),
              codec.decodeBinary(data1, 0, data1.length, numericType, null));
    byte[] data = codec.encodeBinary(Float.NEGATIVE_INFINITY, numericType, null);
    assertEquals(Double.valueOf(Double.NEGATIVE_INFINITY),
              codec.decodeBinary(data, 0, data.length, numericType, null));
  }

  @Test
  void encodeText_specialValues_roundTrip() throws SQLException {
    assertEquals("NaN", codec.encodeText(Double.NaN, numericType, null));
    assertEquals("Infinity", codec.encodeText(Double.POSITIVE_INFINITY, numericType, null));
    assertEquals("-Infinity", codec.encodeText(Double.NEGATIVE_INFINITY, numericType, null));
    // Float too — the sentinels the fuzzer feeds through writeFloat.
    assertEquals("NaN", codec.encodeText(Float.NaN, numericType, null));

    assertEquals(Double.valueOf(Double.NaN), codec.decodeText("NaN", numericType, null));
    assertEquals(Double.valueOf(Double.POSITIVE_INFINITY),
        codec.decodeText("Infinity", numericType, null));
    assertEquals(Double.valueOf(Double.NEGATIVE_INFINITY),
        codec.decodeText("-Infinity", numericType, null));
  }

  @Test
  void encodeBinary_hugeFiniteBigDecimal_notMistakenForInfinity() throws SQLException {
    // BigDecimal.doubleValue() overflows to Infinity for very large finite values, so
    // specialValue must inspect only Float/Double, never BigDecimal.
    BigDecimal huge = new BigDecimal("1E400");
    byte[] data = codec.encodeBinary(huge, numericType, null);
    Object decoded = codec.decodeBinary(data, 0, data.length, numericType, null);
    BigDecimal result = assertInstanceOf(BigDecimal.class, decoded);
    assertEquals(0, huge.compareTo(result));
  }

  @Test
  void numericNonFinite_rejectsFinite() {
    // The helper is only for the sentinels; a finite value is a caller bug, not silent garbage.
    assertThrows(IllegalArgumentException.class, () -> ByteConverter.numericNonFinite(1.0));
  }

  // ==================== malformed binary wire (F3a) ====================

  // A truncated or malformed binary numeric drives ByteConverter.numeric to throw
  // IllegalArgumentException. Every codec binary path must trap that and refuse with a clean
  // PSQLException (DATA_ERROR), rather than leak the unchecked exception the raw-bytes fuzzer flagged.

  @Test
  void decodeBinary_empty_refusesCleanly() {
    // A zero-length buffer is shorter than the 8-byte numeric header (the fuzzer's first input).
    assertBinaryRefused(new byte[0]);
  }

  @Test
  void decodeBinary_short_refusesCleanly() {
    // Fewer than 8 header bytes.
    assertBinaryRefused(new byte[]{0, 0, 0, 0, 0, 0, 0});
  }

  @Test
  void decodeBinary_lengthMismatch_refusesCleanly() {
    // Header claims one 2-byte digit group (len=1) but the buffer stops at the 8-byte header, so
    // numBytes != len*2+8.
    byte[] data = new byte[8];
    ByteConverter.int2(data, 0, (short) 1); // len = 1
    assertBinaryRefused(data);
  }

  @Test
  void decodeBinary_invalidSign_refusesCleanly() {
    // len=0 header with a sign field that is none of POS/NEG/NAN/PINF/NINF.
    byte[] data = new byte[8];
    ByteConverter.int2(data, 4, (short) 0x1234); // sign
    assertBinaryRefused(data);
  }

  @Test
  void decodeBinary_scaleRoundingArtefact_refusesCleanly() {
    // A crafted header whose weight/scale combination drives ByteConverter.numeric's internal
    // BigDecimal.setScale(0) to round throws ArithmeticException ("Rounding necessary"), a distinct
    // unchecked leak from the length IllegalArgumentException. The active Jazzer campaign found this
    // 16-byte input: len=4 with a body that produces a fractional value at scale 0. The guard traps it
    // the same way. (Regression pin for JazzerDecodeRobustnessFuzzTest.numericBinary.)
    byte[] data = {
        0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a, 0x0a,
    };
    assertBinaryRefused(data);
  }

  @Test
  void decodeBinary_negativeWeightZeroScale_refusesCleanly() {
    // weight < 0 with scale <= 0 breaks the server's numeric invariant (a fraction must have a
    // positive scale). ByteConverter.numeric used to assert this (an AssertionError under -ea, garbage
    // otherwise); it now rejects it as malformed and the codec surfaces DATA_ERROR. The active Jazzer
    // campaign found this 10-byte input (len=1, weight=0x9f00 < 0, scale=0).
    byte[] data = {
        0x00, 0x01, (byte) 0x9f, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0a, 0x25,
    };
    assertBinaryRefused(data);
  }

  @Test
  void decodeBinary_valid_stillDecodes() throws SQLException {
    // A well-formed binary numeric is unaffected by the guard.
    byte[] data = ByteConverter.numeric(new BigDecimal("12.5"));
    assertEquals(new BigDecimal("12.5"), codec.decodeBinary(data, 0, data.length, numericType, null));
    assertEquals(new BigDecimal("12.5"), codec.decodeAsBigDecimal(data, 0, data.length, numericType, null));
    assertEquals(12.5, PrimitiveDecoders.asDouble(codec, data, numericType, null), 0.0);
    assertEquals(new BigDecimal("12.5"),
        codec.decodeBinaryAs(data, 0, data.length, numericType, BigDecimal.class, null));
  }

  /**
   * Asserts every binary decode entry point refuses {@code data} with a {@link PSQLState#DATA_ERROR}
   * {@link PSQLException} and never leaks an unchecked exception.
   */
  private void assertBinaryRefused(byte[] data) {
    assertBinaryPathRefused("decodeBinary", () -> codec.decodeBinary(data, 0, data.length, (TypeDescriptor) numericType, (CodecContext) null));
    assertBinaryPathRefused("decodeAsBigDecimal",
        () -> codec.decodeAsBigDecimal(data, 0, data.length, (TypeDescriptor) numericType, (CodecContext) null));
    assertBinaryPathRefused("decodeAsDouble", () -> PrimitiveDecoders.asDouble(codec, data, numericType, null));
    // decodeBinaryAs(BigDecimal) is the exact path the Jazzer numericBinary target exercises.
    assertBinaryPathRefused("decodeBinaryAs",
        () -> codec.decodeBinaryAs(data, 0, data.length, (TypeDescriptor) numericType, BigDecimal.class, (CodecContext) null));
  }

  private static void assertBinaryPathRefused(String path,
      org.junit.jupiter.api.function.Executable decode) {
    PSQLException e = assertThrows(PSQLException.class, decode,
        () -> "numeric binary " + path + " should refuse malformed wire");
    assertEquals(PSQLState.DATA_ERROR.getState(), e.getSQLState(),
        () -> "SQLState for numeric binary " + path);
  }

  @Test
  void getPrimaryTypeName() {
    assertEquals("numeric", codec.getPrimaryTypeName());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(BigDecimal.class, codec.getDefaultJavaType());
  }
}
