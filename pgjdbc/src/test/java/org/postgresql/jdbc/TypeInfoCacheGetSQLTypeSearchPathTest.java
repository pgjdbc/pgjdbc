/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.jdbc.TypeInfoCacheTestUtil.SQLType;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.assertSQLType;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.join;

import org.postgresql.core.TypeInfo;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;

@RunWith(Parameterized.class)
public class TypeInfoCacheGetSQLTypeSearchPathTest extends BaseTest4 {
  public static class Schema {
    static final Schema PUBLIC = new Schema("public");
    static final Schema PG_CATALOG = new Schema("pg_catalog");

    public final String name;

    Schema(String name) {
      this.name = name;
    }

    static String[] names(Schema[] schemas) {
      if (schemas == null) {
        return null;
      }
      Collection<String> coll = new ArrayList<String>();
      for (Schema s : schemas) {
        coll.add(s.name);
      }
      String[] names = new String[coll.size()];
      coll.toArray(names);
      return names;
    }

    @Override
    public String toString() {
      return name;
    }

    static Schema[] noSchemas() {
      return new Schema[0];
    }

    @SuppressWarnings("SameReturnValue")
    static Schema[] defaultSearchPath() {
      return null;
    }

  }

  private static final Schema V = new Schema("v");
  private static final Schema S = new Schema("s");

  private static final HashMap<Integer, Schema> SqlTypeSchemas = new HashMap<Integer, Schema>() {
    {
      put(Types.DISTINCT, Schema.PUBLIC);
      put(Types.STRUCT, S);
      put(Types.VARCHAR, V);
      put(Types.INTEGER, Schema.PG_CATALOG);
    }
  };

  private static final HashMap<Schema, SQLType> SchemaSqlTypes = new HashMap<Schema, SQLType>() {
    {
      put(V, SQLType.VARCHAR);
      put(S, SQLType.STRUCT);
      put(Schema.PUBLIC, SQLType.DISTINCT);
      put(Schema.PG_CATALOG, SQLType.INTEGER);
    }
  };

  @Parameterized.Parameters(name = "{0} → {1} ({2}); schemas {3}; path: {4}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> cases = new ArrayList<Object[]>();

    /*
    getSQLType doesn't return the PostgreSQL type it's matching, so trying to determine which
    underlying type is being selected. Here we're creating different custom type with the same name,
    each in its own schema.

     java.sql.Types |    type     | definition
    ----------------|-------------|-------------------------------------------
     Types.VARCHAR  | v.type      | CREATE TYPE v.type AS ENUM ('black')
     Types.STRUCT   | s.type      | CREATE TYPE s.type AS (v timestamptz)
     Types.DISTINCT | public.type | CREATE DOMAIN public.type AS int

    So, search_path will determine which java.sql.Types value is returned for unqualified type names.

    Each case is: schemas, types, search path schemas,
    and a map of type name strings to the schema the type name string is expected to match; null is no match
     */

    cases.add(new Object[]{
        Schema.noSchemas(),
        Schema.defaultSearchPath(),
        new HashMap<String, Schema>() {
          {
            put("type", null);
            put("TYPE", null);
            put("\"TYPE\"", null);
          }
        }
    });

    cases.add(new Object[]{
        new Schema[]{
            Schema.PUBLIC},
        Schema.defaultSearchPath(),
        new HashMap<String, Schema>() {
          {
            put("type", Schema.PUBLIC);
            put("public.type", Schema.PUBLIC);
          }
        }
    });

    cases.add(new Object[]{
        new Schema[]{
            S, Schema.PUBLIC},
        Schema.defaultSearchPath(),
        new HashMap<String, Schema>() {
          {
            put("type", Schema.PUBLIC);
            put("public.type", Schema.PUBLIC);
            put("s.type", S);
          }
        }
    });

    cases.add(new Object[]{
        new Schema[]{
            Schema.PUBLIC, S},
        Schema.defaultSearchPath(),
        new HashMap<String, Schema>() {
          {
            put("type", Schema.PUBLIC);
            put("public.type", Schema.PUBLIC);
            put("s.type", S);
          }
        }
    });

    cases.add(new Object[]{
        new Schema[]{
            S, Schema.PUBLIC},
        new Schema[]{
            S},
        new HashMap<String, Schema>() {
          {
            put("type", S);
            put("s.type", S);
            put("public.type", Schema.PUBLIC);
          }
        }
    });

    Collection<Object[]> params = new ArrayList<Object[]>();
    for (Object[] c : cases) {
      Schema[] schemas = (Schema[]) c[0];
      Schema[] searchPathSchemas = (Schema[]) c[1];
      @SuppressWarnings("unchecked")
      HashMap<String, Schema> nameStrings = (HashMap<String, Schema>) c[2];
      String searchPath = join(",", Schema.names(searchPathSchemas));

      for (String nameString : nameStrings.keySet()) {
        Schema expectedSchema = nameStrings.get(nameString);
        SQLType expectedSQLType = SQLType.OTHER;
        if (expectedSchema != null) {
          expectedSQLType = SchemaSqlTypes.get(expectedSchema);
        }
        params.add(new Object[]{nameString, expectedSchema, expectedSQLType,
            new ArrayList<Schema>(Arrays.asList(schemas)), searchPath});
      }
    }
    return params;
  }

  private final String typeName;
  private final SQLType expectedSQLType;
  private final ArrayList<Schema> schemas;
  private final String searchPath;

  private TypeInfo typeInfo;

  public TypeInfoCacheGetSQLTypeSearchPathTest(
      String typeName, @SuppressWarnings("unused") Schema expectedSchema, SQLType expectedSQLType,
      ArrayList<Schema> schemas, String searchPath) {
    this.typeName = typeName;
    this.expectedSQLType = expectedSQLType;
    this.schemas = schemas;
    this.searchPath = searchPath;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    typeInfo = ((PgConnection) con).getTypeInfo();

    for (Schema schema : schemas) {
      if (schema == Schema.PG_CATALOG) {
        continue;
      }
      if (schema != Schema.PUBLIC) {
        TestUtil.createSchema(con, schema.name);
      }
      String typeName = schema.name + "." + "type";
      SQLType sqlType = SchemaSqlTypes.get(schema);
      switch (sqlType.type) {
        case Types.DISTINCT:
          TestUtil.createDomain(con, typeName, "int");
          break;
        case Types.STRUCT:
          TestUtil.createCompositeType(con, typeName, "v timestamptz");
          break;
        case Types.VARCHAR:
          TestUtil.createEnumType(con, typeName, "'black'");
          break;
        default:
          throw new IllegalArgumentException("Unknown type " + sqlType + " for schema " + schema);
      }
    }

    if (searchPath != null) {
      String searchPathSql = "SET search_path TO " + searchPath;
      con.createStatement().execute(searchPathSql);
    }
  }

  @Override
  public void tearDown() throws SQLException {
    for (Schema schema : schemas) {
      if (schema == Schema.PG_CATALOG) {
        return;
      }
      if (schema != Schema.PUBLIC) {
        TestUtil.dropSchema(con, schema.name);
      }
      String typeName = schema.name + "." + "type";
      TestUtil.dropType(con, typeName);
    }
    super.tearDown();
  }

  @Test
  public void testSearchPath() throws SQLException {
    int sqlType = typeInfo.getSQLType(typeName);
    Schema actualSchema = SqlTypeSchemas.get(sqlType);
    assertSQLType("got " + actualSchema, expectedSQLType.type, sqlType);
  }
}
