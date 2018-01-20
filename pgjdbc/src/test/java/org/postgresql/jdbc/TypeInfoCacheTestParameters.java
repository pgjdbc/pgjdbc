/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStruct;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.PgTypeStructType;
import static org.postgresql.jdbc.TypeInfoCacheTestUtil.quotify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

/**
 * Many of the TypeInfoCache tests are parameterized, and rely on a common set of data. Some of that
 * requires modification depending on the particular test. All of them are included here rather than
 * in their particular test classes for easy reference to the core set of types.
 */
class TypeInfoCacheTestParameters {

  enum PGTypeParam {
    NAME_STRING(0), TYPE(1), GET_PG_ARRAY_TYPE(2);
    final int idx;

    PGTypeParam(int idx) {
      this.idx = idx;
    }
  }

  /*
   Used by TypeInfoCacheBaseTest to load custom types. Select pg_catalog types are included to
   populate maps of oid to PgTypeStruct instance and vice versa.

   Due to legacy issues with TypeInfoCache, the order of these types matters for some of the tests
   to be deterministic.
   */
  static final ArrayList<PgTypeStruct> installedTypes = new ArrayList<PgTypeStruct>() {
    {
      add(new PgTypeStruct("pg_catalog", "text"));
      add(new PgTypeStruct("pg_catalog", "int2"));
      add(new PgTypeStruct("pg_catalog", "int4"));

      add(PgTypeStruct.createQuotified("%", "%"));
      add(PgTypeStruct.createQuotified("%", "."));
      add(PgTypeStruct.createQuotified("%", "%."));
      add(PgTypeStruct.createQuotified("%", ".%%"));
      add(PgTypeStruct.createQuotified(".", "%"));
      add(PgTypeStruct.createQuotified(".", "."));

      add(PgTypeStruct.createQuotified("ns", "element"));
      add(PgTypeStruct.createQuotified("public", "element"));

      add(PgTypeStruct.createQuotified("public", " "));
      add(PgTypeStruct.createQuotified("public", "% %"));
      add(PgTypeStruct.createQuotified("public", "%"));
      add(PgTypeStruct.createQuotified("public", "%%"));
      add(PgTypeStruct.createQuotified("public", "%%%"));
      add(PgTypeStruct.createQuotified("public", "%%%%"));
      add(PgTypeStruct.createQuotified("public", "%.%"));

      add(PgTypeStruct.createQuotified("public", "%TYPE%"));
      add(PgTypeStruct.createQuotified("public", "%TYPE[]%"));
      add(PgTypeStruct.createQuotified("public", "%n"));
      add(PgTypeStruct.createQuotified("public", "%ns"));
      add(PgTypeStruct.createQuotified("public", "%type%"));
      add(PgTypeStruct.createQuotified("public", "."));
      add(PgTypeStruct.createQuotified("public", "TYPE"));
      add(PgTypeStruct.createQuotified("public", "TYPE[]"));
      add(PgTypeStruct.createQuotified("public", "type"));
      add(PgTypeStruct.createQuotified("public", "type[]"));
      add(PgTypeStruct.createQuotified("public", "%type[]%"));

      add(PgTypeStruct.createQuotified("public", "n"));
      add(PgTypeStruct.createQuotified("public", "NS.%TYPE"));

      add(PgTypeStruct.createQuotified("ns", " "));
      add(PgTypeStruct.createQuotified("ns", "%"));
      add(PgTypeStruct.createQuotified("ns", "."));
      add(PgTypeStruct.createQuotified("n%%.%%s%%", "%%ty%%.%%pe%%"));
      add(PgTypeStruct.createQuotified("n%%s", "%%type%%"));
      add(PgTypeStruct.createQuotified("n%.%s%", "%ty%.%pe%"));
      add(PgTypeStruct.createQuotified("n%s", "%type%"));
      add(PgTypeStruct.createQuotified("n%s", "type"));
      add(PgTypeStruct.createQuotified("N%S", "type"));
      add(PgTypeStruct.createQuotified("N%S", "%type%"));
      add(PgTypeStruct.createQuotified("ns", "ty.pe"));
      add(PgTypeStruct.createQuotified("ns.ty", "pe"));

      add(PgTypeStruct.createQuotified("%NS%", "%TYPE%"));
      add(PgTypeStruct.createQuotified("%%NS%%", "%%TYPE%%"));
      add(PgTypeStruct.createQuotified("%NS%", "%TYPE[]%"));
      add(PgTypeStruct.createQuotified("%%NS%%", "%%TYPE[]%%"));
      add(PgTypeStruct.createQuotified("%ns%", "%type%"));
      add(PgTypeStruct.createQuotified("%ns%", "%type[]%"));
      add(PgTypeStruct.createQuotified("NS", "%TYPE%"));
      add(PgTypeStruct.createQuotified("NS", "%TYPE[]%"));
      add(PgTypeStruct.createQuotified("NS", "%%TYPE[]%%"));
      add(PgTypeStruct.createQuotified("NS", "%type%"));
      add(PgTypeStruct.createQuotified("NS", "%type[]%"));
      add(PgTypeStruct.createQuotified("NS", "TYPE"));
      add(PgTypeStruct.createQuotified("NS", "TYPE[]"));
      add(PgTypeStruct.createQuotified("NS", "type"));
      add(PgTypeStruct.createQuotified("NS", "type[]"));
      add(PgTypeStruct.createQuotified("ns", "%TYPE[]%"));
      add(PgTypeStruct.createQuotified("ns", "%type%"));
      add(PgTypeStruct.createQuotified("ns", "TYPE[]"));
      add(PgTypeStruct.createQuotified("ns", "type"));
      add(PgTypeStruct.createQuotified("ns", "type[]"));

      // shadows pg_catalog.text
      add(PgTypeStruct.createQuotified("sp", "text"));
    }
  };

