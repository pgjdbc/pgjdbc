/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.Codecs;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Factory for the errors thrown by codecs across the codec API.
 *
 * <p>Every {@code new PSQLException}/{@code new SQLException}/{@code GT.tr} call for the codec
 * API lives here so message text and {@link PSQLState} choices stay in one place instead of
 * duplicated across codecs.</p>
 *
 * <p>{@code cannotDecode}/{@code cannotEncode} delegate to {@link Codecs}, which is public: codecs
 * outside this package (e.g. {@code org.postgresql.api.codec.BinaryCodec}) need the same errors and
 * cannot see this package-private class.</p>
 */
class Exceptions {
  private Exceptions() {
  }

  // Read/decode direction: value present, but the wrong shape or out of range for the target type.

  /**
   * Creates a standard error for a value read from the database that cannot be
   * represented as {@code targetType}.
   *
   * <p>This is the read/decode direction (for example {@code getDate} on an int4
   * column), so the error carries {@link PSQLState#DATA_TYPE_MISMATCH}.</p>
   *
   * @param value decoded value, or null when the decoded value is SQL NULL
   * @param targetType target Java type name
   * @return conversion error
   */
  static SQLException cannotDecode(@Nullable Object value, String targetType) {
    return Codecs.cannotDecode(value, targetType);
  }

  /**
   * Creates a standard decode error from a source type name to a target Java
   * type name.
   *
   * <p>This is the read/decode direction, so the error carries
   * {@link PSQLState#DATA_TYPE_MISMATCH}.</p>
   *
   * @param sourceType source type name
   * @param targetType target Java type name
   * @return conversion error
   */
  static SQLException cannotDecode(String sourceType, String targetType) {
    return Codecs.cannotDecode(sourceType, targetType);
  }

  /**
   * Creates a standard error for a Java value that cannot be encoded as the
   * codec's PostgreSQL type.
   *
   * <p>This is the write/bind direction (for example binding a {@code byte[]} to
   * an int4 parameter), so the error carries
   * {@link PSQLState#INVALID_PARAMETER_TYPE}.</p>
   *
   * @param value the value being encoded, or null
   * @param targetType target PostgreSQL type name
   * @return conversion error
   */
  static SQLException cannotEncode(@Nullable Object value, String targetType) {
    return Codecs.cannotEncode(value, targetType);
  }

  /**
   * Creates a standard encode error from a source type name to a target
   * PostgreSQL type name.
   *
   * <p>This is the write/bind direction, so the error carries
   * {@link PSQLState#INVALID_PARAMETER_TYPE}.</p>
   *
   * @param sourceType source type name
   * @param targetType target PostgreSQL type name
   * @return conversion error
   */
  static SQLException cannotEncode(String sourceType, String targetType) {
    return Codecs.cannotEncode(sourceType, targetType);
  }

