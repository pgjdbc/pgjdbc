/*
 * Created on May 15, 2005
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package legacy.org.postgresql.core.types;

import java.math.BigDecimal;
import java.sql.Types;

import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PSQLException;
import legacy.org.postgresql.util.PSQLState;

/**
 * @author davec
 *
 * TODO To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
public class PGFloat implements PGType
{
    private Float val;
    
    protected PGFloat( Float x )
    {
        val = x;
    }
        
    public static PGType castToServerType( Float val, int targetType ) throws PSQLException
    {
        try
        {
            switch ( targetType )
            {
	            case Types.BIT:
	                return new PGBoolean( val.floatValue() == 0?Boolean.FALSE:Boolean.TRUE );
	            
	            case Types.BIGINT:
	                return new PGLong(new Long( val.longValue() ));
	            case Types.INTEGER:
	                return new PGInteger(new Integer( val.intValue() ) );
	            case Types.SMALLINT:
	            case Types.TINYINT:
	                return new PGShort(new Short( val.shortValue() ));
	            case Types.VARCHAR:
	            case Types.LONGVARCHAR:                
	                return new PGString( val.toString() );
	            case Types.DOUBLE:
	            case Types.FLOAT:
	                return( new PGDouble( new Double( val.doubleValue())));
	            case Types.REAL:
	                return new PGFloat( val );
	            case Types.DECIMAL:
	            case Types.NUMERIC:
	                return new PGBigDecimal( new BigDecimal( val.toString()));
	            default:
	                return new PGUnknown(val);            
            }
        }
        catch( Exception ex)
        {
            throw new PSQLException(GT.tr("Cannot convert an instance of {0} to type {1}", new Object[]{val.getClass().getName(),"Types.OTHER"}), PSQLState.INVALID_PARAMETER_TYPE, ex);
        }
    }
    public String toString()
    {
        return val.toString();
    }
}
