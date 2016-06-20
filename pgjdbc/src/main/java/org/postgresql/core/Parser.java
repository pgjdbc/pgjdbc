/*-------------------------------------------------------------------------
*
* Copyright (c) 2006-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.core;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Basic query parser infrastructure.
 *
 * @author Michael Paesold (mpaesold@gmx.at)
 * @author Christopher Deckers (chrriis@gmail.com)
 */
public class Parser {
  private final static int[] NO_BINDS = new int[0];

  /**
   * Parses JDBC query into PostgreSQL's native format. Several queries might be given if separated
   * by semicolon.
   *
   * @param query                     jdbc query to parse
   * @param standardConformingStrings whether to allow backslashes to be used as escape characters
   *                                  in single quote literals
   * @param withParameters            whether to replace ?, ? with $1, $2, etc
   * @param splitStatements           whether to split statements by semicolon
   * @param isAutoCommit              whether autocommit is enabled
   * @param isBatchedReWriteConfigured whether re-write optimization is enabled
   * @return list of native queries
   */
  public static List<NativeQuery> parseJdbcSql(String query, boolean standardConformingStrings,
      boolean withParameters, boolean splitStatements, boolean isAutoCommit,
      boolean isBatchedReWriteConfigured) {
    if (!withParameters && !splitStatements) {
      return Collections.singletonList(new NativeQuery(query,
        DMLCommand.createStatementTypeInfo(DMLCommandType.BLANK)));
    }

    int fragmentStart = 0;
    int inParen = 0;

    char[] aChars = query.toCharArray();

    StringBuilder nativeSql = new StringBuilder(query.length() + 10);
    List<Integer> bindPositions = null; // initialized on demand
    List<NativeQuery> nativeQueries = null;
    boolean isCurrentReWriteCompatible = false;
    boolean isValuesFound = false;
    int valuesBraceOpenPosition = -1;
    int valuesBraceClosePosition = -1;
    boolean isInsertPresent = false;
    boolean isReturningPresent = false;
    DMLCommandType currentCommandType = DMLCommandType.BLANK;

    boolean whitespaceOnly = true;
    int keyWordCount = 0;
    int keywordStart = -1;
    for (int i = 0; i < aChars.length; ++i) {
      char aChar = aChars[i];
      boolean isKeyWordChar = false;
      // ';' is ignored as it splits the queries
      whitespaceOnly &= aChar == ';' || Character.isWhitespace(aChar);
      switch (aChar) {
        case '\'': // single-quotes
          i = Parser.parseSingleQuotes(aChars, i, standardConformingStrings);
          break;

        case '"': // double-quotes
          i = Parser.parseDoubleQuotes(aChars, i);
          break;

        case '-': // possibly -- style comment
          i = Parser.parseLineComment(aChars, i);
          break;

        case '/': // possibly /* */ style comment
          i = Parser.parseBlockComment(aChars, i);
          break;

        case '$': // possibly dollar quote start
          i = Parser.parseDollarQuotes(aChars, i);
          break;

        // case '(' moved below to parse "values(" properly

        case ')':
          inParen--;
          if (inParen == 0 && isValuesFound) {
            // If original statement is multi-values like VALUES (...), (...), ... then
            // search for the latest closing paren
            valuesBraceClosePosition = nativeSql.length() + i - fragmentStart;
          }
          break;

        case '?':
          nativeSql.append(aChars, fragmentStart, i - fragmentStart);
          if (i + 1 < aChars.length && aChars[i + 1] == '?') /* replace ?? with ? */ {
            nativeSql.append('?');
            i++; // make sure the coming ? is not treated as a bind
          } else {
            if (!withParameters) {
              nativeSql.append('?');
            } else {
              if (bindPositions == null) {
                bindPositions = new ArrayList<Integer>();
              }
              bindPositions.add(nativeSql.length());
              int bindIndex = bindPositions.size();
              nativeSql.append(NativeQuery.bindName(bindIndex));
            }
          }
          fragmentStart = i + 1;
          break;

        case ';':
          if (inParen == 0 && splitStatements) {
            if (!whitespaceOnly) {
              nativeSql.append(aChars, fragmentStart, i - fragmentStart);
              whitespaceOnly = true;
            }
            fragmentStart = i + 1;
            if (nativeSql.length() > 0) {
              if (nativeQueries == null) {
                nativeQueries = new ArrayList<NativeQuery>();
              }

              nativeQueries.add(new NativeQuery(nativeSql.toString(),
                  toIntArray(bindPositions), DMLCommand.createStatementTypeInfo(
                      currentCommandType, isBatchedReWriteConfigured, valuesBraceOpenPosition,
                      valuesBraceClosePosition,
                  isReturningPresent, nativeQueries.size())));
            }
            // Prepare for next query
            if (bindPositions != null) {
              bindPositions.clear();
            }
            nativeSql.setLength(0);
            currentCommandType = DMLCommandType.BLANK;
            isReturningPresent = false;
            valuesBraceOpenPosition = -1;
            valuesBraceClosePosition = -1;
          }
          break;

        default:
          isKeyWordChar =
              aChars[i] >= 'a' && aChars[i] <= 'z' || aChars[i] >= 'A' && aChars[i] <= 'Z';
          if (isKeyWordChar && keywordStart < 0) {
            keywordStart = i;
          }
          break;
      }
      if (keywordStart >= 0 && (i == aChars.length - 1 || !isKeyWordChar)) {
        int wordLength = (isKeyWordChar ? i + 1 : i) - keywordStart;
        if (wordLength == 6 && parseUpdateKeyword(aChars, keywordStart)) {
          currentCommandType = DMLCommandType.UPDATE;
        } else if (wordLength == 6 && parseDeleteKeyword(aChars, keywordStart)) {
          currentCommandType = DMLCommandType.DELETE;
        } else if (wordLength == 4 && parseMoveKeyword(aChars, keywordStart)) {
          currentCommandType = DMLCommandType.MOVE;
        } else if (wordLength == 6 && parseInsertKeyword(aChars, keywordStart)) {
          if (!isInsertPresent && (nativeQueries == null || nativeQueries.isEmpty())) {
            // Only allow rewrite for insert command starting with the insert keyword.
            // Else, too many risks of wrong interpretation.
            isCurrentReWriteCompatible = keyWordCount == 0;
            isInsertPresent = true;
            currentCommandType = DMLCommandType.INSERT;
          } else {
            isCurrentReWriteCompatible = false;
          }
        } else if (wordLength == 9 && parseReturningKeyword(aChars, keywordStart)) {
          isReturningPresent = true;
        } else if (wordLength == 6 && parseValuesKeyword(aChars, keywordStart)) {
          isValuesFound = true;
        }
        keywordStart = -1;
        keyWordCount++;
      }
      if (aChar == '(') {
        inParen++;
        if (inParen == 1 && isValuesFound && valuesBraceOpenPosition == -1) {
          valuesBraceOpenPosition = nativeSql.length() + i - fragmentStart;
        }
      }
    }
    if (!isValuesFound || bindPositions == null) {
      isCurrentReWriteCompatible = false;
    }
    if (!isCurrentReWriteCompatible) {
      valuesBraceOpenPosition = -1;
      valuesBraceClosePosition = -1;
    }

    if (fragmentStart < aChars.length && !whitespaceOnly) {
      nativeSql.append(aChars, fragmentStart, aChars.length - fragmentStart);
    }

    if (nativeSql.length() == 0) {
      return nativeQueries != null ? nativeQueries : Collections.<NativeQuery>emptyList();
    }

    NativeQuery lastQuery = new NativeQuery(nativeSql.toString(),
        toIntArray(bindPositions), DMLCommand.createStatementTypeInfo(currentCommandType,
            isBatchedReWriteConfigured, valuesBraceOpenPosition, valuesBraceClosePosition,
            isReturningPresent, (nativeQueries == null ? 0 : nativeQueries.size())));

    if (nativeQueries == null) {
      return Collections.singletonList(lastQuery);
    }

    if (!whitespaceOnly) {
      nativeQueries.add(lastQuery);
    }
    return nativeQueries;
  }

