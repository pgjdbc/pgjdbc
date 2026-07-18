/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.TypeDescriptor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;

/**
 * Base class for SQLInput implementations.
 *
 * <p>Each JDBC {@code readXxx} call advances to the next composite field and pulls that field
 * straight out of the subclass-owned source — the primitive readers ({@code readInt},
 * {@code readLong}, ...) return the value unboxed through the abstract {@code decodeXxx} hooks, while
 * the object readers funnel through {@code decodeObject}. The subclass owns the wire (a binary buffer
 * walked by a cursor, or the parsed text attributes) and reports each field's SQL NULL state through
 * {@link #advanceIsNull()}; a {@code decodeXxx} hook is called only for a non-null field. This mirrors
 * {@link PgSQLOutput}, whose {@code writeXxx} calls stream into a subclass-owned sink.</p>
 */
// SQLInput's annotated JDK marks readString()/readObject(...) return and parameter types @NonNull, but
// JDBC lets them return null (paired with wasNull()); suppress the resulting override mismatches.
@SuppressWarnings({"override.return", "override.param"})
public abstract class PgSQLInput implements SQLInput {

  protected final PgType compositeType;
  protected final PgCodecContext ctx;
  protected final List<PgField> fields;
  protected int fieldIndex = 0;
  protected boolean lastWasNull = false;

  /**
   * The resolved type of the current field (the one just entered by {@link #advanceIsNull()}).
   * Valid only right after a non-null {@link #advanceToNextIsNull()}; a null field leaves it holding
   * the previous field's type, since {@code decodeXxx} is never called for a null field.
   */
  private @Nullable TypeDescriptor currentType;

  /**
   * Creates a new PgSQLInput.
   *
   * @param type the composite type
   * @param ctx the codec context
   */
  protected PgSQLInput(PgType type, PgCodecContext ctx) throws SQLException {
    this.compositeType = type;
    this.ctx = ctx;
    List<PgField> typeFields = type.getFields();
    if (typeFields != null) {
      this.fields = typeFields;
    } else if (ctx.isConnectionBound()) {
      // Fields are loaded lazily for composite types: fall back to the type info cache.
      this.fields = ctx.getTypeInfo().getFields(type.getOid());
    } else {
      // Offline contexts have no type cache to load attributes from, so the caller must register
      // the composite type with its fields.
      throw Exceptions.offlineCompositeAccessNeedsAttributes(type.getFullName());
    }
  }

  @Override
  public boolean wasNull() throws SQLException {
    return lastWasNull;
  }

  /**
   * Advances to the next composite field: checks the arity, moves {@link #fieldIndex} on, and asks the
   * subclass whether the value is SQL NULL, recording it for {@link #wasNull()}. Called at the start of
   * every {@code readXxx}; a {@code decodeXxx} hook runs only when this returns {@code false}.
   *
   * @return true if the current field is SQL NULL
   */
  private boolean advanceToNextIsNull() throws SQLException {
    if (fieldIndex >= fields.size()) {
      throw Exceptions.attemptReadPastEndOfFields();
    }
    fieldIndex++;
    boolean isNull = advanceIsNull();
    lastWasNull = isNull;
    if (!isNull) {
      // Resolved only for a non-null field: a null field is never decoded, so there is nothing to
      // resolve it for, and resolveType() can be non-trivial (it loads composite/range/multirange
      // structure on first use). Stamp the attribute modifier (atttypmod) so a modifier-sensitive
      // field such as numeric(10,2) decodes to its declared scale, matching CompositeCodec's own
      // field walker; -1 leaves the resolved type unchanged.
      PgField field = fields.get(fieldIndex - 1);
      currentType = ctx.resolveType(field.getTypeOid(), field.getTypmod());
    }
    return isNull;
  }

  /**
   * Returns the resolved type of the current field. Called only from a {@code decodeXxx} hook, i.e.
   * only after a non-null {@link #advanceToNextIsNull()}.
   */
  protected TypeDescriptor getCurrentType() {
    return castNonNull(currentType);
  }

  // Abstract per-field hooks implemented by subclasses. advanceIsNull() moves the subclass source to
  // the field just entered (index fieldIndex - 1) and reports its NULL state; the decodeXxx hooks then
  // read that same field, so they are only ever called after advanceIsNull() returned false.

  /** Advances the subclass source to the current field and returns whether it is SQL NULL. */
  protected abstract boolean advanceIsNull() throws SQLException;

  protected abstract int decodeInt() throws SQLException;

  protected abstract long decodeLong() throws SQLException;

  protected abstract double decodeDouble() throws SQLException;

  protected abstract float decodeFloat() throws SQLException;

  protected abstract boolean decodeBoolean() throws SQLException;

