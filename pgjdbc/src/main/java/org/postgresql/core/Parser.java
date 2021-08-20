/*
 * Copyright (c) 2006, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.jdbc.EscapeSyntaxCallMode;
import org.postgresql.jdbc.EscapedFunctions2;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Basic query parser infrastructure.
 * Note: This class should not be considered as pgjdbc public API.
 *
 * @author Michael Paesold (mpaesold@gmx.at)
 * @author Christopher Deckers (chrriis@gmail.com)
 */
public class Parser {
  private static final int[] NO_BINDS = new int[0];

  /**
   * Parses JDBC query into PostgreSQL's native format. Several queries might be given if separated
   * by semicolon.
   *
   * @param query                     jdbc query to parse
   * @param standardConformingStrings whether to allow backslashes to be used as escape characters
   *                                  in single quote literals
   * @param withParameters            whether to replace ?, ? with $1, $2, etc
   * @param splitStatements           whether to split statements by semicolon
   * @param isBatchedReWriteConfigured whether re-write optimization is enabled
   * @param quoteReturningIdentifiers whether to quote identifiers returned using returning clause
   * @param returningColumnNames      for simple insert, update, delete add returning with given column names
   * @return list of native queries
   * @throws SQLException if unable to add returning clause (invalid column names)
   */
  public static List<NativeQuery> parseJdbcSql(String query, boolean standardConformingStrings,
      boolean withParameters, boolean splitStatements,
      boolean isBatchedReWriteConfigured,
      boolean quoteReturningIdentifiers,
      String... returningColumnNames) throws SQLException {
    if (!withParameters && !splitStatements
        && returningColumnNames != null && returningColumnNames.length == 0) {
      return Collections.singletonList(new NativeQuery(query,
        SqlCommand.createStatementTypeInfo(SqlCommandType.BLANK)));
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
    boolean valuesBraceCloseFound = false;
    boolean isInsertPresent = false;
    boolean isReturningPresent = false;
    boolean isReturningPresentPrev = false;
    SqlCommandType currentCommandType = SqlCommandType.BLANK;
    SqlCommandType prevCommandType = SqlCommandType.BLANK;
    int numberOfStatements = 0;

    boolean whitespaceOnly = true;
    int keyWordCount = 0;
    int keywordStart = -1;
    int keywordEnd = -1;
    for (int i = 0; i < aChars.length; ++i) {
      char aChar = aChars[i];
      boolean isKeyWordChar = false;
      // ';' is ignored as it splits the queries
      whitespaceOnly &= aChar == ';' || Character.isWhitespace(aChar);
      keywordEnd = i; // parseSingleQuotes, parseDoubleQuotes, etc move index so we keep old value
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
          if (inParen == 0 && isValuesFound && !valuesBraceCloseFound) {
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
          if (inParen == 0) {
            if (!whitespaceOnly) {
              numberOfStatements++;
              nativeSql.append(aChars, fragmentStart, i - fragmentStart);
              whitespaceOnly = true;
            }
            fragmentStart = i + 1;
            if (nativeSql.length() > 0) {
              if (addReturning(nativeSql, currentCommandType, returningColumnNames, isReturningPresent, quoteReturningIdentifiers)) {
                isReturningPresent = true;
              }

              if (splitStatements) {
                if (nativeQueries == null) {
                  nativeQueries = new ArrayList<NativeQuery>();
                }

                if (!isValuesFound || !isCurrentReWriteCompatible || valuesBraceClosePosition == -1
                    || (bindPositions != null
                    && valuesBraceClosePosition < bindPositions.get(bindPositions.size() - 1))) {
                  valuesBraceOpenPosition = -1;
                  valuesBraceClosePosition = -1;
                }

                nativeQueries.add(new NativeQuery(nativeSql.toString(),
                    toIntArray(bindPositions), false,
                    SqlCommand.createStatementTypeInfo(
                        currentCommandType, isBatchedReWriteConfigured, valuesBraceOpenPosition,
                        valuesBraceClosePosition,
                        isReturningPresent, nativeQueries.size())));
              }
            }
            prevCommandType = currentCommandType;
            isReturningPresentPrev = isReturningPresent;
            currentCommandType = SqlCommandType.BLANK;
            isReturningPresent = false;
            if (splitStatements) {
              // Prepare for next query
              if (bindPositions != null) {
                bindPositions.clear();
              }
              nativeSql.setLength(0);
              isValuesFound = false;
              isCurrentReWriteCompatible = false;
              valuesBraceOpenPosition = -1;
              valuesBraceClosePosition = -1;
              valuesBraceCloseFound = false;
            }
          }
          break;

        default:
          if (keywordStart >= 0) {
            // When we are inside a keyword, we need to detect keyword end boundary
            // Note that isKeyWordChar is initialized to false before the switch, so
            // all other characters would result in isKeyWordChar=false
            isKeyWordChar = isIdentifierContChar(aChar);
            break;
          }
          // Not in keyword, so just detect next keyword start
          isKeyWordChar = isIdentifierStartChar(aChar);
          if (isKeyWordChar) {
            keywordStart = i;
            if (valuesBraceOpenPosition != -1 && inParen == 0) {
              // When the statement already has multi-values, stop looking for more of them
              // Since values(?,?),(?,?),... should not contain keywords in the middle
              valuesBraceCloseFound = true;
            }
          }
          break;
      }
      if (keywordStart >= 0 && (i == aChars.length - 1 || !isKeyWordChar)) {
        int wordLength = (isKeyWordChar ? i + 1 : keywordEnd) - keywordStart;
        if (currentCommandType == SqlCommandType.BLANK) {
          if (wordLength == 6 && parseCreateKeyword(aChars, keywordStart)) {
            currentCommandType = SqlCommandType.CREATE;
          } else if (wordLength == 5 && parseAlterKeyword(aChars, keywordStart)) {
            currentCommandType = SqlCommandType.ALTER;
          } else if (wordLength == 6 && parseUpdateKeyword(aChars, keywordStart)) {
            currentCommandType = SqlCommandType.UPDATE;
          } else if (wordLength == 6 && parseDeleteKeyword(aChars, keywordStart)) {
            currentCommandType = SqlCommandType.DELETE;
          } else if (wordLength == 4 && parseMoveKeyword(aChars, keywordStart)) {
            currentCommandType = SqlCommandType.MOVE;
          } else if (wordLength == 6 && parseSelectKeyword(aChars, keywordStart)) {
            currentCommandType = SqlCommandType.SELECT;
          } else if (wordLength == 4 && parseWithKeyword(aChars, keywordStart)) {
            currentCommandType = SqlCommandType.WITH;
          } else if (wordLength == 6 && parseInsertKeyword(aChars, keywordStart)) {
            if (!isInsertPresent && (nativeQueries == null || nativeQueries.isEmpty())) {
              // Only allow rewrite for insert command starting with the insert keyword.
              // Else, too many risks of wrong interpretation.
              isCurrentReWriteCompatible = keyWordCount == 0;
              isInsertPresent = true;
              currentCommandType = SqlCommandType.INSERT;
            } else {
              isCurrentReWriteCompatible = false;
            }
          }
        } else if (currentCommandType == SqlCommandType.WITH
            && inParen == 0) {
          SqlCommandType command = parseWithCommandType(aChars, i, keywordStart, wordLength);
          if (command != null) {
            currentCommandType = command;
          }
        }
        if (inParen != 0 || aChar == ')') {
          // RETURNING and VALUES cannot be present in braces
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

    if (!isValuesFound || !isCurrentReWriteCompatible || valuesBraceClosePosition == -1
        || (bindPositions != null
        && valuesBraceClosePosition < bindPositions.get(bindPositions.size() - 1))) {
      valuesBraceOpenPosition = -1;
      valuesBraceClosePosition = -1;
    }

    if (fragmentStart < aChars.length && !whitespaceOnly) {
      nativeSql.append(aChars, fragmentStart, aChars.length - fragmentStart);
    } else {
      if (numberOfStatements > 1) {
        isReturningPresent = false;
        currentCommandType = SqlCommandType.BLANK;
      } else if (numberOfStatements == 1) {
        isReturningPresent = isReturningPresentPrev;
        currentCommandType = prevCommandType;
      }
    }

    if (nativeSql.length() == 0) {
      return nativeQueries != null ? nativeQueries : Collections.<NativeQuery>emptyList();
    }

    if (addReturning(nativeSql, currentCommandType, returningColumnNames, isReturningPresent, quoteReturningIdentifiers)) {
      isReturningPresent = true;
    }

    NativeQuery lastQuery = new NativeQuery(nativeSql.toString(),
        toIntArray(bindPositions), !splitStatements,
        SqlCommand.createStatementTypeInfo(currentCommandType,
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

  private static @Nullable SqlCommandType parseWithCommandType(char[] aChars, int i, int keywordStart,
      int wordLength) {
    // This parses `with x as (...) ...`
    // Corner case is `with select as (insert ..) select * from select
    SqlCommandType command;
    if (wordLength == 6 && parseUpdateKeyword(aChars, keywordStart)) {
      command = SqlCommandType.UPDATE;
    } else if (wordLength == 6 && parseDeleteKeyword(aChars, keywordStart)) {
      command = SqlCommandType.DELETE;
    } else if (wordLength == 6 && parseInsertKeyword(aChars, keywordStart)) {
      command = SqlCommandType.INSERT;
    } else if (wordLength == 6 && parseSelectKeyword(aChars, keywordStart)) {
      command = SqlCommandType.SELECT;
    } else {
      return null;
    }
    // update/delete/insert/select keyword detected
    // Check if `AS` follows
    int nextInd = i;
    // The loop should skip whitespace and comments
    for (; nextInd < aChars.length; nextInd++) {
      char nextChar = aChars[nextInd];
      if (nextChar == '-') {
        nextInd = Parser.parseLineComment(aChars, nextInd);
      } else if (nextChar == '/') {
        nextInd = Parser.parseBlockComment(aChars, nextInd);
      } else if (Character.isWhitespace(nextChar)) {
        // Skip whitespace
        continue;
      } else {
        break;
      }
    }
    if (nextInd + 2 >= aChars.length
        || (!parseAsKeyword(aChars, nextInd)
        || isIdentifierContChar(aChars[nextInd + 2]))) {
      return command;
    }
    return null;
  }

  private static boolean addReturning(StringBuilder nativeSql, SqlCommandType currentCommandType,
      String[] returningColumnNames, boolean isReturningPresent, boolean quoteReturningIdentifiers) throws SQLException {
    if (isReturningPresent || returningColumnNames.length == 0) {
      return false;
    }
    if (currentCommandType != SqlCommandType.INSERT
        && currentCommandType != SqlCommandType.UPDATE
        && currentCommandType != SqlCommandType.DELETE
        && currentCommandType != SqlCommandType.WITH) {
      return false;
    }

    nativeSql.append("\nRETURNING ");
    if (returningColumnNames.length == 1 && returningColumnNames[0].charAt(0) == '*') {
      nativeSql.append('*');
      return true;
    }
    for (int col = 0; col < returningColumnNames.length; col++) {
      String columnName = returningColumnNames[col];
      if (col > 0) {
        nativeSql.append(", ");
      }
      /*
      If the client quotes identifiers then doing so again would create an error
       */
      if (quoteReturningIdentifiers) {
        Utils.escapeIdentifier(nativeSql, columnName);
      } else {
        nativeSql.append( columnName );
      }
    }
    return true;
  }

  /**
   * Converts {@code List<Integer>} to {@code int[]}. Empty and {@code null} lists are converted to
   * empty array.
   *
   * @param list input list
   * @return output array
   */
  private static int[] toIntArray(@Nullable List<Integer> list) {
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
   * <p>Find the end of the single-quoted string starting at the given offset.</p>
   *
   * <p>Note: for {@code 'single '' quote in string'}, this method currently returns the offset of
   * first {@code '} character after the initial one. The caller must call the method a second time
   * for the second part of the quoted string.</p>
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
   * <p>Find the end of the double-quoted string starting at the given offset.</p>
   *
   * <p>Note: for {@code "double "" quote in string"}, this method currently
   * returns the offset of first {@code &quot;} character after the initial one. The caller must
   * call the method a second time for the second part of the quoted string.</p>
   *
   * @param query  query
   * @param offset start offset
   * @return position of the end of the double-quoted string
   */
  public static int parseDoubleQuotes(final char[] query, int offset) {
    while (++offset < query.length && query[offset] != '"') {
      // do nothing
    }
    return offset;
  }

  /**
   * Test if the dollar character ({@code $}) at the given offset starts a dollar-quoted string and
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
   * Test if the {@code -} character at {@code offset} starts a {@code --} style line comment,
   * and return the position of the first {@code \r} or {@code \n} character.
   *
   * @param query  query
   * @param offset start offset
   * @return position of the first {@code \r} or {@code \n} character
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
   * Test if the {@code /} character at {@code offset} starts a block comment, and return the
   * position of the last {@code /} character.
   *
   * @param query  query
   * @param offset start offset
   * @return position of the last {@code /} character
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
   * Parse string to check presence of SELECT keyword regardless of case.
   *
   * @param query char[] of the query statement
   * @param offset position of query to start checking
   * @return boolean indicates presence of word
   */
  public static boolean parseSelectKeyword(final char[] query, int offset) {
    if (query.length < (offset + 6)) {
      return false;
    }

    return (query[offset] | 32) == 's'
        && (query[offset + 1] | 32) == 'e'
        && (query[offset + 2] | 32) == 'l'
        && (query[offset + 3] | 32) == 'e'
        && (query[offset + 4] | 32) == 'c'
        && (query[offset + 5] | 32) == 't';
  }

  /**
   * Parse string to check presence of CREATE keyword regardless of case.
   *
   * @param query char[] of the query statement
   * @param offset position of query to start checking
   * @return boolean indicates presence of word
   */
  public static boolean parseAlterKeyword(final char[] query, int offset) {
    if (query.length < (offset + 5)) {
      return false;
    }

    return (query[offset] | 32) == 'a'
        && (query[offset + 1] | 32) == 'l'
        && (query[offset + 2] | 32) == 't'
        && (query[offset + 3] | 32) == 'e'
        && (query[offset + 4] | 32) == 'r';
  }

  /**
   * Parse string to check presence of CREATE keyword regardless of case.
   *
   * @param query char[] of the query statement
   * @param offset position of query to start checking
   * @return boolean indicates presence of word
   */
  public static boolean parseCreateKeyword(final char[] query, int offset) {
    if (query.length < (offset + 6)) {
      return false;
    }

    return (query[offset] | 32) == 'c'
        && (query[offset + 1] | 32) == 'r'
        && (query[offset + 2] | 32) == 'e'
        && (query[offset + 3] | 32) == 'a'
        && (query[offset + 4] | 32) == 't'
        && (query[offset + 5] | 32) == 'e';
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
   * Faster version of {@link Long#parseLong(String)} when parsing a substring is required
   *
   * @param s string to parse
   * @param beginIndex begin index
   * @param endIndex end index
   * @return long value
   */
  public static long parseLong(String s, int beginIndex, int endIndex) {
    // Fallback to default implementation in case the string is long
    if (endIndex - beginIndex > 16) {
      return Long.parseLong(s.substring(beginIndex, endIndex));
    }
    long res = digitAt(s, beginIndex);
    for (beginIndex++; beginIndex < endIndex; beginIndex++) {
      res = res * 10 + digitAt(s, beginIndex);
    }
    return res;
  }

  /**
   * Parse string to check presence of WITH keyword regardless of case.
   *
   * @param query char[] of the query statement
   * @param offset position of query to start checking
   * @return boolean indicates presence of word
   */
  public static boolean parseWithKeyword(final char[] query, int offset) {
    if (query.length < (offset + 4)) {
      return false;
    }

    return (query[offset] | 32) == 'w'
        && (query[offset + 1] | 32) == 'i'
        && (query[offset + 2] | 32) == 't'
        && (query[offset + 3] | 32) == 'h';
  }

  /**
   * Parse string to check presence of AS keyword regardless of case.
   *
   * @param query char[] of the query statement
   * @param offset position of query to start checking
   * @return boolean indicates presence of word
   */
  public static boolean parseAsKeyword(final char[] query, int offset) {
    if (query.length < (offset + 2)) {
      return false;
    }

    return (query[offset] | 32) == 'a'
        && (query[offset + 1] | 32) == 's';
  }

  /**
   * Returns true if a given string {@code s} has digit at position {@code pos}.
   * @param s input string
   * @param pos position (0-based)
   * @return true if input string s has digit at position pos
   */
  public static boolean isDigitAt(String s, int pos) {
    return pos > 0 && pos < s.length() && Character.isDigit(s.charAt(pos));
  }

  /**
   * Converts digit at position {@code pos} in string {@code s} to integer or throws.
   * @param s input string
   * @param pos position (0-based)
   * @return integer value of a digit at position pos
   * @throws NumberFormatException if character at position pos is not an integer
   */
  public static int digitAt(String s, int pos) {
    int c = s.charAt(pos) - '0';
    if (c < 0 || c > 9) {
      throw new NumberFormatException("Input string: \"" + s + "\", position: " + pos);
    }
    return c;
  }

  /**
   * Identifies characters which the backend scanner considers to be whitespace.
   *
   * <p>
   * https://github.com/postgres/postgres/blob/17bb62501787c56e0518e61db13a523d47afd724/src/backend/parser/scan.l#L194-L198
   * </p>
   *
   * @param c character
   * @return true if the character is a whitespace character as defined in the backend's parser
   */
  public static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
  }

  /**
   * Identifies white space characters which the backend uses to determine if a
   * {@code String} value needs to be quoted in array representation.
   *
   * <p>
   * https://github.com/postgres/postgres/blob/f2c587067a8eb9cf1c8f009262381a6576ba3dd0/src/backend/utils/adt/arrayfuncs.c#L421-L438
   * </p>
   *
   * @param c
   *          Character to examine.
   * @return Indication if the character is a whitespace which back end will
   *         escape.
   */
  public static boolean isArrayWhiteSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
  }

  /**
   * @param c character
   * @return true if the given character is a valid character for an operator in the backend's
   *     parser
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
   * PostgreSQL 9.4 allows column names like _, ‿, ⁀, ⁔, ︳, ︴, ﹍, ﹎, ﹏, ＿, so
   * it is assumed isJavaIdentifierPart is good enough for PostgreSQL.
   *
   * @param c the character to check
   * @return true if valid as first character of an identifier; false if not
   * @see <a href="https://www.postgresql.org/docs/9.6/static/sql-syntax-lexical.html#SQL-SYNTAX-IDENTIFIERS">Identifiers and Key Words</a>
   */
  public static boolean isIdentifierStartChar(char c) {
    /*
     * PostgreSQL's implmementation is located in
     * pgsql/src/backend/parser/scan.l:
     * ident_start    [A-Za-z\200-\377_]
     * ident_cont     [A-Za-z\200-\377_0-9\$]
     * however is is not clear how that interacts with unicode, so we just use Java's implementation.
     */
    return Character.isJavaIdentifierStart(c);
  }

  /**
   * Checks if a character is valid as the second or later character of an identifier.
   *
   * @param c the character to check
   * @return true if valid as second or later character of an identifier; false if not
   */
  public static boolean isIdentifierContChar(char c) {
    return Character.isJavaIdentifierPart(c);
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
     *
     * The quoted string starts with $foo$ where "foo" is an optional string
     * in the form of an identifier, except that it may not contain "$",
     * and extends to the first occurrence of an identical string.
     * There is *no* processing of the quoted text.
     */
    return c != '$' && isIdentifierStartChar(c);
  }

  /**
   * Checks if a character is valid as the second or later character of a dollar quoting tag.
   *
   * @param c the character to check
   * @return true if valid as second or later character of a dollar quoting tag; false if not
   */
  public static boolean isDollarQuoteContChar(char c) {
    return c != '$' && isIdentifierContChar(c);
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
   * @param jdbcSql              sql text with JDBC escapes
   * @param stdStrings           if backslash in single quotes should be regular character or escape one
   * @param serverVersion        server version
   * @param protocolVersion      protocol version
   * @param escapeSyntaxCallMode mode specifying whether JDBC escape call syntax is transformed into a CALL/SELECT statement
   * @return SQL in appropriate for given server format
   * @throws SQLException if given SQL is malformed
   */
  public static JdbcCallParseInfo modifyJdbcCall(String jdbcSql, boolean stdStrings,
      int serverVersion, int protocolVersion, EscapeSyntaxCallMode escapeSyntaxCallMode) throws SQLException {
    // Mini-parser for JDBC function-call syntax (only)
    // TODO: Merge with escape processing (and parameter parsing?) so we only parse each query once.
    // RE: frequently used statements are cached (see {@link org.postgresql.jdbc.PgConnection#borrowQuery}), so this "merge" is not that important.
    String sql = jdbcSql;
    boolean isFunction = false;
    boolean outParamBeforeFunc = false;

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
            outParamBeforeFunc =
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

        // Detect PostgreSQL native CALL.
        // (OUT parameter registration, needed for stored procedures with INOUT arguments, will fail without this)
        i = 0;
        while (i < len && Character.isWhitespace(jdbcSql.charAt(i))) {
          i++; // skip any preceding whitespace
        }
        if (i < len - 5) { // 5 == length of "call" + 1 whitespace
          //Check for CALL followed by whitespace
          char ch = jdbcSql.charAt(i);
          if ((ch == 'c' || ch == 'C') && jdbcSql.substring(i, i + 4).equalsIgnoreCase("call")
               && Character.isWhitespace(jdbcSql.charAt(i + 4))) {
            isFunction = true;
          }
        }
        return new JdbcCallParseInfo(sql, isFunction);
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

    String prefix;
    String suffix;
    if (escapeSyntaxCallMode == EscapeSyntaxCallMode.SELECT || serverVersion < 110000
        || (outParamBeforeFunc && escapeSyntaxCallMode == EscapeSyntaxCallMode.CALL_IF_NO_RETURN)) {
      prefix = "select * from ";
      suffix = " as result";
    } else {
      prefix = "call ";
      suffix = "";
    }

    String s = jdbcSql.substring(startIndex, endIndex);
    int prefixLength = prefix.length();
    StringBuilder sb = new StringBuilder(prefixLength + jdbcSql.length() + suffix.length() + 10);
    sb.append(prefix);
    sb.append(s);

    int opening = s.indexOf('(') + 1;
    if (opening == 0) {
      // here the function call has no parameters declaration eg : "{ ? = call pack_getValue}"
      sb.append(outParamBeforeFunc ? "(?)" : "()");
    } else if (outParamBeforeFunc) {
      // move the single out parameter into the function call
      // so that it can be treated like all other parameters
      boolean needComma = false;

      // the following loop will check if the function call has parameters
      // eg "{ ? = call pack_getValue(?) }" vs "{ ? = call pack_getValue() }
      for (int j = opening + prefixLength; j < sb.length(); j++) {
        char c = sb.charAt(j);
        if (c == ')') {
          break;
        }

        if (!Character.isWhitespace(c)) {
          needComma = true;
          break;
        }
      }

      // insert the return parameter as the first parameter of the function call
      if (needComma) {
        sb.insert(opening + prefixLength, "?,");
      } else {
        sb.insert(opening + prefixLength, "?");
      }
    }

    if (!suffix.isEmpty()) {
      sql = sb.append(suffix).toString();
    } else {
      sql = sb.toString();
    }
    return new JdbcCallParseInfo(sql, isFunction);
  }

  /**
   * <p>Filter the SQL string of Java SQL Escape clauses.</p>
   *
   * <p>Currently implemented Escape clauses are those mentioned in 11.3 in the specification.
   * Basically we look through the sql string for {d xxx}, {t xxx}, {ts xxx}, {oj xxx} or {fn xxx}
   * in non-string sql code. When we find them, we just strip the escape part leaving only the xxx
   * part. So, something like "select * from x where d={d '2001-10-09'}" would return "select * from
   * x where d= '2001-10-09'".</p>
   *
   * @param sql                       the original query text
   * @param replaceProcessingEnabled  whether replace_processing_enabled is on
   * @param standardConformingStrings whether standard_conforming_strings is on
   * @return PostgreSQL-compatible SQL
   * @throws SQLException if given SQL is wrong
   */
  public static String replaceProcessing(String sql, boolean replaceProcessingEnabled,
      boolean standardConformingStrings) throws SQLException {
    if (replaceProcessingEnabled) {
      // Since escape codes can only appear in SQL CODE, we keep track
      // of if we enter a string or not.
      int len = sql.length();
      char[] chars = sql.toCharArray();
      StringBuilder newsql = new StringBuilder(len);
      int i = 0;
      while (i < len) {
        i = parseSql(chars, i, newsql, false, standardConformingStrings);
        // We need to loop here in case we encounter invalid
        // SQL, consider: SELECT a FROM t WHERE (1 > 0)) ORDER BY a
        // We can't ending replacing after the extra closing paren
        // because that changes a syntax error to a valid query
        // that isn't what the user specified.
        if (i < len) {
          newsql.append(chars[i]);
          i++;
        }
      }
      return newsql.toString();
    } else {
      return sql;
    }
  }

  /**
   * parse the given sql from index i, appending it to the given buffer until we hit an unmatched
   * right parentheses or end of string. When the stopOnComma flag is set we also stop processing
   * when a comma is found in sql text that isn't inside nested parenthesis.
   *
   * @param sql the original query text
   * @param i starting position for replacing
   * @param newsql where to write the replaced output
   * @param stopOnComma should we stop after hitting the first comma in sql text?
   * @param stdStrings whether standard_conforming_strings is on
   * @return the position we stopped processing at
   * @throws SQLException if given SQL is wrong
   */
  private static int parseSql(char[] sql, int i, StringBuilder newsql, boolean stopOnComma,
      boolean stdStrings) throws SQLException {
    SqlParseState state = SqlParseState.IN_SQLCODE;
    int len = sql.length;
    int nestedParenthesis = 0;
    boolean endOfNested = false;

    // because of the ++i loop
    i--;
    while (!endOfNested && ++i < len) {
      char c = sql[i];

      state_switch:
      switch (state) {
        case IN_SQLCODE:
          if (c == '$') {
            int i0 = i;
            i = parseDollarQuotes(sql, i);
            checkParsePosition(i, len, i0, sql,
                "Unterminated dollar quote started at position {0} in SQL {1}. Expected terminating $$");
            newsql.append(sql, i0, i - i0 + 1);
            break;
          } else if (c == '\'') {
            // start of a string?
            int i0 = i;
            i = parseSingleQuotes(sql, i, stdStrings);
            checkParsePosition(i, len, i0, sql,
                "Unterminated string literal started at position {0} in SQL {1}. Expected ' char");
            newsql.append(sql, i0, i - i0 + 1);
            break;
          } else if (c == '"') {
            // start of a identifier?
            int i0 = i;
            i = parseDoubleQuotes(sql, i);
            checkParsePosition(i, len, i0, sql,
                "Unterminated identifier started at position {0} in SQL {1}. Expected \" char");
            newsql.append(sql, i0, i - i0 + 1);
            break;
          } else if (c == '/') {
            int i0 = i;
            i = parseBlockComment(sql, i);
            checkParsePosition(i, len, i0, sql,
                "Unterminated block comment started at position {0} in SQL {1}. Expected */ sequence");
            newsql.append(sql, i0, i - i0 + 1);
            break;
          } else if (c == '-') {
            int i0 = i;
            i = parseLineComment(sql, i);
            newsql.append(sql, i0, i - i0 + 1);
            break;
          } else if (c == '(') { // begin nested sql
            nestedParenthesis++;
          } else if (c == ')') { // end of nested sql
            nestedParenthesis--;
            if (nestedParenthesis < 0) {
              endOfNested = true;
              break;
            }
          } else if (stopOnComma && c == ',' && nestedParenthesis == 0) {
            endOfNested = true;
            break;
          } else if (c == '{') { // start of an escape code?
            if (i + 1 < len) {
              SqlParseState[] availableStates = SqlParseState.VALUES;
              // skip first state, it's not a escape code state
              for (int j = 1; j < availableStates.length; j++) {
                SqlParseState availableState = availableStates[j];
                int matchedPosition = availableState.getMatchedPosition(sql, i + 1);
                if (matchedPosition == 0) {
                  continue;
                }
                i += matchedPosition;
                if (availableState.replacementKeyword != null) {
                  newsql.append(availableState.replacementKeyword);
                }
                state = availableState;
                break state_switch;
              }
            }
          }
          newsql.append(c);
          break;

        case ESC_FUNCTION:
          // extract function name
          i = escapeFunction(sql, i, newsql, stdStrings);
          state = SqlParseState.IN_SQLCODE; // end of escaped function (or query)
          break;
        case ESC_DATE:
        case ESC_TIME:
        case ESC_TIMESTAMP:
        case ESC_OUTERJOIN:
        case ESC_ESCAPECHAR:
          if (c == '}') {
            state = SqlParseState.IN_SQLCODE; // end of escape code.
          } else {
            newsql.append(c);
          }
          break;
      } // end switch
    }
    return i;
  }

  private static int findOpenBrace(char[] sql, int i) {
    int posArgs = i;
    while (posArgs < sql.length && sql[posArgs] != '(') {
      posArgs++;
    }
    return posArgs;
  }

  private static void checkParsePosition(int i, int len, int i0, char[] sql,
      String message)
      throws PSQLException {
    if (i < len) {
      return;
    }
    throw new PSQLException(
        GT.tr(message, i0, new String(sql)),
        PSQLState.SYNTAX_ERROR);
  }

  private static int escapeFunction(char[] sql, int i, StringBuilder newsql, boolean stdStrings) throws SQLException {
    String functionName;
    int argPos = findOpenBrace(sql, i);
    if (argPos < sql.length) {
      functionName = new String(sql, i, argPos - i).trim();
      // extract arguments
      i = argPos + 1;// we start the scan after the first (
      i = escapeFunctionArguments(newsql, functionName, sql, i, stdStrings);
    }
    // go to the end of the function copying anything found
    i++;
    while (i < sql.length && sql[i] != '}') {
      newsql.append(sql[i++]);
    }
    return i;
  }

  /**
   * Generate sql for escaped functions.
   *
   * @param newsql destination StringBuilder
   * @param functionName the escaped function name
   * @param sql input SQL text (containing arguments of a function call with possible JDBC escapes)
   * @param i position in the input SQL
   * @param stdStrings whether standard_conforming_strings is on
   * @return the right PostgreSQL sql
   * @throws SQLException if something goes wrong
   */
  private static int escapeFunctionArguments(StringBuilder newsql, String functionName, char[] sql, int i,
      boolean stdStrings)
      throws SQLException {
    // Maximum arity of functions in EscapedFunctions is 3
    List<CharSequence> parsedArgs = new ArrayList<CharSequence>(3);
    while (true) {
      StringBuilder arg = new StringBuilder();
      int lastPos = i;
      i = parseSql(sql, i, arg, true, stdStrings);
      if (i != lastPos) {
        parsedArgs.add(arg);
      }
      if (i >= sql.length // should not happen
          || sql[i] != ',') {
        break;
      }
      i++;
    }
    Method method = EscapedFunctions2.getFunction(functionName);
    if (method == null) {
      newsql.append(functionName);
      EscapedFunctions2.appendCall(newsql, "(", ",", ")", parsedArgs);
      return i;
    }
    try {
      method.invoke(null, newsql, parsedArgs);
    } catch (InvocationTargetException e) {
      Throwable targetException = e.getTargetException();
      if (targetException instanceof SQLException) {
        throw (SQLException) targetException;
      } else {
        String message = targetException == null ? "no message" : targetException.getMessage();
        throw new PSQLException(message, PSQLState.SYSTEM_ERROR);
      }
    } catch (IllegalAccessException e) {
      throw new PSQLException(e.getMessage(), PSQLState.SYSTEM_ERROR);
    }
    return i;
  }

  private static final char[] QUOTE_OR_ALPHABETIC_MARKER = {'\"', '0'};
  private static final char[] QUOTE_OR_ALPHABETIC_MARKER_OR_PARENTHESIS = {'\"', '0', '('};
  private static final char[] SINGLE_QUOTE = {'\''};

  // Static variables for parsing SQL when replaceProcessing is true.
  private enum SqlParseState {
    IN_SQLCODE,
    ESC_DATE("d", SINGLE_QUOTE, "DATE "),
    ESC_TIME("t", SINGLE_QUOTE, "TIME "),

    ESC_TIMESTAMP("ts", SINGLE_QUOTE, "TIMESTAMP "),
    ESC_FUNCTION("fn", QUOTE_OR_ALPHABETIC_MARKER, null),
    ESC_OUTERJOIN("oj", QUOTE_OR_ALPHABETIC_MARKER_OR_PARENTHESIS, null),
    ESC_ESCAPECHAR("escape", SINGLE_QUOTE, "ESCAPE ");

    private static final SqlParseState[] VALUES = values();

    private final char[] escapeKeyword;
    private final char[] allowedValues;
    private final @Nullable String replacementKeyword;

    SqlParseState() {
      this("", new char[0], null);
    }

    SqlParseState(String escapeKeyword, char[] allowedValues,
        @Nullable String replacementKeyword) {
      this.escapeKeyword = escapeKeyword.toCharArray();
      this.allowedValues = allowedValues;
      this.replacementKeyword = replacementKeyword;
    }

    private boolean startMatches(char[] sql, int pos) {
      // check for the keyword
      for (char c : escapeKeyword) {
        if (pos >= sql.length) {
          return false;
        }
        char curr = sql[pos++];
        if (curr != c && curr != Character.toUpperCase(c)) {
          return false;
        }
      }
      return pos < sql.length;
    }

    private int getMatchedPosition(char[] sql, int pos) {
      // check for the keyword
      if (!startMatches(sql, pos)) {
        return 0;
      }

      int newPos = pos + escapeKeyword.length;

      // check for the beginning of the value
      char curr = sql[newPos];
      // ignore any in-between whitespace
      while (curr == ' ') {
        newPos++;
        if (newPos >= sql.length) {
          return 0;
        }
        curr = sql[newPos];
      }
      for (char c : allowedValues) {
        if (curr == c || (c == '0' && Character.isLetter(curr))) {
          return newPos - pos;
        }
      }
      return 0;
    }
  }
}