  /**
   * Converts {@code List<Integer>} to {@code int[]}. Empty and {@code null} lists are converted to
   * empty array.
   *
   * @param list input list
   * @return output array
   */
  private static int[] toIntArray(List<Integer> list) {
    if (list == null || list.isEmpty()) {
      return NO_BINDS;
    }
    int[] res = new int[list.size()];
    for (int i = 0; i < list.size(); i++) {
      res[i] = list.get(i); // must not be null
    }
    return res;
  }

  /**
   * Find the end of the single-quoted string starting at the given offset.
   *
   * Note: for <tt>'single '' quote in string'</tt>, this method currently returns the offset of
   * first <tt>'</tt> character after the initial one. The caller must call the method a second time
   * for the second part of the quoted string.
   *
   * @param query                     query
   * @param offset                    start offset
   * @param standardConformingStrings standard conforming strings
   * @return position of the end of the single-quoted string
   */
  public static int parseSingleQuotes(final char[] query, int offset,
      boolean standardConformingStrings) {
    // check for escape string syntax (E'')
    if (standardConformingStrings
        && offset >= 2
        && (query[offset - 1] == 'e' || query[offset - 1] == 'E')
        && charTerminatesIdentifier(query[offset - 2])) {
      standardConformingStrings = false;
    }

    if (standardConformingStrings) {
      // do NOT treat backslashes as escape characters
      while (++offset < query.length) {
        switch (query[offset]) {
          case '\'':
            return offset;
          default:
            break;
        }
      }
    } else {
      // treat backslashes as escape characters
      while (++offset < query.length) {
        switch (query[offset]) {
          case '\\':
            ++offset;
            break;
          case '\'':
            return offset;
          default:
            break;
        }
      }
    }

    return query.length;
  }

