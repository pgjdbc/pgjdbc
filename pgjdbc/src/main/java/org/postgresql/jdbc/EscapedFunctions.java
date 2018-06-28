/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
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
 * This class stores supported escaped function
 *
 * @author Xavier Poinsard
 */
public class EscapedFunctions {
  // numeric functions names
  public static final String ABS = "abs";
  public static final String ACOS = "acos";
  public static final String ASIN = "asin";
  public static final String ATAN = "atan";
  public static final String ATAN2 = "atan2";
  public static final String CEILING = "ceiling";
  public static final String COS = "cos";
  public static final String COT = "cot";
  public static final String DEGREES = "degrees";
  public static final String EXP = "exp";
  public static final String FLOOR = "floor";
  public static final String LOG = "log";
  public static final String LOG10 = "log10";
  public static final String MOD = "mod";
  public static final String PI = "pi";
  public static final String POWER = "power";
  public static final String RADIANS = "radians";
  public static final String ROUND = "round";
  public static final String SIGN = "sign";
  public static final String SIN = "sin";
  public static final String SQRT = "sqrt";
  public static final String TAN = "tan";
  public static final String TRUNCATE = "truncate";

  // string function names
  public static final String ASCII = "ascii";
  public static final String CHAR = "char";
  public static final String CONCAT = "concat";
  public static final String INSERT = "insert"; // change arguments order
  public static final String LCASE = "lcase";
  public static final String LEFT = "left";
  public static final String LENGTH = "length";
  public static final String LOCATE = "locate"; // the 3 args version duplicate args
  public static final String LTRIM = "ltrim";
  public static final String REPEAT = "repeat";
  public static final String REPLACE = "replace";
  public static final String RIGHT = "right"; // duplicate args
  public static final String RTRIM = "rtrim";
  public static final String SPACE = "space";
  public static final String SUBSTRING = "substring";
  public static final String UCASE = "ucase";
  // soundex is implemented on the server side by
  // the contrib/fuzzystrmatch module. We provide a translation
  // for this in the driver, but since we don't want to bother with run
  // time detection of this module's installation we don't report this
  // method as supported in DatabaseMetaData.
  // difference is currently unsupported entirely.

  // date time function names
  public static final String CURDATE = "curdate";
  public static final String CURTIME = "curtime";
  public static final String DAYNAME = "dayname";
  public static final String DAYOFMONTH = "dayofmonth";
  public static final String DAYOFWEEK = "dayofweek";
  public static final String DAYOFYEAR = "dayofyear";
  public static final String HOUR = "hour";
  public static final String MINUTE = "minute";
  public static final String MONTH = "month";
  public static final String MONTHNAME = "monthname";
  public static final String NOW = "now";
  public static final String QUARTER = "quarter";
  public static final String SECOND = "second";
  public static final String WEEK = "week";
  public static final String YEAR = "year";
  // for timestampadd and timestampdiff the fractional part of second is not supported
  // by the backend
  // timestampdiff is very partially supported
  public static final String TIMESTAMPADD = "timestampadd";
  public static final String TIMESTAMPDIFF = "timestampdiff";

  // constants for timestampadd and timestampdiff
  public static final String SQL_TSI_ROOT = "SQL_TSI_";
  public static final String SQL_TSI_DAY = "SQL_TSI_DAY";
  public static final String SQL_TSI_FRAC_SECOND = "SQL_TSI_FRAC_SECOND";
  public static final String SQL_TSI_HOUR = "SQL_TSI_HOUR";
  public static final String SQL_TSI_MINUTE = "SQL_TSI_MINUTE";
  public static final String SQL_TSI_MONTH = "SQL_TSI_MONTH";
  public static final String SQL_TSI_QUARTER = "SQL_TSI_QUARTER";
  public static final String SQL_TSI_SECOND = "SQL_TSI_SECOND";
  public static final String SQL_TSI_WEEK = "SQL_TSI_WEEK";
  public static final String SQL_TSI_YEAR = "SQL_TSI_YEAR";


