/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.fuzzkit.coercion.CoercionOutcome;
import org.postgresql.fuzzkit.coercion.Fidelity;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.fuzzkit.coercion.ReadCoercions.Accessor;
import org.postgresql.fuzzkit.coercion.ScalarDescriptor;
import org.postgresql.fuzzkit.coercion.WriteCoercions.Method;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgSQLInputBinary;
import org.postgresql.jdbc.PgSQLInputText;
import org.postgresql.jdbc.PgType;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * Drives one {@link CoercionRoundTripCase} through the SQLData write and read adapters, composing the
 * two oracles: the write leg is checked through {@link WriteOracle} against {@code WriteCoercions}, and
 * -- when the writer produced wire -- the read leg through {@link ReadOracle} against
 * {@code ReadCoercions}. Neither leg may leak an unchecked exception.
 *
 * <p>The write may be off-diagonal (any writer into any attribute, for example {@code writeString} into
 * a {@code time} attribute), so it can legitimately refuse; when it does, there is nothing to read
 * back. On top of the two outcome checks, an <em>identity pair</em> -- a type's value written with its
 * own writer and read back with its own reader (or {@code readObject} of its own class / {@code Object})
 * -- adds value fidelity: the value read back must equal the value written.
 */
public final class CoercionRoundTripSupport {

  /**
   * A value-preserving triple: a type's value written with its own writer, read back with its own
   * reader. {@code naturalClass} is the Java class whose {@code readObject} also round-trips to the
   * written value (the type's own class); {@code readObject(Object)} does too when it is the type's
   * codec default (it is not for {@code timetz}/{@code timestamptz}). {@code reader} is the typed
   * {@code SQLInput} reader, or {@code null} for a type reached only through the {@code writeObject}/
   * {@code readObject(Class)} object axis ({@code timetz}/{@code timestamptz}).
   */
  public static final class IdentityPair {
    public final ScalarDescriptor attr;
    public final SqlOutputWriterBinding writer;
    public final @Nullable SqlInputReader reader;
    public final Class<?> naturalClass;
    public final Fidelity fidelity;

    IdentityPair(ScalarDescriptor attr, SqlOutputWriterBinding writer, @Nullable SqlInputReader reader,
        Class<?> naturalClass, Fidelity fidelity) {
      this.attr = attr;
      this.writer = writer;
      this.reader = reader;
      this.naturalClass = naturalClass;
      this.fidelity = fidelity;
    }
  }

  /**
   * The identity pairs, derived from {@link PgTypeDescriptors#coercionScalars()}: one per write-populated
   * scalar, never hand-listed. Building from the write-populated scalars keeps the round-trip on exactly
   * the ten coercion types; the codec-only scalars ({@code int2}/{@code float4}/{@code float8}/{@code bytea})
   * carry descriptors but no {@code WriteCoercions} row, so they never enter the round-trip. A type with a
   * diagonal typed writer/reader ({@code int4}..{@code timestamp}) round-trips through them; a type reached
   * only through the object axis ({@code timetz}/{@code timestamptz}, whose {@code typedWriter}/{@code
   * typedReader} are {@code null}) round-trips through {@code writeObject} and {@code readObject(Class)} of
   * its {@code naturalClass}. The writer, reader, natural class and fidelity all come from the descriptor;
   * guard G5 guarantees each descriptor forms exactly one of the two paths.
   */
  public static final List<IdentityPair> IDENTITY_PAIRS =
      Collections.unmodifiableList(buildPairs());

  private static List<IdentityPair> buildPairs() {
    List<IdentityPair> pairs = new ArrayList<>();
    for (ScalarDescriptor descriptor : PgTypeDescriptors.coercionScalars()) {
      Method typedWriter = descriptor.typedWriter();
      Accessor typedReader = descriptor.typedReader();
      // typedWriter/typedReader are both present (diagonal typed pair) or both absent (object axis),
      // by guard G5; the object axis writes through writeObject and reads through readObject(Class).
      SqlOutputWriterBinding writer = typedWriter != null
          ? SqlOutputWriterBinding.of(typedWriter)
          : SqlOutputWriterBinding.WRITE_OBJECT_AS;
      SqlInputReader reader = typedReader != null ? SqlInputReader.of(typedReader) : null;
      pairs.add(new IdentityPair(descriptor, writer, reader, descriptor.naturalClass(),
          descriptor.fidelity()));
    }
    return pairs;
  }

