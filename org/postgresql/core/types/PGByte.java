/*
 * Created on May 15, 2005
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package org.postgresql.core.types;

import java.math.BigDecimal;
import java.sql.Types;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * @author davec
 *
 * TODO To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
public class PGByte implements PGType
{
    private Byte val;
    
    private PGByte( Byte x )
    {
        val = x;
    }
    
    static final PGByte valueOf(final Byte value) {
        return new PGByte(value);
    }
    
    /* (non-Javadoc)
     * @see org.postgresql.types.PGType#castToServerType(int)
     */
    public static PGType castToServerType(Byte val, int targetType) throws PSQLException
    {
        try
        {
            switch ( targetType )
            {
	            case Types.BIT:
	                return PGBoolean.valueOf(val != (byte) 0);
	            
	            case Types.SMALLINT:
	            case Types.TINYINT:
	                return PGByte.valueOf( val );
	            case Types.REAL:
	                return PGFloat.valueOf(val.floatValue());
	            case Types.DOUBLE:
	            case Types.FLOAT:
	                return PGDouble.valueOf(val.doubleValue());
	            case Types.NUMERIC:
	            case Types.DECIMAL:
	                return PGBigDecimal.valueOf( new BigDecimal(val.toString()) );
	            case Types.VARCHAR:
	            case Types.LONGVARCHAR:                
	                return PGString.valueOf ( val.toString() );
	            default:
	                return PGUnknown.valueOf(val);
            }
        }
        catch( Exception ex )
        {
            throw new PSQLException(GT.tr("Cannot convert an instance of {0} to type {1}", new Object[]{val.getClass().getName(),"Types.OTHER"}), PSQLState.INVALID_PARAMETER_TYPE, ex);
        }
    }
    
    public String toString()
    {
        return val.toString();
    }
}
