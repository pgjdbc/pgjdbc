/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecContextBuilder;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayOutputStream;
import java.sql.Struct;
import java.util.Arrays;

/**
 * Behavioural guard that every delegating codec bounds its recursion through {@link CodecDepth}, so a
 * value nested past the limit fails with a clear {@code DATA_ERROR} rather than overflowing the stack,
 * and a value within the limit still round-trips. Unlike {@link CodecDepthTest}, which drives
 * {@code CodecDepth} in isolation, and {@code NestingDepthTest}, which needs a live server, these run
 * fully offline through the public {@link Codecs} surface and exercise the real encode/decode
 * delegation paths in both wire formats.
 *
 * <p>Rather than build a value 64+ levels deep — impossible for the <em>text</em> form, whose
 * {@code record_out} quote-doubling makes it grow as {@code O(2^depth)} and exhaust the heap around
 * depth 30 — each test pre-seeds the depth counter so only a small budget of further levels remains
 * (see {@link #withDepthBudget}). The guard then trips at a shallow structure depth where the text
 * form is still tiny, letting both formats be checked behaviourally. This depends only on
 * {@code CodecDepth}'s public {@link CodecDepth#enter()} / {@link CodecDepth#current()} contract, not
 * on the numeric value of {@code MAX_DEPTH}.
 *
 * <p>The array cases build an {@code array -> composite -> array} cycle because a PostgreSQL array type
 * never nests into itself directly (multidimensionality is one type, capped at
 * {@code MultiDimArrayBinary.MAX_DIMENSIONS}); the only unbounded array recursion runs through a
 * composite/domain element, so that is what the test constructs.
 */
class CodecNestingDepthOfflineTest {

  private static final int BUDGET = 8;

  @BeforeEach
  @AfterEach
  void resetDepth() {
    // The counter is a thread-local shared with the codecs; reset around every test so a pre-seeded
    // or leaked value cannot cross test boundaries.
    CodecDepth.clear();
  }

  // --- composite: exact boundary in both formats -----------------------------------------------

  @Test
  void compositeBinaryEncodeAtBudgetPassesOverBudgetThrows() throws Throwable {
    PgType recordType = anonymousRecord(field("f1", Oid.RECORD, 1));
    PgType leafType = anonymousRecord(field("f1", Oid.INT4, 1));
    CodecContext ctx = OfflineCodecs.builder().build();

    assertPassesAtThrowsOver(recordType, leafType, ctx, Format.BINARY);
  }

  @Test
  void compositeTextEncodeAtBudgetPassesOverBudgetThrows() throws Throwable {
    // Regression for the text composite encode path, which previously recursed through nested records
    // without a depth guard (only the binary encode path had one).
    PgType recordType = anonymousRecord(field("f1", Oid.RECORD, 1));
    PgType leafType = anonymousRecord(field("f1", Oid.INT4, 1));
    CodecContext ctx = OfflineCodecs.builder().build();

    assertPassesAtThrowsOver(recordType, leafType, ctx, Format.TEXT);
  }

  @Test
  void compositeBinaryDecodeAtBudgetPassesOverBudgetThrows() throws Throwable {
    PgType recordType = anonymousRecord(field("f1", Oid.RECORD, 1));
    CodecContext ctx = OfflineCodecs.builder().build();

    // A record nested exactly BUDGET deep decodes; one deeper trips the guard.
    withDepthBudget(BUDGET, false,
        () -> Codecs.decode(RawValue.binary(nestedRecordBinary(BUDGET)), recordType, ctx, Struct.class));
    withDepthBudget(BUDGET, true,
        () -> Codecs.decode(RawValue.binary(nestedRecordBinary(BUDGET + 1)), recordType, ctx, Struct.class));
  }

