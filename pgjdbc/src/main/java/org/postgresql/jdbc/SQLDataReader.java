/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static java.lang.Character.isWhitespace;

import org.postgresql.core.BaseConnection;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SQLDataReader {
  public @Nullable  <T> T read(@Nullable String value, Class<T> type, BaseConnection connection, TimestampUtils timestampUtils) throws SQLException {
    if (value == null) {
      return null;
    }
    SQLData data;
    try {
      data = (SQLData) type.getConstructor().newInstance();
    } catch (Exception ex) {
      throw new PSQLException(GT.tr("An accessible no-arg constructor is required for type {0}", type), PSQLState.SYNTAX_ERROR, ex);
    }

    data.readSQL(new PgSQLInput(parseObj(value), connection, timestampUtils), data.getSQLTypeName());

    return type.cast(data);
  }

  /**
   * This will parse strings such as would be returned from "select table_name from table_name"
   *
   * <p>e.g. (42,43,44,Thing,t,1,42.3,65.97777777777777,78.94444445444,"some bytes",2024-10-10,14:12:35,"2024-10-10 14:12:35")
   *
   * @return list of parsed strings
   */
  public List<@Nullable String> parseObj(String value) {
    return parse(value, '(', ')');
  }

  /**
   * This will parse strings such as would be returned from "select array_column from table_name"
   * and accessed via PgResultSet.getObject(1, T[].class). Note this can be arrays of objects whose
   * items would then be parsed using parseObj() above.
   *
   * <p>e.g. {"2024-10-10 14:12:35",NULL}
   *
   * <p>e.g. {"(42,43,44,Thing,t,1,42.3,65.97777777777777,78.94444445444,\"some bytes\",2024-10-10,14:12:35,\"2024-10-10 14:12:35\")","(,,,,,,,,,,,,)"}
   *
   * @return list of parsed strings.
   */
  public List<@Nullable String> parseArray(String value) {
    return parse(value, '{', '}');
  }

  private static List<@Nullable String> parse(String value, char begin, char end) {
    List<@Nullable String> values = new ArrayList<>();

    int len = value.length();
    StringBuilder builder = null;

    int lastDelimIdx = -1;
    int charIdx = 0;
    while (charIdx < len) {
      char ch = value.charAt(charIdx);
      if (ch == begin) {
        //
        // Found our begin character.
        //
        lastDelimIdx = charIdx;
      } else if (ch == end) {
        //
        // Found our end character. Add the last item and break out of loop.
        //
        addParsedItem(builder, lastDelimIdx, charIdx, values);
        break;
      } else if (ch == '"') {
        //
        // Found the start of a quoted string item. So read till next closing quote.
        //
        builder = new StringBuilder();
        int index;
        for (index = charIdx + 1; index < len; ++index) {
          char ch2 = value.charAt(index);
          //
          // found potential end quote.
          //
          if (ch2 == '"') {
            //
            // Look to make sure this is not an escaped double quote. If so, add double quote character,
            // otherwise we are done, break out of loop.
            //
            if (index < len - 1 && value.charAt(index + 1) == '"') {
              ++index;
              builder.append('"');
            } else {
              break;
            }
          } else if (ch2 == '\\') {
            //
            // Found escape character. Append the next value instead of the escape character.
            // Unless it is the last character then just append the slash.
            //
            ++index;
            if (index < len) {
              builder.append(value.charAt(index));
            } else {
              builder.append(ch2);
            }
          } else {
            builder.append(ch2);
          }
        }
        //
        // Next char should be a comma or our end char. The builder contents will then
        // be added on the next pass through the loop.
        //
        charIdx = index;
      } else if (ch == ',') {
        //
        // Found a comma, so add last item, and get ready to look for next.
        //
        addParsedItem(builder, lastDelimIdx, charIdx, values);
        builder = null;
        lastDelimIdx = charIdx;
      } else {
        //
        // Ignore any whitespace we encounter
        //
        if (isWhitespace(ch)) {
          ++charIdx;
          while (charIdx < len && isWhitespace(value.charAt(charIdx))) {
            ++charIdx;
          }
          continue;
        }

        if (builder == null) {
          builder = new StringBuilder();
        }
        builder.append(ch);
      }
      ++charIdx;
    }
    return values;
  }

  private static void addParsedItem(@Nullable StringBuilder builder, int lastDelimIdx, int charIdx, List<@Nullable String> values) {
    if (lastDelimIdx == charIdx - 1) {
      values.add(null);
    } else if (builder != null) {
      values.add(builder.toString());
    }
  }
}
