/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/v3/SimpleQuery.java,v 1.4 2004/11/09 08:46:19 jurka Exp $
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
            sbuf.append(parameters.toString(i));
            sbuf.append(fragments[i]);
        }
        return sbuf.toString();
    }

    public String toString() {
        return toString(null);
    }

    public void close() {
        if (cleanupRef != null)
        {
            cleanupRef.clear();
            cleanupRef.enqueue();
            cleanupRef = null;
        }
    }

    //
    // V3Query
    //

    public SimpleQuery[] getSubqueries() {
        return null;
    }

    String[] getFragments() {
        return fragments;
    }

    void setStatementName(String statementName) {
        this.statementName = statementName;
        this.encodedStatementName = (statementName == null ? null : Utils.encodeUTF8(statementName));
    }

    String getStatementName() {
        return statementName;
    }

    byte[] getEncodedStatementName() {
        return encodedStatementName;
    }

    void setCleanupRef(PhantomReference cleanupRef) {
        this.cleanupRef = cleanupRef;
    }

    private final String[] fragments;
    private String statementName;
    private byte[] encodedStatementName;
    private PhantomReference cleanupRef;

    final static SimpleParameterList NO_PARAMETERS = new SimpleParameterList(0);
}