  /**
   * Find the end of the double-quoted string starting at the given offset.
   *
   * Note: for <tt>&quot;double &quot;&quot; quote in string&quot;</tt>, this method currently
   * returns the offset of first <tt>&quot;</tt> character after the initial one. The caller must
   * call the method a second time for the second part of the quoted string.
   *
   * @param query  query
   * @param offset start offset
   * @return position of the end of the double-quoted string
   */
  public static int parseDoubleQuotes(final char[] query, int offset) {
    while (++offset < query.length && query[offset] != '"') {
      ;
    }
    return offset;
  }

  /**
   * Test if the dollar character (<tt>$</tt>) at the given offset starts a dollar-quoted string and
   * return the offset of the ending dollar character.
   *
   * @param query  query
   * @param offset start offset
   * @return offset of the ending dollar character
   */
  public static int parseDollarQuotes(final char[] query, int offset) {
    if (offset + 1 < query.length
        && (offset == 0 || !isIdentifierContChar(query[offset - 1]))) {
      int endIdx = -1;
      if (query[offset + 1] == '$') {
        endIdx = offset + 1;
      } else if (isDollarQuoteStartChar(query[offset + 1])) {
        for (int d = offset + 2; d < query.length; ++d) {
          if (query[d] == '$') {
            endIdx = d;
            break;
          } else if (!isDollarQuoteContChar(query[d])) {
            break;
          }
        }
      }
      if (endIdx > 0) {
        // found; note: tag includes start and end $ character
        int tagIdx = offset;
        int tagLen = endIdx - offset + 1;
        offset = endIdx; // loop continues at endIdx + 1
        for (++offset; offset < query.length; ++offset) {
          if (query[offset] == '$'
              && subArraysEqual(query, tagIdx, offset, tagLen)) {
            offset += tagLen - 1;
            break;
          }
        }
      }
    }
    return offset;
  }

  /**
   * Test if the <tt>-</tt> character at <tt>offset</tt> starts a <tt>--</tt> style line comment,
   * and return the position of the first <tt>\r</tt> or <tt>\n</tt> character.
   *
   * @param query  query
   * @param offset start offset
   * @return position of the first <tt>\r</tt> or <tt>\n</tt> character
   */
  public static int parseLineComment(final char[] query, int offset) {
    if (offset + 1 < query.length && query[offset + 1] == '-') {
      while (offset + 1 < query.length) {
        offset++;
        if (query[offset] == '\r' || query[offset] == '\n') {
          break;
        }
      }
    }
    return offset;
  }

  /**
   * Test if the <tt>/</tt> character at <tt>offset</tt> starts a block comment, and return the
   * position of the last <tt>/</tt> character.
   *
   * @param query  query
   * @param offset start offset
   * @return position of the last <tt>/</tt> character
   */
  public static int parseBlockComment(final char[] query, int offset) {
    if (offset + 1 < query.length && query[offset + 1] == '*') {
      // /* /* */ */ nest, according to SQL spec
      int level = 1;
      for (offset += 2; offset < query.length; ++offset) {
        switch (query[offset - 1]) {
          case '*':
            if (query[offset] == '/') {
              --level;
              ++offset; // don't parse / in */* twice
            }
            break;
          case '/':
            if (query[offset] == '*') {
              ++level;
              ++offset; // don't parse * in /*/ twice
            }
            break;
          default:
            break;
        }

        if (level == 0) {
          --offset; // reset position to last '/' char
          break;
        }
      }
    }
    return offset;
  }

