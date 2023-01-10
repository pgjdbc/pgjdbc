/*
 * Created on May 15, 2005
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package legacy.org.postgresql.core.types;

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
    public static PGType castToServerType(Object val, int targetType) 
    {
        return new PGUnknown( val );
    }
    public String toString()
    {
        return val.toString();
    }

}
