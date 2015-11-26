/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v3;

import org.postgresql.core.*;

import java.lang.ref.PhantomReference;

/**
 * V3 Query implementation for a single-statement query.
 * This also holds the state of any associated server-side
 * named statement. We use a PhantomReference managed by
 * the QueryExecutor to handle statement cleanup.
 * 
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class SimpleQuery implements V3Query {

    SimpleQuery(NativeQuery query, ProtocolConnectionImpl protoConnection)
    {
        this.nativeQuery = query;
        this.protoConnection = protoConnection;
    }

    public ParameterList createParameterList() {
        if (nativeQuery.bindPositions.length == 0)
            return NO_PARAMETERS;

        return new SimpleParameterList(nativeQuery.bindPositions.length, protoConnection);
    }

    public String toString(ParameterList parameters) {
        return nativeQuery.toString(parameters);
    }

    public String toString() {
        return toString(null);
    }

    public void close() {
        unprepare();
    }

    //
    // V3Query
    //

    public SimpleQuery[] getSubqueries() {
        return null;
    }

	/*
	 * Return maximum size in bytes that each result row from this query may
	 * return. Mainly used for batches that return results.
	 *
	 * Results are cached until/unless the query is re-described.
	 *
	 * @return Max size of result data in bytes according to returned fields, 0
	 * if no results, -1 if result is unbounded.
	 *
	 * @throws IllegalStateException if the query is not described
	 */
	public int getMaxResultRowSize() {
		if (cachedMaxResultRowSize != null) {
			return cachedMaxResultRowSize;
		}
		if (!this.statementDescribed) {
			throw new IllegalStateException(
					"Cannot estimate result row size on a statement that is not described");
		}
		int maxResultRowSize = 0;
		if (fields != null) {
            for (Field f : fields) {
                final int fieldLength = f.getLength();
                if (fieldLength < 1 || fieldLength >= 65535) {
                    /*
					 * Field length unknown or large; we can't make any safe
					 * estimates about the result size, so we have to fall back to
					 * sending queries individually.
					 */
                    maxResultRowSize = -1;
                    break;
                }
                maxResultRowSize += fieldLength;
            }
		}
		cachedMaxResultRowSize = maxResultRowSize;
		return maxResultRowSize;
	}

    //
    // Implementation guts
    //

    String getNativeSql() {
        return nativeQuery.nativeSql;
    }

    void setStatementName(String statementName) {
        this.statementName = statementName;
        this.encodedStatementName = Utils.encodeUTF8(statementName);
    }

    void setStatementTypes(int[] paramTypes) {
        this.preparedTypes = paramTypes;
    }

    int[] getStatementTypes() {
        return preparedTypes;
    }

    String getStatementName() {
        return statementName;
    }

    boolean isPreparedFor(int[] paramTypes) {
        if (statementName == null)
            return false; // Not prepared.

        // Check for compatible types.
        for (int i = 0; i < paramTypes.length; ++i)
            if (paramTypes[i] != Oid.UNSPECIFIED && paramTypes[i] != preparedTypes[i])
                return false;

        return true;
    }

    boolean hasUnresolvedTypes() {
        if (preparedTypes == null)
            return true;

        for (int preparedType : preparedTypes) {
            if (preparedType == Oid.UNSPECIFIED)
                return true;
        }

        return false;
    }

    byte[] getEncodedStatementName() {
        return encodedStatementName;
    }

    /**
     * Sets the fields that this query will return.
     *
     * @param fields The fields that this query will return.
     */
    void setFields(Field[] fields) {
        this.fields = fields;
        this.cachedMaxResultRowSize = null;
        this.needUpdateFieldFormats = fields != null;
        this.hasBinaryFields = false; // just in case
    }

    /**
     * Returns the fields that this query will return. If the result set fields
     * are not known returns null.
     *
     * @return the fields that this query will return.
     */
    Field[] getFields() {
        return fields;
    }

    /**
     * Returns true if current query needs field formats be adjusted as per connection configuration.
     * Subsequent invocations would return {@code false}.
     * The idea is to perform adjustments only once, not for each {@link QueryExecutorImpl#sendBind(SimpleQuery, SimpleParameterList, Portal, boolean)}.
     * @return true if current query needs field formats be adjusted as per connection configuration
     */
    boolean needUpdateFieldFormats() {
        if (needUpdateFieldFormats) {
            needUpdateFieldFormats = false;
            return true;
        }
        return false;
    }


    public boolean hasBinaryFields() {
        return hasBinaryFields;
    }

    public void setHasBinaryFields(boolean hasBinaryFields) {
        this.hasBinaryFields = hasBinaryFields;
    }

    // Have we sent a Describe Portal message for this query yet?
    boolean isPortalDescribed() {
        return portalDescribed;
    }
    void setPortalDescribed(boolean portalDescribed) {
        this.portalDescribed = portalDescribed;
        this.cachedMaxResultRowSize = null;
    }

    // Have we sent a Describe Statement message for this query yet?
    // Note that we might not have need to, so this may always be false.
    public boolean isStatementDescribed() {
        return statementDescribed;
    }
    void setStatementDescribed(boolean statementDescribed) {
        this.statementDescribed = statementDescribed;
        this.cachedMaxResultRowSize = null;
    }

    public boolean isEmpty()
    {
        return getNativeSql().isEmpty();
    }

    void setCleanupRef(PhantomReference cleanupRef) {
        if (this.cleanupRef != null) {
            this.cleanupRef.clear();
            this.cleanupRef.enqueue();
        }
        this.cleanupRef = cleanupRef;
    }

    void unprepare() {
        if (cleanupRef != null)
        {
            cleanupRef.clear();
            cleanupRef.enqueue();
            cleanupRef = null;
        }

        statementName = null;
        encodedStatementName = null;
        fields = null;
        portalDescribed = false;
        statementDescribed = false;
        cachedMaxResultRowSize = null;
    }

    private final NativeQuery nativeQuery;

    private final ProtocolConnectionImpl protoConnection;
    private String statementName;
    private byte[] encodedStatementName;
    /**
     * The stored fields from previous execution or describe of a prepared 
     * statement. Always null for non-prepared statements.
     */
    private Field[] fields;
    private boolean needUpdateFieldFormats;
    private boolean hasBinaryFields;
    private boolean portalDescribed;
    private boolean statementDescribed;
    private PhantomReference cleanupRef;
    private int[] preparedTypes;

    private Integer cachedMaxResultRowSize;

    final static SimpleParameterList NO_PARAMETERS = new SimpleParameterList(0, null);
}


