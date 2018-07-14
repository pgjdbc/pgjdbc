/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * This class stores supported escaped function.
 * Note: this is a pgjdbc-internal class, so it is not supposed to be used outside of the driver.
 */
public final class EscapedFunctions2 {
  // constants for timestampadd and timestampdiff
  private static final String SQL_TSI_ROOT = "SQL_TSI_";
  private static final String SQL_TSI_DAY = "SQL_TSI_DAY";
  private static final String SQL_TSI_FRAC_SECOND = "SQL_TSI_FRAC_SECOND";
  private static final String SQL_TSI_HOUR = "SQL_TSI_HOUR";
  private static final String SQL_TSI_MINUTE = "SQL_TSI_MINUTE";
  private static final String SQL_TSI_MONTH = "SQL_TSI_MONTH";
  private static final String SQL_TSI_QUARTER = "SQL_TSI_QUARTER";
  private static final String SQL_TSI_SECOND = "SQL_TSI_SECOND";
  private static final String SQL_TSI_WEEK = "SQL_TSI_WEEK";
  private static final String SQL_TSI_YEAR = "SQL_TSI_YEAR";

  /**
   * storage for functions implementations
   */
  private static final ConcurrentMap<String, Method> FUNCTION_MAP = createFunctionMap("sql");

  private static ConcurrentMap<String, Method> createFunctionMap(String prefix) {
    Method[] methods = EscapedFunctions2.class.getMethods();
    ConcurrentMap<String, Method> functionMap = new ConcurrentHashMap<String, Method>(methods.length * 2);
    for (Method method : methods) {
      if (method.getName().startsWith(prefix)) {
        functionMap.put(method.getName().substring(prefix.length()).toLowerCase(Locale.US), method);
      }
    }
    return functionMap;
  }

  /**
   * get Method object implementing the given function
   *
   * @param functionName name of the searched function
   * @return a Method object or null if not found
   */
  public static Method getFunction(String functionName) {
    Method method = FUNCTION_MAP.get(functionName);
    if (method != null) {
      return method;
    }
    String nameLower = functionName.toLowerCase(Locale.US);
    if (nameLower == functionName) {
      // Input name was in lower case, the function is not there
      return null;
    }
    method = FUNCTION_MAP.get(nameLower);
    if (method != null && FUNCTION_MAP.size() < 1000) {
      // Avoid OutOfMemoryError in case input function names are randomized
      // The number of methods is finite, however the number of upper-lower case combinations
      // is quite a few (e.g. substr, Substr, sUbstr, SUbstr, etc).
      FUNCTION_MAP.putIfAbsent(functionName, method);
    }
    return method;
  }

  // ** numeric functions translations **

  /**
   * ceiling to ceil translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlceiling(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "ceil(", "ceiling", parsedArgs);
  }

  /**
   * log to ln translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqllog(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "ln(", "log", parsedArgs);
  }

  /**
   * log10 to log translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqllog10(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "log(", "log10", parsedArgs);
  }

  /**
   * power to pow translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlpower(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    twoArgumentsFunctionCall(buf, "pow(", "power", parsedArgs);
  }

  /**
   * truncate to trunc translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqltruncate(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    twoArgumentsFunctionCall(buf, "trunc(", "truncate", parsedArgs);
  }

  // ** string functions translations **

  /**
   * char to chr translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlchar(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "chr(", "char", parsedArgs);
  }

  /**
   * concat translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   */
  public static void sqlconcat(StringBuilder buf, List<? extends CharSequence> parsedArgs) {
    appendCall(buf, "(", "||", ")", parsedArgs);
  }

