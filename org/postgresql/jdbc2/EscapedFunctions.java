/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
* $PostgreSQL$
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;

/**
 * this class stores supported escaped function
 * @author Xavier Poinsard
 */
public class EscapedFunctions {
    // numeric functions names
    public final static String ABS="abs";
    public final static String ACOS="acos";
    public final static String ASIN="asin";
    public final static String ATAN="atan";
    public final static String ATAN2="atan2";
    public final static String CEILING="ceiling";
    public final static String COS="cos";
    public final static String COT="cot";
    public final static String DEGREES="degrees";
    public final static String EXP="exp";
    public final static String FLOOR="floor";
    public final static String LOG="log";
    public final static String LOG10="log10";
    public final static String MOD="mod";
    public final static String PI="pi";
    public final static String POWER="power";
    public final static String RADIANS="radians";
    public final static String RAND="rand";
    public final static String ROUND="round";
    public final static String SIGN="sign";
    public final static String SIN="sin";
    public final static String SQRT="sqrt";
    public final static String TAN="tan";
    public final static String TRUNCATE="truncate";
    
    // string function names   
    public final static String ASCII="ascii";
    public final static String CHAR="char";
    public final static String CONCAT="concat";
    public final static String INSERT="insert"; // change arguments order
    public final static String LCASE="lcase";
    public final static String LEFT="left";
    public final static String LENGTH="length";
    public final static String LOCATE="locate"; // the 3 args version duplicate args
    public final static String LTRIM="ltrim";
    public final static String REPEAT="repeat";
    public final static String REPLACE="replace";
    public final static String RIGHT="right"; // duplicate args
    public final static String RTRIM="rtrim";
    public final static String SPACE="space";
    public final static String SUBSTRING="substring";
    public final static String UCASE="ucase";
    // soundex and difference are implemented on the server side by 
    // the contrib/fuzzystrmatch module.  We provide a translation
    // for this in the driver, but since we don't want to bother with run
    // time detection of this module's installation we don't report these
    // methods as supported in DatabaseMetaData.

    // date time function names
    public final static String CURDATE="curdate";
    public final static String CURTIME="curtime";
    public final static String DAYNAME="dayname";
    public final static String DAYOFMONTH="dayofmonth";
    public final static String DAYOFWEEK="dayofweek";
    public final static String DAYOFYEAR="dayofyear";
    public final static String HOUR="hour";
    public final static String MINUTE="minute";
    public final static String MONTH="month";
    public final static String MONTHNAME="monthname";
    public final static String NOW="now";
    public final static String QUARTER="quarter";
    public final static String SECOND="second";
    public final static String WEEK="week";
    public final static String YEAR="year";
    // TODO : timestampadd and timestampdiff

    // system functions
    public final static String DATABASE="database";
    public final static String IFNULL="ifnull";
    public final static String USER="user";
    
    
    /** storage for functions implementations */
    private static Map functionMap = null;
    
    /**
     * get Method object implementing the given function
     * @param functionName name of the searched function
     * @return a Method object or null if not found
     */
    public static Method getFunction(String functionName){
        if (functionMap==null){
            Method[] arrayMeths = EscapedFunctions.class.getDeclaredMethods();
            functionMap = new HashMap(arrayMeths.length*2);
            for (int i=0;i<arrayMeths.length;i++){
                Method meth = arrayMeths[i];
                if (meth.getName().startsWith("sql"))
                    functionMap.put(meth.getName().toLowerCase(),meth);
            }
        }
        return (Method) functionMap.get("sql"+functionName.toLowerCase());
    }

