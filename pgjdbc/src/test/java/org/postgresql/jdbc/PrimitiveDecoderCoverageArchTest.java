/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.jdbc.codec.DomainCodec;
import org.postgresql.jdbc.codec.FallbackCodec;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Guards that every codec advertising an optional primitive-decode capability
 * ({@link PrimitiveBinaryDecoder} or {@link PrimitiveTextDecoder}) is pinned by a canonical OID in
 * {@link CodecRegistry}, so the getter-consistency fuzzer -- which enumerates its codec list from the
 * registry ({@code CodecFuzzSupport.primitiveDecoderTypes}) -- exercises it automatically. A new
 * primitive-decoder codec that nobody pins by OID is invisible to that data-driven enumeration; this
 * test surfaces it as a failure rather than letting its accessors go unfuzzed.
 *
 * <p>Two codecs are acknowledged as deliberately unpinned; both are still exercised by the
 * getter-consistency fuzzer, just not via an OID pin. {@link FallbackCodec} is the generic last-resort
 * handler for unknown types, so it has no canonical OID; the fuzzer reaches it through a synthetic
 * unknown type ({@code CodecFuzzSupport.consistencyContext}). {@link DomainCodec} forwards the primitive
 * accessors to its base type's codec; a domain has an installation-dependent OID and is registered by
 * name, and its forwarding is covered through the base type (see
 * {@code PrimitiveCapabilityFuzzTest.domainOver*}). The check is bidirectional -- if either ever gains
 * an OID pin, the acknowledgement is stale and the test fails, mirroring {@link
 * BuiltinTypeCodecCoverageTest}.
 */
class PrimitiveDecoderCoverageArchTest {

  private static final JavaClasses CODEC_CLASSES = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("org.postgresql.jdbc.codec");

  /**
   * Codecs that advertise a primitive-decode capability yet are deliberately not pinned by a canonical
   * OID: the generic fallback and the delegating domain codec (see the class javadoc).
   */
  private static final Set<Class<?>> ACKNOWLEDGED_UNPINNED =
      new LinkedHashSet<>(Arrays.asList(FallbackCodec.class, DomainCodec.class));

  @Test
  void everyPrimitiveDecoderCodecIsPinnedByOid() {
    Set<Class<?>> pinned = new HashSet<>();
    for (Codec codec : new CodecRegistry().builtinCodecsByOid().values()) {
      pinned.add(codec.getClass());
    }

    // Concrete codec classes advertising a primitive-decode capability that no canonical OID pins.
    Set<String> unpinned = new TreeSet<>();
    for (JavaClass javaClass : CODEC_CLASSES) {
      boolean primitiveDecoder = javaClass.isAssignableTo(PrimitiveBinaryDecoder.class)
          || javaClass.isAssignableTo(PrimitiveTextDecoder.class);
      if (!primitiveDecoder
          || javaClass.isInterface()
          || javaClass.getModifiers().contains(JavaModifier.ABSTRACT)) {
        continue;
      }
      Class<?> codecClass = javaClass.reflect();
      if (ACKNOWLEDGED_UNPINNED.contains(codecClass)) {
        continue;
      }
      if (!pinned.contains(codecClass)) {
        unpinned.add(codecClass.getSimpleName());
      }
    }

    assertEquals(emptyList(), new ArrayList<>(unpinned),
        "Codecs implementing PrimitiveBinaryDecoder/PrimitiveTextDecoder that no canonical OID pins in "
            + "CodecRegistry.registerBuiltinOids(), so CodecFuzzSupport.primitiveDecoderTypes() never "
            + "enumerates them and the getter-consistency fuzzer never exercises their accessors. Pin "
            + "the type by OID, or -- if it is a deliberately unpinned generic/delegating handler -- add "
            + "it to ACKNOWLEDGED_UNPINNED in this test.");

    // Bidirectional: an acknowledged codec that later gains an OID pin is enumerated and fuzzed
    // automatically, so its acknowledgement is stale and must be removed.
    Set<String> staleAcknowledged = new TreeSet<>();
    for (Class<?> codecClass : ACKNOWLEDGED_UNPINNED) {
      if (pinned.contains(codecClass)) {
        staleAcknowledged.add(codecClass.getSimpleName());
      }
    }
    assertEquals(emptyList(), new ArrayList<>(staleAcknowledged),
        "Codecs listed in ACKNOWLEDGED_UNPINNED that are now pinned by a canonical OID, so they are "
            + "enumerated and fuzzed automatically. Remove them from the acknowledged set.");
  }
}
