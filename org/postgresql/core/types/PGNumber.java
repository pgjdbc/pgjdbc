/*
 * Created on May 15, 2005
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package org.postgresql.core.types;

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
public class PGNumber implements PGType
{
    private Number val;
    
    private PGNumber( Number x )
    {
        val = x;
    }
    
    static final PGNumber valueOf(final Number value) {
        return new PGNumber(value);
    }
    
    public static PGType castToServerType( Number val, int targetType ) throws PSQLException
    {
        try
        {
            switch ( targetType )
            {
	            case Types.BIT:
	                return PGBoolean.valueOf(val.doubleValue() != 0d);
	            
	            case Types.BIGINT:
	                return PGLong.valueOf(val.longValue());
	            case Types.INTEGER:
	                return PGInteger.valueOf(val.intValue());
	            case Types.TINYINT:
	            case Types.SMALLINT:
	                return PGShort.valueOf(val.shortValue());
	            case Types.VARCHAR:
	            case Types.LONGVARCHAR:                
	                return PGString.valueOf( val.toString() );
	            case Types.DOUBLE:
	            case Types.FLOAT:
	                return PGDouble.valueOf(val.doubleValue());
	            case Types.REAL:
	                return PGFloat.valueOf(val.floatValue());
	            case Types.DECIMAL:
	            case Types.NUMERIC:
	                return PGNumber.valueOf( val );
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
