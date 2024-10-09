/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static java.lang.Character.isWhitespace;

import java.sql.SQLData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SQLDataReader {
  public <T> T read(@Nullable Object obj, Class<T> type, TimestampUtils timestampUtils) throws SQLException {
    if (obj == null) {
      return null;
    }
    SQLData data;
    try {
      data = (SQLData) type.getConstructor().newInstance();
    } catch (Exception ex) {
      throw new SQLException(String.format("An accessible no-arg constructor is required for type [%s]", type), ex);
    }

    data.readSQL(new PgSQLInput(parse(obj.toString(), '(', ')'), timestampUtils), data.getSQLTypeName());

    return type.cast(data);
  }

  public List<String> parseArray(String value) {
    return parse(value, '{', '}');
  }

  private List<String> parse(String value, char begin, char end) {
    List<String> values = new ArrayList<>();

    int len = value.length();
    StringBuilder builder = null;

    int lastDelimIdx = -1;

    scan: for (int charIdx = 0; charIdx < len; ++charIdx) {
      char ch = value.charAt(charIdx);
      if (ch == begin) {
        lastDelimIdx = charIdx;
      } else if (ch == end) {
        addTextElement(builder, lastDelimIdx == charIdx - 1, values);
        break scan;
      } else if (ch == '"') {
        builder = new StringBuilder();
        charIdx = readString(value, charIdx, builder);
      } else if (ch == ',') {
        addTextElement(builder, lastDelimIdx == charIdx - 1, values);
        builder = null;
        lastDelimIdx = charIdx;
      } else {
        //
        // ignore any whitespace we encounter
        //
        if (isWhitespace(ch)) {
          ++charIdx;
          while (charIdx < len && isWhitespace(value.charAt(charIdx))) {
            ++charIdx;
          }
          break;
        }

        if (builder == null) {
          builder = new StringBuilder();
        }
        builder.append(ch);
      }
    }
    return values;
  }

  private int readString(String value, int start, StringBuilder builder) {
    int len = value.length();
    int index;

    scan: for (index = start + 1; index < len; ++index) {
      char ch = value.charAt(index);
      switch (ch) {
        case '"':
          if (index < value.length() - 1 && value.charAt(index + 1) == '"') {
            ++index;
            builder.append('"');
            break;
          } else {
            break scan;
          }
        case '\\':
          ++index;
          if (index < value.length()) {
            ch = value.charAt(index);
          }
        default:
          builder.append(ch);
      }
    }

    return index;
  }

  private void addTextElement(StringBuilder builder, boolean empty, List<String> values) {
    if (empty) {
      values.add(null);
    } else {
      values.add(builder.toString());
    }
  }
}
