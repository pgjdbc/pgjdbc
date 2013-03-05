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
    
    public PGByte( Byte x )
    {
        val = x;
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
	                return new PGBoolean( val.byteValue() == 0?Boolean.FALSE:Boolean.TRUE );
	            
	            case Types.SMALLINT:
	            case Types.TINYINT:
	                return new PGByte( val );
	            case Types.REAL:
	                return new PGFloat( new Float( val.floatValue() ) ); 
	            case Types.DOUBLE:
	            case Types.FLOAT:
	                return new PGDouble( new Double( val.doubleValue() ) );
	            case Types.NUMERIC:
	            case Types.DECIMAL:
	                return new PGBigDecimal( new BigDecimal(val.toString()) );
	            case Types.VARCHAR:
	            case Types.LONGVARCHAR:                
	                return new PGString ( val.toString() );
	            default:
	                return new PGUnknown(val);
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
