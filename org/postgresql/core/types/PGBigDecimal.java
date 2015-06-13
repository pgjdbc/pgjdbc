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
public class PGBigDecimal implements PGType
{
    static final PGBigDecimal ZERO = new PGBigDecimal(BigDecimal.ZERO);
    
    static final PGBigDecimal ONE = new PGBigDecimal(BigDecimal.ONE);
    
    private BigDecimal val;
    
    private PGBigDecimal( BigDecimal x )
    {
        // ensure the value is a valid numeric value to avoid
        // sql injection attacks
        val = new BigDecimal(x.toString());
        
    }
    
    static final PGBigDecimal valueOf(final BigDecimal value) {
        return BigDecimal.ZERO.equals(value) ? PGBigDecimal.ZERO : BigDecimal.ONE.equals(value) ? PGBigDecimal.ONE : new PGBigDecimal(value);
    }

    public static PGType castToServerType( BigDecimal val, int targetType ) throws PSQLException
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
	                return PGInteger.valueOf(val.intValue()) ;
	            case Types.SMALLINT:
	            case Types.TINYINT:
	                return PGShort.valueOf(val.shortValue());
	            case Types.VARCHAR:
	            case Types.LONGVARCHAR:
	                return PGString.valueOf( val.toString() );
	            case Types.DECIMAL:
	            case Types.NUMERIC:
	            case Types.DOUBLE:
	            case Types.FLOAT:
	            case Types.REAL:
	                return PGBigDecimal.valueOf( val );
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
