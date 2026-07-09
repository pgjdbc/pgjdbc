/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static java.nio.charset.StandardCharsets.US_ASCII;

import org.postgresql.api.codec.TypeDescriptor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLOutput;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;

/**
 * Base class for SQLOutput implementations.
 *
 * <p>Each JDBC {@code writeXxx} call advances to the next composite field and streams that field
 * straight into the caller-provided sink — the primitive writers ({@code writeInt},
 * {@code writeLong}, ...) pass their value through the abstract {@code writeFieldXxx} hooks without
 * boxing, while the object writers funnel through {@code writeFieldObject}. The subclass owns the
 * framing (composite header/lengths for binary, the {@code (...)} literal for text). Call
 * {@link #close()} once all attributes are written to finish the framing and check the arity; the
 * completed value then lives in the sink the caller passed to the subclass constructor. This is an
 * {@link AutoCloseable}, so the writer is meant to drive it in a try-with-resources block.</p>
 */
public abstract class PgSQLOutput implements SQLOutput, AutoCloseable {

  protected final PgType compositeType;
  protected final PgCodecContext ctx;
  protected final List<PgField> fields;
  protected int fieldIndex = 0;
  private boolean closed;

  /**
   * Creates a new PgSQLOutput.
   *
   * @param type the composite type
   * @param ctx the codec context
   */
  protected PgSQLOutput(PgType type, PgCodecContext ctx) throws SQLException {
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

  /**
   * Gets the current field and advances the index.
   *
   * @return the current field
   * @throws SQLException if no more fields
   */
  protected PgField nextField() throws SQLException {
    if (fieldIndex >= fields.size()) {
      throw Exceptions.attemptWritePastEndOfFields();
    }
    return fields.get(fieldIndex++);
  }

  /**
   * Gets the PgType for a field.
   */
  protected PgType getFieldType(PgField field) throws SQLException {
    if (ctx.isConnectionBound()) {
      return ctx.getTypeInfo().getPgTypeByOid(field.getTypeOid());
    }
    // Offline: resolve through the context without a type cache. Offline descriptors are PgType.
    TypeDescriptor resolved = ctx.resolveType(field.getTypeOid());
    return resolved instanceof PgType ? (PgType) resolved : compositeType;
  }

  // Abstract per-field hooks implemented by subclasses. The primitive hooks take the value unboxed so
  // a codec that opts into the primitive encoder capability writes it straight into the sink. All are
  // called after nextField() has advanced the index, so the subclass reads the just-consumed field.

  /** Writes a SQL NULL for the current field. */
  protected abstract void writeFieldNull() throws SQLException;

  /** Writes an {@code int} value (also the target of {@code writeByte}/{@code writeShort}). */
  protected abstract void writeFieldInt(int value) throws SQLException;

  /** Writes a {@code long} value. */
  protected abstract void writeFieldLong(long value) throws SQLException;

  /** Writes a {@code float} value. */
  protected abstract void writeFieldFloat(float value) throws SQLException;

  /** Writes a {@code double} value. */
  protected abstract void writeFieldDouble(double value) throws SQLException;

  /** Writes a {@code boolean} value. */
  protected abstract void writeFieldBoolean(boolean value) throws SQLException;

  /** Writes a non-null object value, delegating to the field codec. */
  protected abstract void writeFieldObject(Object value) throws SQLException;

  /**
   * Finishes the composite: checks that every declared attribute was written, then applies the
   * format-specific finalization ({@link #finish()}). The completed value lives in the sink the
   * subclass wrote into. Idempotent — a second call is a no-op — so it is safe to drive the writer
   * in a try-with-resources block.
   *
   * @throws SQLException if fewer (or more) than the composite's attributes were written
   */
  @Override
  public final void close() throws SQLException {
    if (closed) {
      return;
    }
    closed = true;
    if (fieldIndex != fields.size()) {
      throw Exceptions.compositeAttributeCountWrittenMismatch(
          compositeType.getFullName(), fields.size(), fieldIndex);
    }
    finish();
  }

  /**
   * Format-specific finalization after the last field, run by {@link #close()} once the arity check
   * passes. The default does nothing (binary needs no trailer); the text form appends the closing
   * parenthesis.
   */
  protected void finish() throws SQLException {
  }

  // SQLOutput implementation methods

  @Override
  public void writeString(@Nullable String x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(x);
  }

  @Override
  public void writeBoolean(boolean x) throws SQLException {
    nextField();
    writeFieldBoolean(x);
  }

  @Override
  public void writeByte(byte x) throws SQLException {
    nextField();
    writeFieldInt(x);
  }

  @Override
  public void writeShort(short x) throws SQLException {
    nextField();
    writeFieldInt(x);
  }

  @Override
  public void writeInt(int x) throws SQLException {
    nextField();
    writeFieldInt(x);
  }

  @Override
  public void writeLong(long x) throws SQLException {
    nextField();
    writeFieldLong(x);
  }

  @Override
  public void writeFloat(float x) throws SQLException {
    nextField();
    writeFieldFloat(x);
  }

  @Override
  public void writeDouble(double x) throws SQLException {
    nextField();
    writeFieldDouble(x);
  }

  @Override
  public void writeBigDecimal(@Nullable BigDecimal x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(x);
  }

  @Override
  public void writeBytes(byte @Nullable [] x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(x);
  }

  @Override
  public void writeDate(@Nullable Date x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(x);
  }

  @Override
  public void writeTime(@Nullable Time x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(x);
  }

  @Override
  public void writeTimestamp(@Nullable Timestamp x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(x);
  }

  @Override
  public void writeCharacterStream(@Nullable Reader x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(readAll(x));
  }

  @Override
  public void writeAsciiStream(@Nullable InputStream x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(new String(readAll(x), US_ASCII));
  }

  @Override
  public void writeBinaryStream(@Nullable InputStream x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(readAll(x));
  }

  /**
   * Reads a character stream to its end.
   */
  private static String readAll(Reader reader) throws SQLException {
    StringBuilder sb = new StringBuilder();
    char[] buffer = new char[8192];
    try {
      int read;
      while ((read = reader.read(buffer)) != -1) {
        sb.append(buffer, 0, read);
      }
    } catch (IOException e) {
      throw Exceptions.ioErrorReadingStream(e);
    }
    return sb.toString();
  }

  /**
   * Reads a byte stream to its end.
   */
  private static byte[] readAll(InputStream stream) throws SQLException {
    // TODO: support ByteStreamWriter?
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    try {
      int read;
      while ((read = stream.read(buffer)) != -1) {
        baos.write(buffer, 0, read);
      }
    } catch (IOException e) {
      throw Exceptions.ioErrorReadingStream(e);
    }
    return baos.toByteArray();
  }

  @Override
  public void writeObject(@Nullable SQLData x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(x);
  }

  @Override
  public void writeRef(@Nullable Ref x) throws SQLException {
    throw Exceptions.notImplemented("writeRef()");
  }

  @Override
  public void writeBlob(@Nullable Blob x) throws SQLException {
    throw Exceptions.notImplemented("writeBlob()");
  }

  @Override
  public void writeClob(@Nullable Clob x) throws SQLException {
    throw Exceptions.notImplemented("writeClob()");
  }

  @Override
  public void writeStruct(@Nullable Struct x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(x);
  }

  @Override
  public void writeArray(@Nullable Array x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    // Encode array using the object path which delegates to the field codec.
    writeFieldObject(x);
  }

  @Override
  public void writeURL(@Nullable URL x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(x.toString());
  }

  @Override
  public void writeNString(@Nullable String x) throws SQLException {
    writeString(x);
  }

  @Override
  public void writeNClob(@Nullable NClob x) throws SQLException {
    throw Exceptions.notImplemented("writeNClob()");
  }

  @Override
  public void writeRowId(@Nullable RowId x) throws SQLException {
    throw Exceptions.notImplemented("writeRowId()");
  }

  @Override
  public void writeSQLXML(@Nullable SQLXML x) throws SQLException {
    nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    String xml = x.getString();
    if (xml == null) {
      writeFieldNull();
      return;
    }
    writeFieldObject(xml);
  }

  @Override
  public void writeObject(@Nullable Object x, java.sql.SQLType targetSqlType) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeFieldNull();
      return;
    }
    // JDBC's writeObject(Object, SQLType) converts the value to targetSqlType before writing it. The
    // composite field's declared type still owns the wire format — the record framing depends on it
    // — so targetSqlType only steers the input coercion, the same normalisation
    // PgPreparedStatement.setObject applies, and a hint that disagrees with the slot simply yields a
    // value the field codec then rejects. With no usable hint, coerce toward the field's own SQL type
    // so a bare writeObject behaves like the typed writeInt/writeString paths.
    Integer vendorType = targetSqlType == null ? null : targetSqlType.getVendorTypeNumber();
    int coercionType = vendorType != null ? vendorType : getFieldType(field).getSqlType();
    writeFieldObject(SqlTypeCoercion.coerce(x, coercionType, -1));
  }
}
