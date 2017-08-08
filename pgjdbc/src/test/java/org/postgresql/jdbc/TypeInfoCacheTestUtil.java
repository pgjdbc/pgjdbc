/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assume.assumeTrue;

import org.postgresql.core.Oid;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

class TypeInfoCacheTestUtil {

  /**
   * Helper function to make writing strings with internal quotes easier. Rather than escape all of
   * the quotes, use % instead and replace them all before use.
   */
  static String quotify(String s) {
    return s.replaceAll("%", "\"");
  }

  enum PgTypeStructType {
    ELEMENT(true), ARRAY(false);
    final boolean isElement;

    PgTypeStructType(boolean isElement) {
      this.isElement = isElement;
    }
  }

  /**
   * A container for type information independent of TypeInfoCache implementation.
   * As it's used to test TypeInfoCache, it can't really be dependent on it.
   */
  public static class PgTypeStruct {
    static final String ARRAY_SUFFIX = "[]";
    final String nspname;
    final String typname;
    final PgTypeStructType type;
    final PgTypeStruct searchPathException;

    PgTypeStruct(String nspname, String typname) {
      this(nspname, typname, PgTypeStructType.ELEMENT);
    }

    PgTypeStruct(String nspname, String typname, PgTypeStructType type) {
      this.nspname = nspname;
      this.typname = typname;
      this.type = type;
      this.searchPathException = null;

    }

    static PgTypeStruct createQuotified(String nspname, String typname) {
      return new PgTypeStruct(quotify(nspname), quotify(typname));
    }

    static PgTypeStruct createArrayType(PgTypeStruct type) {
      if (type.equals(UNSPECIFIED)) {
        return UNSPECIFIED;
      } else if (type.equals(UNPARSEABLE)) {
        return UNPARSEABLE;
      }

      return new PgTypeStruct(type.nspname, type.typname, PgTypeStructType.ARRAY);
    }

    /**
     * For testing cases where TypeInfoCache's legacy search path behavior is important. See
     * getPGType(String) for details
     */
    @SuppressWarnings("SameParameterValue")
    static PgTypeStruct createWithSearchPathException(String nspname, String typname,
        String exceptionNspname) {
      return new PgTypeStruct(nspname, typname, exceptionNspname);
    }

    private PgTypeStruct(String nspname, String typname, String searchPathException) {
      this.nspname = nspname;
      this.typname = typname;
      this.type = PgTypeStructType.ELEMENT;
      this.searchPathException = new PgTypeStruct(searchPathException, this.typname, this.type);
    }

    boolean hasSearchPathException() {
      return this.searchPathException != null;
    }

    @Override
    public String toString() {
      if (this.equals(UNPARSEABLE)) {
        return "-UNPARSEABLE-";
      }
      if (this.equals(UNSPECIFIED)) {
        return "-UNSPECIFIED-";
      }
      return "(" + nspname + "," + typname + ")" + (type.isElement ? "" : "[]");
    }

    String name() {
      //noinspection ConstantConditions
      return (nspname.equals("pg_catalog") ? typname :
          (nspname == null ? "" : '"' + nspname + "\".") + '"'
              + typname + '"') + (type.isElement ? "" : "[]");
    }

    static final PgTypeStruct UNPARSEABLE =
        new PgTypeStruct((String) "*unparseable*", "*unparseable*");
    static final PgTypeStruct UNSPECIFIED = new PgTypeStruct((String) null, null);

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      PgTypeStruct that = (PgTypeStruct) o;

      return (nspname != null ? nspname.equals(that.nspname) : that.nspname == null) && (
          typname != null ? typname.equals(that.typname) : that.typname == null)
          && type == that.type;
    }

