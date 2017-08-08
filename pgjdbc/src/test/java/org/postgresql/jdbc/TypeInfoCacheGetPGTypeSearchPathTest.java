/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.join;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.quotify;

import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeSet;
import org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

@RunWith(Parameterized.class)
public class TypeInfoCacheGetPGTypeSearchPathTest extends BaseTest4 {

  private static final String[] DEFAULT_SEARCH_PATH = new String[0];

  private final String nameString;
  private final PgTypeStruct type;
  private final PgTypeStruct arrayType;
  private final ArrayList<PgTypeStruct> types;
  private final String searchPath;

  private TypeInfo typeInfo;
  private PgTypeSet typeSet;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    typeSet = PgTypeSet.createAndInstall(types, con);
    typeInfo = ((PgConnection) con).getTypeInfo();
    if (searchPath != null) {
      String searchPathSql = "SET search_path TO " + searchPath;
      con.createStatement().execute(searchPathSql);
    }
  }

  @Override
  public void tearDown() throws SQLException {
    typeSet.uninstall(con);
    super.tearDown();
  }

  @Parameterized.Parameters(name = "{0} Path: {4}; Types: {3}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> cases = new ArrayList<Object[]>();

    /*
    Each case is: types, search path schemas, and a map of type name strings to the
    PgTypeStruct instance it should match, with PgTypeStruct.UNSPECIFIED for no match.

    Notes:
    1. ParsedTypeName.fromString lowers unquoted type names, so type name string "NS.TYPE" will be
       parsed as (nspname, typname) = ('ns', 'type'), and type name string "TYPE" will be parsed as
       the unqualified type (typname) = ('type'). This accounts for matching the lowered type names.

    2. getPGType special-cases unqualified, unquoted type names. If these two conditions are met,
       the type name will be looked up *without regard to the search_path*, matching the most recently
       created type with that name. This is *not* how the type would normally be found if used in a
       query. getPGArrayType does not exhibit this behavior.

     */

    // baseline
    cases.add(new Object[]{
        new PgTypeStruct[]{new PgTypeStruct("pg_catalog", "text")},
        DEFAULT_SEARCH_PATH,
        new HashMap<String, PgTypeStruct>() {
          {
            put("text", new PgTypeStruct("pg_catalog", "text"));
            put("%text%", new PgTypeStruct("pg_catalog", "text"));
            put("TEXT", new PgTypeStruct("pg_catalog", "text"));
            put("%TEXT%", PgTypeStruct.UNSPECIFIED);
          }
        },
    });

    /*
     TEXT matches public because it's simple and lowered on search.
    */
    cases.add(new Object[]{
        new PgTypeStruct[]{new PgTypeStruct("pg_catalog", "text"),
            new PgTypeStruct("public", "text")},
        DEFAULT_SEARCH_PATH,
        new HashMap<String, Object>() {
          {
            put("text", new PgTypeStruct("pg_catalog", "text"));
            put("%text%", new PgTypeStruct("pg_catalog", "text"));
            put("TEXT", Arrays.asList(
                new PgTypeStruct("public", "text"),
                new PgTypeStruct("pg_catalog", "text")));
            put("%TEXT%", PgTypeStruct.UNSPECIFIED);
          }
        },
    });


    // only quoted %TEXT% matches public.TEXT
    cases.add(new Object[]{
        new PgTypeStruct[]{
            new PgTypeStruct("pg_catalog", "text"),
            new PgTypeStruct("public", "TEXT")},
        DEFAULT_SEARCH_PATH,
        new HashMap<String, PgTypeStruct>() {
          {
            put("TEXT", new PgTypeStruct("pg_catalog", "text"));
            put("%TEXT%", new PgTypeStruct("public", "TEXT"));
          }
        },
    });

    /*
     With schema a on the search path, a.type is selected instead of x.type.
     */
    cases.add(new Object[]{
        new PgTypeStruct[]{
            new PgTypeStruct("a", "type"),
            new PgTypeStruct("x", "type")
        },
        new String[]{"a"},
        new HashMap<String, PgTypeStruct>() {
          {
            put("type", new PgTypeStruct("a", "type"));
            put("%type%", new PgTypeStruct("a", "type"));
          }
        },
    });

    /*
    Even though x is not on the path, it's matched anyway. This is a legacy bug.
     */
    cases.add(new Object[]{
        new PgTypeStruct[]{
            new PgTypeStruct("x", "type")
        },
        DEFAULT_SEARCH_PATH,
        new HashMap<String, Object>() {
          {
            // "type" matches (x, type), "type[]" matches UNSPECIFIED
            put("type", Arrays.asList(new PgTypeStruct("x", "type"), PgTypeStruct.UNSPECIFIED));
            put("%type%", PgTypeStruct.UNSPECIFIED);
          }
        },
    });
    /*
    Even though neither a.type nor x.type are on the path, x.type is matched as it has a higher oid
    (it was created after a.type).
     */
    cases.add(new Object[]{
        new PgTypeStruct[]{
            new PgTypeStruct("a", "type"),
            new PgTypeStruct("x", "type")
        },
        DEFAULT_SEARCH_PATH,
        new HashMap<String, Object>() {
          {
            // "type" matches (x, type), "type[]" matches UNSPECIFIED
            put("type", Arrays.asList(new PgTypeStruct("x", "type"), PgTypeStruct.UNSPECIFIED));
          }
        },
    });

    /*
    The following two tests are to confirm that a legacy bug is fixed. Creation order does not
    affect which type is matched.
     */
    cases.add(new Object[]{
        new PgTypeStruct[]{
            new PgTypeStruct("a", "type"),
            new PgTypeStruct("b", "type")
        },
        new String[]{"a", "b"},
        new HashMap<String, Object>() {
          {
            put("type", new PgTypeStruct("a", "type"));
            put("%type%", new PgTypeStruct("a", "type"));
          }
        },
    });

    cases.add(new Object[]{
        new PgTypeStruct[]{
            new PgTypeStruct("b", "type"),
            new PgTypeStruct("a", "type")
        },
        new String[]{"a", "b"},
        new HashMap<String, PgTypeStruct>() {
          {
            put("type", new PgTypeStruct("a", "type"));
            put("%type%", new PgTypeStruct("a", "type"));
          }
        },
    });

    Collection<Object[]> params = new ArrayList<Object[]>();
    for (Object[] c : cases) {
      @SuppressWarnings("unchecked")
      ArrayList<PgTypeStruct> types = new ArrayList<PgTypeStruct>();
      types.addAll(Arrays.asList((PgTypeStruct[]) c[0]));
      @SuppressWarnings("unchecked")
      HashMap<String, Object> nameStrings = (HashMap<String, Object>) c[2];
      String searchPath = join(",", (String[]) c[1]);
      for (String nameString : nameStrings.keySet()) {
        Object obj = nameStrings.get(nameString);
        PgTypeStruct type;
        PgTypeStruct elementType;
        if (obj instanceof List) {
          @SuppressWarnings("unchecked")
          List<PgTypeStruct> expectedTypes = (List<PgTypeStruct>) obj;
          type = expectedTypes.get(0);
          elementType = PgTypeStruct.createArrayType(expectedTypes.get(1));
        } else {
          type = (PgTypeStruct) obj;
          elementType = PgTypeStruct.createArrayType(type);
        }
        params.add(
            new Object[]{quotify(nameString), type, elementType, types, searchPath});
      }
    }
    return params;
  }

  public TypeInfoCacheGetPGTypeSearchPathTest(String nameString, PgTypeStruct type,
      PgTypeStruct arrayType,
      ArrayList<PgTypeStruct> types, String searchPath) {
    this.nameString = nameString;
    this.type = type;
    this.arrayType = arrayType;
    this.types = types;
    this.searchPath = searchPath;
  }

  @Test
  public void testMatchesSearchPath() throws SQLException {
    int oid = typeInfo.getPGType(nameString);
    PgTypeStruct actualType = typeSet.type(oid);
    assertEquals(type, actualType);
  }

  @Test
  public void testArrayMatchesSearchPath() throws SQLException {
    typeSet.assumeSupportedType(con, arrayType);
    int arrayOid = typeInfo.getPGArrayType(nameString);
    PgTypeStruct actualArrayType = typeSet.type(arrayOid);
    assertEquals(arrayType, actualArrayType);
  }
}