  /** Encodes a record nested exactly {@code BUDGET} deep (passes) and one deeper (throws). */
  private void assertPassesAtThrowsOver(PgType recordType, PgType leafType, CodecContext ctx,
      Format format) throws Throwable {
    withDepthBudget(BUDGET, false,
        () -> Codecs.encode(nestedRecordStruct(BUDGET, recordType, leafType), recordType, ctx, format));
    withDepthBudget(BUDGET, true,
        () -> Codecs.encode(nestedRecordStruct(BUDGET + 1, recordType, leafType), recordType, ctx, format));
  }

  // --- array <-> composite cycle: guard trips in both formats ----------------------------------

  @Test
  void arrayCompositeCycleBinaryEncodeThrows() throws Throwable {
    Cycle cycle = arrayCompositeCycle();
    PgStruct value = arrayCompositeCycleValue(BUDGET, cycle);
    withDepthBudget(BUDGET, true,
        () -> Codecs.encode(value, cycle.recordType, cycle.ctx, Format.BINARY));
  }

  @Test
  void arrayCompositeCycleTextEncodeThrows() throws Throwable {
    // With a small budget the guard trips a few levels in, so the text form's escaping stays tiny and
    // this exercises the array leaf's text encode guard without exhausting the heap.
    Cycle cycle = arrayCompositeCycle();
    PgStruct value = arrayCompositeCycleValue(BUDGET, cycle);
    withDepthBudget(BUDGET, true,
        () -> Codecs.encode(value, cycle.recordType, cycle.ctx, Format.TEXT));
  }

  // --- domain chain ----------------------------------------------------------------------------

  @Test
  void domainChainBinaryDecodeThrows() throws Throwable {
    // A chain of domains over domains, innermost base int4. Domains forward the wire bytes unchanged,
    // so a bare 4-byte int is enough: the guard trips while unwrapping.
    CodecContextBuilder builder = OfflineCodecs.builder();
    int baseOid = Oid.INT4;
    PgType outer = null;
    for (int i = 0; i < BUDGET + 4; i++) {
      outer = domain("dom" + i, DOMAIN_OID_BASE + i, baseOid);
      builder.type(outer);
      baseOid = outer.getOid();
    }
    CodecContext ctx = builder.build();
    byte[] intBytes = new byte[4];
    ByteConverter.int4(intBytes, 0, 42);
    PgType outerDomain = outer;

    withDepthBudget(BUDGET, true,
        () -> Codecs.decode(RawValue.binary(intBytes), outerDomain, ctx, Object.class));
  }

  // --- depth-budget harness --------------------------------------------------------------------

  /**
   * Pre-seeds the shared depth counter so only {@code budget} further levels are allowed, runs
   * {@code op}, then asserts it behaved as {@code expectThrow} says and that the codec unwound its own
   * enters back to the seed (a leaked counter would poison later codec calls on this thread).
   */
  private void withDepthBudget(int budget, boolean expectThrow, Executable op) throws Throwable {
    int seed = CodecDepth.MAX_DEPTH - budget;
    for (int i = 0; i < seed; i++) {
      CodecDepth.enter();
    }
    try {
      if (expectThrow) {
        PSQLException ex = assertThrows(PSQLException.class, op);
        assertEquals(PSQLState.DATA_ERROR.getState(), ex.getSQLState(), "SQLState");
        assertTrue(ex.getMessage().contains("nesting depth"),
            "expected a nesting-depth error, got: " + ex.getMessage());
      } else {
        op.execute();
      }
      assertEquals(seed, CodecDepth.current(),
          "codec must unwind its depth enters back to the pre-seeded level");
    } finally {
      CodecDepth.clear();
    }
  }

  // --- record payload / value builders ---------------------------------------------------------