  private static void assertTypeIsInstalled(PgTypeStruct type) {
    if (!installedTypes.contains(type)) {
      throw new AssertionError("Add type " + type + " to installed types");
    }
  }

  static final HashMap<PgTypeStruct, PgTypeStruct> badRoundTripTypes =
      new HashMap<PgTypeStruct, PgTypeStruct>() {
        {
          put(PgTypeStruct.createQuotified("%", "%."), PgTypeStruct.UNSPECIFIED);

          put(PgTypeStruct.createQuotified(".", "%"),
              PgTypeStruct.createQuotified("%", ".%%"));

          put(PgTypeStruct.createQuotified(".", "."),
              PgTypeStruct.createQuotified("%", "%."));

          put(PgTypeStruct.createQuotified("public", "%.%"), PgTypeStruct.UNSPECIFIED);

          put(PgTypeStruct.createQuotified("n%%.%%s%%", "%%ty%%.%%pe%%"),
              PgTypeStruct.createQuotified("public", "n"));

          put(PgTypeStruct.createQuotified("n%.%s%", "%ty%.%pe%"),
              PgTypeStruct.createQuotified("public", "%n"));
        }
      };

  private static Iterable<Object[]> getPGTypeByNameParams() {
    Collection<Object[]> cases = new ArrayList<Object[]>();
    /*
     Each case has two or three elements:

     1. The type name string to be fed to TypeInfoCache getPGType(String) or getPGArrayType(String).
        Internal quotes can be represented as % for easier reading.
          For example                    "\"ns\".\"type\""
          can be written as              "%ns%.%type%"
          to mean the (unquoted) string  |"ns"."type"|

     2. A PgTypeStruct instance representing the type the string should match. If you want % to be
        replaced with quotes as above, use the PgTypeStruct.createQuotified factory method.

     3. A PgTypeStruct instance representing the element type of the type returned by getPGArrayType
        if different from the one returned by getPGType. These are used to note differences in
        behavior between the two functions.

     Array cases are added by default, so both "ns.type" and "ns.type[]" will be tested.
     If the given name string represents an array, such as "_int4", the second argument should be a
     PgTypeStruct for an array, and there should be no third argument.
     */

    // matching core types
    cases.add(new Object[]{"text", new PgTypeStruct("pg_catalog", "text")});
    cases.add(new Object[]{"int2", new PgTypeStruct("pg_catalog", "int2")});
    cases.add(
        new Object[]{"_text", new PgTypeStruct("pg_catalog", "text", PgTypeStructType.ARRAY)});
    cases.add(
        new Object[]{"_int2", new PgTypeStruct("pg_catalog", "int2", PgTypeStructType.ARRAY)});
    cases.add(
        new Object[]{"_int4", new PgTypeStruct("pg_catalog", "int4", PgTypeStructType.ARRAY)});

    /*
     The naming of pg_type.typname for arrays of user-defined types is determined by the order in
     which they're created. For example:

     CREATE SCHEMA case1;
     CREATE TYPE case1.color AS (color text);
     CREATE TYPE case1._color AS (color text);

     CREATE SCHEMA case2;
     CREATE TYPE case2._color AS (color text);
     CREATE TYPE case2.color AS (color text);

     SELECT e.oid::regtype, e.typname, a.typname AS a_typname
       FROM pg_type e
       JOIN pg_namespace n ON e.typnamespace = n.oid
       JOIN pg_type a ON (e.oid, 'array_in'::regproc) = (a.typelem, a.typinput)
       WHERE nspname ~ '^case'
       ORDER BY nspname, e.oid;

          oid      | typname | a_typname
     --------------+---------+-----------
      case1.color  | color   | __color
      case1._color | _color  | ___color
      case2._color | _color  | __color
      case2.color  | color   | ___color
    */
    cases.add(new Object[]{"element", new PgTypeStruct("public", "element")});
    cases.add(new Object[]{"ns.element", new PgTypeStruct("ns", "element")});
    cases.add(
        new Object[]{"_element", new PgTypeStruct("public", "element", PgTypeStructType.ARRAY)});
    cases.add(
        new Object[]{"ns._element", new PgTypeStruct("ns", "element", PgTypeStructType.ARRAY)});

    // matching aliases
    cases.add(
        new Object[]{"%smallint%", PgTypeStruct.UNSPECIFIED});
    cases.add(
        new Object[]{"smallint", PgTypeStruct.UNSPECIFIED, new PgTypeStruct("pg_catalog", "int2")});
    cases.add(new Object[]{"%int4%", new PgTypeStruct("pg_catalog", "int4")});
    cases.add(new Object[]{"int4", new PgTypeStruct("pg_catalog", "int4")});
    cases.add(
        new Object[]{"int", PgTypeStruct.UNSPECIFIED, new PgTypeStruct("pg_catalog", "int4")});
    cases.add(
        new Object[]{"integer", PgTypeStruct.UNSPECIFIED, new PgTypeStruct("pg_catalog", "int4")});
    cases.add(
        new Object[]{"INT", PgTypeStruct.UNSPECIFIED, new PgTypeStruct("pg_catalog", "int4")});

    /*
     sp.text is shadowing pg_catalog.text. Lower-case "text" matches pg_catalog.text because
     it's cached when TypeInfoCache is instantiated via addCoreType
     */
    cases.add(new Object[]{"TEXT",
        PgTypeStruct.createWithSearchPathException("pg_catalog", "text", "sp")});

    // edge cases
    cases.add(new Object[]{" ", PgTypeStruct.createQuotified("public", " ")});
    cases.add(new Object[]{"", PgTypeStruct.UNSPECIFIED}); // empty string
    cases.add(new Object[]{"% %", PgTypeStruct.createQuotified("public", " ")});
    cases.add(new Object[]{"%", PgTypeStruct.createQuotified("public", "%")});
    cases.add(new Object[]{"%%", PgTypeStruct.UNSPECIFIED}); // "%%" parses as (null, "")
    cases.add(new Object[]{"%%%", PgTypeStruct.createQuotified("public", "%")});
    cases.add(new Object[]{"%%%%", PgTypeStruct.createQuotified("public", "%%")});
    cases.add(new Object[]{"%.%", PgTypeStruct.createQuotified("public", ".")});
    cases.add(new Object[]{"%.%.", PgTypeStruct.UNSPECIFIED}); // "%.%." parses as ("", "..")
    cases.add(new Object[]{"%.%.%.%", PgTypeStruct.createQuotified("%", "%.")});
    cases.add(new Object[]{".", PgTypeStruct.UNSPECIFIED});    // "." parses as ("", "")
    cases.add(new Object[]{".%.%", PgTypeStruct.UNSPECIFIED}); // ".%.%" parses as ("", "%.%")
    cases.add(new Object[]{"..", PgTypeStruct.UNSPECIFIED});   // ".." parses as ("", ".")
    cases.add(new Object[]{"...", PgTypeStruct.UNSPECIFIED});  // "..." parses as ("", "..")

    // unqualified (default search path, so public is an available schema for users)
    // quotes are stripped
    cases.add(new Object[]{"%TYPE%", PgTypeStruct.createQuotified("public", "TYPE")});
    cases.add(new Object[]{"%TYPE[]%", PgTypeStruct.createQuotified("public", "TYPE[]")});
    cases.add(new Object[]{"%%TYPE[]%%", PgTypeStruct.createQuotified("public", "%TYPE[]%")});
    cases.add(new Object[]{"%type%", PgTypeStruct.createQuotified("public", "type")});

    cases.add(new Object[]{"type", PgTypeStruct.createQuotified("public", "type")});
    // unquoted type names are case-folded
    cases.add(new Object[]{"TYPE", PgTypeStruct.createQuotified("public", "type")});
    cases.add(new Object[]{"%NS.%TYPE%", PgTypeStruct.createQuotified("public", "NS.%TYPE")});

    // qualified
    cases.add(new Object[]{"%NS%.%TYPE%", PgTypeStruct.createQuotified("NS", "TYPE")});
    cases.add(new Object[]{"%%NS%%.%%TYPE%%", PgTypeStruct.createQuotified("%NS%", "%TYPE%")});
    cases.add(new Object[]{"%%%NS%%%.%%%TYPE%%%", PgTypeStruct.createQuotified("%%NS%%", "%%TYPE%%")});
    cases.add(new Object[]{"%NS%.%TYPE[]%", PgTypeStruct.createQuotified("NS", "TYPE[]")});
    cases.add(new Object[]{"%NS%.%%TYPE[]%%", PgTypeStruct.createQuotified("NS", "%TYPE[]%")});
    cases.add(new Object[]{"%NS%.%%%TYPE[]%%%", PgTypeStruct.createQuotified("NS", "%%TYPE[]%%")});
    cases.add(new Object[]{"%ns%.%type%", PgTypeStruct.createQuotified("ns", "type")});

    // unquoted type names are is case-folded
    cases.add(new Object[]{"NS.TYPE", PgTypeStruct.createQuotified("ns", "type")});
    cases.add(new Object[]{"ns.%type[]%", PgTypeStruct.createQuotified("ns", "type[]")});
    cases.add(new Object[]{"ns.type", PgTypeStruct.createQuotified("ns", "type")});

    cases.add(new Object[]{"ns.ty.pe", PgTypeStruct.createQuotified("ns", "ty.pe")});
    cases.add(new Object[]{"n%s.%type%", PgTypeStruct.createQuotified("n%s", "type")});
    cases.add(new Object[]{"N%S.type", PgTypeStruct.createQuotified("n%s", "type")});
    cases.add(new Object[]{"%n%s%.%%type%%", PgTypeStruct.createQuotified("n%s", "%type%")});
    cases.add(new Object[]{"%n%%s%.%%%type%%%", PgTypeStruct.createQuotified("n%%s", "%%type%%")});

    // bad parsing
    cases.add(
        new Object[]{"%n%%.%%s%%%.%%%ty%%.%%pe%%%", PgTypeStruct.createQuotified("public", "n")});
    cases.add(new Object[]{"%ns%.%ty%.%pe%", PgTypeStruct.createQuotified("public", "%ns")});

    Collection<Object[]> params = new ArrayList<Object[]>();
    for (Object[] c : cases) {
      String nameString = quotify((String) c[0]);
      PgTypeStruct type = (PgTypeStruct) c[1];
      boolean haveArrayTypeOverride = c.length > 2;
      PgTypeStruct getPGArrayTypeType =
          PgTypeStruct.createArrayType(haveArrayTypeOverride ? (PgTypeStruct) c[2] : type);
      if (type.type.isElement || haveArrayTypeOverride) {
        params.add(new Object[]{nameString, type, getPGArrayTypeType});
      } else {
        params.add(new Object[]{nameString, type, PgTypeStruct.UNSPECIFIED});
      }
      if (type.equals(PgTypeStruct.UNPARSEABLE) || type.equals(PgTypeStruct.UNSPECIFIED)) {
        continue;
      }
      if (type.type.isElement) {
        assertTypeIsInstalled(type);
      }
      if (type.type.isElement) {
        String arrayNameString = nameString + PgTypeStruct.ARRAY_SUFFIX;
        PgTypeStruct arrayType = PgTypeStruct.createArrayType(type);
        params.add(new Object[]{arrayNameString, arrayType});
      }
    }
    return params;
  }

