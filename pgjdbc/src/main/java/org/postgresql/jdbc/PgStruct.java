/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc.codec.CompositeCodec;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.Arrays;
import java.util.Map;

/**
 * Implementation of {@link Struct} for PostgreSQL composite types.
 *
 * <p>This class represents a PostgreSQL composite type (struct) value.
 * It can be created via {@link java.sql.Connection#createStruct(String, Object[])}
 * or returned from ResultSet when reading composite type columns.</p>
 */
public class PgStruct extends org.postgresql.util.PGobject implements Struct {

  private final String typeName;
  private final @Nullable PgType pgType;
  private final @Nullable Object[] attributes;
  // Decode and createStruct carry the codec context (the canonical dependency): it resolves the
  // per-field text codecs that rebuild the value literal in getValue(), and it works offline. The
  // legacy connection below is only a fallback for the public BaseConnection constructors, which
  // cannot derive a context themselves because getCodecContext() throws SQLException.
  private final @Nullable PgCodecContext codecContext;
  private final @Nullable BaseConnection connection;

  /**
   * Creates a new PgStruct with the given type name and attributes.
   *
   * @param typeName the SQL type name of the struct
   * @param attributes the attribute values
   */
  public PgStruct(String typeName, @Nullable Object[] attributes) {
    this(typeName, null, attributes, null, null);
  }

  /**
   * Creates a new PgStruct with the given type name, attributes, and connection.
   *
   * @param typeName the SQL type name of the struct
   * @param attributes the attribute values
   * @param connection the connection (used for type mapping)
   */
  public PgStruct(String typeName, @Nullable Object[] attributes, @Nullable BaseConnection connection) {
    this(typeName, null, attributes, connection, null);
  }

  /**
   * Creates a new PgStruct that carries the resolved {@link PgType}.
   *
   * <p>For an anonymous record the {@code pgType} carries field types synthesized
   * from the binary wire (see
   * {@link org.postgresql.jdbc.codec.CompositeCodec}), so {@link #getValue()} can
   * rebuild the text literal without a catalog lookup that would find no
   * attributes. For a named composite it is simply the cached type.</p>
   *
   * @param pgType the resolved type, whose fields drive text reconstruction
   * @param attributes the attribute values
   * @param connection the connection (used for type mapping)
   */
  public PgStruct(PgType pgType, @Nullable Object[] attributes, @Nullable BaseConnection connection) {
    this(pgType.getFullName(), pgType, attributes, connection, null);
  }

  /**
   * Creates a struct that carries a {@link PgCodecContext} and the resolved {@link PgType}.
   *
   * <p>This is the decode-path factory: the codec context resolves the per-field text codecs that
   * {@link #getValue()} uses to rebuild the value literal, and it works offline (no connection).
   * The {@code pgType} already holds the right fields (synthesized from the binary wire for an
   * anonymous record), so the literal can be rebuilt without a catalog lookup.</p>
   *
   * @param pgType the resolved type, whose fields drive text reconstruction
   * @param attributes the attribute values
   * @param codecContext the codec context (offline or connection-bound)
   * @return the new struct
   */
  public static PgStruct withCodecContext(PgType pgType, @Nullable Object[] attributes,
      PgCodecContext codecContext) {
    return new PgStruct(pgType.getFullName(), pgType, attributes, null, codecContext);
  }

  /**
   * Creates a struct that carries a {@link PgCodecContext} but no resolved {@link PgType}.
   *
   * <p>Intended for the connection-bound {@code createStruct} path: the type is resolved lazily by
   * name via {@code codecContext.getTypeInfo()} on the first {@link #getValue()}. An offline
   * decode must use the {@link #withCodecContext(PgType, Object[], PgCodecContext)} overload
   * instead — without a {@code pgType} and without a connection-bound context the value literal
   * cannot be reconstructed.</p>
   *
   * @param typeName the SQL type name of the struct
   * @param attributes the attribute values
   * @param codecContext the connection-bound codec context
   * @return the new struct
   */
  public static PgStruct withCodecContext(String typeName, @Nullable Object[] attributes,
      PgCodecContext codecContext) {
    return new PgStruct(typeName, null, attributes, null, codecContext);
  }

  @SuppressWarnings("method.invocation")
  private PgStruct(String typeName, @Nullable PgType pgType, @Nullable Object[] attributes,
      @Nullable BaseConnection connection, @Nullable PgCodecContext codecContext) {
    this.typeName = typeName;
    this.pgType = pgType;
    this.attributes = attributes.clone();
    this.connection = connection;
    this.codecContext = codecContext;
    // Also satisfy the PGobject contract — callers that expect getObject(int)
    // to return a typed PGobject (the legacy contract) get a non-null type
    // string back, and value is left null since the composite text
    // representation isn't materialized here. PGobject.setType is final and
    // only assigns a field, so the partially-initialized 'this' is fine
    // (matches the PGmoney constructor pattern).
    setType(typeName);
  }

  /**
   * Returns the codec context to use for lazy text reconstruction and type-map normalization.
   *
   * <p>Prefers the carried {@link #codecContext} (decode and {@code createStruct} paths); falls
   * back to deriving one from the legacy {@link #connection} for structs built through the public
   * {@code BaseConnection} constructors. Returns {@code null} when neither is available (an offline
   * struct built through a plain {@code (typeName, attributes)} constructor).</p>
   */
  private @Nullable PgCodecContext codecContext() throws SQLException {
    if (codecContext != null) {
      return codecContext;
    }
    return connection != null ? connection.getCodecContext() : null;
  }

