/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.Codec;
import org.postgresql.jdbc.codec.FallbackCodec;
import org.postgresql.jdbc.codec.TextLikeCodec;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Guards codec coverage of the server's built-in scalar types against the live catalog.
 *
 * <p>Every built-in scalar type resolves either to a <em>dedicated</em> codec, or to a
 * <em>generic</em> handler -- {@link FallbackCodec} (a raw-text {@link org.postgresql.util.PGobject})
 * or {@link TextLikeCodec} (a charset-text {@code PGobject} for types whose {@code typsend} emits raw
 * text). This test enumerates the built-in scalar types of the connected server that have a binary
 * wire form (a {@code typsend} or {@code typreceive}) and asserts that each one either resolves to a
 * dedicated codec or is named in one of the acknowledged sets below ({@link #INTERNAL_TEXT},
 * {@link #IDENTIFIER_TEXT}, {@link #CODEC_BACKLOG}, {@link #TEXTLIKE}). A type handled only by a
 * generic handler must be acknowledged, so a freshly added built-in type -- on a beta or
 * {@code HEAD} build -- surfaces as a failure rather than being silently swept up by
 * {@code TextLikeCodec} or {@code FallbackCodec}; that is the cue to add a codec or list it. A
 * text-only type cannot use a binary codec, so it is out of scope.</p>
 *
 * <p>The check is bidirectional: a listed type that later gains a dedicated codec also fails, so the
 * lists cannot silently rot as codecs land.</p>
 */
class BuiltinTypeCodecCoverageTest {

  /**
   * Internal and catalog types a client almost never selects (BRIN/extended-statistics summaries,
   * snapshots, the raw node-tree text). They have a binary wire form but the driver keeps them on
   * {@link FallbackCodec} as a text {@code PGobject} on purpose -- no codec is planned. Purely
   * text-only internal types (such as {@code aclitem} and {@code gtsvector}) are filtered out by the
   * query and need no entry here.
   */
  private static final Set<String> INTERNAL_TEXT = new HashSet<>(Arrays.asList(
      "pg_node_tree",
      "pg_dependencies",
      "pg_ndistinct",
      "pg_mcv_list",
      "pg_brin_bloom_summary",
      "pg_brin_minmax_multi_summary",
      "pg_snapshot",
      "txid_snapshot"
  ));

  /**
   * Object-identifier types where the text form is the value worth having and the binary wire adds
   * nothing. A {@code reg*} value travels in binary as the bare object OID
   * ({@code regclass_send('pg_class')} is the oid {@code 1259}, not the name {@code pg_class}),
   * while its text form is the resolved name. Decoding these in binary would only expose the OID, so
   * the driver keeps them on {@link FallbackCodec} as a text {@code PGobject}; at most a text-only
   * {@code String} codec would help, never a binary one. ({@code refcursor}, also a name type, is
   * acknowledged in {@link #TEXTLIKE} instead: its {@code typsend} is {@code textsend}, so its binary
   * wire is the name text.)
   */
  private static final Set<String> IDENTIFIER_TEXT = new HashSet<>(Arrays.asList(
      "regclass",
      "regproc",
      "regprocedure",
      "regoper",
      "regoperator",
      "regtype",
      "regconfig",
      "regdictionary",
      "regnamespace",
      "regrole",
      "regcollation"
  ));

  /**
   * User-facing types with a real binary value the driver should decode -- a network address, a
   * MAC, a full-text vector or query, an LSN, a jsonpath (version byte plus text, like ltree), a
   * transaction, command or tuple id. Each is a binary-codec candidate; drop its entry here when a
   * codec lands (the bidirectional check enforces that).
   */
  private static final Set<String> CODEC_BACKLOG = new HashSet<>(Arrays.asList(
      "inet",
      "cidr",
      "macaddr",
      "macaddr8",
      "tsvector",
      "tsquery",
      "pg_lsn",
      "jsonpath",
      "xid",
      "xid8",
      "cid",
      "tid"
  ));

  /**
   * Types handled by the generic {@link TextLikeCodec}: their {@code typsend} emits raw charset text
   * ({@code textsend}/{@code varcharsend}/{@code bpcharsend}/{@code namesend}), so the binary wire is
   * the text and they decode to a {@code PGobject} either way. They have no dedicated codec; a new
   * built-in text-send type surfaces here for a decision -- keep the generic handling, or add a
   * codec. (Among built-ins only {@code refcursor} lands here; {@code text}/{@code varchar}/
   * {@code name}/{@code bpchar} use a text send too but resolve to their own codecs first.)
   */
  private static final Set<String> TEXTLIKE = new HashSet<>(Arrays.asList(
      "refcursor"
  ));

  @Test
  void builtinScalarTypesResolveToACodecOrAreListed() throws Exception {
    Connection con = TestUtil.openDB();
    try {
      TypeInfoCache typeInfo = (TypeInfoCache) con.unwrap(PgConnection.class).getTypeInfo();
      CodecRegistry registry = typeInfo.getCodecRegistry();

      // Types resting on a generic handler that nothing acknowledges -- usually a newer server added
      // a type the driver does not decode yet. Sorted for a stable, readable failure message.
      Set<String> missingCodec = new TreeSet<>();
      // Listed types that now resolve to a dedicated codec, so their entry above is stale.
      Set<String> staleListing = new TreeSet<>();

      // Built-in (oid < FirstNormalObjectId = 16384, i.e. not a user or extension type) defined
      // scalar types, excluding arrays (typcategory 'A'). A type is in scope only if the server can
      // transfer it in binary at all -- it has a binary send or receive (typsend/typreceive); a
      // text-only type (aclitem, gtsvector) can never use a binary codec, so it is out of scope.
      // Pseudo-types (record, unknown, cstring, ...) are typtype 'p' and never reach a per-column
      // codec, so they are out too.
      String sql = "SELECT oid, typname FROM pg_catalog.pg_type "
          + "WHERE oid < 16384 AND typisdefined AND typtype = 'b' AND typcategory <> 'A' "
          + "AND (typsend <> 0 OR typreceive <> 0) "
          + "ORDER BY typname";
      try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
        while (rs.next()) {
          int oid = rs.getInt("oid");
          String typname = rs.getString("typname");
          Codec codec = registry.getByOid(oid, typeInfo.getPgTypeByOid(oid));
          // FallbackCodec and TextLikeCodec are generic handlers (both yield a text PGobject), not a
          // dedicated codec; types resting on either must be acknowledged in a set below.
          boolean dedicatedCodec = codec != FallbackCodec.INSTANCE && codec != TextLikeCodec.INSTANCE;
          boolean listed = INTERNAL_TEXT.contains(typname)
              || IDENTIFIER_TEXT.contains(typname)
              || CODEC_BACKLOG.contains(typname)
              || TEXTLIKE.contains(typname);
          if (!dedicatedCodec && !listed) {
            missingCodec.add(typname + " (oid " + oid + ")");
          } else if (dedicatedCodec && listed) {
            staleListing.add(typname);
          }
        }
      }

      assertEquals(emptyList(), new ArrayList<>(missingCodec),
          "Built-in scalar types resting on a generic handler (FallbackCodec or TextLikeCodec, both a "
              + "text PGobject) that nothing acknowledges. Add a dedicated codec, or list the type in "
              + "INTERNAL_TEXT, IDENTIFIER_TEXT, CODEC_BACKLOG or TEXTLIKE. A new entry here usually "
              + "means a newer PostgreSQL added a type the driver does not decode yet.");
      assertEquals(emptyList(), new ArrayList<>(staleListing),
          "Types listed in INTERNAL_TEXT, IDENTIFIER_TEXT, CODEC_BACKLOG or TEXTLIKE that now resolve "
              + "to a dedicated codec. Remove them from the list.");
    } finally {
      TestUtil.closeDB(con);
    }
  }
}