  /**
   * insert to overlay translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlinsert(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 4) {
      throw new PSQLException(GT.tr("{0} function takes four and only four argument.", "insert"),
          PSQLState.SYNTAX_ERROR);
    }
    buf.append("overlay(");
    buf.append(parsedArgs.get(0)).append(" placing ").append(parsedArgs.get(3));
    buf.append(" from ").append(parsedArgs.get(1)).append(" for ").append(parsedArgs.get(2));
    buf.append(')');
  }

  /**
   * lcase to lower translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqllcase(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "lower(", "lcase", parsedArgs);
  }

  /**
   * left to substring translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlleft(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 2) {
      throw new PSQLException(GT.tr("{0} function takes two and only two arguments.", "left"),
          PSQLState.SYNTAX_ERROR);
    }
    appendCall(buf, "substring(", " for ", ")", parsedArgs);
  }

  /**
   * length translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqllength(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", "length"),
          PSQLState.SYNTAX_ERROR);
    }
    appendCall(buf, "length(trim(trailing from ", "", "))", parsedArgs);
  }

  /**
   * locate translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqllocate(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() == 2) {
      appendCall(buf, "position(", " in ", ")", parsedArgs);
    } else if (parsedArgs.size() == 3) {
      String tmp = "position(" + parsedArgs.get(0) + " in substring(" + parsedArgs.get(1) + " from "
          + parsedArgs.get(2) + "))";
      buf.append("(")
          .append(parsedArgs.get(2))
          .append("*sign(")
          .append(tmp)
          .append(")+")
          .append(tmp)
          .append(")");
    } else {
      throw new PSQLException(GT.tr("{0} function takes two or three arguments.", "locate"),
          PSQLState.SYNTAX_ERROR);
    }
  }

  /**
   * ltrim translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlltrim(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "trim(leading from ", "ltrim", parsedArgs);
  }

  /**
   * right to substring translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlright(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 2) {
      throw new PSQLException(GT.tr("{0} function takes two and only two arguments.", "right"),
          PSQLState.SYNTAX_ERROR);
    }
    buf.append("substring(");
    buf.append(parsedArgs.get(0))
        .append(" from (length(")
        .append(parsedArgs.get(0))
        .append(")+1-")
        .append(parsedArgs.get(1));
    buf.append("))");
  }

  /**
   * rtrim translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlrtrim(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "trim(trailing from ", "rtrim", parsedArgs);
  }

  /**
   * space translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlspace(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "repeat(' ',", "space", parsedArgs);
  }

  /**
   * substring to substr translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlsubstring(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    int argSize = parsedArgs.size();
    if (argSize != 2 && argSize != 3) {
      throw new PSQLException(GT.tr("{0} function takes two or three arguments.", "substring"),
          PSQLState.SYNTAX_ERROR);
    }
    appendCall(buf, "substr(", ",", ")", parsedArgs);
  }

  /**
   * ucase to upper translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlucase(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "upper(", "ucase", parsedArgs);
  }

  /**
   * curdate to current_date translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlcurdate(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    zeroArgumentFunctionCall(buf, "current_date", "curdate", parsedArgs);
  }

  /**
   * curtime to current_time translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlcurtime(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    zeroArgumentFunctionCall(buf, "current_time", "curtime", parsedArgs);
  }

  /**
   * dayname translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqldayname(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", "dayname"),
          PSQLState.SYNTAX_ERROR);
    }
    appendCall(buf, "to_char(", ",", ",'Day')", parsedArgs);
  }

  /**
   * dayofmonth translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqldayofmonth(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(day from ", "dayofmonth", parsedArgs);
  }

  /**
   * dayofweek translation adding 1 to postgresql function since we expect values from 1 to 7
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqldayofweek(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", "dayofweek"),
          PSQLState.SYNTAX_ERROR);
    }
    appendCall(buf, "extract(dow from ", ",", ")+1", parsedArgs);
  }

  /**
   * dayofyear translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqldayofyear(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(doy from ", "dayofyear", parsedArgs);
  }

  /**
   * hour translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlhour(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(hour from ", "hour", parsedArgs);
  }

  /**
   * minute translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlminute(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(minute from ", "minute", parsedArgs);
  }

  /**
   * month translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlmonth(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(month from ", "month", parsedArgs);
  }

  /**
   * monthname translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlmonthname(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", "monthname"),
          PSQLState.SYNTAX_ERROR);
    }
    appendCall(buf, "to_char(", ",", ",'Month')", parsedArgs);
  }

  /**
   * quarter translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlquarter(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(quarter from ", "quarter", parsedArgs);
  }

  /**
   * second translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlsecond(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(second from ", "second", parsedArgs);
  }

  /**
   * week translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlweek(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(week from ", "week", parsedArgs);
  }

  /**
   * year translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlyear(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(year from ", "year", parsedArgs);
  }

  /**
   * time stamp add
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqltimestampadd(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 3) {
      throw new PSQLException(
          GT.tr("{0} function takes three and only three arguments.", "timestampadd"),
          PSQLState.SYNTAX_ERROR);
    }
    buf.append('(');
    appendInterval(buf, parsedArgs.get(0).toString(), parsedArgs.get(1).toString());
    buf.append('+').append(parsedArgs.get(2)).append(')');
  }

  private static void appendInterval(StringBuilder buf, String type, String value) throws SQLException {
    if (!isTsi(type)) {
      throw new PSQLException(GT.tr("Interval {0} not yet implemented", type),
          PSQLState.SYNTAX_ERROR);
    }
    if (appendSingleIntervalCast(buf, SQL_TSI_DAY, type, value, "day")
        || appendSingleIntervalCast(buf, SQL_TSI_SECOND, type, value, "second")
        || appendSingleIntervalCast(buf, SQL_TSI_HOUR, type, value, "hour")
        || appendSingleIntervalCast(buf, SQL_TSI_MINUTE, type, value, "minute")
        || appendSingleIntervalCast(buf, SQL_TSI_MONTH, type, value, "month")
        || appendSingleIntervalCast(buf, SQL_TSI_WEEK, type, value, "week")
        || appendSingleIntervalCast(buf, SQL_TSI_YEAR, type, value, "year")
    ) {
      return;
    }
    if (areSameTsi(SQL_TSI_QUARTER, type)) {
      buf.append("CAST((").append(value).append("::int * 3) || ' month' as interval)");
      return;
    }
    throw new PSQLException(GT.tr("Interval {0} not yet implemented", type),
        PSQLState.NOT_IMPLEMENTED);
  }

  private static boolean appendSingleIntervalCast(StringBuilder buf, String cmp, String type, String value, String pgType) {
    if (!areSameTsi(type, cmp)) {
      return false;
    }
    buf.ensureCapacity(buf.length() + 5 + 4 + 14 + value.length() + pgType.length());
    buf.append("CAST(").append(value).append("||' ").append(pgType).append("' as interval)");
    return true;
  }

  /**
   * Compares two TSI intervals. It is
   * @param a first interval to compare
   * @param b second interval to compare
   * @return true when both intervals are equal (case insensitive)
   */
  private static boolean areSameTsi(String a, String b) {
    return a.length() == b.length() && b.length() > SQL_TSI_ROOT.length()
        && a.regionMatches(true, SQL_TSI_ROOT.length(), b, SQL_TSI_ROOT.length(), b.length() - SQL_TSI_ROOT.length());
  }

