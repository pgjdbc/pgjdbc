/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.PrimitiveTextEncoder;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.codec.CompositeCodec;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Text format SQLOutput implementation.
 *
 * <p>Each {@code writeXxx} call appends its field to the composite text literal {@code (f0,f1,...)} in
 * the caller-provided {@link Appendable} as it arrives. A field codec that implements
 * {@link PrimitiveTextEncoder} and never needs quoting receives the primitive unboxed and streams its
 * digits straight in; every other value goes through {@link CompositeCodec#writeTextFieldValue}, the
 * same per-field encoder the {@code Struct} path uses. Because the writer streams into the caller's
 * sink, a nested {@code SQLData} composite serializes directly into the enclosing literal, its
 * escaping compounding through the enclosing composite. Codecs and field types are pre-cached at
 * construction time; {@link #close()} checks the arity and appends the closing parenthesis.</p>
 */
public final class PgSQLOutputText extends PgSQLOutput {

  /**
   * Pre-cached codecs for each field.
   */
  private final TextCodec[] cachedCodecs;

  /**
   * Pre-cached field types.
   */
  private final TypeDescriptor[] cachedTypes;

  /**
   * The caller-owned sink the composite text literal is written into.
   */
  private final Appendable out;

  private boolean firstField = true;

  /**
   * Creates a new PgSQLOutputText that streams into {@code out}.
   *
   * @param type the composite type
   * @param ctx the codec context
   * @param out the sink the composite text literal is written into; owned by the caller
   */
  public PgSQLOutputText(PgType type, PgCodecContext ctx, Appendable out) throws SQLException {
    super(type, ctx);
    this.cachedCodecs = new TextCodec[fields.size()];
    this.cachedTypes = new TypeDescriptor[fields.size()];
    this.out = out;
    cacheCodecs();
    try {
      out.append('(');
    } catch (IOException e) {
      throw new AssertionError(e); // the in-memory sink never throws
    }
  }

  private void cacheCodecs() throws SQLException {
    for (int i = 0; i < fields.size(); i++) {
      PgField field = fields.get(i);
      int oid = field.getTypeOid();
      // resolveType/resolveTextCodec dispatch composite/array/domain/range/enum types by
      // typtype/typcategory when the OID is not explicitly registered (dynamic OIDs for
      // user-defined types).
      cachedTypes[i] = ctx.resolveType(oid);
      cachedCodecs[i] = castNonNull(ctx.resolveTextCodec(oid));
    }
  }

  private TextCodec getCodec() {
    return cachedCodecs[fieldIndex - 1];
  }

  private TypeDescriptor getCurrentType() {
    return cachedTypes[fieldIndex - 1];
  }

  /** Appends the inter-field comma; the first field emits none. */
  private void separator() throws IOException {
    if (firstField) {
      firstField = false;
    } else {
      out.append(',');
    }
  }

  @Override
  protected void writeFieldNull() {
    // A SQL NULL attribute is an empty, unquoted field.
    try {
      separator();
    } catch (IOException e) {
      throw new AssertionError(e); // the in-memory sink never throws
    }
  }

  @Override
  protected void writeFieldInt(int value) throws SQLException {
    TextCodec codec = getCodec();
    try {
      separator();
      if (codec instanceof PrimitiveTextEncoder && !codec.mayRequireQuoting()) {
        ((PrimitiveTextEncoder) codec).encodeInt(value, getCurrentType(), ctx, out);
      } else {
        CompositeCodec.writeTextFieldValue(out, value, getCurrentType(), codec, ctx);
      }
    } catch (IOException e) {
      throw new AssertionError(e); // the in-memory sink never throws
    }
  }

  @Override
  protected void writeFieldLong(long value) throws SQLException {
    TextCodec codec = getCodec();
    try {
      separator();
      if (codec instanceof PrimitiveTextEncoder && !codec.mayRequireQuoting()) {
        ((PrimitiveTextEncoder) codec).encodeLong(value, getCurrentType(), ctx, out);
      } else {
        CompositeCodec.writeTextFieldValue(out, value, getCurrentType(), codec, ctx);
      }
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  protected void writeFieldFloat(float value) throws SQLException {
    TextCodec codec = getCodec();
    try {
      separator();
      if (codec instanceof PrimitiveTextEncoder && !codec.mayRequireQuoting()) {
        ((PrimitiveTextEncoder) codec).encodeFloat(value, getCurrentType(), ctx, out);
      } else {
        CompositeCodec.writeTextFieldValue(out, value, getCurrentType(), codec, ctx);
      }
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  protected void writeFieldDouble(double value) throws SQLException {
    TextCodec codec = getCodec();
    try {
      separator();
      if (codec instanceof PrimitiveTextEncoder && !codec.mayRequireQuoting()) {
        ((PrimitiveTextEncoder) codec).encodeDouble(value, getCurrentType(), ctx, out);
      } else {
        CompositeCodec.writeTextFieldValue(out, value, getCurrentType(), codec, ctx);
      }
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  protected void writeFieldBoolean(boolean value) throws SQLException {
    TextCodec codec = getCodec();
    try {
      separator();
      CompositeCodec.writeTextFieldValue(out, value, getCurrentType(), codec, ctx);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  protected void writeFieldObject(Object value) throws SQLException {
    try {
      separator();
      CompositeCodec.writeTextFieldValue(out, value, getCurrentType(), getCodec(), ctx);
    } catch (IOException e) {
      throw new AssertionError(e); // the in-memory sink never throws
    }
  }

  @Override
  protected void finish() {
    try {
      out.append(')');
    } catch (IOException e) {
      throw new AssertionError(e); // the in-memory sink never throws
    }
  }
}
