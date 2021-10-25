/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@RunWith(Parameterized.class)
public class ReturningParserTest {
  private final String columnName;
  private final String returning;
  private final String prefix;
  private final String suffix;

  public ReturningParserTest(String columnName, String returning, String prefix, String suffix) {
    this.columnName = columnName;
    this.returning = returning;
    this.prefix = prefix;
    this.suffix = suffix;
  }

  @Parameterized.Parameters(name = "columnName={2} {0} {3}, returning={2} {1} {3}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();

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

  @Test
  public void test() throws SQLException {
    String query =
        "insert into\"prep\"(a, " + prefix + columnName + suffix + ")values(1,2)" + prefix
            + returning + suffix;
    List<NativeQuery> qry = Parser.parseJdbcSql(query, true, true, true, true, true);
    boolean returningKeywordPresent = qry.get(0).command.isReturningKeywordPresent();

    boolean expectedReturning = this.returning.equalsIgnoreCase("returning")
        && (prefix.isEmpty() || !Character.isJavaIdentifierStart(prefix.charAt(0)))
        && (suffix.isEmpty() || !Character.isJavaIdentifierPart(suffix.charAt(0)));
    if (expectedReturning != returningKeywordPresent) {
      Assert.assertEquals("Wrong <returning_clause> detected in SQL " + query,
          expectedReturning,
          returningKeywordPresent);
    }
  }

}