    @Override
    public int hashCode() {
      int result = nspname != null ? nspname.hashCode() : 0;
      result = 31 * result + (typname != null ? typname.hashCode() : 0);
      result = 31 * result + (type != null ? type.hashCode() : 0);
      return result;
    }
  }

  /**
   * Testing TypeInfoCache often relies on creating a bunch of types and referencing them and others
   * that are part of core PostgreSQL implementation. In some ways it's a limited TypeInfoCache.
   *
   * Given a list of PgTypeStruct instances, a PgTypeset instance creates those that are user defined
   * (as well as any necessary schema). It also stores their oids and those of any core PostgreSQL
   * types included in the list so the types and oids can be used to reference each other in tests.
   *
   * The intended pattern of use:
   *<pre>
   *{@code
   * // setUp
   * PgTypeSet typeSet = PgTypeSet.createAndInstall(conn, types);
   *
   * // do work
   * int myOid = typeSet.oid(type);
   * PgTypeStruct myType = typeSet.type(myOid);
   * assertEquals(type, myType);
   *
   * // tearDown
   * typeSet.uninstall(conn);
   * }
   *</pre>
   */
  static class PgTypeSet {
    private final HashMap<Integer, PgTypeStruct> oidToType = new HashMap<Integer, PgTypeStruct>();
    private final HashMap<PgTypeStruct, Integer> typeToOid = new HashMap<PgTypeStruct, Integer>();
    private final List<String> quotedTypeNames = new ArrayList<String>();
    private final HashSet<String> quotedSchemaNames = new HashSet<String>();
    private final HashSet<PgTypeStruct> userDefinedTypeArrayTypes = new HashSet<PgTypeStruct>();

    private static final String quoteSql = "SELECT quote_ident(?), quote_ident(?)";
    private static final String oidSql = "SELECT t.oid, COALESCE(arr.oid, 0)"
        + " FROM pg_catalog.pg_type t"
        + " JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace"
        + " LEFT JOIN pg_catalog.pg_type arr"
        + " ON (arr.typelem, arr.typinput) = (t.oid, 'array_in'::regproc)"
        + " WHERE (n.nspname, t.typname) = (?, ?)";

    private final List<PgTypeStruct> types;

    PgTypeSet(List<PgTypeStruct> types) {
      this.types = types;
    }

    void install(Connection conn) throws SQLException {
      oidToType.put(Oid.UNSPECIFIED, PgTypeStruct.UNSPECIFIED);

      PreparedStatement quoteStatement = conn.prepareStatement(quoteSql);
      PreparedStatement oidStatement = conn.prepareStatement(oidSql);
      ResultSet rs;
      int oid;
      int arrayOid;
      PgTypeStruct arrayType;

      for (PgTypeStruct type : types) {
        quoteStatement.setString(1, type.nspname);
        quoteStatement.setString(2, type.typname);
        rs = quoteStatement.executeQuery();
        rs.next();
        String quotedSchema = rs.getString(1);
        String quotedType = rs.getString(2);
        String quotedName = quotedSchema + "." + quotedType;
        arrayType = new PgTypeStruct(type.nspname, type.typname, PgTypeStructType.ARRAY);

        if (!quotedSchemaNames.contains(quotedSchema)
            && !type.nspname.equals("public")
            && !type.nspname.equals("pg_catalog")) {
          TestUtil.createSchema(conn, quotedSchema);
          quotedSchemaNames.add(quotedSchema);
        }

        if (!"pg_catalog".equals(type.nspname)) {
          if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v8_3)) {
            TestUtil.createEnumType(conn, quotedName, "'black'");
          } else {
            TestUtil.createCompositeType(conn, quotedName, "color text");
          }
          userDefinedTypeArrayTypes.add(arrayType);
        }
        oidStatement.setString(1, type.nspname);
        oidStatement.setString(2, type.typname);
        rs = oidStatement.executeQuery();
        rs.next();
        oid = (int) rs.getLong(1);
        arrayOid = (int) rs.getLong(2);
        oidToType.put(oid, type);
        typeToOid.put(type, oid);

        /*
        Arrays for user-defined types were introduced in 8.3. Prior to that, there will be no
        pg_type row for the array type. We don't want to overwrite UNSPECIFIED with some other type.
        */
        if (isSupportedType(conn, arrayType)) {
          oidToType.put(arrayOid, arrayType);
        }
        typeToOid.put(arrayType, arrayOid);

        quotedTypeNames.add(quotedName);
      }
    }

    static PgTypeSet createAndInstall(List<PgTypeStruct> types, Connection conn)
        throws SQLException {
      PgTypeSet typeSet = new PgTypeSet(types);
      typeSet.install(conn);
      return typeSet;
    }

    PgTypeStruct type(int oid) {
      return oidToType.get(oid);
    }

    int oid(PgTypeStruct type) {
      return typeToOid.get(type);
    }

    void uninstall(Connection conn) throws SQLException {
      for (String quotedName : quotedTypeNames) {
        TestUtil.dropType(conn, quotedName);
      }
      for (String quotedName : quotedSchemaNames) {
        TestUtil.dropSchema(conn, quotedName);
      }
    }

    private boolean isSupportedType(Connection conn, PgTypeStruct type) throws SQLException {
      return TestUtil.haveMinimumServerVersion(conn, ServerVersion.v8_3)
          || !userDefinedTypeArrayTypes.contains(type);
    }

    void assumeSupportedType(Connection conn, PgTypeStruct type) throws SQLException {
      assumeTrue("arrays of user-defined types require version PostgreSQL 8.3 or later",
          isSupportedType(conn, type));
    }
  }
}
