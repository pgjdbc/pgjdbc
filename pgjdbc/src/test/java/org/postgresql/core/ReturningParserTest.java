/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ReturningParserTest {
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();

    String[] delimiters = {"", "_", "3", "*", " "};

    for (String columnName : new String[]{"returning", "returningreturning"}) {
      for (String prefix : delimiters) {
        for (String suffix : delimiters) {
          for (String returning : new String[]{"returning", "returningreturning"}) {
            ids.add(new Object[]{columnName, returning, prefix, suffix});
          }
        }
      }
    }
    return ids;
  }

  @MethodSource("data")
  @ParameterizedTest(name = "columnName={2} {0} {3}, returning={2} {1} {3}")
  void test(String columnName, String returning, String prefix, String suffix) throws SQLException {
    String query =
        "insert into\"prep\"(a, " + prefix + columnName + suffix + ")values(1,2)" + prefix
            + returning + suffix;
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();

    boolean expectedReturning = "returning".equalsIgnoreCase(returning)
        && (prefix.isEmpty() || !Character.isJavaIdentifierStart(prefix.charAt(0)))
        && (suffix.isEmpty() || !Character.isJavaIdentifierPart(suffix.charAt(0)));
    if (expectedReturning != returningKeywordPresent) {
      assertEquals(expectedReturning,
          returningKeywordPresent,
          "Wrong <returning_clause> detected in SQL " + query);
    }
  }

}
