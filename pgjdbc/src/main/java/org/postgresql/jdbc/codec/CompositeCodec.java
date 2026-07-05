/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgSQLInputBinary;
import org.postgresql.jdbc.PgSQLInputText;
import org.postgresql.jdbc.PgSQLOutputBinary;
import org.postgresql.jdbc.PgSQLOutputText;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Codec for PostgreSQL composite (record) types.
 *
 * <p>This codec handles encoding and decoding of PostgreSQL composite types,
 * including SQLData implementations.</p>
 *
 * <p>Note: Composite type handling requires type metadata which is typically
 * retrieved from TypeInfoCache. Full composite support is handled via
 * PgStruct and SQLInput/SQLOutput implementations.</p>
 */
public final class CompositeCodec implements StreamingBinaryCodec, StreamingTextCodec {

  public static final CompositeCodec INSTANCE = new CompositeCodec();

  private CompositeCodec() {
    // Singleton for composite handling
  }

  // The composite codec downcasts to PgCodecContext to pass the connection (or null, offline) into
  // the PgStruct it returns, and to hand the concrete PgType and PgCodecContext to the internal
  // SQLData adapters (PgSQLInput*/PgSQLOutput*). The struct and the SQLData adapters both work
  // offline; only a nested array or XML field inside an SQLData value still needs a connection and
  // reports a clear error. Child field types and codecs resolve through the CodecContext interface
  // (slice 2c).
  private static PgCodecContext impl(CodecContext ctx) {
    return (PgCodecContext) ctx;
  }

  /**
   * Returns the composite's attribute fields, loading them through the context when the descriptor
   * does not already carry them. The anonymous RECORD pseudo-type has no catalog attributes, so its
   * fields resolve to an empty list.
   */
  private static List<? extends org.postgresql.api.codec.PgField> resolveFields(
      TypeDescriptor type, CodecContext ctx) throws SQLException {
    List<? extends org.postgresql.api.codec.PgField> fields = type.getFields();
    if (fields != null) {
      return fields;
    }
    fields = ctx.resolveType(type.getOid()).getFields();
    return fields != null ? fields : Collections.emptyList();
  }

  // =========================================================================
  // Static utility methods for binary composite encoding/decoding
  // =========================================================================

  /**
   * Represents a decoded field from a composite type.
   */
  public static final class DecodedField {
    private final int typeOid;
    private final byte[] source;
    private final int offset;
    private final int length;

    DecodedField(int typeOid, byte[] source, int offset, int length) {
      this.typeOid = typeOid;
      this.source = source;
      this.offset = offset;
      this.length = length;
    }

    /**
     * Returns the OID of the field's type.
     */
    public int getTypeOid() {
      return typeOid;
    }

    /**
     * Returns a copy of this field's raw binary data, or null if the field is NULL.
     */
    public byte @Nullable [] getData() {
      return length < 0 ? null : Arrays.copyOfRange(source, offset, offset + length);
    }

    /**
     * Returns true if this field is NULL.
     */
    public boolean isNull() {
      return length < 0;
    }

    /**
     * Decodes this field through {@code codec}, reading directly from the backing
     * buffer without copying the field's bytes out first.
     */
    @Nullable Object decode(BinaryCodec codec, TypeDescriptor type, CodecContext ctx) throws SQLException {
      return codec.decodeBinary(source, offset, length, type, ctx);
    }
  }

  /**
   * Decodes a composite type from binary format.
   *
   * <p>Binary format for composite types:</p>
   * <pre>
   * [4 bytes] field_count (int32)
   * For each field:
   *   [4 bytes] field_type_oid (int32)
   *   [4 bytes] field_length (-1 for NULL, otherwise length in bytes)
   *   [N bytes] field_data (if length &gt; 0)
   * </pre>
   *
   * @param data the binary data
   * @return list of decoded fields
   * @throws SQLException if the data format is invalid
   */
  public static List<DecodedField> decodeBinaryFields(byte[] data) throws SQLException {
    return decodeBinaryFields(data, 0, data.length);
  }

