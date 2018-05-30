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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * This class stores supported escaped function.
 *
 * @author Xavier Poinsard
 * @deprecated see {@link EscapedFunctions2}
 */
@Deprecated
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
  public static final String SQL_TSI_DAY = "DAY";
  public static final String SQL_TSI_FRAC_SECOND = "FRAC_SECOND";
  public static final String SQL_TSI_HOUR = "HOUR";
  public static final String SQL_TSI_MINUTE = "MINUTE";
  public static final String SQL_TSI_MONTH = "MONTH";
  public static final String SQL_TSI_QUARTER = "QUARTER";
  public static final String SQL_TSI_SECOND = "SECOND";
  public static final String SQL_TSI_WEEK = "WEEK";
  public static final String SQL_TSI_YEAR = "YEAR";


  // system functions
  public static final String DATABASE = "database";
  public static final String IFNULL = "ifnull";
  public static final String USER = "user";


  /**
   * storage for functions implementations.
   */
  private static Map<String, Method> functionMap = createFunctionMap();

  private static Map<String, Method> createFunctionMap() {
    Method[] arrayMeths = EscapedFunctions.class.getDeclaredMethods();
    Map<String, Method> functionMap = new HashMap<String, Method>(arrayMeths.length * 2);
    for (Method meth : arrayMeths) {
      if (meth.getName().startsWith("sql")) {
        functionMap.put(meth.getName().toLowerCase(Locale.US), meth);
      }
    }
    return functionMap;
  }

  /**
   * get Method object implementing the given function.
   *
   * @param functionName name of the searched function
   * @return a Method object or null if not found
   */
  public static Method getFunction(String functionName) {
    return functionMap.get("sql" + functionName.toLowerCase(Locale.US));
  }

  // ** numeric functions translations **

  /**
   * ceiling to ceil translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlceiling(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("ceil(", "ceiling", parsedArgs);
  }

  /**
   * log to ln translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqllog(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("ln(", "log", parsedArgs);
  }

  /**
   * log10 to log translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqllog10(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("log(", "log10", parsedArgs);
  }

  /**
   * power to pow translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlpower(List<?> parsedArgs) throws SQLException {
    return twoArgumentsFunctionCall("pow(", "power", parsedArgs);
  }

  /**
   * truncate to trunc translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqltruncate(List<?> parsedArgs) throws SQLException {
    return twoArgumentsFunctionCall("trunc(", "truncate", parsedArgs);
  }

  // ** string functions translations **

  /**
   * char to chr translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlchar(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("chr(", "char", parsedArgs);
  }

  /**
   * concat translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   */
  public static String sqlconcat(List<?> parsedArgs) {
    StringBuilder buf = new StringBuilder();
    buf.append('(');
    for (int iArg = 0; iArg < parsedArgs.size(); iArg++) {
      buf.append(parsedArgs.get(iArg));
      if (iArg != (parsedArgs.size() - 1)) {
        buf.append(" || ");
      }
    }
    return buf.append(')').toString();
  }

  /**
   * insert to overlay translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlinsert(List<?> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 4) {
      throw new PSQLException(GT.tr("{0} function takes four and only four argument.", "insert"),
          PSQLState.SYNTAX_ERROR);
    }
    StringBuilder buf = new StringBuilder();
    buf.append("overlay(");
    buf.append(parsedArgs.get(0)).append(" placing ").append(parsedArgs.get(3));
    buf.append(" from ").append(parsedArgs.get(1)).append(" for ").append(parsedArgs.get(2));
    return buf.append(')').toString();
  }

  /**
   * lcase to lower translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqllcase(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("lower(", "lcase", parsedArgs);
  }

  /**
   * left to substring translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlleft(List<?> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 2) {
      throw new PSQLException(GT.tr("{0} function takes two and only two arguments.", "left"),
          PSQLState.SYNTAX_ERROR);
    }
    StringBuilder buf = new StringBuilder();
    buf.append("substring(");
    buf.append(parsedArgs.get(0)).append(" for ").append(parsedArgs.get(1));
    return buf.append(')').toString();
  }

  /**
   * length translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqllength(List<?> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", "length"),
          PSQLState.SYNTAX_ERROR);
    }
    StringBuilder buf = new StringBuilder();
    buf.append("length(trim(trailing from ");
    buf.append(parsedArgs.get(0));
    return buf.append("))").toString();
  }

  /**
   * locate translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqllocate(List<?> parsedArgs) throws SQLException {
    if (parsedArgs.size() == 2) {
      return "position(" + parsedArgs.get(0) + " in " + parsedArgs.get(1) + ")";
    } else if (parsedArgs.size() == 3) {
      String tmp = "position(" + parsedArgs.get(0) + " in substring(" + parsedArgs.get(1) + " from "
          + parsedArgs.get(2) + "))";
      return "(" + parsedArgs.get(2) + "*sign(" + tmp + ")+" + tmp + ")";
    } else {
      throw new PSQLException(GT.tr("{0} function takes two or three arguments.", "locate"),
          PSQLState.SYNTAX_ERROR);
    }
  }

  /**
   * ltrim translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlltrim(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("trim(leading from ", "ltrim", parsedArgs);
  }

  /**
   * right to substring translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlright(List<?> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 2) {
      throw new PSQLException(GT.tr("{0} function takes two and only two arguments.", "right"),
          PSQLState.SYNTAX_ERROR);
    }
    StringBuilder buf = new StringBuilder();
    buf.append("substring(");
    buf.append(parsedArgs.get(0))
        .append(" from (length(")
        .append(parsedArgs.get(0))
        .append(")+1-")
        .append(parsedArgs.get(1));
    return buf.append("))").toString();
  }

  /**
   * rtrim translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlrtrim(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("trim(trailing from ", "rtrim", parsedArgs);
  }

  /**
   * space translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlspace(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("repeat(' ',", "space", parsedArgs);
  }

  /**
   * substring to substr translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlsubstring(List<?> parsedArgs) throws SQLException {
    if (parsedArgs.size() == 2) {
      return "substr(" + parsedArgs.get(0) + "," + parsedArgs.get(1) + ")";
    } else if (parsedArgs.size() == 3) {
      return "substr(" + parsedArgs.get(0) + "," + parsedArgs.get(1) + "," + parsedArgs.get(2)
          + ")";
    } else {
      throw new PSQLException(GT.tr("{0} function takes two or three arguments.", "substring"),
          PSQLState.SYNTAX_ERROR);
    }
  }

  /**
   * ucase to upper translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlucase(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("upper(", "ucase", parsedArgs);
  }

  /**
   * curdate to current_date translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlcurdate(List<?> parsedArgs) throws SQLException {
    if (!parsedArgs.isEmpty()) {
      throw new PSQLException(GT.tr("{0} function doesn''t take any argument.", "curdate"),
          PSQLState.SYNTAX_ERROR);
    }
    return "current_date";
  }

  /**
   * curtime to current_time translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlcurtime(List<?> parsedArgs) throws SQLException {
    if (!parsedArgs.isEmpty()) {
      throw new PSQLException(GT.tr("{0} function doesn''t take any argument.", "curtime"),
          PSQLState.SYNTAX_ERROR);
    }
    return "current_time";
  }

  /**
   * dayname translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqldayname(List<?> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", "dayname"),
          PSQLState.SYNTAX_ERROR);
    }
    return "to_char(" + parsedArgs.get(0) + ",'Day')";
  }

  /**
   * dayofmonth translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqldayofmonth(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("extract(day from ", "dayofmonth", parsedArgs);
  }

  /**
   * dayofweek translation adding 1 to postgresql function since we expect values from 1 to 7.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqldayofweek(List<?> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", "dayofweek"),
          PSQLState.SYNTAX_ERROR);
    }
    return "extract(dow from " + parsedArgs.get(0) + ")+1";
  }

  /**
   * dayofyear translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqldayofyear(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("extract(doy from ", "dayofyear", parsedArgs);
  }

  /**
   * hour translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlhour(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("extract(hour from ", "hour", parsedArgs);
  }

  /**
   * minute translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlminute(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("extract(minute from ", "minute", parsedArgs);
  }

  /**
   * month translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlmonth(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("extract(month from ", "month", parsedArgs);
  }

  /**
   * monthname translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlmonthname(List<?> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", "monthname"),
          PSQLState.SYNTAX_ERROR);
    }
    return "to_char(" + parsedArgs.get(0) + ",'Month')";
  }

  /**
   * quarter translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlquarter(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("extract(quarter from ", "quarter", parsedArgs);
  }

  /**
   * second translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlsecond(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("extract(second from ", "second", parsedArgs);
  }

  /**
   * week translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlweek(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("extract(week from ", "week", parsedArgs);
  }

  /**
   * year translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlyear(List<?> parsedArgs) throws SQLException {
    return singleArgumentFunctionCall("extract(year from ", "year", parsedArgs);
  }

  /**
   * time stamp add.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqltimestampadd(List<?> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 3) {
      throw new PSQLException(
          GT.tr("{0} function takes three and only three arguments.", "timestampadd"),
          PSQLState.SYNTAX_ERROR);
    }
    String interval = EscapedFunctions.constantToInterval(parsedArgs.get(0).toString(),
        parsedArgs.get(1).toString());
    StringBuilder buf = new StringBuilder();
    buf.append("(").append(interval).append("+");
    buf.append(parsedArgs.get(2)).append(")");
    return buf.toString();
  }

  private static String constantToInterval(String type, String value) throws SQLException {
    if (!type.startsWith(SQL_TSI_ROOT)) {
      throw new PSQLException(GT.tr("Interval {0} not yet implemented", type),
          PSQLState.SYNTAX_ERROR);
    }
    String shortType = type.substring(SQL_TSI_ROOT.length());
    if (SQL_TSI_DAY.equalsIgnoreCase(shortType)) {
      return "CAST(" + value + " || ' day' as interval)";
    } else if (SQL_TSI_SECOND.equalsIgnoreCase(shortType)) {
      return "CAST(" + value + " || ' second' as interval)";
    } else if (SQL_TSI_HOUR.equalsIgnoreCase(shortType)) {
      return "CAST(" + value + " || ' hour' as interval)";
    } else if (SQL_TSI_MINUTE.equalsIgnoreCase(shortType)) {
      return "CAST(" + value + " || ' minute' as interval)";
    } else if (SQL_TSI_MONTH.equalsIgnoreCase(shortType)) {
      return "CAST(" + value + " || ' month' as interval)";
    } else if (SQL_TSI_QUARTER.equalsIgnoreCase(shortType)) {
      return "CAST((" + value + "::int * 3) || ' month' as interval)";
    } else if (SQL_TSI_WEEK.equalsIgnoreCase(shortType)) {
      return "CAST(" + value + " || ' week' as interval)";
    } else if (SQL_TSI_YEAR.equalsIgnoreCase(shortType)) {
      return "CAST(" + value + " || ' year' as interval)";
    } else if (SQL_TSI_FRAC_SECOND.equalsIgnoreCase(shortType)) {
      throw new PSQLException(GT.tr("Interval {0} not yet implemented", "SQL_TSI_FRAC_SECOND"),
          PSQLState.SYNTAX_ERROR);
    } else {
      throw new PSQLException(GT.tr("Interval {0} not yet implemented", type),
          PSQLState.SYNTAX_ERROR);
    }
  }


  /**
   * time stamp diff.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqltimestampdiff(List<?> parsedArgs) throws SQLException {
    if (parsedArgs.size() != 3) {
      throw new PSQLException(
          GT.tr("{0} function takes three and only three arguments.", "timestampdiff"),
          PSQLState.SYNTAX_ERROR);
    }
    String datePart = EscapedFunctions.constantToDatePart(parsedArgs.get(0).toString());
    StringBuilder buf = new StringBuilder();
    buf.append("extract( ")
        .append(datePart)
        .append(" from (")
        .append(parsedArgs.get(2))
        .append("-")
        .append(parsedArgs.get(1))
        .append("))");
    return buf.toString();
  }

  private static String constantToDatePart(String type) throws SQLException {
    if (!type.startsWith(SQL_TSI_ROOT)) {
      throw new PSQLException(GT.tr("Interval {0} not yet implemented", type),
          PSQLState.SYNTAX_ERROR);
    }
    String shortType = type.substring(SQL_TSI_ROOT.length());
    if (SQL_TSI_DAY.equalsIgnoreCase(shortType)) {
      return "day";
    } else if (SQL_TSI_SECOND.equalsIgnoreCase(shortType)) {
      return "second";
    } else if (SQL_TSI_HOUR.equalsIgnoreCase(shortType)) {
      return "hour";
    } else if (SQL_TSI_MINUTE.equalsIgnoreCase(shortType)) {
      return "minute";
    } else if (SQL_TSI_FRAC_SECOND.equalsIgnoreCase(shortType)) {
      throw new PSQLException(GT.tr("Interval {0} not yet implemented", "SQL_TSI_FRAC_SECOND"),
          PSQLState.SYNTAX_ERROR);
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
   * database translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqldatabase(List<?> parsedArgs) throws SQLException {
    if (!parsedArgs.isEmpty()) {
      throw new PSQLException(GT.tr("{0} function doesn''t take any argument.", "database"),
          PSQLState.SYNTAX_ERROR);
    }
    return "current_database()";
  }

  /**
   * ifnull translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqlifnull(List<?> parsedArgs) throws SQLException {
    return twoArgumentsFunctionCall("coalesce(", "ifnull", parsedArgs);
  }

  /**
   * user translation.
   *
   * @param parsedArgs arguments
   * @return sql call
   * @throws SQLException if something wrong happens
   */
  public static String sqluser(List<?> parsedArgs) throws SQLException {
    if (!parsedArgs.isEmpty()) {
      throw new PSQLException(GT.tr("{0} function doesn''t take any argument.", "user"),
          PSQLState.SYNTAX_ERROR);
    }
    return "user";
  }

  private static String singleArgumentFunctionCall(String call, String functionName,
      List<?> parsedArgs) throws PSQLException {
    if (parsedArgs.size() != 1) {
      throw new PSQLException(GT.tr("{0} function takes one and only one argument.", functionName),
          PSQLState.SYNTAX_ERROR);
    }
    StringBuilder buf = new StringBuilder();
    buf.append(call);
    buf.append(parsedArgs.get(0));
    return buf.append(')').toString();
  }

  private static String twoArgumentsFunctionCall(String call, String functionName,
      List<?> parsedArgs) throws PSQLException {
    if (parsedArgs.size() != 2) {
      throw new PSQLException(GT.tr("{0} function takes two and only two arguments.", functionName),
          PSQLState.SYNTAX_ERROR);
    }
    StringBuilder buf = new StringBuilder();
    buf.append(call);
    buf.append(parsedArgs.get(0)).append(',').append(parsedArgs.get(1));
    return buf.append(')').toString();
  }
}