  /**
   * Value could not be encoded as {@code typeName} (used by codecs that phrase the base
   * {@link #cannotEncode(Object, String)} case as "encode ... as ..." with
   * {@link PSQLState#DATA_TYPE_MISMATCH} rather than {@link PSQLState#INVALID_PARAMETER_TYPE}).
   *
   * @param value the value being encoded
   * @param typeName target PostgreSQL type name (or description, e.g. {@code "range type"})
   * @return conversion error
   */
  static SQLException cannotEncodeAs(Object value, String typeName) {
    return new PSQLException(
        GT.tr("Cannot encode {0} as {1}", value.getClass().getName(), typeName),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  /**
   * A decoded numeric value does not fit {@code targetType} (int/long/short/byte/...).
   *
   * @param value the out-of-range value
   * @param targetType target type name
   * @return conversion error
   */
  static SQLException outOfRange(Object value, String targetType) {
    return new PSQLException(
        GT.tr("Value {0} is out of range for {1}", value, targetType),
        PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
  }

  /**
   * Narrows a decoded {@code long} to {@code int}, reporting {@code targetType} if it does not fit.
   *
   * <p>Only for call sites where the checked value and the reported value are the same {@code long}
   * (no independent source object to show instead) — that is what lets the check and the throw
   * collapse into one line at the call site.</p>
   *
   * @param value the decoded value
   * @param targetType target type name, as reported by {@link #outOfRange(Object, String)}
   * @return {@code value} narrowed to {@code int}
   * @throws SQLException if {@code value} does not fit in {@code int}
   */
  static int checkIntRange(long value, String targetType) throws SQLException {
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw outOfRange(value, targetType);
    }
    return (int) value;
  }

  /**
   * A textual literal could not be parsed as {@code targetType}.
   *
   * @param targetType target type name
   * @param data the literal that failed to parse
   * @param cause the underlying parse failure
   * @return conversion error, carrying {@link PSQLState#NUMERIC_VALUE_OUT_OF_RANGE}
   */
  static SQLException cannotConvertValue(String targetType, Object data, Throwable cause) {
    return cannotConvertValue(targetType, data, PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, cause);
  }

  /**
   * A textual literal could not be parsed as {@code targetType}.
   *
   * @param targetType target type name
   * @param data the literal that failed to parse
   * @param state SQLState to report
   * @param cause the underlying parse failure
   * @return conversion error
   */
  static SQLException cannotConvertValue(String targetType, Object data, PSQLState state, Throwable cause) {
    return new PSQLException(
        GT.tr("Cannot convert value to {0}: {1}", targetType, data),
        state, cause);
  }

  /**
   * A floating-point value (NaN or infinite) has no {@link java.math.BigDecimal} representation.
   *
   * @param value the value that cannot be represented
   * @return conversion error, carrying {@link PSQLState#NUMERIC_VALUE_OUT_OF_RANGE}
   */
  static SQLException cannotConvertToBigDecimal(Object value) {
    return new PSQLException(
        GT.tr("Cannot convert {0} to BigDecimal", value),
        PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
  }

  /**
   * A textual literal is not a legal value for {@code typeName}.
   *
   * @param typeName target type name (as reported to the user, e.g. {@code "BigDecimal"})
   * @param value the offending literal or value
   * @return conversion error, carrying {@link PSQLState#NUMERIC_VALUE_OUT_OF_RANGE}
   */
  static SQLException badValueForType(String typeName, Object value) {
    return new PSQLException(
        GT.tr("Bad value for type {0} : {1}", typeName, value),
        PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
  }

  /**
   * A binary interval carries more hours than a {@link org.postgresql.util.PGInterval} can hold.
   *
   * <p>PGInterval stores hours in an {@code int}, but the wire microsecond field (an int64) reaches
   * about 2562047788 hours near its limit. The value is well-formed on the wire, so this is a range
   * failure of the driver's representation rather than corrupt data; {@code getString} still renders
   * it, only {@code getObject} (which builds a PGInterval) refuses.</p>
   *
   * @param hours the hour count that overflows PGInterval's int field
   * @return decode error, carrying {@link PSQLState#NUMERIC_CONSTANT_OUT_OF_RANGE}
   */
  static SQLException intervalHoursOutOfRange(long hours) {
    return new PSQLException(
        GT.tr("Interval hour value {0} is out of range for PGInterval", hours),
        PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE);
  }

  // Binary wire format: fixed-width values and array elements with the wrong length.

  /**
   * The binary representation of {@code typeName} had the wrong length.
   *
   * @param typeName PostgreSQL type name
   * @param length actual length received
   * @return decode error, carrying {@link PSQLState#DATA_ERROR}
   */
  static SQLException invalidBinaryLength(String typeName, int length) {
    return new PSQLException(
        GT.tr("Invalid {0} binary data length: {1}", typeName, length),
        PSQLState.DATA_ERROR);
  }

  /**
   * A binary array element of {@code typeName} had the wrong length.
   *
   * @param typeName PostgreSQL element type name
   * @param length actual length received
   * @return decode error, carrying {@link PSQLState#DATA_ERROR}
   */
  static SQLException invalidArrayElementLength(String typeName, int length) {
    return new PSQLException(
        GT.tr("Invalid {0} array element length: {1}", typeName, length),
        PSQLState.DATA_ERROR);
  }

  /**
   * A textual array element of {@code typeName} failed to parse.
   *
   * @param typeName PostgreSQL element type name
   * @param literal the offending literal
   * @param cause the underlying parse failure
   * @return decode error, carrying {@link PSQLState#NUMERIC_VALUE_OUT_OF_RANGE}
   */
  static SQLException invalidArrayElement(String typeName, String literal, Throwable cause) {
    return new PSQLException(
        GT.tr("Invalid {0} array element: {1}", typeName, literal),
        PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, cause);
  }

  /**
   * A SQL NULL array element was decoded into a primitive (non-boxed) array leaf, which cannot
   * represent NULL.
   *
   * @param leafTypeName primitive leaf array type, e.g. {@code "double[]"}
   * @return decode error, carrying {@link PSQLState#DATA_ERROR}
   */
  static SQLException cannotDecodeNullIntoPrimitiveLeaf(String leafTypeName) {
    return new PSQLException(
        GT.tr("Cannot decode NULL into primitive {0} leaf", leafTypeName),
        PSQLState.DATA_ERROR);
  }

  // One-off codec errors, still centralized here so no other class in this package builds
  // a PSQLException/SQLException/GT.tr message directly.

  static SQLException invalidBitCount(int nbits, int length) {
    return new PSQLException(
        GT.tr("Invalid bit binary data: bit count {0} does not match data length {1}", nbits, length),
        PSQLState.DATA_ERROR);
  }

  static SQLException cannotConvertColumn(String sourceType, String targetType) {
    return new PSQLException(
        GT.tr("Cannot convert the column of type {0} to requested type {1}.", sourceType, targetType),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  static SQLException unsupportedArrayLeafClass(String arrayDescription, String leafClassName) {
    return new PSQLException(
        GT.tr("Unsupported leaf array class for {0}: {1}", arrayDescription, leafClassName),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  static String describeArrayElementOid(int oid) {
    return GT.tr("array with element oid {0}", oid);
  }

  static SQLException noBinaryCodecForArrayElement(String elementTypeName) {
    return new PSQLException(
        GT.tr("No binary codec registered for array element type {0}", elementTypeName),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  static SQLException noTextCodecForArrayElement(String elementTypeName) {
    return new PSQLException(
        GT.tr("No text codec registered for array element type {0}", elementTypeName),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  static SQLException failedToInstantiate(String className, Throwable cause) {
    return new PSQLException(
        GT.tr("Failed to instantiate {0}", className),
        PSQLState.DATA_TYPE_MISMATCH, cause);
  }

  static SQLException cannotInstantiate(String className, Throwable cause) {
    return new PSQLException(
        GT.tr("Cannot create instance of {0}. An accessible no-arg constructor is required.", className),
        PSQLState.SYSTEM_ERROR, cause);
  }

  // Composite (row) type binary/text decode and encode.
  //
  // The wire-format checks below delegate to Codecs (public) because
  // org.postgresql.jdbc.PgSQLInputBinary parses the same binary composite format and needs to
  // report the identical errors, but cannot see this package-private class.

  static SQLException invalidCompositeTooShort() {
    return Codecs.invalidCompositeTooShort();
  }

  static SQLException invalidCompositeNegativeFieldCount(int fieldCount) {
    return Codecs.invalidCompositeNegativeFieldCount(fieldCount);
  }

  static SQLException invalidCompositeFieldCountExceedsData(int fieldCount) {
    return new PSQLException(
        GT.tr("Invalid binary composite data: field count {0} exceeds remaining data", fieldCount),
        PSQLState.DATA_ERROR);
  }

  static SQLException invalidCompositeUnexpectedEnd(int fieldIndex) {
    return Codecs.invalidCompositeUnexpectedEnd(fieldIndex);
  }

  static SQLException invalidCompositeFieldLength(int length, int fieldIndex) {
    return Codecs.invalidCompositeFieldLength(length, fieldIndex);
  }

  static SQLException invalidCompositeNotEnoughData(int fieldIndex) {
    return Codecs.invalidCompositeNotEnoughData(fieldIndex);
  }

  static SQLException cannotConvertToComposite(Object value) {
    return new PSQLException(
        GT.tr("Cannot convert {0} to composite", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  static SQLException cannotEncodeCompositeBinary(Object value) {
    return new PSQLException(
        GT.tr("Cannot encode {0} as composite binary. Use SQLData or Struct implementation.", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  static SQLException cannotConvertCompositeTo(String targetClassName) {
    return new PSQLException(
        GT.tr("Cannot convert composite to {0}. Use an SQLData or Struct implementation.", targetClassName),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  static SQLException compositeTextHasMoreAttributes(String typeName, int expected) {
    return new PSQLException(
        GT.tr("Composite type {0} has {1} attribute(s), but its text literal has more", typeName, expected),
        PSQLState.DATA_ERROR);
  }

  static SQLException compositeTextAttributeCountMismatch(String typeName, int expected, int actual) {
    return new PSQLException(
        GT.tr("Composite type {0} has {1} attribute(s), but its text literal has {2}",
            typeName, expected, actual),
        PSQLState.DATA_ERROR);
  }

  static SQLException compositeAttributeCountMismatch(String typeName, int expected, int actual) {
    return new PSQLException(
        GT.tr("Composite type {0} expects {1} attribute(s), but {2} were provided",
            typeName, expected, actual),
        PSQLState.DATA_ERROR);
  }

  static SQLException noTextCodecForCompositeField(int fieldOid, String fieldName, String compositeTypeName) {
    return new PSQLException(
        GT.tr("No text codec registered for type OID {0} (field {1} of {2})",
            fieldOid, fieldName, compositeTypeName),
        PSQLState.SYSTEM_ERROR);
  }

  static SQLException noBinaryCodecForCompositeField(int fieldOid, String fieldName, String compositeTypeName) {
    return new PSQLException(
        GT.tr("No binary codec registered for type OID {0} (field {1} of {2})",
            fieldOid, fieldName, compositeTypeName),
        PSQLState.SYSTEM_ERROR);
  }

  static SQLException errorWritingComposite(Throwable cause) {
    return new PSQLException(GT.tr("Error writing composite value"), PSQLState.SYSTEM_ERROR, cause);
  }

  // Arrays (single-dimension convenience wrapper and the multi-dimensional walker).

  static SQLException cannotConvertToArray(Object value) {
    return new PSQLException(
        GT.tr("Cannot convert {0} to array", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  static SQLException cannotConvertArrayTo(String targetClassName) {
    return new PSQLException(
        GT.tr("Cannot convert array to {0}", targetClassName),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  static SQLException arrayNullSubArray() {
    return new PSQLException(
        GT.tr("Multidimensional arrays must not contain null sub-arrays"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  static SQLException arrayNotRectangular() {
    return new PSQLException(
        GT.tr("Multidimensional arrays must be rectangular"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  static SQLException requiresJavaArray(String methodContext, Object value) {
    return new PSQLException(
        GT.tr("{0} requires a Java array, got {1}", methodContext, value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  static SQLException requiresArrayLiteral(String methodContext, Object data) {
    return new PSQLException(
        GT.tr("{0} requires an array literal, got {1}", methodContext, data),
        PSQLState.DATA_ERROR);
  }

  static SQLException invalidArrayDimensionCount(int dimensions) {
    return new PSQLException(
        GT.tr("Invalid binary array data: dimension count {0} out of range", dimensions),
        PSQLState.DATA_ERROR);
  }

  static SQLException cannotDecodeArrayNullIntoPrimitiveLeaf(String leafComponentTypeName) {
    return new PSQLException(
        GT.tr("Cannot decode array containing NULL into a primitive {0}[] leaf", leafComponentTypeName),
        PSQLState.DATA_ERROR);
  }

  static SQLException invalidArrayDimensionLength(int dimLength) {
    return new PSQLException(
        GT.tr("Invalid binary array data: negative dimension length {0}", dimLength),
        PSQLState.DATA_ERROR);
  }

  static SQLException invalidArrayElementCountExceedsData(long partialProduct) {
    return new PSQLException(
        GT.tr("Invalid binary array data: element count {0} exceeds remaining data", partialProduct),
        PSQLState.DATA_ERROR);
  }

  static SQLException invalidArrayTruncatedElementBody(Throwable cause) {
    return new PSQLException(
        GT.tr("Invalid binary array data: truncated element body"),
        PSQLState.DATA_ERROR, cause);
  }

  static SQLException invalidArrayElementBodyOverrun() {
    return new PSQLException(
        GT.tr("Invalid binary array data: element body extends past the declared length"),
        PSQLState.DATA_ERROR);
  }

  static SQLException arrayLeafCodecUnsupported(int oid, String leafComponentTypeName) {
    return new PSQLException(
        GT.tr("Array leaf codec for oid {0} does not support {1}", oid, leafComponentTypeName),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  static SQLException invalidArrayTruncatedHeader() {
    return new PSQLException(GT.tr("Invalid binary array data: truncated header"), PSQLState.DATA_ERROR);
  }

  // Array/composite text literal parsing (LiteralCursor).

  static SQLException malformedLiteral(char expected, int pos) {
    return new PSQLException(
        GT.tr("Malformed array/composite literal: expected ''{0}'' at offset {1}", expected, pos),
        PSQLState.DATA_ERROR);
  }

  // range/multirange: subtype (or range-type) resolution, wire format, and text format.
  //
  // "range"/"range type"/"multirange"/"multirange type" stay as fixed text in each message below
  // (rather than a shared parameterized template) because they are translatable words, not literal
  // type/class identifiers like "hstore" or "BigDecimal".

  static SQLException cannotEncodeRange(Object value) {
    return new PSQLException(
        GT.tr("Cannot encode {0} as range type", value.getClass().getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  static SQLException cannotDecodeRangeTo(String targetClassName) {
    return new PSQLException(
        GT.tr("Cannot decode range to {0}", targetClassName),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  static SQLException cannotEncodeMultirange(Object value) {
    return new PSQLException(
        GT.tr("Cannot encode {0} as multirange type", value.getClass().getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  static SQLException cannotDecodeMultirangeTo(String targetClassName) {
    return new PSQLException(
        GT.tr("Cannot decode multirange to {0}", targetClassName),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  static SQLException rangeSubtypeUnresolvedForDecode(String rangeTypeFullName) {
    return new PSQLException(
        GT.tr("Cannot decode range {0} in binary: its subtype (pg_range.rngsubtype) "
            + "could not be resolved.", rangeTypeFullName),
        PSQLState.DATA_ERROR);
  }

  static SQLException rangeSubtypeCodecMissingForDecode(String rangeTypeFullName, int subtypeOid) {
    return new PSQLException(
        GT.tr("Cannot decode range {0} in binary: no binary codec for subtype OID {1}.",
            rangeTypeFullName, subtypeOid),
        PSQLState.DATA_ERROR);
  }

  static SQLException rangeSubtypeUnresolvedForEncode(String rangeTypeFullName) {
    return new PSQLException(
        GT.tr("Cannot encode range {0} in binary: its subtype (pg_range.rngsubtype) "
            + "could not be resolved.", rangeTypeFullName),
        PSQLState.DATA_ERROR);
  }

  static SQLException rangeSubtypeCodecMissingForEncode(String rangeTypeFullName, int subtypeOid) {
    return new PSQLException(
        GT.tr("Cannot encode range {0} in binary: no binary codec for subtype OID {1}.",
            rangeTypeFullName, subtypeOid),
        PSQLState.DATA_ERROR);
  }

  static SQLException invalidRangeMissingLowerBoundLength() {
    return new PSQLException(
        GT.tr("Invalid range binary data: missing lower bound length"), PSQLState.DATA_ERROR);
  }

  static SQLException invalidRangeLowerBoundTruncated() {
    return new PSQLException(
        GT.tr("Invalid range binary data: lower bound truncated"), PSQLState.DATA_ERROR);
  }

  static SQLException invalidRangeMissingUpperBoundLength() {
    return new PSQLException(
        GT.tr("Invalid range binary data: missing upper bound length"), PSQLState.DATA_ERROR);
  }

  static SQLException invalidRangeUpperBoundTruncated() {
    return new PSQLException(
        GT.tr("Invalid range binary data: upper bound truncated"), PSQLState.DATA_ERROR);
  }

  static SQLException errorEncodingRange(Throwable cause) {
    return new PSQLException(GT.tr("Error encoding range"), PSQLState.DATA_ERROR, cause);
  }

  static SQLException invalidRangeBoundaryFormat(String expectedDescription, String literal) {
    return new PSQLException(
        GT.tr("Invalid range format, expected {0}: {1}", expectedDescription, literal),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  static SQLException multirangeRangeTypeUnresolvedForDecode(String multirangeTypeFullName) {
    return new PSQLException(
        GT.tr("Cannot decode multirange {0} in binary: its range type (pg_range.rngtypid) "
            + "could not be resolved.", multirangeTypeFullName),
        PSQLState.DATA_ERROR);
  }

  static SQLException multirangeRangeCodecMissingForDecode(String multirangeTypeFullName, int rangeOid) {
    return new PSQLException(
        GT.tr("Cannot decode multirange {0} in binary: no binary codec for range OID {1}.",
            multirangeTypeFullName, rangeOid),
        PSQLState.DATA_ERROR);
  }

  static SQLException multirangeRangeTypeUnresolvedForEncode(String multirangeTypeFullName) {
    return new PSQLException(
        GT.tr("Cannot encode multirange {0} in binary: its range type (pg_range.rngtypid) "
            + "could not be resolved.", multirangeTypeFullName),
        PSQLState.DATA_ERROR);
  }

  static SQLException multirangeRangeCodecMissingForEncode(String multirangeTypeFullName, int rangeOid) {
    return new PSQLException(
        GT.tr("Cannot encode multirange {0} in binary: no binary codec for range OID {1}.",
            multirangeTypeFullName, rangeOid),
        PSQLState.DATA_ERROR);
  }

  static SQLException multirangeRangeTypeUnresolvedForDecodeText(String multirangeTypeFullName) {
    return new PSQLException(
        GT.tr("Cannot decode multirange {0}: its range type (pg_range.rngtypid) could not be resolved.",
            multirangeTypeFullName),
        PSQLState.DATA_ERROR);
  }

  static SQLException invalidMultirangeMissingRangeCount() {
    return new PSQLException(
        GT.tr("Invalid multirange binary data: missing range count"), PSQLState.DATA_ERROR);
  }

  static SQLException invalidMultirangeNegativeRangeCount(int count) {
    return new PSQLException(
        GT.tr("Invalid multirange binary data: negative range count {0}", count), PSQLState.DATA_ERROR);
  }

  static SQLException invalidMultirangeMissingRangeLength() {
    return new PSQLException(
        GT.tr("Invalid multirange binary data: missing range length"), PSQLState.DATA_ERROR);
  }

  static SQLException invalidMultirangeRangeTruncated() {
    return new PSQLException(
        GT.tr("Invalid multirange binary data: range truncated"), PSQLState.DATA_ERROR);
  }

  static SQLException multirangeElementNotARange(String decodedClassName) {
    return new PSQLException(
        GT.tr("Multirange element did not decode to a range: {0}", decodedClassName),
        PSQLState.DATA_ERROR);
  }

  // numeric binary decode.

  static SQLException invalidBinaryNumericValue(Throwable cause) {
    return new PSQLException(GT.tr("Invalid binary numeric value"), PSQLState.DATA_ERROR, cause);
  }
}
