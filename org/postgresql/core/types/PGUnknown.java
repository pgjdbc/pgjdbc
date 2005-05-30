/*
 * Created on May 15, 2005
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package org.postgresql.core.types;

/**
 * @author davec
 *
 * TODO To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
public class PGUnknown implements PGType
{

    /* (non-Javadoc)
     * @see org.postgresql.types.PGType#castToServerType(int)
     */
    Object val;
    public PGUnknown( Object x)
    {
        val = x;
    }
    public PGType castToServerType(int targetType) 
    {
        return this;
    }
    public String toString()
    {
        return val.toString();
    }

}
