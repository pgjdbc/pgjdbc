/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.PrefersJavaTime;
import org.postgresql.api.codec.RawValue;
import org.postgresql.fuzzkit.coercion.CoercionOutcome;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgSQLInputBinary;
import org.postgresql.jdbc.PgSQLInputText;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.util.Map;
import java.util.TimeZone;

/**
 * Drives one {@link CoercionCase} through the SQLData read adapters and asserts the read outcome via
 * {@link ReadOracle} against the {@code ReadCoercions} registry. The value reaches the reader on the
 * canonical wire -- the field's own codec -- so the read side is the single axis under test here.
 *
 * <p>The reader fuzzer stays on the canonical wire alone. The driver write paths (the typed
 * {@code PgSQLOutput} method, the generic {@code writeObject}) present field bytes identical to the
 * canonical codec on the diagonal, so they add no unique read coverage; the config dependence lives in
 * the field decoder over those bytes, and off-diagonal write&rarr;read stays in the round-trip fuzzer.
 * The byte-equivalence assumption is pinned by {@code TypedWriteMatchesCanonicalWireTest}.
 *
 * <p>The remaining dimensions: a {@link org.postgresql.fuzzkit.coercion.ScalarDescriptor} maps the field
 * type to its OID; {@link SqlInputReader} binds each {@code SQLInput} call to the
 * {@code ReadCoercions.Accessor} whose outcome it checks; and the read axes ({@code readObject} target
 * classes, {@code prefersJavaTime} config) and the outcome check live in {@link ReadOracle}, shared
 * with {@link CoercionRoundTripSupport}.
 */
public final class CoercionFuzzSupport {

  private CoercionFuzzSupport() {
  }

  private static PgType scalar(int oid) {
    return new PgType(new ObjectName("pg_catalog", "t" + oid), "t" + oid, oid, 'b', 'N', -1, 0, 0, 0);
  }

  public static void run(CoercionCase c) throws SQLException {
    int oid = c.kind.oid();
    PgType comp = FuzzComposites.singleField(oid);
    PrefersJavaTime p = c.prefersJavaTime;
    Map<String, String> config = ReadOracle.configFor(p);
    PgCodecContext ctx = (PgCodecContext) OfflineCodecContexts.offlineBuilder()
        .type(comp)
        .timeZone(TimeZone.getDefault())
        .prefersJavaTime(p)
        .build();

    // The target class is only meaningful for readObject(Class); other readers ignore it, so a null
    // targetClass maps to a harmless placeholder.
    Class<?> target = c.targetClass == null ? Object.class : c.targetClass;
    // The registry outcome is format-independent, so it is looked up once for the whole matrix cell.
    @Nullable CoercionOutcome expected = ReadOracle.expected(oid, c.reader, target, config);

    for (Format format : Format.values()) {
      SQLInput in = openReader(c, oid, comp, ctx, format);
      ReadOracle.verify(in, c.reader, target, expected, format, c);
    }
  }

  private static SQLInput openReader(CoercionCase c, int oid, PgType comp, PgCodecContext ctx,
      Format format) throws SQLException {
    // Server-realistic wire: the field's own codec, handed pre-split to the reader adapter.
    RawValue field = Codecs.encode(c.value, scalar(oid), ctx, format);
    return format == Format.TEXT
        ? new PgSQLInputText(new String[]{field.asString(StandardCharsets.UTF_8)}, comp, ctx)
        : new PgSQLInputBinary(singleFieldComposite(oid, field.toByteArray()), comp, ctx);
  }

  /**
   * Wraps one pre-encoded field body in the binary composite wire the reader expects: {@code int4}
   * field count, then the field's {@code int4} OID, {@code int4} length, and body.
   */
  private static byte[] singleFieldComposite(int oid, byte[] body) {
    byte[] wire = new byte[12 + body.length];
    ByteConverter.int4(wire, 0, 1);
    ByteConverter.int4(wire, 4, oid);
    ByteConverter.int4(wire, 8, body.length);
    System.arraycopy(body, 0, wire, 12, body.length);
    return wire;
  }
}