  protected abstract @Nullable String decodeString() throws SQLException;

  protected abstract @Nullable BigDecimal decodeBigDecimal() throws SQLException;

  protected abstract byte @Nullable [] decodeBytes() throws SQLException;

  protected abstract @Nullable Date decodeDate() throws SQLException;

  protected abstract @Nullable Time decodeTime() throws SQLException;

  protected abstract @Nullable Timestamp decodeTimestamp() throws SQLException;

  protected abstract @Nullable Object decodeObject() throws SQLException;

  protected abstract Array decodeArray() throws SQLException;

  protected abstract <T> @Nullable T decodeObjectAs(Class<T> type) throws SQLException;

  // SQLInput implementation methods

  @Override
  public @Nullable String readString() throws SQLException {
    return advanceToNextIsNull() ? null : decodeString();
  }

  @Override
  public boolean readBoolean() throws SQLException {
    if (advanceToNextIsNull()) {
      return false;
    }
    return decodeBoolean();
  }

  @Override
  public byte readByte() throws SQLException {
    return advanceToNextIsNull() ? 0 : (byte) decodeInt();
  }

  @Override
  public short readShort() throws SQLException {
    return advanceToNextIsNull() ? 0 : (short) decodeInt();
  }

  @Override
  public int readInt() throws SQLException {
    return advanceToNextIsNull() ? 0 : decodeInt();
  }

  @Override
  public long readLong() throws SQLException {
    return advanceToNextIsNull() ? 0 : decodeLong();
  }

  @Override
  public float readFloat() throws SQLException {
    return advanceToNextIsNull() ? 0 : decodeFloat();
  }

  @Override
  public double readDouble() throws SQLException {
    return advanceToNextIsNull() ? 0 : decodeDouble();
  }

  @Override
  public @Nullable BigDecimal readBigDecimal() throws SQLException {
    return advanceToNextIsNull() ? null : decodeBigDecimal();
  }

  @Override
  public byte @Nullable [] readBytes() throws SQLException {
    return advanceToNextIsNull() ? null : decodeBytes();
  }

  @Override
  public @Nullable Date readDate() throws SQLException {
    return advanceToNextIsNull() ? null : decodeDate();
  }

  @Override
  public @Nullable Time readTime() throws SQLException {
    return advanceToNextIsNull() ? null : decodeTime();
  }

  @Override
  public @Nullable Timestamp readTimestamp() throws SQLException {
    return advanceToNextIsNull() ? null : decodeTimestamp();
  }

  @Override
  public @Nullable Reader readCharacterStream() throws SQLException {
    String s = readString();
    return s == null ? null : new StringReader(s);
  }

  @Override
  public @Nullable InputStream readAsciiStream() throws SQLException {
    String s = readString();
    return s == null ? null : new ByteArrayInputStream(s.getBytes(US_ASCII));
  }

  @Override
  public @Nullable InputStream readBinaryStream() throws SQLException {
    byte[] bytes = readBytes();
    return bytes == null ? null : new ByteArrayInputStream(bytes);
  }

  @Override
  public @Nullable Object readObject() throws SQLException {
    return advanceToNextIsNull() ? null : decodeObject();
  }

  @Override
  public Ref readRef() throws SQLException {
    throw Exceptions.notImplemented("readRef()");
  }

  @Override
  public Blob readBlob() throws SQLException {
    throw Exceptions.notImplemented("readBlob()");
  }

  @Override
  public Clob readClob() throws SQLException {
    throw Exceptions.notImplemented("readClob()");
  }

  @Override
  public @Nullable Array readArray() throws SQLException {
    return advanceToNextIsNull() ? null : decodeArray();
  }

  @Override
  public @Nullable URL readURL() throws SQLException {
    String s = readString();
    if (s == null) {
      return null;
    }
    try {
      return new URL(s);
    } catch (MalformedURLException e) {
      throw Exceptions.invalidUrl(s, e);
    }
  }

  @Override
  public NClob readNClob() throws SQLException {
    throw Exceptions.notImplemented("readNClob()");
  }

  @Override
  public @Nullable String readNString() throws SQLException {
    return readString();
  }

  @Override
  public @Nullable SQLXML readSQLXML() throws SQLException {
    String s = readString();
    // PgSQLXML is connection-bound; offline reports a clear limitation rather than dereferencing a
    // null connection.
    return s == null ? null : new PgSQLXML(ctx.requireConnection(compositeType), s);
  }

  @Override
  public RowId readRowId() throws SQLException {
    throw Exceptions.notImplemented("readRowId()");
  }

  @Override
  public <T> @Nullable T readObject(Class<T> type) throws SQLException {
    return advanceToNextIsNull() ? null : decodeObjectAs(type);
  }
}
