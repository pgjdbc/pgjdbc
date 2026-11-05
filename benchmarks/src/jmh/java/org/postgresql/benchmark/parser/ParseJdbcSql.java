/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.parser;

import org.postgresql.core.NativeQuery;
import org.postgresql.core.Parser;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures {@code Parser.parseJdbcSql} on inputs that call the keyword matchers as often as the
 * parser allows, and on a typical INSERT.
 *
 * <p>In {@code withTail}, every word after {@code with x as (select 1)} is outside parentheses, so
 * it is tested against UPDATE, DELETE, INSERT, SELECT, and VALUES; {@code selecz} differs from
 * {@code select} only in its last character. In {@code statements}, every word is the first word of
 * a statement and goes through the whole chain of command keywords.
 */
@Fork(value = 3, jvmArgsPrepend = "-Xmx512m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ParseJdbcSql {
  @Param({"withTail", "statements", "insert"})
  private String shape;

  private String sql;

  @Setup
  public void setup() {
    StringBuilder sb = new StringBuilder();
    switch (shape) {
      case "withTail":
        sb.append("with x as (select 1) ");
        for (int i = 0; i < 10000; i++) {
          sb.append("selecz ");
        }
        break;
      case "statements":
        for (int i = 0; i < 10000; i++) {
          sb.append("insera;");
        }
        break;
      case "insert":
        sb.append("insert into t(a, b, c) values (?, ?, ?)");
        break;
      default:
        throw new IllegalArgumentException(shape);
    }
    sql = sb.toString();
  }

  @Benchmark
  public List<NativeQuery> parse() throws SQLException {
    return Parser.parseJdbcSql(sql, true, true, true, false, true);
  }
}