  // system functions
  public static final String DATABASE = "database";
  public static final String IFNULL = "ifnull";
  public static final String USER = "user";


  /**
   * storage for functions implementations
   */
  private static ConcurrentMap<String, Method> functionMap = createFunctionMap("sql");

  private static ConcurrentMap<String, Method> createFunctionMap(String prefix) {
    Method[] methods = EscapedFunctions.class.getMethods();
    ConcurrentMap<String, Method> functionMap = new ConcurrentHashMap<>(methods.length * 2);
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
    Method method = functionMap.get(functionName);
    if (method != null) {
      return method;
    }
    String nameLower = functionName.toLowerCase(Locale.US);
    if (nameLower == functionName) {
      // Input name was in lower case, the function is not there
      return null;
    }
    method = functionMap.get(nameLower);
    if (method != null) {
      if (functionMap.size() < 1000) {
        // Avoid OutOfMemoryError in case input function names are randomized
        // The number of methods is finite, however the number of upper-lower case combinations
        // is quite a few (e.g. substr, Substr, sUbstr, SUbstr, etc).
        functionMap.put(nameLower, method);
      }
      return method;
    }
    return null;
  }

  // ** numeric functions translations **

  /**
   * ceiling to ceil translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlceiling(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "ceil(", "ceiling", parsedArgs);
  }

  /**
   * log to ln translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqllog(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "ln(", "log", parsedArgs);
  }

  /**
   * log10 to log translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqllog10(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "log(", "log10", parsedArgs);
  }

  /**
   * power to pow translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlpower(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    twoArgumentsFunctionCall(buf, "pow(", "power", parsedArgs);
  }

  /**
   * truncate to trunc translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqltruncate(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    twoArgumentsFunctionCall(buf, "trunc(", "truncate", parsedArgs);
  }

  // ** string functions translations **

  /**
   * char to chr translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlchar(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "chr(", "char", parsedArgs);
  }

  /**
   * concat translation
   *
   * @param parsedArgs arguments
   */
  public static void sqlconcat(StringBuilder buf, List<CharSequence> parsedArgs) {
    appendCall(buf, "(", "||", ")", parsedArgs);
  }

