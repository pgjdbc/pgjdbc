/*-------------------------------------------------------------------------
*
* Copyright (c) 2004, Open Cloud Limited.
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/v3/V3ParameterList.java,v 1.2 2004/11/07 22:15:39 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v3;

import java.sql.SQLException;
import org.postgresql.core.ParameterList;

/**
 * Common interface for all V3 parameter list implementations.
 * 
 * @author Oliver Jowett (oliver@opencloud.com)
 */
interface V3ParameterList extends ParameterList {
    /**
     * Ensure that all parameters in this list have been
     * assigned values. Return silently if all is well, otherwise
     * throw an appropriate exception.
     *
     * @throws SQLException if not all parameters are set.
     */
    void checkAllParametersSet() throws SQLException;

    /**
     * Return a list of the SimpleParameterList objects that
     * make up this parameter list. If this object is already a
     * SimpleParameterList, returns null (avoids an extra array
     * construction in the common case).
     *
     * @return an array of single-statement parameter lists, or
     *   <code>null</code> if this object is already a single-statement
     *   parameter list.
     */
    SimpleParameterList[] getSubparams();
}
