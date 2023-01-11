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
public class PGShort implements PGType
{
    Short val;
    protected  PGShort( Short x )
    {
        val = x;
    }
    public static PGType castToServerType( Short val, int targetType ) throws PSQLException
    {
        try
        {
            switch ( targetType )
            {
	            case Types.BIT:
	                return new PGBoolean( val.shortValue() == 0?Boolean.FALSE:Boolean.TRUE );
	            
	            case Types.SMALLINT:
	            case Types.TINYINT:
	                return new PGShort(val);
	            case Types.REAL:
	                return new PGFloat( new Float( val.floatValue() ) ); 
	            case Types.DOUBLE:
	            case Types.FLOAT:
	                return new PGDouble( new Double( val.doubleValue() ) );
	            case Types.VARCHAR:
	            case Types.LONGVARCHAR:                
	                return new PGString ( val.toString() );
	            case Types.DECIMAL:
	            case Types.NUMERIC:
	                return new PGBigDecimal( new BigDecimal( val.toString()));
	            default:
	                return new PGUnknown(val);
	            }
        }
        catch (Exception ex)
        {
            throw new PSQLException(GT.tr("Cannot convert an instance of {0} to type {1}", new Object[]{val.getClass().getName(),"Types.OTHER"}), PSQLState.INVALID_PARAMETER_TYPE, ex);
        }
    }
    
    public String toString()
    {
        return val.toString();
    }
}