  /**
   * Checks if given input starts with {@link #SQL_TSI_ROOT}
   * @param interval input string
   * @return true if interval.startsWithIgnoreCase(SQL_TSI_ROOT)
   */
  private static boolean isTsi(String interval) {
    return interval.regionMatches(true, 0, SQL_TSI_ROOT, 0, SQL_TSI_ROOT.length());
  }


  /**
   * time stamp diff
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqltimestampdiff(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 3) {
      throw new PSQLException(
          GT.tr("{0} function takes three and only three arguments.", "timestampdiff"),
          PSQLState.SYNTAX_ERROR);
    }
    buf.append("extract( ")
        .append(constantToDatePart(buf, parsedArgs.get(0).toString()))
        .append(" from (")
        .append(parsedArgs.get(2))
        .append("-")
        .append(parsedArgs.get(1))
        .append("))");
  }

  private static String constantToDatePart(StringBuilder buf, String type) throws SQLException {
    if (!isTsi(type)) {
      throw new PSQLException(GT.tr("Interval {0} not yet implemented", type),
          PSQLState.SYNTAX_ERROR);
    }
    if (areSameTsi(SQL_TSI_DAY, type)) {
      return "day";
    } else if (areSameTsi(SQL_TSI_SECOND, type)) {
      return "second";
    } else if (areSameTsi(SQL_TSI_HOUR, type)) {
      return "hour";
    } else if (areSameTsi(SQL_TSI_MINUTE, type)) {
      return "minute";
    } else {
      throw new PSQLException(GT.tr("Interval {0} not yet implemented", type),
          PSQLState.SYNTAX_ERROR);
    }
    // See http://archives.postgresql.org/pgsql-jdbc/2006-03/msg00096.php
    /*
     * else if (SQL_TSI_MONTH.equalsIgnoreCase(shortType)) return "month"; else if
     * (SQL_TSI_QUARTER.equalsIgnoreCase(shortType)) return "quarter"; else if
     * (SQL_TSI_WEEK.equalsIgnoreCase(shortType)) return "week"; else if
     * (SQL_TSI_YEAR.equalsIgnoreCase(shortType)) return "year";
     */
  }

  /**
   * database translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqldatabase(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    zeroArgumentFunctionCall(buf, "current_database()", "database", parsedArgs);
  }

  /**
   * ifnull translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlifnull(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    twoArgumentsFunctionCall(buf, "coalesce(", "ifnull", parsedArgs);
  }

  /**
   * user translation
   *
   * @param buf The buffer to append into
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqluser(StringBuilder buf, List<? extends CharSequence> parsedArgs) throws SQLException {
    zeroArgumentFunctionCall(buf, "user", "user", parsedArgs);
  }

  private static void zeroArgumentFunctionCall(StringBuilder buf, String call, String functionName,
      List<? extends CharSequence> parsedArgs) throws PSQLException {
    if (!parsedArgs.isEmpty()) {
      throw new PSQLException(GT.tr("{0} function doesn''t take any argument.", functionName),
          PSQLState.SYNTAX_ERROR);
    }
    buf.append(call);
  }

  private static void singleArgumentFunctionCall(StringBuilder buf, String call, String functionName,
      List<? extends CharSequence> parsedArgs) throws PSQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", functionName),
          PSQLState.SYNTAX_ERROR);
    }
    CharSequence arg0 = parsedArgs.get(0);
    buf.ensureCapacity(buf.length() + call.length() + arg0.length() + 1);
    buf.append(call).append(arg0).append(')');
  }

  private static void twoArgumentsFunctionCall(StringBuilder buf, String call, String functionName,
      List<? extends CharSequence> parsedArgs) throws PSQLException {
    if (parsedArgs.size() != 2) {
      throw new PSQLException(GT.tr("{0} function takes two and only two arguments.", functionName),
          PSQLState.SYNTAX_ERROR);
    }
    appendCall(buf, call, ",", ")", parsedArgs);
  }

  /**
   * Appends {@code begin arg0 separator arg1 separator end} sequence to the input {@link StringBuilder}
   * @param sb destination StringBuilder
   * @param begin begin string
   * @param separator separator string
   * @param end end string
   * @param args arguments
   */
  public static void appendCall(StringBuilder sb, String begin, String separator,
      String end, List<? extends CharSequence> args) {
    int size = begin.length();
    // Typically just-in-time compiler would eliminate Iterator in case foreach is used,
    // however the code below uses indexed iteration to keep the conde independent from
    // various JIT implementations (== avoid Iterator allocations even for not-so-smart JITs)
    // see https://bugs.openjdk.java.net/browse/JDK-8166840
    // see http://2016.jpoint.ru/talks/cheremin/ (video and slides)
    int numberOfArguments = args.size();
    for (int i = 0; i < numberOfArguments; i++) {
      size += args.get(i).length();
    }
    size += separator.length() * (numberOfArguments - 1);
    sb.ensureCapacity(sb.length() + size + 1);
    sb.append(begin);
    for (int i = 0; i < numberOfArguments; i++) {
      if (i > 0) {
        sb.append(separator);
      }
      sb.append(args.get(i));
    }
    sb.append(end);
  }
}
