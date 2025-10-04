package org.postgresql.test.jdbc4;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PGobject;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class CreateDropStructTest extends BaseTest4 {

  public CreateDropStructTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Before
  public void setup() throws Exception {
    dropStructAndTable();
  }

  @AfterClass
  public static void cleanup() throws Exception {
    dropStructAndTable();
  }

  private static void dropStructAndTable() throws SQLException {
    try (Connection con = TestUtil.openDB();) {
      TestUtil.dropType(con, "create_drop_struct");
      TestUtil.dropTable(con, "create_drop_struct_table");
    }
  }

  @Test
  public void createTypeThenDropAndRecreate() throws SQLException {
    try (Statement statement = con.createStatement()) {
      statement.execute("create type create_drop_struct as (v text)");
      statement.execute("create table create_drop_struct_table (s create_drop_struct)");
    }
    try (PreparedStatement statement = con.prepareStatement(
        "insert into create_drop_struct_table values (?)")) {
      PGobject o = new PGobject();
      o.setType("create_drop_struct");
      o.setValue("(\"abc\")");
      statement.setObject(1, o);
      statement.executeUpdate();
    }
    try (Statement statement = con.createStatement()) {
      statement.execute("drop table create_drop_struct_table");
      statement.execute("drop type create_drop_struct");
      statement.execute("create type create_drop_struct as (v text)");
      statement.execute("create table create_drop_struct_table (s create_drop_struct)");
    }
    try (PreparedStatement statement = con.prepareStatement(
        "insert into create_drop_struct_table values (?)")) {
      PGobject o = new PGobject();
      o.setType("create_drop_struct");
      o.setValue("(\"abc\")");
      statement.setObject(1, o);
      statement.executeUpdate();
    }
  }
}
