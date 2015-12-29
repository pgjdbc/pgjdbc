package org.postgresql.test.jdbc3;

import org.postgresql.core.NativeQuery;
import org.postgresql.core.Parser;

import junit.framework.TestCase;

import java.util.List;

public class CompositeQueryParseTest extends TestCase {

  public void testEmptyQuery() {
    assertEquals("", reparse("", true, false, true));
  }

  public void testWhitespaceQuery() {
    assertEquals("", reparse("     ", true, false, true));
  }

  public void testOnlyEmptyQueries() {
    assertEquals("", reparse(";;;;  ;  \n;\n", true, false, true));
  }

  public void testSimpleQuery() {
    assertEquals("select 1", reparse("select 1", true, false, true));
  }

  public void testSimpleBind() {
    assertEquals("select $1", reparse("select ?", true, true, true));
  }

  public void testUnquotedQuestionmark() {
    assertEquals("select '{\"key\": \"val\"}'::jsonb ? 'key'",
        reparse("select '{\"key\": \"val\"}'::jsonb ? 'key'", true, false, true));
  }

  public void testRepeatedQuestionmark() {
    assertEquals("select '{\"key\": \"val\"}'::jsonb ? 'key'",
        reparse("select '{\"key\": \"val\"}'::jsonb ?? 'key'", true, false, true));
  }

  public void testQuotedQuestionmark() {
    assertEquals("select '?'", reparse("select '?'", true, false, true));
  }

  public void testDoubleQuestionmark() {
    assertEquals("select '?', $1 ?=> $2", reparse("select '?', ? ??=> ?", true, true, true));
  }

  public void testCompositeBasic() {
    assertEquals("select 1;/*cut*/\n select 2", reparse("select 1; select 2", true, false, true));
  }

  public void testCompositeWithBinds() {
    assertEquals("select $1;/*cut*/\n select $1", reparse("select ?; select ?", true, true, true));
  }

  public void testTrailingSemicolon() {
    assertEquals("select 1", reparse("select 1;", true, false, true));
  }

  public void testTrailingSemicolonAndSpace() {
    assertEquals("select 1", reparse("select 1; ", true, false, true));
  }

  public void testMultipleTrailingSemicolons() {
    assertEquals("select 1", reparse("select 1;;;", true, false, true));
  }

  public void testMultipleEmptyQueries() {
    assertEquals("select 1;/*cut*/\n"
        + "select 2", reparse("select 1; ;\t;select 2", true, false, true));
  }

  public void testCompositeWithComments() {
    assertEquals("select 1;/*cut*/\n"
        + "/* noop */;/*cut*/\n"
        + "select 2", reparse("select 1;/* noop */;select 2", true, false, true));
  }

  private String reparse(String query, boolean standardConformingStrings, boolean withParameters,
      boolean splitStatements) {
    return toString(
        Parser.parseJdbcSql(query, standardConformingStrings, withParameters, splitStatements));
  }

  private String toString(List<NativeQuery> queries) {
    StringBuilder sb = new StringBuilder();
    for (NativeQuery query : queries) {
      if (sb.length() != 0) {
        sb.append(";/*cut*/\n");
      }
      sb.append(query.nativeSql);
    }
    return sb.toString();
  }
}
