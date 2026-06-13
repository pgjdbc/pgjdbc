/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.jdbc.CodecRegistry;
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
import java.io.OutputStream;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Arrays;
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
    @Nullable Object decode(BinaryCodec codec, PgType type, CodecContext ctx) throws SQLException {
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
      PgType compositeType,
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
      PgType compositeType,
      CodecContext ctx) throws SQLException {
    BackpatchByteArrayOutputStream out = new BackpatchByteArrayOutputStream();
    try {
      streamAttributesAsBinary(attributes, compositeType, ctx, out);
    } catch (IOException e) {
      throw new AssertionError(e); // BackpatchByteArrayOutputStream never throws
    }
    return out.toByteArray();
  }

  /**
   * Encodes pre-stringified attributes as a PostgreSQL composite text value.
   *
   * <p>Each entry is treated as the final on-wire string for its field — the
   * caller is responsible for producing a server-compatible representation.
   * Prefer {@link #encodeAttributesAsText(Object[], PgType, CodecContext)},
   * which delegates to the per-field {@link TextCodec}; this overload exists
   * for callers that have already serialized values (e.g., raw escape tests).</p>
   *
   * @param attributes the attribute values; non-null entries are quoted via {@link Object#toString()}
   * @return the text representation in PostgreSQL composite format: (val1,val2,...)
   */
  public static String encodeAttributesAsText(@Nullable Object[] attributes) {
    StringBuilder sb = new StringBuilder();
    sb.append('(');
    for (int i = 0; i < attributes.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      Object attr = attributes[i];
      if (attr != null) {
        appendQuotedField(sb, attr.toString());
      }
    }
    sb.append(')');
    return sb.toString();
  }

  private static void appendQuotedField(StringBuilder sb, String value) {
    if (needsQuoting(value)) {
      sb.append('"');
      sb.append(value.replace("\\", "\\\\").replace("\"", "\\\""));
      sb.append('"');
    } else {
      sb.append(value);
    }
  }

  /** {@link Appendable} overload used by the streaming text path. */
  private static void appendQuotedField(Appendable out, String value) throws IOException {
    if (needsQuoting(value)) {
      out.append('"');
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (c == '"' || c == '\\') {
          out.append('\\');
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
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    // PgStruct extends PGobject and implements Struct, so the same return value
    // satisfies both the legacy "(PGobject) rs.getObject(i)" contract and the
    // new "(Struct) rs.getObject(i)" contract.
    return decodeBinaryAsStruct(data, 0, data.length, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, PgType type,
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
  private PgStruct decodeBinaryAsStruct(byte[] data, int offset, int length, PgType type,
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
        PgType fieldType = ctx.getTypeInfo().getPgTypeByOid(fieldOid);
        BinaryCodec fieldCodec = ctx.getCodecs().getBinaryCodec(fieldOid, fieldType);
        if (fieldCodec == null) {
          attributes[i] = field.getData();
        } else {
          attributes[i] = field.decode(fieldCodec, fieldType, ctx);
        }
      }
      return new PgStruct(type.getFullName(), attributes, ctx.getConnection());
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof SQLData) {
      CodecDepth.enter();
      try {
        PgSQLOutputBinary output = new PgSQLOutputBinary(type, ctx);
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
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
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
  public @Nullable Object decodeText(char[] data, int offset, int length, PgType type,
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
  private PgStruct decodeTextAsStruct(LiteralCursor cur, PgType type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      List<PgField> fields = type.getFields();
      if (fields == null) {
        fields = ctx.getTypeInfo().getFields(type.getOid());
      }
      final List<PgField> fieldList = fields;
      final int expected = fieldList.size();
      final @Nullable Object[] attributes = new @Nullable Object[expected];
      // Decode each field from its borrowed slice in place: no per-field String,
      // and nested composites/arrays recurse through the child codec's own cursor.
      readCompositeFields(cur, (index, isNull, buf, offset, length) -> {
        if (index >= expected) {
          return; // tolerate a literal with more fields than the type declares
        }
        if (isNull) {
          attributes[index] = null;
          return;
        }
        PgField field = fieldList.get(index);
        int fieldOid = field.getTypeOid();
        PgType fieldType = ctx.getTypeInfo().getPgTypeByOid(fieldOid);
        TextCodec fieldCodec = ctx.getCodecs().getTextCodec(fieldOid, fieldType);
        if (fieldCodec == null) {
          attributes[index] = new String(buf, offset, length);
        } else {
          attributes[index] = fieldCodec.decodeText(buf, offset, length, fieldType, ctx);
        }
      });
      return new PgStruct(type.getFullName(), attributes, ctx.getConnection());
    } finally {
      CodecDepth.exit();
    }
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof SQLData) {
      CodecDepth.enter();
      try {
        PgSQLOutputText output = new PgSQLOutputText(type, ctx);
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
    throw new PSQLException(
        GT.tr("Cannot convert {0} to composite", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  /**
   * Streaming variant: writes the composite text representation directly into
   * {@code out}. For the {@link Struct} path this avoids the intermediate
   * String produced by {@link #encodeText(Object, PgType, CodecContext)} —
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
  public void encodeText(Object value, PgType type, CodecContext ctx, Appendable out)
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
      PgType compositeType,
      CodecContext ctx,
      Appendable out) throws SQLException, IOException {
    List<PgField> fields = compositeType.getFields();
    if (fields == null) {
      fields = ctx.getTypeInfo().getFields(compositeType.getOid());
    }
    if (fields.size() != attributes.length) {
      throw new PSQLException(
          GT.tr("Composite type {0} expects {1} attribute(s), but {2} were provided",
              compositeType.getTypeName(), fields.size(), attributes.length),
          PSQLState.DATA_ERROR);
    }
    CodecRegistry codecs = ctx.getCodecs();
    out.append('(');
    for (int i = 0; i < attributes.length; i++) {
      if (i > 0) {
        out.append(',');
      }
      Object attr = attributes[i];
      if (attr == null) {
        continue;
      }
      PgField field = fields.get(i);
      int fieldOid = field.getTypeOid();
      PgType fieldType = ctx.getTypeInfo().getPgTypeByOid(fieldOid);
      TextCodec codec = codecs.getTextCodec(fieldOid, fieldType);
      if (codec == null) {
        throw new PSQLException(
            GT.tr("No text codec registered for type OID {0} (field {1} of {2})",
                fieldOid, field.getName(), compositeType.getTypeName()),
            PSQLState.SYSTEM_ERROR);
      }
      if (codec instanceof StreamingTextCodec) {
        // Stream the field straight into out with composite-level escaping
        // (always quote — we don't have a way to look ahead and decide).
        out.append('"');
        EscapingAppendable esc = new EscapingAppendable(out);
        ((StreamingTextCodec) codec).encodeText(attr, fieldType, ctx, esc);
        out.append('"');
      } else {
        String value = codec.encodeText(attr, fieldType, ctx);
        appendQuotedField(out, value);
      }
    }
    out.append(')');
  }

  /** Streaming variant of {@link #encodeBinary(Object, PgType, CodecContext)}. */
  @Override
  public void encodeBinary(Object value, PgType type, CodecContext ctx, OutputStream out)
      throws SQLException, IOException {
    if (value instanceof Struct && !(value instanceof SQLData)
        && out instanceof BackpatchByteArrayOutputStream) {
      streamAttributesAsBinary(((Struct) value).getAttributes(), type, ctx,
          (BackpatchByteArrayOutputStream) out);
      return;
    }
    // Fallback: defer to the non-streaming path that materializes a byte[].
    out.write(encodeBinary(value, type, ctx));
  }

  /** Streaming counterpart of {@link #encodeAttributesAsBinary}. */
  private static void streamAttributesAsBinary(
      @Nullable Object[] attributes,
      PgType compositeType,
      CodecContext ctx,
      BackpatchByteArrayOutputStream out) throws SQLException, IOException {
    List<PgField> fields = compositeType.getFields();
    if (fields == null) {
      fields = ctx.getTypeInfo().getFields(compositeType.getOid());
    }
    if (fields.size() != attributes.length) {
      throw new PSQLException(
          GT.tr("Composite type {0} expects {1} attribute(s), but {2} were provided",
              compositeType.getTypeName(), fields.size(), attributes.length),
          PSQLState.DATA_ERROR);
    }
    CodecRegistry codecs = ctx.getCodecs();
    byte[] buf = new byte[4];
    // field count
    ByteConverter.int4(buf, 0, fields.size());
    out.write(buf);
    for (int i = 0; i < attributes.length; i++) {
      PgField field = fields.get(i);
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
      PgType fieldType = ctx.getTypeInfo().getPgTypeByOid(fieldOid);
      BinaryCodec codec = codecs.getBinaryCodec(fieldOid, fieldType);
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

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
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
        SQLInput input = new PgSQLInputBinary(data, type, ctx);
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
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
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
        SQLInput input = new PgSQLInputText(data, type, ctx);
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
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert composite to int"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert composite to int"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert composite to long"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert composite to long"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert composite to double"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert composite to double"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