  private CoercionRoundTripSupport() {
  }

  /**
   * The identity pair matching this case, or {@code null} for an off-diagonal case. A pair matches when
   * the value is written with its own writer and read back with its own typed reader or through
   * {@code readObject(Class)}. A typed writer fixes the value class; the {@code writeObject} object axis
   * ({@code timetz}/{@code timestamptz}) does not, so it additionally requires the value to be the
   * pair's natural class -- an off-diagonal {@code writeObject} of some other class is not a round-trip.
   */
  private static @Nullable IdentityPair identityPair(ScalarDescriptor attr, SqlOutputWriterBinding writer,
      SqlInputReader reader, @Nullable Object value) {
    for (IdentityPair pair : IDENTITY_PAIRS) {
      if (pair.attr != attr || pair.writer != writer
          || (reader != pair.reader && reader != SqlInputReader.READ_OBJECT_AS)) {
        continue;
      }
      if (writer == SqlOutputWriterBinding.WRITE_OBJECT_AS
          && (value == null || value.getClass() != pair.naturalClass)) {
        continue;
      }
      return pair;
    }
    return null;
  }

  public static void run(CoercionRoundTripCase c) throws SQLException {
    int oid = c.attr.oid();
    PgType comp = FuzzComposites.singleField(oid);
    PgCodecContext ctx = (PgCodecContext) OfflineCodecContexts.offlineBuilder()
        .type(comp)
        .timeZone(TimeZone.getDefault())
        .build();

    IdentityPair pair = identityPair(c.attr, c.writer, c.reader, c.writeValue);
    // readObject(Class) carries the target; the typed reader ignores it, so null maps to a placeholder.
    Class<?> target = c.targetClass == null ? Object.class : c.targetClass;
    boolean readObjectMode = c.reader == SqlInputReader.READ_OBJECT_AS;
    // Fidelity holds for an identity pair read through its typed reader, or through readObject of its
    // natural class -- or readObject(Object) only when Object resolves to that natural class, which it
    // does for the scalar types but not for timetz/timestamptz (they default to java.sql.Time/Timestamp).
    Class<?> defaultObject = ReadOracle.defaultObjectClass(oid);
    boolean checkFidelity = pair != null
        && (!readObjectMode
            || target == pair.naturalClass
            || (target == Object.class && defaultObject == pair.naturalClass));

    CoercionOutcome writeExpected = WriteOracle.expected(oid, c.writer, c.writeValue);
    CoercionOutcome readExpected = ReadOracle.expected(oid, c.reader, target, Collections.emptyMap());
    // A poison value (a non-finite numeric has no BigDecimal form) preempts the registry's type-based
    // outcome, so its read leg keeps only the weak invariant. descriptor.poison generalises the former
    // hard-coded nonFiniteNumeric rule; poison cases are always off-diagonal, so no fidelity is lost.
    boolean poison = c.attr.poison(c.writeValue);

    for (Format format : Format.values()) {
      RawValue wire = WriteOracle.verify(comp, ctx, format, c.writer, c.writeValue, writeExpected, c);
      if (wire == null) {
        // The writer refused (outcome already checked); there is nothing to read back.
        continue;
      }
      SQLInput in = format == Format.TEXT
          ? new PgSQLInputText(wire.asString(StandardCharsets.UTF_8), comp, ctx)
          : new PgSQLInputBinary(wire.toByteArray(), comp, ctx);

      if (poison) {
        readNoLeak(in, c.reader, target, format, c);
        continue;
      }
      ReadOracle.ReadResult result = ReadOracle.verify(in, c.reader, target, readExpected, format, c);
      if (checkFidelity && result.returned() && !pair.fidelity.equal(c.writeValue, result.value())) {
        throw new AssertionError("round-trip changed the value on " + format + ": wrote "
            + c.writeValue + ", read " + result.value() + " " + c);
      }
    }
  }

  private static void readNoLeak(SQLInput in, SqlInputReader reader, Class<?> target, Format format,
      CoercionRoundTripCase c) {
    try {
      reader.read(in, target);
    } catch (SQLException clean) {
      // Acceptable: the poison value refuses, and its exact state is value- not registry-driven.
    } catch (RuntimeException leak) {
      throw new AssertionError(ReadOracle.describe(reader, target) + " leaked "
          + leak.getClass().getName() + " (expected only SQLException) on " + format + " " + c, leak);
    }
  }
}
