/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Test to check if values in Oid class are correct with Oid values in a database.
 */
@RunWith(Parameterized.class)
public class OidValuesCorrectnessTest extends BaseTest4 {

  @Parameterized.Parameter(0)
  public String oidName;
  @Parameterized.Parameter(1)
  public int oidValue;

  /**
   * List to contain names of all variables, which should be ignored by this test.
   * Prevents situation that a new value will be added to Oid class with ignoring the test.
   */
  private static List<String> oidsToIgnore = Arrays.asList(
      "UNSPECIFIED" //UNSPECIFIED isn't an Oid, it's a value to specify that Oid value is unspecified
  );

  /**
   * Map to contain Oid names with server version of their support.
   * Prevents that some Oid values will be tested with a database not supporting given Oid.
   */
  private static Map<String, ServerVersion> oidsMinimumVersions;

  static {
    oidsMinimumVersions = new HashMap<>();
    oidsMinimumVersions.put("JSON", ServerVersion.v9_2);
    oidsMinimumVersions.put("JSON_ARRAY", ServerVersion.v9_2);
    oidsMinimumVersions.put("JSONB", ServerVersion.v9_4);
    oidsMinimumVersions.put("JSONB_ARRAY", ServerVersion.v9_4);
    oidsMinimumVersions.put("MACADDR8", ServerVersion.v10);
  }

  /**
   * Map to contain Oid names with their proper names from pg_type table (typname) if they are different.
   * Helps in situation when variable name in Oid class isn't the same as typname in pg_type table.
   */
  private static Map<String, String> oidTypeNames;

  static {
    oidTypeNames = new HashMap<>();
    oidTypeNames.put("BOX_ARRAY", "_BOX");
    oidTypeNames.put("INT2_ARRAY", "_INT2");
    oidTypeNames.put("INT4_ARRAY", "_INT4");
    oidTypeNames.put("INT8_ARRAY", "_INT8");
    oidTypeNames.put("TEXT_ARRAY", "_TEXT");
    oidTypeNames.put("NUMERIC_ARRAY", "_NUMERIC");
    oidTypeNames.put("FLOAT4_ARRAY", "_FLOAT4");
    oidTypeNames.put("FLOAT8_ARRAY", "_FLOAT8");
    oidTypeNames.put("BOOL_ARRAY", "_BOOL");
    oidTypeNames.put("DATE_ARRAY", "_DATE");
    oidTypeNames.put("TIME_ARRAY", "_TIME");
    oidTypeNames.put("TIMETZ_ARRAY", "_TIMETZ");
    oidTypeNames.put("TIMESTAMP_ARRAY", "_TIMESTAMP");
    oidTypeNames.put("TIMESTAMPTZ_ARRAY", "_TIMESTAMPTZ");
    oidTypeNames.put("BYTEA_ARRAY", "_BYTEA");
    oidTypeNames.put("VARCHAR_ARRAY", "_VARCHAR");
    oidTypeNames.put("OID_ARRAY", "_OID");
    oidTypeNames.put("BPCHAR_ARRAY", "_BPCHAR");
    oidTypeNames.put("MONEY_ARRAY", "_MONEY");
    oidTypeNames.put("NAME_ARRAY", "_NAME");
    oidTypeNames.put("BIT_ARRAY", "_BIT");
    oidTypeNames.put("INTERVAL_ARRAY", "_INTERVAl");
    oidTypeNames.put("CHAR_ARRAY", "_CHAR");
    oidTypeNames.put("VARBIT_ARRAY", "_VARBIT");
    oidTypeNames.put("UUID_ARRAY", "_UUID");
    oidTypeNames.put("XML_ARRAY", "_XML");
    oidTypeNames.put("POINT_ARRAY", "_POINT");
    oidTypeNames.put("JSONB_ARRAY", "_JSONB");
    oidTypeNames.put("JSON_ARRAY", "_JSON");
    oidTypeNames.put("REF_CURSOR", "REFCURSOR");
    oidTypeNames.put("REF_CURSOR_ARRAY", "_REFCURSOR");
  }

  @Parameterized.Parameters(name = "oidName={0}, oidValue={1}")
  public static Iterable<Object[]> data() throws IllegalAccessException {
    Field[] fields = Oid.class.getFields();
    List<Object[]> data = new ArrayList<>();

    for (Field field : fields) {
      if (!oidsToIgnore.contains(field.getName())) {
        data.add(new Object[]{field.getName(), field.getInt(null)});
      }
    }

    return data;
  }

  /**
   * The testcase to check if expected value of Oid, read from a database, is the same as value
   * written in the Oid class.
   */
  @Test
  public void testValue() throws SQLException {
    // check if Oid can be tested with given database by checking version
    if (oidsMinimumVersions.containsKey(oidName)) {
      Assume.assumeTrue(TestUtil.haveMinimumServerVersion(con, oidsMinimumVersions.get(oidName)));
    }

    String typeName = oidTypeNames.getOrDefault(oidName, oidName);

    Statement stmt = con.createStatement();
    ResultSet resultSet;
    stmt.execute("select oid from pg_type where typname = '" + typeName.toLowerCase(Locale.ROOT) + "'");
    resultSet = stmt.getResultSet();

    // resultSet have to have next row
    Assert.assertTrue("Oid value doesn't exist for oid " + oidName + ";with used type: " + typeName,
        resultSet.next());
    // check if expected value from a database is the same as value in Oid class
    Assert.assertEquals("Wrong value for oid: " + oidName + ";with used type: " + typeName,
        resultSet.getInt(1), oidValue);

  }
}
