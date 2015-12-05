/*-------------------------------------------------------------------------
*
* Copyright (c) 2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

/**
 * Contains parse flags from {@link Parser#modifyJdbcCall(String, boolean, int, int)}.
 * Originally {@link Parser#modifyJdbcCall(String, boolean, int, int)} was located in {@link org.postgresql.jdbc2.AbstractJdbc2Statement},
 * however it was moved out to avoid parse on each prepareCall.
 */
public class JdbcCallParseInfo {
    private final String sql;
    private final boolean isFunction;
    private final boolean outParmBeforeFunc;

    public JdbcCallParseInfo(String sql, boolean isFunction, boolean outParmBeforeFunc) {
        this.sql = sql;
        this.isFunction = isFunction;
        this.outParmBeforeFunc = outParmBeforeFunc;
    }

    /**
     * SQL in a native for certain backend version.
     * @return SQL in a native for certain backend version
     */
    public String getSql() {
        return sql;
    }

    /**
     * Returns if given SQL is a function.
     * @return {@code true} if given SQL is a function
     */
    public boolean isFunction() {
        return isFunction;
    }

    /**
     * Returns if given SQL is a function with one out parameter.
     * @return {@code true} if given SQL is a function with one out parameter
     */
    public boolean isOutParmBeforeFunc() {
        return outParmBeforeFunc;
    }
}