  @Override
  public String getSQLTypeName() throws SQLException {
    return typeName;
  }

  @Override
  @SuppressWarnings("override.return")
  public @Nullable Object[] getAttributes() throws SQLException {
    return attributes.clone();
  }

  @Override
  @SuppressWarnings({"unchecked", "override.return"})
  public @Nullable Object[] getAttributes(@Nullable Map<String, Class<?>> map) throws SQLException {
    if (map == null || map.isEmpty()) {
      return getAttributes();
    }
    PgCodecContext ctx = codecContext();
    if (ctx != null && ctx.isConnectionBound()) {
      map = IdentifierNormalizingTypeMap.of(map, ctx.getTypeInfo());
    }

    // Apply type mapping to nested types in the attributes
    // Note: The map entry for this struct's own type (if present) is ignored here
    // because getAttributes() returns the attribute values, not a converted struct.
    @Nullable Object[] result = new @Nullable Object[attributes.length];
    for (int i = 0; i < attributes.length; i++) {
      result[i] = convertAttribute(attributes[i], map);
    }
    return result;
  }

  @Override
  public @Nullable String getValue() {
    String value = super.getValue();
    if (value != null || attributes == null) {
      return value;
    }
    try {
      PgCodecContext ctx = codecContext();
      if (ctx == null) {
        // An offline struct built without a context (the plain (typeName, attributes)
        // constructor) has nothing to rebuild the literal with.
        return value;
      }
      // The carried type already holds the right fields (synthesized from the
      // binary wire for an anonymous record); fall back to a catalog lookup by
      // name only for structs created without one (e.g. createStruct), which is
      // connection-bound.
      PgType type = pgType != null
          ? pgType
          : (ctx.isConnectionBound() ? ctx.getTypeInfo().getPgTypeByPgName(typeName) : null);
      if (type == null) {
        return value;
      }
      String text = CompositeCodec.encodeAttributesAsText(attributes, type, ctx);
      super.setValue(text);
      return text;
    } catch (SQLException e) {
      return value;
    }
  }

  /**
   * Converts an attribute value according to the type map.
   */
  @SuppressWarnings("unchecked")
  private @Nullable Object convertAttribute(@Nullable Object attr, Map<String, Class<?>> map) throws SQLException {
    if (attr == null) {
      return null;
    }

    if (attr instanceof Struct) {
      Struct nestedStruct = (Struct) attr;
      String nestedTypeName = nestedStruct.getSQLTypeName();
      Class<?> nestedClass = map.get(nestedTypeName);
      PgCodecContext ctx = codecContext();

      if (nestedClass != null && SQLData.class.isAssignableFrom(nestedClass)) {
        if (ctx == null || !ctx.isConnectionBound()) {
          throw new PSQLException(
              GT.tr("Cannot convert nested struct to SQLData without connection context"),
              PSQLState.OBJECT_NOT_IN_STATE);
        }
        // Convert nested struct to SQLData using CompositeCodec.
        PgType pgType = ctx.getTypeInfo().getPgTypeByPgName(nestedTypeName);
        Object[] nestedAttrs = nestedStruct.getAttributes();
        String textValue = CompositeCodec.encodeAttributesAsText(nestedAttrs, pgType, ctx);
        return CompositeCodec.INSTANCE.decodeTextAs(
            textValue, pgType, (Class<? extends SQLData>) nestedClass, ctx);
      }
      // If no mapping, recursively convert nested struct's attributes
      if (nestedStruct instanceof PgStruct) {
        // The nested struct will handle its own attribute conversion. Pass the context down so it
        // can rebuild its own literal; an offline struct with no context falls back to the plain
        // constructor (it has nothing to rebuild with anyway).
        Object[] nestedAttributes = nestedStruct.getAttributes(map);
        return ctx != null
            ? PgStruct.withCodecContext(nestedTypeName, nestedAttributes, ctx)
            : new PgStruct(nestedTypeName, nestedAttributes);
      }
      return attr;
    }

    if (attr instanceof java.sql.Array) {
      // Arrays may contain structs that need conversion
      java.sql.Array array = (java.sql.Array) attr;
      Object arrayObj = array.getArray(map);
      if (arrayObj instanceof Object[]) {
        Object[] elements = (Object[]) arrayObj;
        @Nullable Object[] converted = new @Nullable Object[elements.length];
        boolean anyConverted = false;
        for (int i = 0; i < elements.length; i++) {
          converted[i] = convertAttribute(elements[i], map);
          if (converted[i] != elements[i]) {
            anyConverted = true;
          }
        }
        if (anyConverted) {
          return converted;
        }
      }
      return arrayObj;
    }

    return attr;
  }

  @Override
  public String toString() {
    String value = getValue();
    return value != null ? value
        : "PgStruct{typeName='" + typeName + "', attributes=" + Arrays.toString(attributes) + "}";
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PgStruct)) {
      return false;
    }
    PgStruct pgStruct = (PgStruct) o;
    return typeName.equals(pgStruct.typeName) && Arrays.equals(attributes, pgStruct.attributes);
  }

  @Override
  public int hashCode() {
    int result = typeName.hashCode();
    result = 31 * result + Arrays.hashCode(attributes);
    return result;
  }
}
