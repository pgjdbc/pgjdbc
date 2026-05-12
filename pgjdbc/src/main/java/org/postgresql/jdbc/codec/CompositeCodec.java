/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
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
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.Struct;
import java.util.ArrayList;
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
public final class CompositeCodec implements BinaryCodec, TextCodec {

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
    private final byte @Nullable [] data;

    DecodedField(int typeOid, byte @Nullable [] data) {
      this.typeOid = typeOid;
      this.data = data;
    }

    /**
     * Returns the OID of the field's type.
     */
    public int getTypeOid() {
      return typeOid;
    }

    /**
     * Returns the raw binary data for this field, or null if the field is NULL.
     */
    public byte @Nullable [] getData() {
      return data;
    }

    /**
     * Returns true if this field is NULL.
     */
    public boolean isNull() {
      return data == null;
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
    if (data.length < 4) {
      throw new PSQLException(
          GT.tr("Invalid binary composite data: too short"),
          PSQLState.DATA_ERROR);
    }

    ByteBuffer buffer = ByteBuffer.wrap(data);
    buffer.order(ByteOrder.BIG_ENDIAN);

    int fieldCount = buffer.getInt();
    if (fieldCount < 0) {
      throw new PSQLException(
          GT.tr("Invalid binary composite data: negative field count {0}", fieldCount),
          PSQLState.DATA_ERROR);
    }

    List<DecodedField> fields = new ArrayList<>(fieldCount);

    for (int i = 0; i < fieldCount; i++) {
      if (buffer.remaining() < 8) {
        throw new PSQLException(
            GT.tr("Invalid binary composite data: unexpected end at field {0}", i),
            PSQLState.DATA_ERROR);
      }

      int typeOid = buffer.getInt();
      int length = buffer.getInt();

      byte[] fieldData;
      if (length == -1) {
        fieldData = null;
      } else if (length < 0) {
        throw new PSQLException(
            GT.tr("Invalid binary composite data: invalid length {0} at field {1}", length, i),
            PSQLState.DATA_ERROR);
      } else if (length == 0) {
        fieldData = new byte[0];
      } else {
        if (buffer.remaining() < length) {
          throw new PSQLException(
              GT.tr("Invalid binary composite data: not enough data for field {0}", i),
              PSQLState.DATA_ERROR);
        }
        fieldData = new byte[length];
        buffer.get(fieldData);
      }

      fields.add(new DecodedField(typeOid, fieldData));
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
    ByteBuffer buffer = ByteBuffer.wrap(result);
    buffer.order(ByteOrder.BIG_ENDIAN);

    buffer.putInt(fieldOids.length);

    for (int i = 0; i < fieldOids.length; i++) {
      buffer.putInt(fieldOids[i]);
      byte[] data = fieldData[i];
      if (data == null) {
        buffer.putInt(-1);
      } else {
        buffer.putInt(data.length);
        buffer.put(data);
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
    List<PgField> fields = compositeType.getFields();
    if (fields == null) {
      // Lazily load fields from the type cache.
      fields = ctx.getTypeInfo().getFields(compositeType.getOid());
    }
    if (fields.size() != attributes.length) {
      throw new PSQLException(
          GT.tr("Composite type {0} expects {1} attribute(s), but {2} were provided",
              compositeType.getTypeName(), fields.size(), attributes.length),
          PSQLState.DATA_ERROR);
    }

    CodecRegistry codecs = ctx.getCodecs();
    StringBuilder sb = new StringBuilder();
    sb.append('(');
    for (int i = 0; i < attributes.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      Object attr = attributes[i];
      if (attr == null) {
        // SQL NULL inside a composite is rendered as an empty slot.
        continue;
      }
      PgField field = fields.get(i);
      int fieldOid = field.getTypeOid();
      PgType fieldType = ctx.getTypeInfo().getPgTypeByOid(fieldOid);
      TextCodec codec = codecs.getTextCodec(fieldOid, fieldType);
      if (codec == null) {
        // CodecRegistry guarantees FallbackCodec for unknown OIDs, so this is unreachable.
        throw new PSQLException(
            GT.tr("No text codec registered for type OID {0} (field {1} of {2})",
                fieldOid, field.getName(), compositeType.getTypeName()),
            PSQLState.SYSTEM_ERROR);
      }
      String value = codec.encodeText(attr, fieldType, ctx);
      appendQuotedField(sb, value);
    }
    sb.append(')');
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
    byte[] @Nullable [] datas = new byte[fields.size()][];
    for (int i = 0; i < fields.size(); i++) {
      Object attr = attributes[i];
      if (attr == null) {
        datas[i] = null;
        continue;
      }
      PgField field = fields.get(i);
      int fieldOid = field.getTypeOid();
      PgType fieldType = ctx.getTypeInfo().getPgTypeByOid(fieldOid);
      BinaryCodec codec = codecs.getBinaryCodec(fieldOid, fieldType);
      if (codec == null) {
        // CodecRegistry guarantees FallbackCodec for unknown OIDs, so this is unreachable.
        throw new PSQLException(
            GT.tr("No binary codec registered for type OID {0} (field {1} of {2})",
                fieldOid, field.getName(), compositeType.getTypeName()),
            PSQLState.SYSTEM_ERROR);
      }
      datas[i] = codec.encodeBinary(attr, fieldType, ctx);
    }
    return encodeBinaryFields(fields, datas);
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

  /**
   * Parses a PostgreSQL composite text value, e.g. {@code (val1,"val,2",)}, into the
   * raw per-field strings. A null element represents a SQL NULL attribute. The
   * surrounding parentheses are optional.
   *
   * @param text the composite text value
   * @return per-field strings (with composite escape sequences resolved)
   */
  public static @Nullable String[] parseCompositeText(String text) {
    List<@Nullable String> values = new ArrayList<>();

    if (text.startsWith("(") && text.endsWith(")")) {
      text = text.substring(1, text.length() - 1);
    }

    int i = 0;
    int len = text.length();

    while (i <= len) {
      if (i == len) {
        // Handle trailing empty value
        if (!values.isEmpty() || len == 0) {
          values.add(null);
        }
        break;
      }

      char c = text.charAt(i);
      if (c == '"') {
        // Quoted value
        StringBuilder sb = new StringBuilder();
        i++; // skip opening quote
        while (i < len) {
          char ch = text.charAt(i);
          if (ch == '"') {
            if (i + 1 < len && text.charAt(i + 1) == '"') {
              sb.append('"');
              i += 2;
            } else {
              i++; // skip closing quote
              break;
            }
          } else if (ch == '\\' && i + 1 < len) {
            sb.append(text.charAt(i + 1));
            i += 2;
          } else {
            sb.append(ch);
            i++;
          }
        }
        values.add(sb.toString());
        if (i < len && text.charAt(i) == ',') {
          i++;
        }
      } else if (c == ',') {
        values.add(null);
        i++;
      } else {
        // Unquoted value
        int start = i;
        while (i < len && text.charAt(i) != ',') {
          i++;
        }
        values.add(text.substring(start, i));
        if (i < len && text.charAt(i) == ',') {
          i++;
        }
      }
    }

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
    return decodeBinaryAsStruct(data, type, ctx);
  }

  /**
   * Decodes binary composite data into a PgStruct with per-attribute decoding
   * routed through the codec registered for each field's OID.
   */
  private PgStruct decodeBinaryAsStruct(byte[] data, PgType type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      List<DecodedField> binaryFields = decodeBinaryFields(data);
      @Nullable Object[] attributes = new @Nullable Object[binaryFields.size()];
      for (int i = 0; i < binaryFields.size(); i++) {
        DecodedField field = binaryFields.get(i);
        byte @Nullable [] fieldData = field.getData();
        if (fieldData == null) {
          attributes[i] = null;
          continue;
        }
        int fieldOid = field.getTypeOid();
        PgType fieldType = ctx.getTypeInfo().getPgTypeByOid(fieldOid);
        BinaryCodec fieldCodec = ctx.getCodecs().getBinaryCodec(fieldOid, fieldType);
        if (fieldCodec == null) {
          attributes[i] = fieldData.clone();
        } else {
          attributes[i] = fieldCodec.decodeBinary(fieldData, fieldType, ctx);
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
    PgStruct struct = decodeTextAsStruct(data, type, ctx);
    // Make the PGobject view carry the raw composite text so callers that fall
    // back to getValue() keep working.
    struct.setValue(data);
    return struct;
  }

  /**
   * Decodes composite text into a PgStruct with per-attribute decoding routed
   * through the text codec registered for each field's OID.
   */
  private PgStruct decodeTextAsStruct(String data, PgType type, CodecContext ctx)
      throws SQLException {
    CodecDepth.enter();
    try {
      @Nullable String[] rawFields = parseCompositeText(data);
      if (rawFields == null) {
        return new PgStruct(type.getFullName(), new Object[0], ctx.getConnection());
      }
      List<PgField> fields = type.getFields();
      if (fields == null) {
        fields = ctx.getTypeInfo().getFields(type.getOid());
      }
      int expected = fields.size();
      int actual = Math.min(rawFields.length, expected);
      @Nullable Object[] attributes = new @Nullable Object[expected];
      for (int i = 0; i < expected; i++) {
        String raw = i < actual ? rawFields[i] : null;
        if (raw == null) {
          attributes[i] = null;
          continue;
        }
        PgField field = fields.get(i);
        int fieldOid = field.getTypeOid();
        PgType fieldType = ctx.getTypeInfo().getPgTypeByOid(fieldOid);
        TextCodec fieldCodec = ctx.getCodecs().getTextCodec(fieldOid, fieldType);
        if (fieldCodec == null) {
          attributes[i] = raw;
        } else {
          attributes[i] = fieldCodec.decodeText(raw, fieldType, ctx);
        }
      }
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
      return (T) decodeBinaryAsStruct(data, type, ctx);
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
      return (T) decodeTextAsStruct(data, type, ctx);
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
