/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v3;

import org.postgresql.core.Query;

/**
 * Common interface for all V3 query implementations.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
interface V3Query extends Query {
    /**
     * Return a list of the SimpleQuery objects that
     * make up this query. If this object is already a
     * SimpleQuery, returns null (avoids an extra array
     * construction in the common case).
     *
     * @return an array of single-statement queries, or <code>null</code>
     *   if this object is already a single-statement query.
     */
    SimpleQuery[] getSubqueries();
}