  static Iterable<Object[]> getPGTypeParseableNameParams() {
    Collection<Object[]> params = new ArrayList<Object[]>();
    for (Object[] p : getPGTypeByNameParams()) {
      PgTypeStruct type = (PgTypeStruct) p[PGTypeParam.TYPE.idx];
      if (type == PgTypeStruct.UNPARSEABLE) {
        continue;
      }
      if (type.type.isElement && !type.equals(PgTypeStruct.UNSPECIFIED)) {
        assertTypeIsInstalled(type);
      }
      params.add(new Object[]{p[PGTypeParam.NAME_STRING.idx], p[PGTypeParam.TYPE.idx]});
    }
    return params;
  }

  static Iterable<Object[]> getPGTypeUnParseableNameParams() {
    Collection<Object[]> params = new ArrayList<Object[]>();
    for (Object[] p : getPGTypeByNameParams()) {
      PgTypeStruct type = (PgTypeStruct) p[PGTypeParam.TYPE.idx];
      if (type == PgTypeStruct.UNPARSEABLE) {
        params.add(new Object[]{p[PGTypeParam.NAME_STRING.idx]});
      }
    }
    return params;
  }

  private static Iterable<Object[]> getPGArrayTypeTestParams() {
    Collection<Object[]> params = new ArrayList<Object[]>();
    for (Object[] p : getPGTypeByNameParams()) {
      if (p.length > 2) {
        PgTypeStruct type = (PgTypeStruct) p[PGTypeParam.GET_PG_ARRAY_TYPE.idx];
        params.add(new Object[]{p[PGTypeParam.NAME_STRING.idx], type});
      }
    }
    return params;
  }