  /**
   * insert to overlay translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlinsert(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
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
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqllcase(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "lower(", "lcase", parsedArgs);
  }

  /**
   * left to substring translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlleft(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 2) {
      throw new PSQLException(GT.tr("{0} function takes two and only two arguments.", "left"),
          PSQLState.SYNTAX_ERROR);
    }
    buf.append("substring(");
    buf.append(parsedArgs.get(0)).append(" for ").append(parsedArgs.get(1));
    buf.append(')');
  }

  /**
   * length translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqllength(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", "length"),
          PSQLState.SYNTAX_ERROR);
    }
    buf.append("length(trim(trailing from ");
    buf.append(parsedArgs.get(0));
    buf.append("))");
  }

  /**
   * locate translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqllocate(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
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
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlltrim(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "trim(leading from ", "ltrim", parsedArgs);
  }

  /**
   * right to substring translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlright(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
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
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlrtrim(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "trim(trailing from ", "rtrim", parsedArgs);
  }

  /**
   * space translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlspace(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "repeat(' ',", "space", parsedArgs);
  }

  /**
   * substring to substr translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlsubstring(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
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
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlucase(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "upper(", "ucase", parsedArgs);
  }

  /**
   * curdate to current_date translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlcurdate(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    zeroArgumentFunctionCall(buf, "current_date", "curdate", parsedArgs);
  }

  /**
   * curtime to current_time translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlcurtime(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    zeroArgumentFunctionCall(buf, "current_time", "curtime", parsedArgs);
  }

  /**
   * dayname translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqldayname(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", "dayname"),
          PSQLState.SYNTAX_ERROR);
    }
    appendCall(buf, "to_char(", ",", ",'Day')", parsedArgs);
  }

  /**
   * dayofmonth translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqldayofmonth(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(day from ", "dayofmonth", parsedArgs);
  }

  /**
   * dayofweek translation adding 1 to postgresql function since we expect values from 1 to 7
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqldayofweek(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", "dayofweek"),
          PSQLState.SYNTAX_ERROR);
    }
    appendCall(buf, "extract(dow from ", ",", ")+1", parsedArgs);
  }

  /**
   * dayofyear translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqldayofyear(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(doy from ", "dayofyear", parsedArgs);
  }

  /**
   * hour translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlhour(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(hour from ", "hour", parsedArgs);
  }

  /**
   * minute translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlminute(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(minute from ", "minute", parsedArgs);
  }

  /**
   * month translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlmonth(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(month from ", "month", parsedArgs);
  }

  /**
   * monthname translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlmonthname(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", "monthname"),
          PSQLState.SYNTAX_ERROR);
    }
    appendCall(buf, "to_char(", ",", ",'Month')", parsedArgs);
  }

  /**
   * quarter translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlquarter(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(quarter from ", "quarter", parsedArgs);
  }

  /**
   * second translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlsecond(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(second from ", "second", parsedArgs);
  }

  /**
   * week translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlweek(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(week from ", "week", parsedArgs);
  }

  /**
   * year translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlyear(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    singleArgumentFunctionCall(buf, "extract(year from ", "year", parsedArgs);
  }

  /**
   * time stamp add
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqltimestampadd(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
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
        && a.regionMatches(true, SQL_TSI_ROOT.length(), b, SQL_TSI_ROOT.length(), SQL_TSI_ROOT.length() - b.length());
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
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqltimestampdiff(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
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
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqldatabase(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    zeroArgumentFunctionCall(buf, "current_database()", "database", parsedArgs);
  }

  /**
   * ifnull translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqlifnull(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    twoArgumentsFunctionCall(buf, "coalesce(", "ifnull", parsedArgs);
  }

  /**
   * user translation
   *
   * @param parsedArgs arguments
   * @throws SQLException if something wrong happens
   */
  public static void sqluser(StringBuilder buf, List<CharSequence> parsedArgs) throws SQLException {
    zeroArgumentFunctionCall(buf, "user", "user", parsedArgs);
  }

  private static void zeroArgumentFunctionCall(StringBuilder buf, String call, String functionName,
      List<CharSequence> parsedArgs) throws PSQLException {
    if (!parsedArgs.isEmpty()) {
      throw new PSQLException(GT.tr("{0} function doesn''t take any argument.", functionName),
          PSQLState.SYNTAX_ERROR);
    }
    buf.append(call);
  }

  private static void singleArgumentFunctionCall(StringBuilder buf, String call, String functionName,
      List<CharSequence> parsedArgs) throws PSQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", functionName),
          PSQLState.SYNTAX_ERROR);
    }
    CharSequence arg0 = parsedArgs.get(0);
    buf.ensureCapacity(buf.length() + call.length() + arg0.length() + 1);
    buf.append(call).append(arg0).append(')');
  }

  private static void twoArgumentsFunctionCall(StringBuilder buf, String call, String functionName,
      List<CharSequence> parsedArgs) throws PSQLException {
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
      String end, List<CharSequence> args) {
    int size = begin.length();
    // Avoid Iterator instantiations just in case, so plain for, not forach
    for (int i = 0; i < args.size(); i++) {
      size += args.get(i).length();
    }
    size += separator.length() * (args.size() - 1);
    sb.ensureCapacity(sb.length() + size + 1);
    sb.append(begin);
    // Avoid Iterator instantiations just in case, so plain for, not forach
    for (int i = 0; i < args.size(); i++) {
      if (i > 0) {
        sb.append(separator);
      }
      sb.append(args.get(i));
    }
    sb.append(end);
  }
}
