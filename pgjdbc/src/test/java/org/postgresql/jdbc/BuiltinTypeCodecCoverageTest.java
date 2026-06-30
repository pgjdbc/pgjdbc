/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.jdbc.codec.FallbackCodec;
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
 * <p>Every built-in scalar type the driver can decode resolves to a real codec; the rest fall back
 * to {@link FallbackCodec} and are received as a text {@link org.postgresql.util.PGobject}. This
 * test enumerates the built-in scalar types of the connected server that have a binary wire form (a
 * {@code typsend} or {@code typreceive}) and asserts that each one either resolves to a real codec
 * or is named in one of the acknowledged sets below ({@link #INTERNAL_TEXT},
 * {@link #IDENTIFIER_TEXT}, {@link #CODEC_BACKLOG}). A text-only type cannot use a binary codec, so
 * it is out of scope. Running against a newer PostgreSQL (a beta or {@code HEAD} build) then
 * surfaces a freshly added type as a failure, which is the cue to add a codec for it.</p>
 *
 * <p>The check is bidirectional: a listed type that later gains a real codec also fails, so the
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
   * Object-identifier and name types where the text form is the value worth having and the binary
   * wire adds nothing. A {@code reg*} value travels in binary as the bare object OID
   * ({@code regclass_send('pg_class')} is the oid {@code 1259}, not the name {@code pg_class}),
   * while its text form is the resolved name; {@code refcursor} is {@code textsend}/{@code textrecv}
   * in both directions, so it is literally text under another type name. Decoding these in binary
   * would only expose the OID, so the driver keeps them as a text {@code PGobject}. At most a
   * text-only codec returning a {@code String} would help here -- never a binary one.
   */
  private static final Set<String> IDENTIFIER_TEXT = new HashSet<>(Arrays.asList(
      "refcursor",
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

  @Test
  void builtinScalarTypesResolveToACodecOrAreListed() throws Exception {
    Connection con = TestUtil.openDB();
    try {
      TypeInfoCache typeInfo = (TypeInfoCache) con.unwrap(PgConnection.class).getTypeInfo();
      CodecRegistry registry = typeInfo.getCodecRegistry();

      // Types with no codec that nothing acknowledges -- usually a newer server added a type the
      // driver does not decode yet. Sorted for a stable, readable failure message.
      Set<String> missingCodec = new TreeSet<>();
      // Listed types that now resolve to a real codec, so their entry above is stale.
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
          boolean hasCodec = registry.getByOid(oid, typeInfo.getPgTypeByOid(oid)) != FallbackCodec.INSTANCE;
          boolean listed = INTERNAL_TEXT.contains(typname)
              || IDENTIFIER_TEXT.contains(typname)
              || CODEC_BACKLOG.contains(typname);
          if (!hasCodec && !listed) {
            missingCodec.add(typname + " (oid " + oid + ")");
          } else if (hasCodec && listed) {
            staleListing.add(typname);
          }
        }
      }

      assertEquals(emptyList(), new ArrayList<>(missingCodec),
          "Built-in scalar types that resolve to FallbackCodec (received as a text PGobject). Add a "
              + "codec, or -- if the type should stay text -- list it in INTERNAL_TEXT, "
              + "IDENTIFIER_TEXT or CODEC_BACKLOG. A new entry here usually means a newer PostgreSQL "
              + "added a type the driver does not decode yet.");
      assertEquals(emptyList(), new ArrayList<>(staleListing),
          "Types listed in INTERNAL_TEXT, IDENTIFIER_TEXT or CODEC_BACKLOG that now resolve to a "
              + "real codec. Remove them from the list.");
    } finally {
      TestUtil.closeDB(con);
    }
  }
}
