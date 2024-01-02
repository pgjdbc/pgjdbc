/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.NativeQuery;
import org.postgresql.core.Parser;
import org.postgresql.core.SqlCommandType;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

public class SqlCommandParseTest {
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {SqlCommandType.INSERT, "insert/**/ into table(select) values(1)"},
        {SqlCommandType.SELECT, "select'abc'/**/ as insert"},
        {SqlCommandType.INSERT, "INSERT/*fool /*nest comments -- parser*/*/ INTO genkeys (b,c) VALUES ('a', 2), ('b', 4) SELECT"},
        {SqlCommandType.INSERT, "with update as (update foo set (a=?,b=?,c=?)) insert into table(select) values(1)"},
        {SqlCommandType.INSERT, "with update as (update foo set (a=?,b=?,c=?)) insert into table(select) select * from update"},
        {SqlCommandType.INSERT, "with update as (update foo set (a=?,b=?,c=?)) insert/**/ into table(select) values(1)"},
        {SqlCommandType.INSERT, "with update as (update foo set (a=?,b=?,c=?)) insert /**/ into table(select) values(1)"},
        {SqlCommandType.SELECT, "with update as (update foo set (a=?,b=?,c=?)) insert --\nas () select 1"},
        {SqlCommandType.SELECT, "with update as (update foo set (a=?,b=?,c=?)) insert --\n/* dfhg \n*/\nas () select 1"},
        {SqlCommandType.SELECT, "WITH x as (INSERT INTO genkeys(a,b,c) VALUES (1, 'a', 2) returning  returning a, b) select * from x"},
        // No idea if it works, but it should be parsed as WITH
        {SqlCommandType.WITH, "with update as (update foo set (a=?,b=?,c=?)) copy from stdin"},
    });
  }

  @MethodSource("data")
  @ParameterizedTest(name = "expected={0}, sql={1}")
  void run(SqlCommandType type, String sql) throws SQLException {
    List<NativeQuery> queries;
    queries = Parser.parseJdbcSql(sql, true, true, false, true, true);
    NativeQuery query = queries.get(0);
    assertEquals(type, query.command.getType(), sql);
  }
}