  /**
   * Parse string to check presence of DELETE keyword regardless of case. The initial character is
   * assumed to have been matched.
   *
   * @param query char[] of the query statement
   * @param offset position of query to start checking
   * @return boolean indicates presence of word
   */
  public static boolean parseDeleteKeyword(final char[] query, int offset) {
    if (query.length < (offset + 6)) {
      return false;
    }

    return (query[offset] | 32) == 'd'
        && (query[offset + 1] | 32) == 'e'
        && (query[offset + 2] | 32) == 'l'
        && (query[offset + 3] | 32) == 'e'
        && (query[offset + 4] | 32) == 't'
        && (query[offset + 5] | 32) == 'e';
  }

  /**
   * Parse string to check presence of INSERT keyword regardless of case.
   *
   * @param query char[] of the query statement
   * @param offset position of query to start checking
   * @return boolean indicates presence of word
   */
  public static boolean parseInsertKeyword(final char[] query, int offset) {
    if (query.length < (offset + 7)) {
      return false;
    }

    return (query[offset] | 32) == 'i'
        && (query[offset + 1] | 32) == 'n'
        && (query[offset + 2] | 32) == 's'
        && (query[offset + 3] | 32) == 'e'
        && (query[offset + 4] | 32) == 'r'
        && (query[offset + 5] | 32) == 't';
  }

  /**
   * Parse string to check presence of MOVE keyword regardless of case.
   *
   * @param query char[] of the query statement
   * @param offset position of query to start checking
   * @return boolean indicates presence of word
   */
  public static boolean parseMoveKeyword(final char[] query, int offset) {
    if (query.length < (offset + 4)) {
      return false;
    }

    return (query[offset] | 32) == 'm'
        && (query[offset + 1] | 32) == 'o'
        && (query[offset + 2] | 32) == 'v'
        && (query[offset + 3] | 32) == 'e';
  }

  /**
   * Parse string to check presence of RETURNING keyword regardless of case.
   *
   * @param query char[] of the query statement
   * @param offset position of query to start checking
   * @return boolean indicates presence of word
   */
  public static boolean parseReturningKeyword(final char[] query, int offset) {
    if (query.length < (offset + 9)) {
      return false;
    }

    return (query[offset] | 32) == 'r'
        && (query[offset + 1] | 32) == 'e'
        && (query[offset + 2] | 32) == 't'
        && (query[offset + 3] | 32) == 'u'
        && (query[offset + 4] | 32) == 'r'
        && (query[offset + 5] | 32) == 'n'
        && (query[offset + 6] | 32) == 'i'
        && (query[offset + 7] | 32) == 'n'
        && (query[offset + 8] | 32) == 'g';
  }

  /**
   * Parse string to check presence of UPDATE keyword regardless of case.
   *
   * @param query char[] of the query statement
   * @param offset position of query to start checking
   * @return boolean indicates presence of word
   */
  public static boolean parseUpdateKeyword(final char[] query, int offset) {
    if (query.length < (offset + 6)) {
      return false;
    }

    return (query[offset] | 32) == 'u'
        && (query[offset + 1] | 32) == 'p'
        && (query[offset + 2] | 32) == 'd'
        && (query[offset + 3] | 32) == 'a'
        && (query[offset + 4] | 32) == 't'
        && (query[offset + 5] | 32) == 'e';
  }

  /**
   * Parse string to check presence of VALUES keyword regardless of case.
   *
   * @param query char[] of the query statement
   * @param offset position of query to start checking
   * @return boolean indicates presence of word
   */
  public static boolean parseValuesKeyword(final char[] query, int offset) {
    if (query.length < (offset + 6)) {
      return false;
    }

    return (query[offset] | 32) == 'v'
        && (query[offset + 1] | 32) == 'a'
        && (query[offset + 2] | 32) == 'l'
        && (query[offset + 3] | 32) == 'u'
        && (query[offset + 4] | 32) == 'e'
        && (query[offset + 5] | 32) == 's';
  }