  /**
   * Decodes the composite fields contained in {@code data[start, start + len)}.
   *
   * <p>Each {@link DecodedField} records the field's {@code (offset, length)}
   * within {@code data} instead of a copied {@code byte[]}, so callers that
   * decode through a codec avoid a slice allocation per field.
   * {@link DecodedField#getData()} still materializes a copy on demand.</p>
   *
   * @param data the backing buffer
   * @param start start of the composite within {@code data}
   * @param len length of the composite
   * @return list of decoded fields referencing {@code data}
   * @throws SQLException if the data format is invalid
   */
  public static List<DecodedField> decodeBinaryFields(byte[] data, int start, int len)
      throws SQLException {
    if (len < 4) {
      throw new PSQLException(
          GT.tr("Invalid binary composite data: too short"),
          PSQLState.DATA_ERROR);
    }

    int end = start + len;
    int pos = start;

    int fieldCount = ByteConverter.int4(data, pos);
    pos += 4;
    if (fieldCount < 0) {
      throw new PSQLException(
          GT.tr("Invalid binary composite data: negative field count {0}", fieldCount),
          PSQLState.DATA_ERROR);
    }
    // Bound the field count against the bytes that remain before sizing the list: every field carries
    // at least an 8-byte header (type OID + length) on the wire, so a count larger than
    // (remaining bytes / 8) is corrupt. Without this, a hostile count near Integer.MAX_VALUE would
    // drive an OutOfMemoryError in the ArrayList allocation before the per-field bounds check runs.
    if (fieldCount > (end - pos) / 8) {
      throw new PSQLException(
          GT.tr("Invalid binary composite data: field count {0} exceeds remaining data", fieldCount),
          PSQLState.DATA_ERROR);
    }

    List<DecodedField> fields = new ArrayList<>(fieldCount);

    for (int i = 0; i < fieldCount; i++) {
      if (end - pos < 8) {
        throw new PSQLException(
            GT.tr("Invalid binary composite data: unexpected end at field {0}", i),
            PSQLState.DATA_ERROR);
      }

      int typeOid = ByteConverter.int4(data, pos);
      pos += 4;
      int length = ByteConverter.int4(data, pos);
      pos += 4;

      if (length == -1) {
        fields.add(new DecodedField(typeOid, data, pos, -1));
      } else if (length < 0) {
        throw new PSQLException(
            GT.tr("Invalid binary composite data: invalid length {0} at field {1}", length, i),
            PSQLState.DATA_ERROR);
      } else {
        if (end - pos < length) {
          throw new PSQLException(
              GT.tr("Invalid binary composite data: not enough data for field {0}", i),
              PSQLState.DATA_ERROR);
        }
        fields.add(new DecodedField(typeOid, data, pos, length));
        pos += length;
      }
    }

    return fields;
  }

