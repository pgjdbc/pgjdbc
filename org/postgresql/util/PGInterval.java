/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/util/PGInterval.java,v 1.5 2005/01/11 08:25:49 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.util;

import java.io.Serializable;

public class PGInterval extends PGobject implements Serializable, Cloneable
{
    public PGInterval()
    {
        setType("interval");
    }
    public PGInterval(String value )
    {
        setType("interval");
        this.value = value;
    }

    /*
     * This must be overidden to allow the object to be cloned
     */
    public Object clone()
    {
        return new PGInterval( value );
    }
}