  /**
   * @param c character
   * @return true if the character is a whitespace character as defined in the backend's parser
   */
  public static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
  }

  /**
   * @param c character
   * @return true if the given character is a valid character for an operator in the backend's
   * parser
   */
  public static boolean isOperatorChar(char c) {
    /*
     * Extracted from operators defined by {self} and {op_chars}
     * in pgsql/src/backend/parser/scan.l.
     */
    return ",()[].;:+-*/%^<>=~!@#&|`?".indexOf(c) != -1;
  }

  /**
   * Checks if a character is valid as the start of an identifier.
   *
   * @param c the character to check
   * @return true if valid as first character of an identifier; false if not
   */
  public static boolean isIdentifierStartChar(char c) {
    /*
     * Extracted from {ident_start} and {ident_cont} in
     * pgsql/src/backend/parser/scan.l:
     * ident_start    [A-Za-z\200-\377_]
     * ident_cont     [A-Za-z\200-\377_0-9\$]
     */
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
        || c == '_' || c > 127;
  }

  /**
   * Checks if a character is valid as the second or later character of an identifier.
   *
   * @param c the character to check
   * @return true if valid as second or later character of an identifier; false if not
   */
  public static boolean isIdentifierContChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
        || c == '_' || c > 127
        || (c >= '0' && c <= '9')
        || c == '$';
  }

  /**
   * @param c character
   * @return true if the character terminates an identifier
   */
  public static boolean charTerminatesIdentifier(char c) {
    return c == '"' || isSpace(c) || isOperatorChar(c);
  }

  /**
   * Checks if a character is valid as the start of a dollar quoting tag.
   *
   * @param c the character to check
   * @return true if valid as first character of a dollar quoting tag; false if not
   */
  public static boolean isDollarQuoteStartChar(char c) {
    /*
     * The allowed dollar quote start and continuation characters
     * must stay in sync with what the backend defines in
     * pgsql/src/backend/parser/scan.l
     */
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
        || c == '_' || c > 127;
  }

  /**
   * Checks if a character is valid as the second or later character of a dollar quoting tag.
   *
   * @param c the character to check
   * @return true if valid as second or later character of a dollar quoting tag; false if not
   */
  public static boolean isDollarQuoteContChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
        || c == '_' || c > 127
        || (c >= '0' && c <= '9');
  }

  /**
   * Compares two sub-arrays of the given character array for equalness. If the length is zero, the
   * result is true if and only if the offsets are within the bounds of the array.
   *
   * @param arr  a char array
   * @param offA first sub-array start offset
   * @param offB second sub-array start offset
   * @param len  length of the sub arrays to compare
   * @return true if the sub-arrays are equal; false if not
   */
  private static boolean subArraysEqual(final char[] arr,
      final int offA, final int offB,
      final int len) {
    if (offA < 0 || offB < 0
        || offA >= arr.length || offB >= arr.length
        || offA + len > arr.length || offB + len > arr.length) {
      return false;
    }

    for (int i = 0; i < len; ++i) {
      if (arr[offA + i] != arr[offB + i]) {
        return false;
      }
    }

    return true;
  }

  /**
   * Converts JDBC-specific callable statement escapes {@code { [? =] call <some_function> [(?,
   * [?,..])] }} into the PostgreSQL format which is {@code select <some_function> (?, [?, ...]) as
   * result} or {@code select * from <some_function> (?, [?, ...]) as result} (7.3)
   *
   * @param jdbcSql         sql text with JDBC escapes
   * @param stdStrings      if backslash in single quotes should be regular character or escape one
   * @param serverVersion   server version
   * @param protocolVersion protocol version
   * @return SQL in appropriate for given server format
   * @throws SQLException if given SQL is malformed
   */
  public static JdbcCallParseInfo modifyJdbcCall(String jdbcSql, boolean stdStrings,
      int serverVersion, int protocolVersion) throws SQLException {
    // Mini-parser for JDBC function-call syntax (only)
    // TODO: Merge with escape processing (and parameter parsing?) so we only parse each query once.
    // RE: frequently used statements are cached (see {@link org.postgresql.jdbc.PgConnection#borrowQuery}), so this "merge" is not that important.
    String sql = jdbcSql;
    boolean isFunction = false;
    boolean outParmBeforeFunc = false;

    int len = jdbcSql.length();
    int state = 1;
    boolean inQuotes = false;
    boolean inEscape = false;
    int startIndex = -1;
    int endIndex = -1;
    boolean syntaxError = false;
    int i = 0;

    while (i < len && !syntaxError) {
      char ch = jdbcSql.charAt(i);

      switch (state) {
        case 1:  // Looking for { at start of query
          if (ch == '{') {
            ++i;
            ++state;
          } else if (Character.isWhitespace(ch)) {
            ++i;
          } else {
            // Not function-call syntax. Skip the rest of the string.
            i = len;
          }
          break;

        case 2:  // After {, looking for ? or =, skipping whitespace
          if (ch == '?') {
            outParmBeforeFunc =
                isFunction = true;   // { ? = call ... }  -- function with one out parameter
            ++i;
            ++state;
          } else if (ch == 'c' || ch == 'C') {  // { call ... }      -- proc with no out parameters
            state += 3; // Don't increase 'i'
          } else if (Character.isWhitespace(ch)) {
            ++i;
          } else {
            // "{ foo ...", doesn't make sense, complain.
            syntaxError = true;
          }
          break;

        case 3:  // Looking for = after ?, skipping whitespace
          if (ch == '=') {
            ++i;
            ++state;
          } else if (Character.isWhitespace(ch)) {
            ++i;
          } else {
            syntaxError = true;
          }
          break;

        case 4:  // Looking for 'call' after '? =' skipping whitespace
          if (ch == 'c' || ch == 'C') {
            ++state; // Don't increase 'i'.
          } else if (Character.isWhitespace(ch)) {
            ++i;
          } else {
            syntaxError = true;
          }
          break;

        case 5:  // Should be at 'call ' either at start of string or after ?=
          if ((ch == 'c' || ch == 'C') && i + 4 <= len && jdbcSql.substring(i, i + 4)
              .equalsIgnoreCase("call")) {
            isFunction = true;
            i += 4;
            ++state;
          } else if (Character.isWhitespace(ch)) {
            ++i;
          } else {
            syntaxError = true;
          }
          break;

        case 6:  // Looking for whitespace char after 'call'
          if (Character.isWhitespace(ch)) {
            // Ok, we found the start of the real call.
            ++i;
            ++state;
            startIndex = i;
          } else {
            syntaxError = true;
          }
          break;

        case 7:  // In "body" of the query (after "{ [? =] call ")
          if (ch == '\'') {
            inQuotes = !inQuotes;
            ++i;
          } else if (inQuotes && ch == '\\' && !stdStrings) {
            // Backslash in string constant, skip next character.
            i += 2;
          } else if (!inQuotes && ch == '{') {
            inEscape = !inEscape;
            ++i;
          } else if (!inQuotes && ch == '}') {
            if (!inEscape) {
              // Should be end of string.
              endIndex = i;
              ++i;
              ++state;
            } else {
              inEscape = false;
            }
          } else if (!inQuotes && ch == ';') {
            syntaxError = true;
          } else {
            // Everything else is ok.
            ++i;
          }
          break;

        case 8:  // At trailing end of query, eating whitespace
          if (Character.isWhitespace(ch)) {
            ++i;
          } else {
            syntaxError = true;
          }
          break;

        default:
          throw new IllegalStateException("somehow got into bad state " + state);
      }
    }

    // We can only legally end in a couple of states here.
    if (i == len && !syntaxError) {
      if (state == 1) {
        // Not an escaped syntax.
        return new JdbcCallParseInfo(sql, isFunction, outParmBeforeFunc);
      }
      if (state != 8) {
        syntaxError = true; // Ran out of query while still parsing
      }
    }

    if (syntaxError) {
      throw new PSQLException(
          GT.tr("Malformed function or procedure escape syntax at offset {0}.", i),
          PSQLState.STATEMENT_NOT_ALLOWED_IN_FUNCTION_CALL);
    }

    if (serverVersion < 80100 /* 8.1 */ || protocolVersion != 3) {
      sql = "select " + jdbcSql.substring(startIndex, endIndex) + " as result";
      return new JdbcCallParseInfo(sql, isFunction, outParmBeforeFunc);
    }
    String s = jdbcSql.substring(startIndex, endIndex);
    StringBuilder sb = new StringBuilder(s);
    if (outParmBeforeFunc) {
      // move the single out parameter into the function call
      // so that it can be treated like all other parameters
      boolean needComma = false;

      // have to use String.indexOf for java 2
      int opening = s.indexOf('(') + 1;
      int closing = s.indexOf(')');
      for (int j = opening; j < closing; j++) {
        if (!Character.isWhitespace(sb.charAt(j))) {
          needComma = true;
          break;
        }
      }
      if (needComma) {
        sb.insert(opening, "?,");
      } else {
        sb.insert(opening, "?");
      }
    }
    sql = "select * from " + sb.toString() + " as result";
    return new JdbcCallParseInfo(sql, isFunction, outParmBeforeFunc);
  }

}