  /**
   * Encodes field values into binary composite format.
   *
   * @param fieldOids the OIDs of each field's type
   * @param fieldData the binary data for each field (null elements represent NULL values)
   * @return the encoded binary data
   * @throws SQLException if the arrays have different lengths
   */
  public static byte[] encodeBinaryFields(int[] fieldOids, byte[] @Nullable [] fieldData)
      throws SQLException {
    if (fieldOids.length != fieldData.length) {
      throw new PSQLException(
          GT.tr("Field OIDs and data arrays must have the same length"),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    // Calculate total size
    int size = 4; // field count
    for (byte[] data : fieldData) {
      size += 8; // type oid + length
      if (data != null) {
        size += data.length;
      }
    }

    byte[] result = new byte[size];
    int pos = 0;

    ByteConverter.int4(result, pos, fieldOids.length);
    pos += 4;

    for (int i = 0; i < fieldOids.length; i++) {
      ByteConverter.int4(result, pos, fieldOids[i]);
      pos += 4;
      byte[] data = fieldData[i];
      if (data == null) {
        ByteConverter.int4(result, pos, -1);
        pos += 4;
      } else {
        ByteConverter.int4(result, pos, data.length);
        pos += 4;
        System.arraycopy(data, 0, result, pos, data.length);
        pos += data.length;
      }
    }

    return result;
  }

  /**
   * Encodes field values into binary composite format using PgField metadata.
   *
   * @param fields the field metadata from the composite type
   * @param fieldData the binary data for each field (null elements represent NULL values)
   * @return the encoded binary data
   * @throws SQLException if the arrays have different lengths
   */
  public static byte[] encodeBinaryFields(List<PgField> fields, byte[] @Nullable [] fieldData)
      throws SQLException {
    if (fields.size() != fieldData.length) {
      throw new PSQLException(
          GT.tr("Field metadata and data arrays must have the same length: {0} vs {1}",
              fields.size(), fieldData.length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    int[] fieldOids = new int[fields.size()];
    for (int i = 0; i < fields.size(); i++) {
      fieldOids[i] = fields.get(i).getTypeOid();
    }

    return encodeBinaryFields(fieldOids, fieldData);
  }

  /**
   * Checks if a value needs quoting in composite text format.
   *
   * @param value the value to check
   * @return true if the value contains characters that require quoting
   */
  public static boolean needsQuoting(String value) {
    if (value.isEmpty()) {
      return true;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == ',' || c == '(' || c == ')' || c == '"' || c == '\\'
          || Character.isWhitespace(c)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Encodes struct/composite attributes as a PostgreSQL composite text value
   * by delegating each attribute to its per-field {@link TextCodec}.
   *
   * <p>Field types are resolved via {@code compositeType.getFields()} (loaded
   * lazily through {@link org.postgresql.core.TypeInfo#getFields(int)} when
   * not yet cached). Each non-null attribute is converted by the registered
   * text codec for its field's OID, then quoted/escaped per PostgreSQL's
   * composite text format.</p>
   *
   * @param attributes the attribute values (may contain nulls)
   * @param compositeType the composite type metadata; must have fields loaded or loadable
   * @param ctx the codec context
   * @return the text representation in PostgreSQL composite format: (val1,val2,...)
   * @throws SQLException if a field codec is missing or attribute encoding fails
   */
  public static String encodeAttributesAsText(
      @Nullable Object[] attributes,
      TypeDescriptor compositeType,
      CodecContext ctx) throws SQLException {
    StringBuilder sb = new StringBuilder();
    try {
      streamAttributesAsText(attributes, compositeType, ctx, sb);
    } catch (IOException e) {
      throw new AssertionError(e); // StringBuilder never throws
    }
    return sb.toString();
  }

  /**
   * Encodes struct/composite attributes as a PostgreSQL composite binary value
   * by delegating each attribute to its per-field {@link BinaryCodec}.
   *
   * <p>Field types are resolved via {@code compositeType.getFields()} (loaded
   * lazily through {@link org.postgresql.core.TypeInfo#getFields(int)} when
   * not yet cached). Each non-null attribute is converted by the registered
   * binary codec for its field's OID; null attributes are encoded as SQL NULL
   * placeholders in the composite wire layout.</p>
   *
   * @param attributes the attribute values (may contain nulls)
   * @param compositeType the composite type metadata; must have fields loaded or loadable
   * @param ctx the codec context
   * @return the binary representation in PostgreSQL composite wire format
   * @throws SQLException if a field codec is missing or attribute encoding fails
   */
  public static byte[] encodeAttributesAsBinary(
      @Nullable Object[] attributes,
      TypeDescriptor compositeType,
      CodecContext ctx) throws SQLException {
    BackpatchByteArrayOutputStream out = new BackpatchByteArrayOutputStream();
    try {
      streamAttributesAsBinary(attributes, compositeType, ctx, out);
    } catch (IOException e) {
      throw new AssertionError(e); // BackpatchByteArrayOutputStream never throws
    }
    return out.toByteArray();
  }

  /** {@link Appendable} overload used by the streaming text path. */
  private static void appendQuotedField(Appendable out, String value) throws IOException {
    if (needsQuoting(value)) {
      out.append('"');
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        // record_out doubles embedded quotes and backslashes.
        if (c == '"' || c == '\\') {
          out.append(c);
        }
        out.append(c);
      }
      out.append('"');
    } else {
      out.append(value);
    }
  }

  /**
   * Reads each field of a PostgreSQL composite text literal {@code (f0,f1,...)}
   * from {@code cur} and hands it to {@code consumer} as a borrowed,
   * already-unquoted slice. The surrounding parentheses are optional.
   *
   * <p>A field is SQL NULL iff it was unquoted and empty; a quoted empty field
   * is the empty string. Composites have at least one field, so an empty
   * {@code ()} yields a single NULL field. The slice is valid only for the
   * duration of the {@code accept} call (the cursor reuses its unescape buffer),
   * so the consumer must decode it before the next field.</p>
   */
  private static void readCompositeFields(LiteralCursor cur, CompositeFieldConsumer consumer)
      throws SQLException {
    cur.skipWhitespace();
    boolean parens = cur.peek() == '(';
    if (parens) {
      cur.expect('(');
    }
    int index = 0;
    while (true) {
      cur.readValue(',', ')');
      boolean isNull = !cur.tokenWasQuoted() && cur.tokenLength() == 0;
      consumer.accept(index, isNull, cur.tokenChars(), cur.tokenOffset(), cur.tokenLength());
      index++;
      if (!cur.tryConsume(',')) {
        break;
      }
    }
    if (parens) {
      cur.expect(')');
    }
  }

  /** Receives one composite field as a borrowed, already-unquoted char slice. */
  @FunctionalInterface
  private interface CompositeFieldConsumer {
    void accept(int index, boolean isNull, char[] buf, int offset, int length) throws SQLException;
  }

  /**
   * Parses a PostgreSQL composite text value, e.g. {@code (val1,"val,2",)}, into the
   * raw per-field strings. A null element represents a SQL NULL attribute. The
   * surrounding parentheses are optional.
   *
   * @param text the composite text value
   * @return per-field strings (with composite escape sequences resolved)
   * @throws SQLException if the literal is malformed
   */
  public static @Nullable String[] parseCompositeText(String text) throws SQLException {
    List<@Nullable String> values = new ArrayList<>();
    readCompositeFields(LiteralCursor.over(text), (index, isNull, buf, offset, length) ->
        values.add(isNull ? null : new String(buf, offset, length)));
    return values.toArray(new @Nullable String[0]);
  }

  // =========================================================================
  // Instance methods (BinaryCodec/TextCodec implementation)
  // =========================================================================

  /**
   * Creates a new instance of the given SQLData class.
   *
   * @param targetClass the SQLData class to instantiate
   * @return a new instance
   * @throws SQLException if instantiation fails
   */
  private static <T extends SQLData> T createSQLDataInstance(Class<T> targetClass) throws SQLException {
    try {
      return targetClass.getConstructor().newInstance();
    } catch (Exception ex) {
      throw new PSQLException(
          GT.tr("Cannot create instance of {0}. An accessible no-arg constructor is required.",
              targetClass.getName()),
          PSQLState.SYSTEM_ERROR, ex);
    }
  }

  @Override
  public String getTypeName() {
    return "record";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Struct.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    // PgStruct extends PGobject and implements Struct, so the same return value
    // satisfies both the legacy "(PGobject) rs.getObject(i)" contract and the
    // new "(Struct) rs.getObject(i)" contract.
    return decodeBinaryAsStruct(data, 0, data.length, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length == 0) {
      return null;
    }
    // Decode an array-of-struct element in place: decodeBinaryFields records the
    // field offsets as absolute indices into data, so each field decodes without
    // a per-element or per-field copy.
    return decodeBinaryAsStruct(data, offset, length, type, ctx);
  }

  /**
   * Decodes binary composite data into a PgStruct with per-attribute decoding
   * routed through the codec registered for each field's OID.
   */
  private static PgStruct decodeBinaryAsStruct(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    CodecDepth.enter();
    try {
      List<DecodedField> binaryFields = decodeBinaryFields(data, offset, length);
      @Nullable Object[] attributes = new @Nullable Object[binaryFields.size()];
      for (int i = 0; i < binaryFields.size(); i++) {
        DecodedField field = binaryFields.get(i);
        if (field.isNull()) {
          attributes[i] = null;
          continue;
        }
        int fieldOid = field.getTypeOid();
        TypeDescriptor fieldType = ctx.resolveType(fieldOid);
        BinaryCodec fieldCodec = ctx.resolveBinaryCodec(fieldOid);
        if (fieldCodec == null) {
          attributes[i] = field.getData();
        } else {
          attributes[i] = field.decode(fieldCodec, fieldType, ctx);
        }
      }
      // The struct carries the codec context (offline or connection-bound), so getValue() can
      // rebuild the text literal from the attributes either way; getAttributes() works regardless.
      return PgStruct.withCodecContext(structTypeFor(type, binaryFields), attributes, impl(ctx));
    } finally {
      CodecDepth.exit();
    }
  }

  /**
   * Returns the {@link PgType} the decoded {@link PgStruct} should carry. For a
   * named composite this is {@code type} as-is (its fields come from the
   * catalog). For the anonymous record pseudo-type (OID 2249) the catalog has no
   * attributes, so the field types are synthesized from the self-describing
   * binary wire — without touching the type cache — so that
   * {@link PgStruct#getValue()} can rebuild the {@code record_out} literal.
   */
  private static PgType structTypeFor(TypeDescriptor type, List<DecodedField> binaryFields) {
    // The SPI type reaching this codec is the driver's own PgType; PgStruct needs the concrete
    // type so it can carry the fields synthesized below for the anonymous RECORD pseudo-type.
    PgType pgType = (PgType) type;
    if (pgType.getOid() != Oid.RECORD) {
      return pgType;
    }
    List<PgField> synthesized = new ArrayList<>(binaryFields.size());
    for (int i = 0; i < binaryFields.size(); i++) {
      // Anonymous record fields have no catalog names; "fN" is positional only.
      synthesized.add(new PgField("f" + (i + 1), binaryFields.get(i).getTypeOid(), i + 1, -1));
    }
    return pgType.withFields(synthesized);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof SQLData) {
      CodecDepth.enter();
      try {
        // The SPI type reaching this codec is the driver's own PgType; the SQLData output
        // adapter is internal machinery keyed on the concrete composite type.
        PgSQLOutputBinary output = new PgSQLOutputBinary((PgType) type, impl(ctx));
        ((SQLData) value).writeSQL(output);
        return output.toBytes();
      } finally {
        CodecDepth.exit();
      }
    }
    if (value instanceof Struct) {
      // Delegate per-field encoding to the registered BinaryCodec for each
      // attribute's OID, mirroring the Struct branch of encodeText.
      Struct struct = (Struct) value;
      return encodeAttributesAsBinary(struct.getAttributes(), type, ctx);
    }
    throw new PSQLException(
        GT.tr("Cannot encode {0} as composite binary. Use SQLData implementation.", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) {
    // encodeBinary serializes a Struct/SQLData attribute-by-attribute; a plain PGobject carries
    // only the composite text literal and must bind as text.
    return value instanceof Struct || value instanceof SQLData;
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null) {
      return null;
    }
    // PgStruct extends PGobject and implements Struct, so the same return value
    // satisfies both the legacy "(PGobject) rs.getObject(i)" contract and the
    // new "(Struct) rs.getObject(i)" contract.
    PgStruct struct = decodeTextAsStruct(LiteralCursor.over(data), type, ctx);
    // The top-level value already exists as a String, so record it on the PGobject
    // view verbatim — callers that fall back to getValue() see the exact server text.
    struct.setValue(data);
    return struct;
  }

  @Override
  public @Nullable Object decodeText(char[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // Slice form: a composite nested in an array/composite is decoded directly off
    // the parent's borrowed char[] — no per-element String and no toCharArray
    // round-trip. The PGobject view's raw value is left unset; PgStruct.getValue()
    // reconstructs it lazily from the attributes if needed (the same path that
    // getObject(col, Struct.class) already relies on).
    return decodeTextAsStruct(new LiteralCursor(data, offset, length), type, ctx);
  }

  /**
   * Decodes composite text into a PgStruct with per-attribute decoding routed
   * through the text codec registered for each field's OID.
   */
  private static PgStruct decodeTextAsStruct(LiteralCursor cur, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      final List<? extends org.postgresql.api.codec.PgField> fieldList = resolveFields(type, ctx);
      final int expected = fieldList.size();
      final @Nullable Object[] attributes = new @Nullable Object[expected];
      final int[] seen = {0};
      // Decode each field from its borrowed slice in place: no per-field String,
      // and nested composites/arrays recurse through the child codec's own cursor.
      readCompositeFields(cur, (index, isNull, buf, offset, length) -> {
        if (index >= expected) {
          // A named composite (expected > 0) must match its literal exactly: surface a
          // catalog/literal field-count skew (e.g. after ALTER TYPE ADD ATTRIBUTE) instead of
          // silently dropping the surplus fields and corrupting the value. The anonymous record
          // pseudo-type has no catalog attributes (expected == 0) and so cannot be validated;
          // its fields are tolerated as before, and PgStruct.getValue() keeps the raw literal.
          if (expected == 0) {
            return;
          }
          throw new PSQLException(
              GT.tr("Composite type {0} has {1} attribute(s), but its text literal has more",
                  type.getTypeName(), expected),
              PSQLState.DATA_ERROR);
        }
        seen[0] = index + 1;
        if (isNull) {
          attributes[index] = null;
          return;
        }
        org.postgresql.api.codec.PgField field = fieldList.get(index);
        int fieldOid = field.getTypeOid();
        TypeDescriptor fieldType = ctx.resolveType(fieldOid);
        TextCodec fieldCodec = ctx.resolveTextCodec(fieldOid);
        if (fieldCodec == null) {
          attributes[index] = new String(buf, offset, length);
        } else {
          attributes[index] = fieldCodec.decodeText(buf, offset, length, fieldType, ctx);
        }
      });
      if (expected > 0 && seen[0] != expected) {
        // Fewer literal fields than the type declares is the same catalog/literal skew as the
        // surplus case above; reject it rather than NULL-filling the missing trailing fields.
        throw new PSQLException(
            GT.tr("Composite type {0} has {1} attribute(s), but its text literal has {2}",
                type.getTypeName(), expected, seen[0]),
            PSQLState.DATA_ERROR);
      }
      // Text transfer carries no per-field OIDs, so an anonymous record keeps the
      // fieldless pseudo-type; getValue() still works because the raw server
      // literal is recorded verbatim by the caller. The SPI type reaching this codec is
      // the driver's own PgType, which PgStruct (internal) carries. The struct also carries the
      // codec context so getValue() can rebuild the literal offline.
      return PgStruct.withCodecContext((PgType) type, attributes, impl(ctx));
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof SQLData) {
      CodecDepth.enter();
      try {
        // See encodeBinary: the SQLData output adapter needs the driver's concrete PgType.
        PgSQLOutputText output = new PgSQLOutputText((PgType) type, impl(ctx));
        ((SQLData) value).writeSQL(output);
        return output.toCompositeString();
      } finally {
        CodecDepth.exit();
      }
    }
    if (value instanceof Struct) {
      // Check Struct before PGobject: PgStruct extends PGobject AND implements
      // Struct, and the PGobject view's value field is intentionally null —
      // taking the PGobject branch would bind an empty string and the server
      // would reject it as a malformed record literal.
      // Delegate per-field encoding to the registered TextCodec for each
      // attribute's OID, instead of relying on Object.toString() which is
      // wrong for many types (e.g., Timestamp, byte[], Boolean).
      Struct struct = (Struct) value;
      return encodeAttributesAsText(struct.getAttributes(), type, ctx);
    }
    if (value instanceof PGobject) {
      String strValue = ((PGobject) value).getValue();
      return strValue != null ? strValue : "";
    }
    if (value instanceof String) {
      // A bare String is already the composite's text literal — the form
      // createArrayOf("composite_type", new String[]{"(1,2)"}) produces. Emit it
      // verbatim, matching PGobject.getValue() and the legacy array encoder.
      return (String) value;
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to composite", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  /**
   * Streaming variant: writes the composite text representation directly into
   * {@code out}. For the {@link Struct} path this avoids the intermediate
   * String produced by {@link #encodeText(Object, TypeDescriptor, CodecContext)} —
   * worthwhile when the composite is itself an element of an array, because
   * the array codec then wraps {@code out} in an
   * {@link EscapingAppendable} and array-level escaping happens char-by-char
   * during the write rather than as a second pass over a buffered String.
   *
   * <p>{@link SQLData} path still falls through to the non-streaming form —
   * the user-provided {@code writeSQL} is fundamentally batch-oriented via
   * {@link PgSQLOutputText}.</p>
   */
  @Override
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    if (value instanceof Struct && !(value instanceof SQLData)) {
      streamAttributesAsText(((Struct) value).getAttributes(), type, ctx, out);
      return;
    }
    if (value instanceof PGobject) {
      String strValue = ((PGobject) value).getValue();
      if (strValue != null) {
        out.append(strValue);
      }
      return;
    }
    // SQLData and anything else: defer to the non-streaming path.
    out.append(encodeText(value, type, ctx));
  }

  /** Streaming counterpart of {@link #encodeAttributesAsText}. */
  private static void streamAttributesAsText(
      @Nullable Object[] attributes,
      TypeDescriptor compositeType,
      CodecContext ctx,
      Appendable out) throws SQLException, IOException {
    List<? extends org.postgresql.api.codec.PgField> fields = resolveFields(compositeType, ctx);
    if (fields.size() != attributes.length) {
      throw new PSQLException(
          GT.tr("Composite type {0} expects {1} attribute(s), but {2} were provided",
              compositeType.getTypeName(), fields.size(), attributes.length),
          PSQLState.DATA_ERROR);
    }
    out.append('(');
    for (int i = 0; i < attributes.length; i++) {
      if (i > 0) {
        out.append(',');
      }
      Object attr = attributes[i];
      if (attr == null) {
        continue;
      }
      org.postgresql.api.codec.PgField field = fields.get(i);
      int fieldOid = field.getTypeOid();
      TypeDescriptor fieldType = ctx.resolveType(fieldOid);
      // A nested record field carries the anonymous-record pseudo-type OID (2249) on the wire, which
      // resolves to a fieldless descriptor. Re-encoding the decoded struct against it would fail the
      // attribute-count check (0 declared fields against the struct's real arity), and
      // PgStruct.getValue() swallows that error into a null literal. The decoded PgStruct already
      // carries the fields synthesized from the wire and rebuilds itself recursively, so emit its own
      // record_out literal, quoted per the composite text format.
      if (fieldType.getOid() == Oid.RECORD && attr instanceof PgStruct) {
        String nested = ((PgStruct) attr).getValue();
        if (nested != null) {
          appendQuotedField(out, nested);
          continue;
        }
      }
      TextCodec codec = ctx.resolveTextCodec(fieldOid);
      if (codec == null) {
        throw new PSQLException(
            GT.tr("No text codec registered for type OID {0} (field {1} of {2})",
                fieldOid, field.getName(), compositeType.getTypeName()),
            PSQLState.SYSTEM_ERROR);
      }
      if (codec instanceof StreamingTextCodec) {
        StreamingTextCodec streamingTextCodec = (StreamingTextCodec) codec;
        if (!codec.mayRequireQuoting()) {
          streamingTextCodec.encodeText(attr, fieldType, ctx, out);
        } else {
          out.append('"');
          streamingTextCodec.encodeText(attr, fieldType, ctx, new EscapingAppendable(out, true));
          out.append('"');
        }
      } else {
        String value = codec.encodeText(attr, fieldType, ctx);
        appendQuotedField(out, value);
      }
    }
    out.append(')');
  }

  /** Streaming variant of {@link #encodeBinary(Object, TypeDescriptor, CodecContext)}. */
  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    if (value instanceof Struct && !(value instanceof SQLData)) {
      // Struct fast path: stream each attribute straight into the sink, back-patching
      // per-field length prefixes, so no per-field byte[] is materialized.
      streamAttributesAsBinary(((Struct) value).getAttributes(), type, ctx, out);
      return;
    }
    // Fallback for values that do not take the Struct fast path (chiefly SQLData, which
    // serializes through its own PgSQLOutputBinary adapter): materialize the whole composite
    // into a byte[] and copy it into the sink. This is a length-correct write, not true
    // streaming — the intermediate byte[] is unavoidable via that path. Plain PGobject values
    // never reach here: canEncodeBinary() gates them out and they bind as text.
    out.write(encodeBinary(value, type, ctx));
  }

  /** Streaming counterpart of {@link #encodeAttributesAsBinary}. */
  private static void streamAttributesAsBinary(
      @Nullable Object[] attributes,
      TypeDescriptor compositeType,
      CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    List<? extends org.postgresql.api.codec.PgField> fields = resolveFields(compositeType, ctx);
    if (fields.size() != attributes.length) {
      throw new PSQLException(
          GT.tr("Composite type {0} expects {1} attribute(s), but {2} were provided",
              compositeType.getTypeName(), fields.size(), attributes.length),
          PSQLState.DATA_ERROR);
    }
    byte[] buf = new byte[4];
    // field count
    ByteConverter.int4(buf, 0, fields.size());
    out.write(buf);
    for (int i = 0; i < attributes.length; i++) {
      org.postgresql.api.codec.PgField field = fields.get(i);
      int fieldOid = field.getTypeOid();
      // type oid
      ByteConverter.int4(buf, 0, fieldOid);
      out.write(buf);
      Object attr = attributes[i];
      if (attr == null) {
        ByteConverter.int4(buf, 0, -1);
        out.write(buf);
        continue;
      }
      TypeDescriptor fieldType = binaryFieldType(fieldOid, attr, ctx);
      BinaryCodec codec = ctx.resolveBinaryCodec(fieldOid);
      if (codec == null) {
        throw new PSQLException(
            GT.tr("No binary codec registered for type OID {0} (field {1} of {2})",
                fieldOid, field.getName(), compositeType.getTypeName()),
            PSQLState.SYSTEM_ERROR);
      }
      if (codec instanceof StreamingBinaryCodec) {
        int lengthSlot = out.reserveInt32();
        int startPos = out.position();
        ((StreamingBinaryCodec) codec).encodeBinary(attr, fieldType, ctx, out);
        out.setInt32At(lengthSlot, out.position() - startPos);
      } else {
        byte[] bytes = codec.encodeBinary(attr, fieldType, ctx);
        ByteConverter.int4(buf, 0, bytes.length);
        out.write(buf);
        out.write(bytes);
      }
    }
  }

  /**
   * Returns the descriptor to encode a composite field's value against.
   *
   * <p>The composite wire carries only each field's type OID, so a nested anonymous record reports
   * the {@code record} pseudo-type OID (2249), which resolves to a fieldless descriptor — encoding
   * a struct against it would fail the attribute-count check. A {@link PgStruct} decoded from the
   * wire already carries the fields synthesized from its own self-description, so prefer that type
   * for a record-typed field. This is the binary counterpart of the {@code record_out} fallback in
   * {@link #streamAttributesAsText}.</p>
   */
  private static TypeDescriptor binaryFieldType(int fieldOid, Object attr, CodecContext ctx)
      throws SQLException {
    if (fieldOid == Oid.RECORD && attr instanceof PgStruct) {
      PgType carried = ((PgStruct) attr).getResolvedType();
      if (carried != null && carried.getFields() != null) {
        return carried;
      }
    }
    return ctx.resolveType(fieldOid);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }

    // Handle SQLData implementations
    if (SQLData.class.isAssignableFrom(targetClass)) {
      CodecDepth.enter();
      try {
        Class<? extends SQLData> sqlDataClass = (Class<? extends SQLData>) targetClass;
        SQLData sqlData = createSQLDataInstance(sqlDataClass);
        SQLInput input = new PgSQLInputBinary(data, (PgType) type, impl(ctx));
        sqlData.readSQL(input, type.getFullName());
        return (T) sqlData;
      } finally {
        CodecDepth.exit();
      }
    }

    // Structured access — build a PgStruct with per-field decoded attributes.
    if (targetClass == Struct.class || targetClass == PgStruct.class) {
      return (T) decodeBinaryAsStruct(data, 0, data.length, type, ctx);
    }

    // Legacy access — return the typed PGobject wrapper produced by decodeBinary.
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) decodeBinary(data, type, ctx);
    }

    throw new PSQLException(
        GT.tr("Cannot convert composite to {0}. Use an SQLData implementation.", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (data == null || data.isEmpty()) {
      return null;
    }

    // Handle SQLData implementations
    if (SQLData.class.isAssignableFrom(targetClass)) {
      CodecDepth.enter();
      try {
        Class<? extends SQLData> sqlDataClass = (Class<? extends SQLData>) targetClass;
        SQLData sqlData = createSQLDataInstance(sqlDataClass);
        SQLInput input = new PgSQLInputText(data, (PgType) type, impl(ctx));
        sqlData.readSQL(input, type.getFullName());
        return (T) sqlData;
      } finally {
        CodecDepth.exit();
      }
    }

    // Structured access — build a PgStruct with per-field decoded attributes.
    if (targetClass == Struct.class || targetClass == PgStruct.class) {
      return (T) decodeTextAsStruct(LiteralCursor.over(data), type, ctx);
    }

    // Legacy access — return the typed PGobject wrapper produced by decodeText.
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }

    // String is allowed
    if (targetClass == String.class) {
      return (T) data;
    }

    throw new PSQLException(
        GT.tr("Cannot convert composite to {0}. Use an SQLData implementation.", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public int decodeAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert composite to int"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert composite to int"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert composite to long"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert composite to long"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert composite to double"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert composite to double"),
        PSQLState.DATA_TYPE_MISMATCH);
  }
}
