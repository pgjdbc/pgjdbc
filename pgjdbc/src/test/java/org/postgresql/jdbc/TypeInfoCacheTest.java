/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.Oid;
import org.postgresql.core.TypeInfo;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class TypeInfoCacheTest {

  // Property that enables generateBaseTypes and, optionally, names an output file.
  // build-logic.test-base.gradle.kts forwards any pgjdbc.* property to the test JVM, whether it
  // was given as a Gradle project property (-P, the idiomatic form) or a JVM system property (-D).
  static final String GENERATE_PROPERTY = "pgjdbc.test.TypeInfoCacheTest.generateBaseTypes";

  /**
   * {@link TypeInfo#backendCanSendBinary(PgType)} must recurse into element, field and
   * base types: a container's own {@code typsend} (such as {@code array_send})
   * does not imply its contents can be sent in binary. {@code aclitem} is the
   * canonical type with no binary send.
   */
  @Test
  void backendCanSendBinaryRecursesIntoComponents() throws Exception {
    Connection con = TestUtil.openDB();
    try {
      TypeInfo ti = con.unwrap(PgConnection.class).getTypeInfo();

      // Scalars and arrays backed by a real binary send.
      assertTrue(ti.backendCanSendBinary(ti.getPgTypeByOid(Oid.INT4)), "int4");
      assertTrue(ti.backendCanSendBinary(ti.getPgTypeByOid(Oid.INT4_ARRAY)), "int4[]");
      // Anonymous record is optimistic — its field types are unknown from the catalog.
      assertTrue(ti.backendCanSendBinary(ti.getPgTypeByOid(Oid.RECORD)), "record");

      // aclitem has no binary send; _aclitem carries array_send, but the recursion
      // into the element must still report the array as not binary-send capable.
      PgType aclitem = ti.getPgTypeByPgName("aclitem");
      assertFalse(ti.backendCanSendBinary(aclitem), "aclitem");
      assertFalse(ti.backendCanSendBinary(ti.getPgTypeByOid(aclitem.getArrayOid())), "aclitem[]");

      // A named composite is binary-send capable only when every field type is.
      TestUtil.createCompositeType(con, "binsend_ok", "a int4, b text");
      TestUtil.createCompositeType(con, "binsend_bad", "a int4, b aclitem");
      try {
        assertTrue(ti.backendCanSendBinary(ti.getPgTypeByPgName("binsend_ok")),
            "composite of binary-send types");
        assertFalse(ti.backendCanSendBinary(ti.getPgTypeByPgName("binsend_bad")),
            "composite with an aclitem field");
      } finally {
        TestUtil.dropType(con, "binsend_ok");
        TestUtil.dropType(con, "binsend_bad");
      }
    } finally {
      TestUtil.closeDB(con);
    }
  }

  /**
   * {@link TypeInfo#backendCanReceiveBinary(PgType)} is the send-direction mirror:
   * a type can be sent in binary only if the server has a binary input
   * ({@code typreceive}) for it, recursing into element, field and base types.
   * Built-in scalar types have symmetric {@code typsend}/{@code typreceive}, so the
   * recursion is what guards custom composites/arrays whose element lacks
   * {@code typreceive}; {@code aclitem} (no binary I/O at all) stands in here.
   */
  @Test
  void backendCanReceiveBinaryRecursesIntoComponents() throws Exception {
    Connection con = TestUtil.openDB();
    try {
      TypeInfo ti = con.unwrap(PgConnection.class).getTypeInfo();

      assertTrue(ti.backendCanReceiveBinary(ti.getPgTypeByOid(Oid.INT4)), "int4");
      assertTrue(ti.backendCanReceiveBinary(ti.getPgTypeByOid(Oid.INT4_ARRAY)), "int4[]");
      assertTrue(ti.backendCanReceiveBinary(ti.getPgTypeByOid(Oid.RECORD)), "record");

      PgType aclitem = ti.getPgTypeByPgName("aclitem");
      assertFalse(ti.backendCanReceiveBinary(aclitem), "aclitem has no typreceive");
      assertFalse(ti.backendCanReceiveBinary(ti.getPgTypeByOid(aclitem.getArrayOid())),
          "aclitem[] inherits the element's lack of typreceive");

      TestUtil.createCompositeType(con, "binrecv_ok", "a int4, b text");
      TestUtil.createCompositeType(con, "binrecv_bad", "a int4, b aclitem");
      try {
        assertTrue(ti.backendCanReceiveBinary(ti.getPgTypeByPgName("binrecv_ok")),
            "composite of binary-receivable types");
        assertFalse(ti.backendCanReceiveBinary(ti.getPgTypeByPgName("binrecv_bad")),
            "composite with an aclitem field");
      } finally {
        TestUtil.dropType(con, "binrecv_ok");
        TestUtil.dropType(con, "binrecv_bad");
      }
    } finally {
      TestUtil.closeDB(con);
    }
  }

  /**
   * {@link TypeInfo#driverCanReceiveBinary(PgType)} gates binary receive on the driver actually
   * having a binary decoder, recursing into element/field types. A text-only codec
   * ({@code circle}/{@code line}/{@code lseg}/{@code path}) is refused even though the server can
   * send those in binary; a text-send type ({@code refcursor}) is accepted, because
   * {@code TextLikeCodec} reads its binary wire, which is the charset text.
   */
  @Test
  void driverCanReceiveBinaryMatchesCodecCapability() throws Exception {
    Connection con = TestUtil.openDB();
    try {
      TypeInfo ti = con.unwrap(PgConnection.class).getTypeInfo();

      // Types with a real binary codec in the driver.
      assertTrue(ti.driverCanReceiveBinary(ti.getPgTypeByOid(Oid.INT4)), "int4");
      assertTrue(ti.driverCanReceiveBinary(ti.getPgTypeByOid(Oid.INT4_ARRAY)), "int4[]");
      assertTrue(ti.driverCanReceiveBinary(ti.getPgTypeByOid(Oid.POINT)), "point");
      assertTrue(ti.driverCanReceiveBinary(ti.getPgTypeByOid(Oid.RECORD)), "record");

      // refcursor has no dedicated codec, but TextLikeCodec reads its text-send binary wire, so it
      // is binary-decodable and thus eligible for binary receive.
      assertTrue(ti.driverCanReceiveBinary(ti.getPgTypeByOid(Oid.REFCURSOR)),
          "refcursor: text-send binary is decodable via TextLikeCodec");

      // Geometric types whose codec is text-only: server can send them binary, but
      // the driver cannot decode it, so binary receive must be refused.
      PgType circle = ti.getPgTypeByOid(Oid.CIRCLE);
      assertTrue(ti.backendCanSendBinary(circle), "circle has circle_send");
      assertFalse(ti.driverCanReceiveBinary(circle), "circle codec is text-only");
      assertFalse(ti.driverCanReceiveBinary(ti.getPgTypeByOid(Oid.CIRCLE_ARRAY)),
          "circle[] inherits the element's lack of a binary codec");
    } finally {
      TestUtil.closeDB(con);
    }
  }

  /**
   * {@code binaryTransferDisable} is recursive: opting a type out also keeps any
   * array, composite or domain that contains it in text, since a column has a
   * single transfer format for the whole value.
   */
  @Test
  void binaryReceiveDisableIsRecursive() throws Exception {
    Connection con = TestUtil.openDB();
    try {
      TypeInfo ti = con.unwrap(PgConnection.class).getTypeInfo();
      ti.setBinaryReceiveDisabledOids(java.util.Collections.singleton(Oid.UUID));

      assertTrue(ti.isBinaryReceiveDisabled(ti.getPgTypeByOid(Oid.UUID)), "uuid");
      // _uuid itself is not in the set, but it inherits the element's opt-out.
      assertTrue(ti.isBinaryReceiveDisabled(ti.getPgTypeByOid(Oid.UUID_ARRAY)), "uuid[]");

      TestUtil.createCompositeType(con, "disable_rec", "a int4, b uuid");
      try {
        assertTrue(ti.isBinaryReceiveDisabled(ti.getPgTypeByPgName("disable_rec")),
            "composite with a uuid field");
      } finally {
        TestUtil.dropType(con, "disable_rec");
      }

      // Unrelated types are untouched.
      assertFalse(ti.isBinaryReceiveDisabled(ti.getPgTypeByOid(Oid.INT4)), "int4");
      assertFalse(ti.isBinaryReceiveDisabled(ti.getPgTypeByOid(Oid.INT4_ARRAY)), "int4[]");
    } finally {
      TestUtil.closeDB(con);
    }
  }

  /**
   * Regenerates {@link BaseTypes} from a live server and prints the resulting Java source. The
   * output is the whole {@code BaseTypes.java} file (license header, package, imports and the
   * {@code BASE_TYPES} array); overwrite the file with it rather than editing the array in place.
   * {@link TypeInfoCache} seeds its offline catalog from that array.
   *
   * <p>This is a code generator rather than an assertion, so it stays off by default. Enable it
   * with the {@value #GENERATE_PROPERTY} Gradle project property:</p>
   *
   * <pre>{@code
   * ./gradlew :postgresql:test --tests '*TypeInfoCacheTest.generateBaseTypes' \
   *     -Ppgjdbc.test.TypeInfoCacheTest.generateBaseTypes
   * }</pre>
   *
   * <p>Gradle is configured to echo test stdout to the console, so the generated lines appear in
   * the build output and no file is needed. To capture them in a file instead, give the property a
   * path as its value (anything other than {@code true} is treated as a file name):</p>
   *
   * <pre>{@code
   * ./gradlew :postgresql:test --tests '*TypeInfoCacheTest.generateBaseTypes' \
   *     -Ppgjdbc.test.TypeInfoCacheTest.generateBaseTypes=/tmp/base-types.txt
   * }</pre>
   *
   * <p>A {@code -D} system property of the same name works too; the build forwards both forms to
   * the test JVM.</p>
   */
  @Test
  @EnabledIfSystemProperty(named = GENERATE_PROPERTY, matches = ".*",
      disabledReason = "Set -P" + GENERATE_PROPERTY + " to regenerate TypeInfoCache.BASE_TYPES")
  public void generateBaseTypes() throws Exception {
    StringBuilder rows = new StringBuilder();
    try (Connection con = TestUtil.openDB();
        PreparedStatement ps = con.prepareStatement(
            // Constructor: PgType(typeName, fullName, oid, typtype, typcategory, typtypmod,
            //                     typsend, typreceive, typelem, arrayOid, typbasetype)
            "with pgjdbc_types(typname) as (\n"
                + "    select * from (values\n"
                + "        ('bit'), ('bool'), ('box'), ('bpchar'), ('bytea'), ('circle'), ('date'),\n"
                + "        ('float4'), ('float8'), ('int2'), ('int4'), ('int8'), ('interval'),\n"
                + "        ('json'), ('jsonb'), ('line'), ('lseg'), ('money'), ('name'), ('numeric'),\n"
                + "        ('oid'), ('path'), ('point'), ('polygon'), ('record'), ('refcursor'), ('text'),\n"
                + "        ('time'), ('timestamp'), ('timestamptz'), ('timetz'), ('uuid'), ('varbit'),\n"
                + "        ('varchar'), ('xml')\n"
                + "    ) as t(typname)\n"
                + ")\n"
                + "select\n"
                + "    'new PgType(new ObjectName(\"'||nspname||'\", \"'||typname||'\"), '\n"
                + "    ||'\"'||fullname||'\", '\n"
                + "    ||oid_const||', '\n"
                + "    ||''''||typtype::text||''''||', '\n"
                + "    ||''''||typcategory::text||''''||', '\n"
                + "    ||typtypmod||', '\n"
                + "    ||'\"'||typsend::text||'\", '\n"
                + "    ||'\"'||typreceive::text||'\", '\n"
                + "    ||elem_const||', '\n"
                + "    ||array_const||', '\n"
                + "    ||'Oid.UNSPECIFIED),'\n"
                + "from (\n"
                + "    -- base type row\n"
                + "    select ns.nspname, bt.typname, pg_catalog.format_type(bt.oid, null) as fullname,\n"
                + "           'Oid.'||upper(bt.typname) as oid_const,\n"
                + "           bt.typtype, bt.typcategory, bt.typtypmod,\n"
                + "           coalesce('Oid.'||upper(et.typname), 'Oid.UNSPECIFIED') as elem_const,\n"
                + "           case when bt.typarray <> 0\n"
                + "                then 'Oid.'||upper(bt.typname)||'_ARRAY'\n"
                + "                else 'Oid.UNSPECIFIED' end as array_const,\n"
                + "           bt.typsend, bt.typreceive,\n"
                + "           bt.typname as sort1, 1 as sort2\n"
                + "    from pgjdbc_types pt\n"
                + "    join pg_catalog.pg_type bt on (bt.typname = pt.typname)\n"
                + "    join pg_catalog.pg_namespace ns on (ns.oid = bt.typnamespace and ns.nspname = 'pg_catalog')\n"
                + "    left join pg_catalog.pg_type et on (et.oid = bt.typelem and bt.typelem <> 0)\n"
                + "\n"
                + "    union all\n"
                + "\n"
                + "    -- array type row\n"
                + "    select ns.nspname, at.typname, pg_catalog.format_type(at.oid, null) as fullname,\n"
                + "           'Oid.'||upper(bt.typname)||'_ARRAY' as oid_const,\n"
                + "           at.typtype, at.typcategory, at.typtypmod,\n"
                + "           'Oid.'||upper(bt.typname) as elem_const,\n"
                + "           case when at.typarray <> 0\n"
                + "                then at.typarray::text\n"
                + "                else 'Oid.UNSPECIFIED' end as array_const,\n"
                + "           at.typsend, at.typreceive,\n"
                + "           bt.typname as sort1, 2 as sort2\n"
                + "    from pgjdbc_types pt\n"
                + "    join pg_catalog.pg_type bt on (bt.typname = pt.typname)\n"
                + "    join pg_catalog.pg_namespace ns on (ns.oid = bt.typnamespace and ns.nspname = 'pg_catalog')\n"
                + "    join pg_catalog.pg_type at on (at.oid = bt.typarray and bt.typarray <> 0)\n"
                + ") as data\n"
                + "order by sort1, sort2");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        // Each row is a bare `new PgType(...),`; indent it as an array element.
        rows.append("      ").append(rs.getString(1)).append('\n');
      }
    }

    String out = renderBaseTypesFile(rows.toString());

    String target = System.getProperty(GENERATE_PROPERTY, "");
    if (target.isEmpty() || "true".equalsIgnoreCase(target)) {
      // Gradle echoes test stdout to the console, so printing is enough.
      System.out.print(out);
    } else {
      try (PrintWriter writer = new PrintWriter(
          Files.newBufferedWriter(Paths.get(target), StandardCharsets.UTF_8))) {
        writer.print(out);
      }
    }
  }

  /**
   * Wraps the generated {@code new PgType(...)} rows in the full text of {@code BaseTypes.java} so
   * the output can overwrite that file verbatim, keeping the generated array out of the
   * hand-maintained {@link TypeInfoCache}.
   */
  private static String renderBaseTypesFile(String rows) {
    return "/*\n"
        + " * Copyright (c) 2026, PostgreSQL Global Development Group\n"
        + " * See the LICENSE file in the project root for more information.\n"
        + " */\n"
        + "\n"
        + "package org.postgresql.jdbc;\n"
        + "\n"
        + "import org.postgresql.core.Oid;\n"
        + "\n"
        + "/**\n"
        + " * The driver's static catalog of built-in {@code pg_catalog} types, seeded into every\n"
        + " * {@link TypeInfoCache} and used by the connectionless (offline) codec context to resolve built-in\n"
        + " * types without a live type cache.\n"
        + " *\n"
        + " * <p><strong>Generated file — do not edit by hand.</strong> Regenerate it from a live server with:</p>\n"
        + " *\n"
        + " * <pre>{@code\n"
        + " * ./gradlew :postgresql:test --tests '*TypeInfoCacheTest.generateBaseTypes' \\\n"
        + " *     -Ppgjdbc.test.TypeInfoCacheTest.generateBaseTypes=pgjdbc/src/main/java/org/postgresql/jdbc/BaseTypes.java\n"
        + " * }</pre>\n"
        + " *\n"
        + " * <p>The generator ({@code org.postgresql.jdbc.TypeInfoCacheTest.generateBaseTypes}) emits this whole\n"
        + " * file; overwrite it rather than editing {@link #BASE_TYPES} in place. {@link TypeInfoCache} seeds its\n"
        + " * offline catalog from that array, and that test also documents the {@code pg_type} column list the\n"
        + " * type name set is drawn from.</p>\n"
        + " */\n"
        + "final class BaseTypes {\n"
        + "\n"
        + "  private BaseTypes() {\n"
        + "  }\n"
        + "\n"
        + "  // Constructor: PgType(typeName, fullName, oid, typtype, typcategory, typtypmod, typsend, typreceive, typelem, arrayOid, typbasetype)\n"
        + "  static final PgType[] BASE_TYPES = {\n"
        + rows
        + "  };\n"
        + "}\n";
  }
}