    // ** numeric functions translations **
    /** ceiling to ceil translation */
    public static String sqlceiling(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("ceil(");
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","ceiling"));
        }
        buf.append(parsedArgs.get(0));
        return buf.append(')').toString();
    }
    
    /** log to ln translation */
    public static String sqllog(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("ln(");
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","log"));
        }
        buf.append(parsedArgs.get(0));
        return buf.append(')').toString();
    }
    
    /** log10 to log translation */
    public static String sqllog10(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("log(");
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","log10"));
        }
        buf.append(parsedArgs.get(0));
        return buf.append(')').toString();
    }
    
    /** power to pow translation */
    public static String sqlpower(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("pow(");
        if (parsedArgs.size()!=2){
            throw new PSQLException(GT.tr("{0} function takes two and only two arguments.","power"));
        }
        buf.append(parsedArgs.get(0)).append(',').append(parsedArgs.get(1));
        return buf.append(')').toString();
    }
    
    /** rand to setSeed + random translation */
    public static String sqlrand(List parsedArgs) throws SQLException{
        if (parsedArgs.size()==0){
            return "random()";
        }else if (parsedArgs.size()==1){
            return "(setseed("+parsedArgs.get(0)+")*0+random())";
        }else{
            throw new PSQLException(GT.tr("rand function only takes zero or one argument(the seed)."));
        }
    }
    
    /** truncate to trunc translation */
    public static String sqltruncate(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("trunc(");
        if (parsedArgs.size()!=2){
            throw new PSQLException(GT.tr("{0} function takes two and only two arguments.","truncate"));
        }
        buf.append(parsedArgs.get(0)).append(',').append(parsedArgs.get(1));
        return buf.append(')').toString();
    }

    // ** string functions translations **
    /** char to chr translation */
    public static String sqlchar(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("chr(");
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","char"));
        }
        buf.append(parsedArgs.get(0));
        return buf.append(')').toString();
    }

    /** concat translation */
    public static String sqlconcat(List parsedArgs){
        StringBuffer buf = new StringBuffer();
        buf.append('(');
        for (int iArg = 0;iArg<parsedArgs.size();iArg++){
            buf.append(parsedArgs.get(iArg));
            if (iArg!=(parsedArgs.size()-1))
                buf.append(" || ");
        }
        return buf.append(')').toString();
    }

    /** insert to overlay translation */
    public static String sqlinsert(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("overlay(");
        if (parsedArgs.size()!=4){
            throw new PSQLException(GT.tr("{0} function takes four and only four argument.","insert"));
        }
        buf.append(parsedArgs.get(0)).append(" placing ").append(parsedArgs.get(3));
        buf.append(" from ").append(parsedArgs.get(1)).append(" for ").append(parsedArgs.get(2));
        return buf.append(')').toString();
    }

    /** lcase to lower translation */
    public static String sqllcase(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("lower(");
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","lcase"));
        }
        buf.append(parsedArgs.get(0));
        return buf.append(')').toString();
    }

    /** left to substring translation */
    public static String sqlleft(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("substring(");
        if (parsedArgs.size()!=2){
            throw new PSQLException(GT.tr("{0} function takes two and only two arguments.","left"));
        }
        buf.append(parsedArgs.get(0)).append(" for ").append(parsedArgs.get(1));
        return buf.append(')').toString();
    }

    /** length translation */
    public static String sqllength(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("length(trim(trailing from ");
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","length"));
        }
        buf.append(parsedArgs.get(0));
        return buf.append("))").toString();
    }

    /** locate translation */
    public static String sqllocate(List parsedArgs) throws SQLException{
        if (parsedArgs.size()==2){
            return "position("+parsedArgs.get(0)+" in "+parsedArgs.get(1)+")";
        }else if (parsedArgs.size()==3){
            String tmp = "position("+parsedArgs.get(0)+" in substring("+parsedArgs.get(1)+" from "+parsedArgs.get(2)+"))";
            return "("+parsedArgs.get(2)+"*sign("+tmp+")+"+tmp+")";
        }else{
            throw new PSQLException(GT.tr("{0} function takes two or three arguments.","locate"));
        }
    }

    /** ltrim translation */
    public static String sqlltrim(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("trim(leading from ");
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","ltrim"));
        }
        buf.append(parsedArgs.get(0));
        return buf.append(')').toString();
    }

    /** right to substring translation */
    public static String sqlright(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("substring(");
        if (parsedArgs.size()!=2){
            throw new PSQLException(GT.tr("{0} function takes two and only two arguments.","right"));
        }
        buf.append(parsedArgs.get(0)).append(" from (length(").append(parsedArgs.get(0)).append(")+1-").append(parsedArgs.get(1));
        return buf.append("))").toString();
    }

    /** rtrim translation */
    public static String sqlrtrim(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("trim(trailing from ");
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","rtrim"));
        }
        buf.append(parsedArgs.get(0));
        return buf.append(')').toString();
    }

    /** space translation */
    public static String sqlspace(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("repeat(' ',");
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","space"));
        }
        buf.append(parsedArgs.get(0));
        return buf.append(')').toString();
    }

    /** substring to substr translation */
    public static String sqlsubstring(List parsedArgs) throws SQLException{
        if (parsedArgs.size()==2){
            return "substr("+parsedArgs.get(0)+","+parsedArgs.get(1)+")";
        }else if (parsedArgs.size()==3){
            return "substr("+parsedArgs.get(0)+","+parsedArgs.get(1)+","+parsedArgs.get(2)+")";
        }else{
            throw new PSQLException(GT.tr("{0} function takes two or three arguments.","substring"));
        }
    }

    /** ucase to upper translation */
    public static String sqlucase(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("upper(");
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","ucase"));
        }
        buf.append(parsedArgs.get(0));
        return buf.append(')').toString();
    }
    
    /** difference to levenshtein translation */
    public static String sqldifference(List parsedArgs) throws SQLException{
        StringBuffer buf = new StringBuffer();
        buf.append("levenshtein(");
        if (parsedArgs.size()!=2){
            throw new PSQLException(GT.tr("{0} function takes two and only two arguments.","difference"));
        }
        buf.append(parsedArgs.get(0)).append(",").append(parsedArgs.get(1));
        return buf.append(")").toString();
    }
    
    
    /** curdate to current_date translation */
    public static String sqlcurdate(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=0){
            throw new PSQLException(GT.tr("{0} function doesn't take any argument.","curdate"));
        }
        return "current_date";
    }

    /** curtime to current_time translation */
    public static String sqlcurtime(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=0){
            throw new PSQLException(GT.tr("{0} function doesn't take any argument.","curtime"));
        }
        return "current_time";
    }

    /** dayname translation */
    public static String sqldayname(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","dayname"));
        }
        return "to_char("+parsedArgs.get(0)+",'Day')";
    }

    /** dayofmonth translation */
    public static String sqldayofmonth(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","dayofmonth"));
        }
        return "extract(day from "+parsedArgs.get(0)+")";
    }

    /** dayofweek translation */
    public static String sqldayofweek(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","dayofweek"));
        }
        return "extract(dow from "+parsedArgs.get(0)+")";
    }

    /** dayofyear translation */
    public static String sqldayofyear(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","dayofyear"));
        }
        return "extract(doy from "+parsedArgs.get(0)+")";
    }

    /** hour translation */
    public static String sqlhour(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","hour"));
        }
        return "extract(hour from "+parsedArgs.get(0)+")";
    }

    /** minute translation */
    public static String sqlminute(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","minute"));
        }
        return "extract(minute from "+parsedArgs.get(0)+")";
    }

    /** month translation */
    public static String sqlmonth(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","month"));
        }
        return "extract(month from "+parsedArgs.get(0)+")";
    }

    /** monthname translation */
    public static String sqlmonthname(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","monthname"));
        }
        return "to_char("+parsedArgs.get(0)+",'Month')";
    }

    /** quarter translation */
    public static String sqlquarter(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","quarter"));
        }
        return "extract(quarter from "+parsedArgs.get(0)+")";
    }

    /** second translation */
    public static String sqlsecond(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","second"));
        }
        return "extract(second from "+parsedArgs.get(0)+")";
    }

    /** week translation */
    public static String sqlweek(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","week"));
        }
        return "extract(week from "+parsedArgs.get(0)+")";
    }

    /** year translation */
    public static String sqlyear(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=1){
            throw new PSQLException(GT.tr("{0} function takes one and only one argument.","year"));
        }
        return "extract(year from "+parsedArgs.get(0)+")";
    }
    
    /** database translation */
    public static String sqldatabase(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=0){
            throw new PSQLException(GT.tr("{0} function doesn't take any argument.","database"));
        }
        return "current_database()";
    }

    /** ifnull translation */
    public static String sqlifnull(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=2){
            throw new PSQLException(GT.tr("{0} function takes two and only two arguments.","ifnull"));
        }
        return "coalesce("+parsedArgs.get(0)+","+parsedArgs.get(1)+")";
    }

    /** user translation */
    public static String sqluser(List parsedArgs) throws SQLException{
        if (parsedArgs.size()!=0){
            throw new PSQLException(GT.tr("{0} function doesn't take any argument.","user"));
        }
        return "user";
    }
}
