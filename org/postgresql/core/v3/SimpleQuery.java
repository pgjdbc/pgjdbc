/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/v3/SimpleQuery.java,v 1.7 2005/01/27 22:50:14 oliver Exp $
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
    SimpleQuery(String[] fragments) {
        this.fragments = fragments;
    }

    public ParameterList createParameterList() {
        if (fragments.length == 1)
            return NO_PARAMETERS;

        return new SimpleParameterList(fragments.length - 1);
    }

    public String toString(ParameterList parameters) {
        StringBuffer sbuf = new StringBuffer(fragments[0]);
        for (int i = 1; i < fragments.length; ++i)
        {
            if (parameters == null)
                sbuf.append('?');
            else
                sbuf.append(parameters.toString(i));
            sbuf.append(fragments[i]);
        }
        return sbuf.toString();
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

    //
    // Implementation guts
    //

    String[] getFragments() {
        return fragments;
    }

    void setStatementName(String statementName) {
        this.statementName = statementName;
        this.encodedStatementName = Utils.encodeUTF8(statementName);
    }

    void setStatementTypes(int[] paramTypes) {
        this.preparedTypes = paramTypes;
    }

    String getStatementName() {
        return statementName;
    }

    boolean isPreparedFor(int[] paramTypes) {
        if (statementName == null)
            return false; // Not prepared.

        // Check for compatible types.
        for (int i = 0; i < paramTypes.length; ++i)
            if (paramTypes[i] != preparedTypes[i])
                return false;

        return true;
    }

    byte[] getEncodedStatementName() {
        return encodedStatementName;
    }

    void setCleanupRef(PhantomReference cleanupRef) {
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
    }

    private final String[] fragments;
    private String statementName;
    private byte[] encodedStatementName;
    private PhantomReference cleanupRef;
    private int[] preparedTypes;

    final static SimpleParameterList NO_PARAMETERS = new SimpleParameterList(0);
}


