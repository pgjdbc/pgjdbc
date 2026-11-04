/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class TestPgTypeToClass extends BaseTest4 {
  List<PgTypeToClass> types = new ArrayList<>();

  class PgTypeToClass {
    String type;
    String select;
    String className;

    PgTypeToClass(String [] tokens) {
      type = tokens[0];
      select = tokens[1];
      className = tokens[2];
    }
  }

  private void readCSVFile(String file) throws Exception {
    BufferedReader reader = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream(file)));
    reader.lines().forEach( line -> {
      String [] tokens = line.split(",");
      types.add( new PgTypeToClass(tokens));

    });
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    readCSVFile("/typetoclass.csv");
  }

  @Test
  public void testResultingClasses() throws Exception {

    for ( PgTypeToClass typetoclass : types ) {
      ResultSet rs = con.createStatement().executeQuery("select " + typetoclass.select);
      assertTrue(rs.next());
      Object obj = rs.getObject(1);
      assertEquals(typetoclass.className.trim(), obj.getClass().getName(), "Unexpected class for " + typetoclass.type);
    }
  }
}