  /** Builds {@code record(record(... record(int4) ...))} {@code depth} levels deep as binary. */
  private static byte[] nestedRecordBinary(int depth) {
    ByteArrayOutputStream leaf = new ByteArrayOutputStream();
    putInt(leaf, 1);         // nfields
    putInt(leaf, Oid.INT4);  // field type oid
    putInt(leaf, 4);         // field length
    putInt(leaf, 1);         // int4 value
    byte[] payload = leaf.toByteArray();
    for (int i = 1; i < depth; i++) {
      ByteArrayOutputStream wrap = new ByteArrayOutputStream();
      putInt(wrap, 1);            // nfields
      putInt(wrap, Oid.RECORD);   // field type oid = record
      putInt(wrap, payload.length);
      wrap.write(payload, 0, payload.length);
      payload = wrap.toByteArray();
    }
    return payload;
  }

  private static void putInt(ByteArrayOutputStream out, int value) {
    out.write((value >>> 24) & 0xff);
    out.write((value >>> 16) & 0xff);
    out.write((value >>> 8) & 0xff);
    out.write(value & 0xff);
  }

  /** Builds a {@code depth}-deep nested {@link PgStruct} graph, innermost a single int4. */
  private static PgStruct nestedRecordStruct(int depth, PgType recordType, PgType leafType) {
    PgStruct value = new PgStruct(leafType, new Object[]{1}, null);
    for (int i = 1; i < depth; i++) {
      value = new PgStruct(recordType, new Object[]{value}, null);
    }
    return value;
  }

  // --- array <-> composite cycle ---------------------------------------------------------------

  private static final int CYCLE_RECORD_OID = 90_101;
  private static final int CYCLE_ARRAY_OID = 90_102;
  private static final int DOMAIN_OID_BASE = 90_201;

  private static final class Cycle {
    final PgType recordType;
    final CodecContext ctx;

    Cycle(PgType recordType, CodecContext ctx) {
      this.recordType = recordType;
      this.ctx = ctx;
    }
  }

  /** A composite whose only field is an array of that same composite: {@code rec(rec[])}. */
  private static Cycle arrayCompositeCycle() {
    PgType recordType = composite("cyc", CYCLE_RECORD_OID, field("f1", CYCLE_ARRAY_OID, 1));
    PgType arrayType = new PgType(new ObjectName("public", "_cyc"), "public._cyc", CYCLE_ARRAY_OID,
        'b', 'A', -1, CYCLE_RECORD_OID, 0, 0);
    CodecContext ctx = OfflineCodecs.builder().type(recordType).type(arrayType).build();
    return new Cycle(recordType, ctx);
  }

  /**
   * Builds {@code cycles} nesting steps of {@code rec([rec([... rec([]) ...])])}. Each step adds a
   * composite and an array level, so the depth grows twice as fast as {@code cycles} — comfortably
   * past any small budget.
   */
  private static PgStruct arrayCompositeCycleValue(int cycles, Cycle cycle) {
    // Innermost: a record whose array field is empty, terminating the recursion.
    PgStruct value = new PgStruct(cycle.recordType, new Object[]{new Object[0]}, null);
    for (int i = 0; i < cycles; i++) {
      Object[] array = new Object[]{value};
      value = new PgStruct(cycle.recordType, new Object[]{array}, null);
    }
    return value;
  }

  // --- type helpers (mirroring OfflineContainerRoundtripTest) -----------------------------------

  private static PgField field(String name, int oid, int position) {
    return new PgField(name, oid, position, -1);
  }

  private static PgType composite(String simpleName, int oid, PgField... fields) {
    return new PgType(new ObjectName("public", simpleName), "public." + simpleName, oid, 'c', 'C',
        -1, 0, 0, 0, ',', Arrays.asList(fields));
  }

  private static PgType anonymousRecord(PgField... fields) {
    return new PgType(new ObjectName("pg_catalog", "record"), "record", Oid.RECORD, 'c', 'C',
        -1, 0, 0, 0, ',', Arrays.asList(fields));
  }

  private static PgType domain(String simpleName, int oid, int baseOid) {
    return new PgType(new ObjectName("public", simpleName), "public." + simpleName, oid, 'd', 'N',
        -1, 0, 0, baseOid, null);
  }
}
