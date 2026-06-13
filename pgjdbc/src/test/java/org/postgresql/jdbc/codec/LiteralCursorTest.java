/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests {@link LiteralCursor}'s element scanning and single-level un-escaping.
 *
 * <p>The cursor peels exactly one level of quoting/escaping per pass. Nested
 * containers ({@code row(row(...))}, {@code array-of-composite}) are handled by
 * recursion: each level dispatches the peeled slice to a child codec that
 * re-parses it with a fresh cursor, so the cursor itself never tracks nesting
 * depth. These tests pin both the single-level behaviour and that one peel
 * leaves the inner literal intact for the next level.</p>
 */
class LiteralCursorTest {

  private static List<String> drive(LiteralCursor c, char open, char delim, char close)
      throws SQLException {
    c.expect(open);
    List<String> out = new ArrayList<>();
    if (c.tryConsume(close)) {
      return out;
    }
    do {
      c.readValue(delim, close);
      if (!c.tokenWasQuoted() && c.tokenEquals("NULL")) {
        out.add(null);
      } else {
        out.add(new String(c.tokenChars(), c.tokenOffset(), c.tokenLength()));
      }
    } while (c.tryConsume(delim));
    c.expect(close);
    return out;
  }

  /** Parses a one-dimensional array literal {@code {...}} into its peeled elements. */
  private static List<String> array(String literal) throws SQLException {
    LiteralCursor c = LiteralCursor.over(literal);
    c.skipDimensionPrefix();
    return drive(c, '{', ',', '}');
  }

  /** Parses a composite literal {@code (...)} into its peeled fields. */
  private static List<String> composite(String literal) throws SQLException {
    return drive(LiteralCursor.over(literal), '(', ',', ')');
  }

  // -------------------------- plain scanning --------------------------

  @Test
  void plainElements() throws SQLException {
    assertEquals(Arrays.asList("1", "2", "3"), array("{1,2,3}"));
  }

  @Test
  void emptyContainer() throws SQLException {
    assertEquals(Arrays.asList(), array("{}"));
  }

  @Test
  void ignoresUnquotedWhitespace() throws SQLException {
    assertEquals(Arrays.asList("1", "2"), array("{ 1 , 2 }"));
  }

  @Test
  void skipsDimensionPrefix() throws SQLException {
    assertEquals(Arrays.asList("a", "b"), array("[0:1]={a,b}"));
  }

  @Test
  void customDelimiter() throws SQLException {
    LiteralCursor c = LiteralCursor.over("{1;2;3}");
    assertEquals(Arrays.asList("1", "2", "3"), drive(c, '{', ';', '}'));
  }

  // -------------------------- one-level un-escaping --------------------------

  @Test
  void delimiterInsideQuotesIsNotASeparator() throws SQLException {
    assertEquals(Arrays.asList("a,b", "c", "d,e"), array("{\"a,b\",\"c\",\"d,e\"}"));
  }

  @Test
  void bracketsInsideQuotesArePreserved() throws SQLException {
    // An array element that is itself a composite literal: the inner "(1,2)" is
    // returned intact, ready for a child composite codec to re-parse.
    assertEquals(Arrays.asList("(1,2)", "(3,4)"), array("{\"(1,2)\",\"(3,4)\"}"));
  }

  @Test
  void backslashEscapedQuote() throws SQLException {
    // literal: {"c\"d"}
    assertEquals(Arrays.asList("c\"d"), array("{\"c\\\"d\"}"));
  }

  @Test
  void doubledQuote() throws SQLException {
    // literal: {"c""d"}
    assertEquals(Arrays.asList("c\"d"), array("{\"c\"\"d\"}"));
  }

  @Test
  void escapedBackslash() throws SQLException {
    // literal: {"a\\b"}
    assertEquals(Arrays.asList("a\\b"), array("{\"a\\\\b\"}"));
  }

  @Test
  void unquotedNullVersusQuotedNullString() throws SQLException {
    // unquoted NULL -> null; quoted "NULL" -> the four-character string
    assertEquals(Arrays.asList(null, "NULL", "x"), array("{NULL,\"NULL\",x}"));
  }

  // -------------------------- container-specific bracket stops --------------------------

  @Test
  void compositeFieldMayContainUnquotedBraces() throws SQLException {
    // An empty array field {} is not quoted by the server (no comma/quote/paren),
    // so it must not terminate the composite field early at '}'.
    assertEquals(Arrays.asList("a", "{}", "b"), composite("(a,{},b)"));
  }

  @Test
  void arrayElementMayBeUnquotedParen() throws SQLException {
    // A lone ')' is a valid unquoted array element; only '}' closes the array.
    assertEquals(Arrays.asList("a", ")", "b"), array("{a,),b}"));
  }

  // -------------------------- nested escaping unwinds by recursion --------------------------

  /**
   * {@code row(row('x"y'), 2)} serialises with compounded quoting. One pass peels
   * exactly one level, leaving the inner composite literal {@code ("x""y",1)}
   * still escaped for the next level — which the second pass then peels.
   */
  @Test
  void nestedComposite_peelsOneLevelPerPass() throws SQLException {
    // outer literal: ("(""x""""y"",1)",2)
    List<String> outer = composite("(\"(\"\"x\"\"\"\"y\"\",1)\",2)");
    assertEquals(Arrays.asList("(\"x\"\"y\",1)", "2"), outer);

    // the inner field, re-parsed by a fresh cursor, peels the remaining level
    List<String> inner = composite(outer.get(0));
    assertEquals(Arrays.asList("x\"y", "1"), inner);
  }
}