  static Iterable<Object[]> getPGArrayTypeParseableTestParams() {
    Collection<Object[]> params = new ArrayList<Object[]>();
    for (Object[] p : getPGArrayTypeTestParams()) {
      PgTypeStruct type = (PgTypeStruct) p[1];
      if (type.equals(PgTypeStruct.UNPARSEABLE)) {
        continue;
      }
      params.add(new Object[]{p[PGTypeParam.NAME_STRING.idx], type});
    }
    return params;
  }

  static Iterable<Object[]> getPGArrayTypeUnparseableTestParams() {
    Collection<Object[]> params = new ArrayList<Object[]>();
    for (Object[] p : getPGArrayTypeTestParams()) {
      PgTypeStruct type = (PgTypeStruct) p[1];
      if (type.equals(PgTypeStruct.UNPARSEABLE)) {
        params.add(new Object[]{p[0]});
      }
    }
    return params;
  }

  static Iterable<Object[]> getPGTypeByOidParams() {
    Collection<Object[]> cases = new ArrayList<Object[]>();
    cases.add(new Object[]{new PgTypeStruct("pg_catalog", "text"), "text", "_text"});
    cases.add(new Object[]{new PgTypeStruct("pg_catalog", "int2"), "int2", "_int2"});
    cases.add(new Object[]{new PgTypeStruct("pg_catalog", "int4"), "int4", "_int4"});

    cases.add(new Object[]{PgTypeStruct.createQuotified("public", " "), " ", "_ "});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "% %"), "%% %%", "_% %"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "%"), "%%%", "_%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "%%"), "%%%%", "_%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "%%%"), "%%%%%", "_%%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "%%%%"), "%%%%%%", "_%%%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "."), "%.%", "%_.%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "%.%"), "%%.%%", "%_%.%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "%TYPE%"), "%%TYPE%%", "%_%TYPE%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "%TYPE[]%"), "%%TYPE[]%%", "%_%TYPE[]%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "%type%"), "%%type%%", "_%type%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "%type[]%"), "%%type[]%%", "_%type[]%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "TYPE"), "%TYPE%", "%_TYPE%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "TYPE[]"), "%TYPE[]%", "%_TYPE[]%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "type"), "type", "_type"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "type[]"), "%type[]%", "%_type[]%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "%ns"), "%ns", "_%ns"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("public", "NS.%TYPE"), "%NS.%TYPE%", "%_NS.%TYPE%"});

    cases.add(new Object[]{PgTypeStruct.createQuotified("%", "%"), "%%%.%%%", "%%%.%_%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("%", "."), "%%%.%.%", "%%%.%_.%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified(".", "%"), "%.%.%%%", "%.%.%_%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified(".", "."), "%.%.%.%", "%.%.%_.%"});

    cases.add(new Object[]{PgTypeStruct.createQuotified("ns", " "), "%ns%.% %", "%ns%.%_ %"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("ns", "%"), "%ns%.%%%", "%ns%.%_%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("ns", "."), "%ns%.%.%", "%ns%.%_.%"});

    cases.add(new Object[]{PgTypeStruct.createQuotified("%NS%", "%TYPE%"), "%%NS%%.%%TYPE%%", "%%NS%%.%_%TYPE%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("%NS%", "%TYPE[]%"), "%%NS%%.%%TYPE[]%%", "%%NS%%.%_%TYPE[]%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("NS", "%TYPE[]%"), "%NS%.%%TYPE[]%%", "%NS%.%_%TYPE[]%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("NS", "%%TYPE[]%%"), "%NS%.%%%TYPE[]%%%", "%NS%.%_%%TYPE[]%%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("NS", "TYPE"), "%NS%.%TYPE%", "%NS%.%_TYPE%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("NS", "TYPE[]"), "%NS%.%TYPE[]%", "%NS%.%_TYPE[]%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("%ns%", "%type%"), "%%ns%%.%%type%%", "%%ns%%.%_%type%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("%ns%", "%type[]%"), "%%ns%%.%%type[]%%", "%%ns%%.%_%type[]%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("ns", "type"), "%ns%.%type%", "%ns%.%_type%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("ns", "type[]"), "%ns%.%type[]%", "%ns%.%_type[]%"});

    cases.add(new Object[]{PgTypeStruct.createQuotified("ns", "ty.pe"), "%ns%.%ty.pe%", "%ns%.%_ty.pe%"});

    cases.add(new Object[]{PgTypeStruct.createQuotified("n%%.%%s%%", "%%ty%%.%%pe%%"), "%n%%.%%s%%%.%%%ty%%.%%pe%%%", "%n%%.%%s%%%.%_%%ty%%.%%pe%%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("n%%s", "%%type%%"), "%n%%s%.%%%type%%%","%n%%s%.%_%%type%%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("n%.%s%", "%ty%.%pe%"), "%n%.%s%%.%%ty%.%pe%%", "%n%.%s%%.%_%ty%.%pe%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("n%s", "%type%"), "%n%s%.%%type%%", "%n%s%.%_%type%%"});
    cases.add(new Object[]{PgTypeStruct.createQuotified("n%s", "type"), "%n%s%.%type%", "%n%s%.%_type%"});

    Collection<Object[]> params = new ArrayList<Object[]>();
    for (Object[] c : cases) {
      PgTypeStruct type = (PgTypeStruct) c[0];
      assertTypeIsInstalled(type);
      PgTypeStruct arrayType = PgTypeStruct.createArrayType(type);

      String nameString = quotify((String) c[1]);
      String arrayNameString =
          (c.length > 2 ? quotify((String) c[2]) : nameString + PgTypeStruct.ARRAY_SUFFIX);
      params.add(new Object[]{type, nameString, arrayType, arrayNameString});
    }
    return params;
  }

  /*
   This is similar to getPGTypeByOidParams, though it's used when testing names that have been cached
   by getPGType(name).
   */
  static Iterable<Object[]> getPGTypeByOidCachedNameParams() {
    Collection<Object[]> params = new ArrayList<Object[]>();
    for (Object[] c : getPGTypeByOidParams()) {
      params.add(new Object[]{c[0], quotify((String) c[1]), quotify((String) c[3])});
    }
    return params;
  }
}
