/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/Oid.java,v 1.5 2004/11/07 22:15:32 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

/**
 * Provides constants for well-known backend OIDs for the types we commonly
 * use.
 */
public class Oid {
    public static final int INVALID = 0;
    public static final int INT2 = 21;
    public static final int INT4 = 23;
    public static final int INT8 = 20;
    public static final int TEXT = 25;
    public static final int NUMERIC = 1700;
    public static final int FLOAT4 = 700;
    public static final int FLOAT8 = 701;
    public static final int BOOL = 16;
    public static final int DATE = 1082;
    public static final int TIME = 1083;
    public static final int TIMESTAMP = 1114;
    public static final int TIMESTAMPTZ = 1184;
    public static final int BYTEA = 17;
    public static final int VARCHAR = 1043;
    public static final int OID = 26;
    public static final int BPCHAR = 1042;
}
