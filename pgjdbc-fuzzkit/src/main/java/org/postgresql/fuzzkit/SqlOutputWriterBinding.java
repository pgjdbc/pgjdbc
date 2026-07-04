/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import static java.nio.charset.StandardCharsets.US_ASCII;

import org.postgresql.fuzzkit.coercion.WriteCoercions.Method;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.sql.SQLOutput;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * One {@link SQLOutput} writer the coercion fuzzer drives, binding a {@link Method} to the actual
 * {@code SQLOutput} call. Each constant carries the canonical {@link Method} whose presented class and
 * outcome the oracle checks against, the invocation itself, and a label for assertion messages. The
 * outcome comes from {@code WriteCoercions.encode(oid, method.presentedClass(value))}; the presented
 * class and the {@code NOT_IMPLEMENTED} flag live on {@link Method}, not here.
 *
 * <p>The static initialiser guards completeness (guard G2, the write-side mirror of
 * {@link SqlInputReader}'s guard): every {@link Method} must be bound here, so adding one to
 * {@code WriteCoercions} without wiring up its {@code SQLOutput} call fails fast rather than silently
 * narrowing coverage. Every method has a binding, including {@link #WRITE_OBJECT_AS} and the
 * {@code NOT_IMPLEMENTED} writers ({@code writeRef} and friends), which reject before a codec runs.
 */
public enum SqlOutputWriterBinding {
  WRITE_INT(Method.WRITE_INT, "writeInt", (out, v) -> out.writeInt((Integer) v)),
  WRITE_LONG(Method.WRITE_LONG, "writeLong", (out, v) -> out.writeLong((Long) v)),
  WRITE_FLOAT(Method.WRITE_FLOAT, "writeFloat", (out, v) -> out.writeFloat((Float) v)),
  WRITE_DOUBLE(Method.WRITE_DOUBLE, "writeDouble", (out, v) -> out.writeDouble((Double) v)),
  WRITE_BOOLEAN(Method.WRITE_BOOLEAN, "writeBoolean", (out, v) -> out.writeBoolean((Boolean) v)),
  WRITE_STRING(Method.WRITE_STRING, "writeString", (out, v) -> out.writeString((String) v)),
  WRITE_N_STRING(Method.WRITE_N_STRING, "writeNString", (out, v) -> out.writeNString((String) v)),
  WRITE_CHARACTER_STREAM(Method.WRITE_CHARACTER_STREAM, "writeCharacterStream",
      (out, v) -> out.writeCharacterStream(new StringReader((String) v))),
  WRITE_ASCII_STREAM(Method.WRITE_ASCII_STREAM, "writeAsciiStream",
      (out, v) -> out.writeAsciiStream(new ByteArrayInputStream(((String) v).getBytes(US_ASCII)))),
  WRITE_BIG_DECIMAL(Method.WRITE_BIG_DECIMAL, "writeBigDecimal",
      (out, v) -> out.writeBigDecimal((BigDecimal) v)),
  WRITE_BYTES(Method.WRITE_BYTES, "writeBytes", (out, v) -> out.writeBytes((byte[]) v)),
  WRITE_BINARY_STREAM(Method.WRITE_BINARY_STREAM, "writeBinaryStream",
      (out, v) -> out.writeBinaryStream(new ByteArrayInputStream((byte[]) v))),
  WRITE_DATE(Method.WRITE_DATE, "writeDate", (out, v) -> out.writeDate((Date) v)),
  WRITE_TIME(Method.WRITE_TIME, "writeTime", (out, v) -> out.writeTime((Time) v)),
  WRITE_TIMESTAMP(Method.WRITE_TIMESTAMP, "writeTimestamp", (out, v) -> out.writeTimestamp((Timestamp) v)),
  // Widen to Integer via encodeInt: the presented class is Integer, carried by Method.WRITE_BYTE.
  WRITE_BYTE(Method.WRITE_BYTE, "writeByte", (out, v) -> out.writeByte((Byte) v)),
  WRITE_SHORT(Method.WRITE_SHORT, "writeShort", (out, v) -> out.writeShort((Short) v)),
  WRITE_URL(Method.WRITE_URL, "writeURL", (out, v) -> out.writeURL((URL) v)),
  // The free-class axis; the SQLType is dropped by the composite path, so a fixed placeholder is passed.
  WRITE_OBJECT_AS(Method.WRITE_OBJECT_AS, "writeObject(Object,SQLType)",
      (out, v) -> out.writeObject(v, JDBCType.OTHER)),
  WRITE_REF(Method.WRITE_REF, "writeRef", (out, v) -> out.writeRef(null)),
  WRITE_BLOB(Method.WRITE_BLOB, "writeBlob", (out, v) -> out.writeBlob(null)),
  WRITE_CLOB(Method.WRITE_CLOB, "writeClob", (out, v) -> out.writeClob(null)),
  WRITE_NCLOB(Method.WRITE_NCLOB, "writeNClob", (out, v) -> out.writeNClob(null)),
  WRITE_ROWID(Method.WRITE_ROWID, "writeRowId", (out, v) -> out.writeRowId(null));

  /** Invokes one {@code SQLOutput} writer with the given value. */
  @FunctionalInterface
  interface Invoker {
    void write(SQLOutput out, Object value) throws SQLException;
  }

  /** Every {@link Method} to its binding; guard G2 keeps this map total over {@code Method}. */
  private static final Map<Method, SqlOutputWriterBinding> BY_METHOD = indexByMethod();

  static {
    // Every write method in the registry must map to an SQLOutput call, so a new Method cannot slip
    // through untested. This is guard G2, the write-side mirror of SqlInputReader's completeness guard.
    Set<Method> missing = EnumSet.allOf(Method.class);
    missing.removeAll(BY_METHOD.keySet());
    if (!missing.isEmpty()) {
      throw new ExceptionInInitializerError("SqlOutputWriterBinding is missing SQLOutput methods: "
          + missing);
    }
  }

  private static Map<Method, SqlOutputWriterBinding> indexByMethod() {
    Map<Method, SqlOutputWriterBinding> map = new EnumMap<>(Method.class);
    for (SqlOutputWriterBinding binding : values()) {
      map.put(binding.method, binding);
    }
    return map;
  }

  /**
   * The binding for a write method -- used to reach a descriptor's diagonal typed writer. Guard G2
   * guarantees every {@link Method} is bound, so a registered method always resolves.
   *
   * @param method the canonical write method
   * @return the binding that invokes it
   */
  static SqlOutputWriterBinding of(Method method) {
    SqlOutputWriterBinding binding = BY_METHOD.get(method);
    if (binding == null) {
      throw new IllegalArgumentException("No SqlOutputWriterBinding for " + method);
    }
    return binding;
  }

  private final Method method;
  private final String label;
  private final Invoker invoker;

  SqlOutputWriterBinding(Method method, String label, Invoker invoker) {
    this.method = method;
    this.label = label;
    this.invoker = invoker;
  }

  /** The canonical write method whose presented class and outcome the oracle checks against. */
  public Method method() {
    return method;
  }

  /** A human-readable name for assertion messages. */
  String label() {
    return label;
  }

  void write(SQLOutput out, Object value) throws SQLException {
    invoker.write(out, value);
  }
}
