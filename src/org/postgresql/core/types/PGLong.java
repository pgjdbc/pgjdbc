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
public class PGLong implements PGType
{
    private Long val;
    
    protected PGLong( Long x )
    {
        val = x;
    }
    
    public static PGType castToServerType(Long val, int targetType ) throws PSQLException 
    {
        try
        {
            switch ( targetType )
            {
	            case Types.BIT:
	                return new PGBoolean(val.longValue()==0?Boolean.FALSE:Boolean.TRUE);
	            case Types.REAL:
	                return new PGFloat( new Float(val.floatValue()) );
	            case Types.FLOAT:
	            case Types.DOUBLE:
	                return new PGDouble( new Double(val.doubleValue()) );
	            case Types.VARCHAR:
	            case Types.LONGVARCHAR:                
	                return new PGString(val.toString());
	            case Types.BIGINT:
	                return new PGLong( val );
	            case Types.INTEGER:
	                return new PGInteger( new Integer( val.intValue()));
	            case Types.SMALLINT:
	            case Types.TINYINT:
	                return new PGShort( new Short( val.shortValue() ));
	            case Types.DECIMAL:
	            case Types.NUMERIC:
	                return new PGBigDecimal( new BigDecimal( val.toString())); 
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
