/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveBinaryEncoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.PrimitiveTextEncoder;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.OfflineCodecs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The drift guard for the generated primitive-capability parity family, cross-checking two independent
 * signals over the authoritative {@link OfflineCodecs#defaultRegistry()}{@code .builtinCodecsByOid()}. The
 * model derives its parity targets from {@link Codec#getDefaultJavaType()} (a primitive wrapper &rarr; a
 * target); this test instead walks the codecs that IMPLEMENT a primitive capability
 * ({@link PrimitiveBinaryDecoder}, {@link PrimitiveTextDecoder}, {@link PrimitiveBinaryEncoder},
 * {@link PrimitiveTextEncoder}). Because the two signals differ, the test catches a codec that gains a
 * capability without a primitive default -- which the model would silently skip.
 *
 * <p>Every capability-implementing built-in must be accounted for: either it is a parity target
 * ({@link PrimitiveCapabilityModel#parityOids()}) or it is a documented exclusion below. A primitive
 * capability alone does not make a parity target -- a text or {@code numeric} codec offers
 * {@code decodeAsInt} as a parse coercion, not as its identity -- so the two sets are kept apart
 * deliberately. If a codec is added to (or removed from) the registry, or gains a primitive capability,
 * this test fails until it is classified, which is exactly the "you will not forget" the generator is for.
 */
class PrimitiveCapabilityCoverageTest {

  // Built-in codecs that implement a primitive capability yet are NOT parity targets, each with its reason.
  // Their primitive accessors are coercions (parse the text/number to a primitive), not the type's identity
  // round-trip, so the "no-box override == boxing default" parity property is not the right oracle for them.
  private static final Set<Integer> EXCLUDED = new TreeSet<>(Arrays.asList(
      Oid.NAME,     // String-natural: decodeAsInt parses the text, it is not the name's value
      Oid.TEXT,     // String-natural
      Oid.BPCHAR,   // String-natural
      Oid.VARCHAR,  // String-natural
      Oid.NUMERIC,  // BigDecimal-natural: the primitive accessors narrow a BigDecimal
      Oid.MONEY,    // coercion-primitive, no coercion descriptor
      Oid.BIT,      // coercion-primitive (single-bit -> boolean), no coercion descriptor
      Oid.VARBIT    // coercion-primitive, no coercion descriptor
  ));

  @Test
  void everyPrimitiveCapableBuiltinIsAParityTargetOrExcluded() {
    Set<Integer> primitiveCapable = primitiveCapableBuiltinOids();
    Set<Integer> parity = PrimitiveCapabilityModel.parityOids();

    // No OID is both a parity target and an excluded coercion type.
    Set<Integer> overlap = new TreeSet<>(parity);
    overlap.retainAll(EXCLUDED);
    assertEquals(emptyList(), new ArrayList<>(overlap),
        "An OID is both a parity target and an EXCLUDED coercion type; it can be only one.");

    // Completeness: every primitive-capable built-in is classified (parity or excluded).
    Set<Integer> unclassified = new TreeSet<>(primitiveCapable);
    unclassified.removeAll(parity);
    unclassified.removeAll(EXCLUDED);
    assertEquals(emptyList(), new ArrayList<>(unclassified),
        "Built-in codecs implement a primitive capability but are neither a parity target nor an EXCLUDED "
            + "entry. Give each a parity target (a primitive-natural PgTypeDescriptor, or an oid8/xid8-style "
            + "addition in PrimitiveCapabilityModel) or add it to EXCLUDED with a reason.");

    // No stale classification: nothing claimed as parity or excluded has lost its primitive capability.
    Set<Integer> staleParity = new TreeSet<>(parity);
    staleParity.removeAll(primitiveCapable);
    assertEquals(emptyList(), new ArrayList<>(staleParity),
        "PrimitiveCapabilityModel.parityOids() names OIDs whose built-in codec no longer implements a "
            + "primitive capability. Remove the stale target.");

    Set<Integer> staleExcluded = new TreeSet<>(EXCLUDED);
    staleExcluded.removeAll(primitiveCapable);
    assertEquals(emptyList(), new ArrayList<>(staleExcluded),
        "EXCLUDED names OIDs whose built-in codec no longer implements a primitive capability. "
            + "Remove the stale exclusion.");
  }

  private static Set<Integer> primitiveCapableBuiltinOids() {
    Set<Integer> oids = new TreeSet<>();
    for (Map.Entry<Integer, Codec> entry : OfflineCodecs.defaultRegistry().builtinCodecsByOid().entrySet()) {
      Codec codec = entry.getValue();
      if (codec instanceof PrimitiveBinaryDecoder || codec instanceof PrimitiveTextDecoder
          || codec instanceof PrimitiveBinaryEncoder || codec instanceof PrimitiveTextEncoder) {
        oids.add(entry.getKey());
      }
    }
    return oids;
  }
}
