/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.Format;
import org.postgresql.core.Oid;

import java.util.Locale;

/**
 * The single naming function for the generated scalar decode-robustness targets, shared by every engine's
 * source generator and seed generator. Each engine derives a target's identity from the same
 * {@code (oid, format)} pair, so a generated method name and the seed directory an engine resolves for it
 * never drift.
 *
 * <p>A name is the PostgreSQL type name of the OID (for example {@code int4}, {@code numeric}) plus a
 * format suffix: {@code _binary} or {@code _text}. The type name comes from {@link Oid#toString(int)},
 * which is keyed by OID, so the two OIDs
 * that share {@code BitCodec} still get distinct names ({@code bit} for 1560, {@code varbit} for 1562) --
 * unlike {@link org.postgresql.api.codec.Codec#getPrimaryTypeName()}, whose value is a single name-resolution key
 * both OIDs report as {@code bit}. An OID with no {@code Oid} constant falls back to {@code oid<n>}.
 */
public final class ScalarDecodeRobustnessNaming {

  private ScalarDecodeRobustnessNaming() {
  }

  /**
   * The stable {@code @FuzzTest} method name for a generated target.
   *
   * @param oid the built-in scalar OID
   * @param format the wire format the target decodes
   * @return the method name, for example {@code int4_binary}, {@code text_text}
   */
  public static String methodName(int oid, Format format) {
    String base = typeName(oid);
    return format == Format.TEXT ? base + "_text" : base + "_binary";
  }

  /** The PostgreSQL type name for {@code oid}, or {@code oid<n>} when no {@code Oid} constant names it. */
  public static String typeName(int oid) {
    String name = Oid.toString(oid);
    if (name.startsWith("<")) {
      // Oid.toString renders an unnamed OID as "<unknown:NNNN>"; keep a legal, stable stem instead.
      return "oid" + oid;
    }
    return sanitise(name.toLowerCase(Locale.ROOT));
  }

  // Maps a codec type name to a valid Java identifier: non-alphanumeric characters become '_', and a name
  // that does not start with a letter gains a 't' prefix so it stays a legal method-name stem.
  private static String sanitise(String typeName) {
    String cleaned = typeName.replaceAll("[^A-Za-z0-9]", "_");
    if (cleaned.isEmpty() || !Character.isLetter(cleaned.charAt(0))) {
      cleaned = "t" + cleaned;
    }
    return cleaned;
  }
}
