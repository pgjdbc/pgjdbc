/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.fuzzkit.coercion.ReadCoercions.Accessor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.sql.SQLInput;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * One {@link SQLInput} reader the coercion fuzzer drives, binding a {@code ReadCoercions} outcome to
 * the actual {@code SQLInput} call. Each constant carries the {@link Accessor} whose outcome cell the
 * oracle checks against, the invocation itself, and a label for assertion messages. Replaces the
 * former {@code R_*} ordinals and their {@code accessorFor}/{@code invoke}/{@code readerName} switches
 * with one table, keeping {@code ReadCoercions.Accessor} the single source of truth for the reader
 * set.
 *
 * <p>The static initialiser guards completeness: every {@code Accessor} must be bound here, so adding
 * one to {@code ReadCoercions} without wiring up its {@code SQLInput} call fails fast rather than
 * silently narrowing coverage.
 *
 * <p>{@link #READ_OBJECT_AS} is the one parametrised reader: {@code readObject(Class)} takes a target
 * class and its outcome comes from {@code ReadCoercions.readObjectAs} rather than an {@code Accessor}
 * cell, so its {@code accessor} is {@code null} and it is exempt from the completeness guard. Every
 * other reader ignores the target class.
 */
public enum SqlInputReader {
  READ_STRING(Accessor.READ_STRING, "readString", (in, target) -> in.readString()),
  READ_BOOLEAN(Accessor.READ_BOOLEAN, "readBoolean", (in, target) -> in.readBoolean()),
  READ_BYTE(Accessor.READ_BYTE, "readByte", (in, target) -> in.readByte()),
  READ_SHORT(Accessor.READ_SHORT, "readShort", (in, target) -> in.readShort()),
  READ_INT(Accessor.READ_INT, "readInt", (in, target) -> in.readInt()),
  READ_LONG(Accessor.READ_LONG, "readLong", (in, target) -> in.readLong()),
  READ_FLOAT(Accessor.READ_FLOAT, "readFloat", (in, target) -> in.readFloat()),
  READ_DOUBLE(Accessor.READ_DOUBLE, "readDouble", (in, target) -> in.readDouble()),
  READ_BIG_DECIMAL(Accessor.READ_BIG_DECIMAL, "readBigDecimal", (in, target) -> in.readBigDecimal()),
  READ_BYTES(Accessor.READ_BYTES, "readBytes", (in, target) -> in.readBytes()),
  READ_DATE(Accessor.READ_DATE, "readDate", (in, target) -> in.readDate()),
  READ_TIME(Accessor.READ_TIME, "readTime", (in, target) -> in.readTime()),
  READ_TIMESTAMP(Accessor.READ_TIMESTAMP, "readTimestamp", (in, target) -> in.readTimestamp()),
  READ_CHARACTER_STREAM(Accessor.READ_CHARACTER_STREAM, "readCharacterStream",
      (in, target) -> in.readCharacterStream()),
  READ_ASCII_STREAM(Accessor.READ_ASCII_STREAM, "readAsciiStream",
      (in, target) -> in.readAsciiStream()),
  READ_BINARY_STREAM(Accessor.READ_BINARY_STREAM, "readBinaryStream",
      (in, target) -> in.readBinaryStream()),
  READ_OBJECT(Accessor.READ_OBJECT, "readObject", (in, target) -> in.readObject()),
  READ_REF(Accessor.READ_REF, "readRef", (in, target) -> in.readRef()),
  READ_BLOB(Accessor.READ_BLOB, "readBlob", (in, target) -> in.readBlob()),
  READ_CLOB(Accessor.READ_CLOB, "readClob", (in, target) -> in.readClob()),
  READ_ARRAY(Accessor.READ_ARRAY, "readArray", (in, target) -> in.readArray()),
  READ_URL(Accessor.READ_URL, "readURL", (in, target) -> in.readURL()),
  READ_NCLOB(Accessor.READ_NCLOB, "readNClob", (in, target) -> in.readNClob()),
  READ_NSTRING(Accessor.READ_NSTRING, "readNString", (in, target) -> in.readNString()),
  READ_SQLXML(Accessor.READ_SQLXML, "readSQLXML", (in, target) -> in.readSQLXML()),
  READ_ROWID(Accessor.READ_ROWID, "readRowId", (in, target) -> in.readRowId()),
  READ_OBJECT_AS(null, "readObject(Class)", (in, target) -> in.readObject(target));

  /**
   * Invokes one {@code SQLInput} reader. {@code target} is used only by {@code readObject(Class)}.
   * Nullable: a SQL {@code NULL} column legitimately reads back as {@code null} -- most concretely
   * through {@code readObject(Class)}, whose unbounded generic return type the Checker Framework's
   * JDK stubs model as nullable.
   */
  @FunctionalInterface
  interface Invoker {
    @Nullable Object read(SQLInput in, Class<?> target) throws SQLException;
  }

  /** Every bound {@link Accessor} to its reader; {@code readObject(Class)} has no accessor and is absent. */
  private static final Map<Accessor, SqlInputReader> BY_ACCESSOR = indexByAccessor();

  static {
    // Every value reader in the registry must map to an SQLInput call, so a new Accessor cannot slip
    // through untested. readObject(Class) has no Accessor, so it is excluded.
    Set<Accessor> missing = EnumSet.allOf(Accessor.class);
    missing.removeAll(BY_ACCESSOR.keySet());
    if (!missing.isEmpty()) {
      throw new ExceptionInInitializerError("SqlInputReader is missing SQLInput accessors: " + missing);
    }
  }

  private static Map<Accessor, SqlInputReader> indexByAccessor() {
    Map<Accessor, SqlInputReader> map = new EnumMap<>(Accessor.class);
    for (SqlInputReader reader : values()) {
      if (reader.accessor != null) {
        map.put(reader.accessor, reader);
      }
    }
    return map;
  }

  /**
   * The reader for an accessor -- used to reach a descriptor's diagonal typed reader. Guard G1
   * guarantees every {@link Accessor} is bound, so a registered accessor always resolves;
   * {@code readObject(Class)} has no accessor and is not reachable here.
   *
   * @param accessor the canonical read accessor
   * @return the reader that invokes it
   */
  static SqlInputReader of(Accessor accessor) {
    SqlInputReader reader = BY_ACCESSOR.get(accessor);
    if (reader == null) {
      throw new IllegalArgumentException("No SqlInputReader for " + accessor);
    }
    return reader;
  }

  private final @Nullable Accessor accessor;
  private final String label;
  // The invoker is a stateless method-reference-style lambda, so the enum is effectively immutable;
  // errorprone cannot prove it because the functional interface is not annotated @Immutable.
  @SuppressWarnings("ImmutableEnumChecker")
  private final Invoker invoker;

  SqlInputReader(@Nullable Accessor accessor, String label, Invoker invoker) {
    this.accessor = accessor;
    this.label = label;
    this.invoker = invoker;
  }

  /**
   * The registry outcome cell this reader checks against, or {@code null} for {@code readObject(Class)}
   * (its outcome comes from {@code ReadCoercions.readObjectAs}).
   */
  @Nullable Accessor accessor() {
    return accessor;
  }

  /** A human-readable name for assertion messages. */
  String label() {
    return label;
  }

  @Nullable Object read(SQLInput in, Class<?> target) throws SQLException {
    return invoker.read(in, target);
  }
}
