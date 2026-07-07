/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.PrimitiveBinaryEncoder;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.codec.CompositeCodec;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Binary format SQLOutput implementation.
 *
 * <p>Each {@code writeXxx} call streams its field straight into the caller-provided
 * {@link BackpatchingBinarySink}: the type OID, a back-patched length prefix, and the body. A field
 * codec that implements {@link PrimitiveBinaryEncoder} receives the primitive unboxed; other values
 * go through {@link CompositeCodec#writeBinaryFieldValue}, the same per-field encoder the
 * {@code Struct} path uses. Because the writer streams into the caller's sink, a nested
 * {@code SQLData} composite serializes directly into the enclosing array or composite buffer with no
 * intermediate copy. Codecs and field types are pre-cached at construction time; {@link #close()}
 * checks the arity.</p>
 */
public final class PgSQLOutputBinary extends PgSQLOutput {

  /**
   * Pre-cached codecs for each field.
   */
  private final BinaryCodec[] cachedCodecs;

  /**
   * Pre-cached field types.
   */
  private final TypeDescriptor[] cachedTypes;

  /**
   * The caller-owned sink the composite binary form is written into.
   */
  private final BackpatchingBinarySink sink;

  /**
   * Creates a new PgSQLOutputBinary that streams into {@code sink}.
   *
   * @param type the composite type
   * @param ctx the codec context
   * @param sink the buffer the composite binary form is written into; owned by the caller
   */
  public PgSQLOutputBinary(PgType type, PgCodecContext ctx, BackpatchingBinarySink sink) throws SQLException {
    super(type, ctx);
    this.cachedCodecs = new BinaryCodec[fields.size()];
    this.cachedTypes = new TypeDescriptor[fields.size()];
    this.sink = sink;
    try {
      // Binary composite header: the field count. Per-field oid/length/body follow as each writeXxx
      // streams in; close() checks the arity once writeSQL returns.
      sink.writeInt32(fields.size());
    } catch (IOException e) {
      throw new AssertionError(e); // the in-memory sink never throws
    }
    cacheCodecs();
  }

  private void cacheCodecs() throws SQLException {
    for (int i = 0; i < fields.size(); i++) {
      PgField field = fields.get(i);
      int oid = field.getTypeOid();
      cachedTypes[i] = ctx.resolveType(oid);
      cachedCodecs[i] = castNonNull(ctx.resolveBinaryCodec(oid));
    }
  }

  private BinaryCodec getCodec() {
    return cachedCodecs[fieldIndex - 1];
  }

  private TypeDescriptor getCurrentType() {
    return cachedTypes[fieldIndex - 1];
  }

  /**
   * Writes the current field's type OID and reserves its length slot, returning the slot position for
   * a later {@link #closeField(int)}.
   */
  private int openField() throws IOException {
    sink.writeInt32(fields.get(fieldIndex - 1).getTypeOid());
    return sink.reserveInt32();
  }

  /** Back-patches the length slot with the number of body bytes written since it was reserved. */
  private void closeField(int slot) {
    sink.setInt32At(slot, sink.position() - (slot + 4));
  }

  @Override
  protected void writeFieldNull() {
    try {
      sink.writeInt32(fields.get(fieldIndex - 1).getTypeOid());
      sink.writeInt32(-1);
    } catch (IOException e) {
      throw new AssertionError(e); // the in-memory sink never throws
    }
  }

  @Override
  protected void writeFieldInt(int value) throws SQLException {
    BinaryCodec codec = getCodec();
    if (codec instanceof PrimitiveBinaryEncoder) {
      try {
        int slot = openField();
        ((PrimitiveBinaryEncoder) codec).encodeInt(value, getCurrentType(), ctx, sink);
        closeField(slot);
      } catch (IOException e) {
        throw new AssertionError(e); // the in-memory sink never throws
      }
    } else {
      writeFieldObject(value);
    }
  }

  @Override
  protected void writeFieldLong(long value) throws SQLException {
    BinaryCodec codec = getCodec();
    if (codec instanceof PrimitiveBinaryEncoder) {
      try {
        int slot = openField();
        ((PrimitiveBinaryEncoder) codec).encodeLong(value, getCurrentType(), ctx, sink);
        closeField(slot);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    } else {
      writeFieldObject(value);
    }
  }

  @Override
  protected void writeFieldFloat(float value) throws SQLException {
    BinaryCodec codec = getCodec();
    if (codec instanceof PrimitiveBinaryEncoder) {
      try {
        int slot = openField();
        ((PrimitiveBinaryEncoder) codec).encodeFloat(value, getCurrentType(), ctx, sink);
        closeField(slot);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    } else {
      writeFieldObject(value);
    }
  }

  @Override
  protected void writeFieldDouble(double value) throws SQLException {
    BinaryCodec codec = getCodec();
    if (codec instanceof PrimitiveBinaryEncoder) {
      try {
        int slot = openField();
        ((PrimitiveBinaryEncoder) codec).encodeDouble(value, getCurrentType(), ctx, sink);
        closeField(slot);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    } else {
      writeFieldObject(value);
    }
  }

  @Override
  protected void writeFieldBoolean(boolean value) throws SQLException {
    writeFieldObject(value);
  }

  @Override
  protected void writeFieldObject(Object value) throws SQLException {
    try {
      int slot = openField();
      CompositeCodec.writeBinaryFieldValue(sink, value, getCurrentType(), getCodec(), ctx);
      closeField(slot);
    } catch (IOException e) {
      throw new AssertionError(e); // the in-memory sink never throws
    }
  }
}
