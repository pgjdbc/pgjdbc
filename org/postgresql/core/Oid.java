package org.postgresql.core;

/**
 * Provides constants for well-known backend OIDs for the types we commonly
 * use.
 */
public class Oid {
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
	public static final int CHAR = 18;
	public static final int VARCHAR = 1043;
}
