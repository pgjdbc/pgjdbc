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
public class PGShort implements PGType
{
    static final PGShort ZERO = new PGShort((short) 0);

    static final PGShort ONE = new PGShort((short) 1);
    
    private Short val;

    private PGShort( Short x )
    {
        val = x;
    }
    
    static final PGShort valueOf(final Short value) {
        final short v = value; // Prevent double auto-unboxing
        return v == (short) 0 ? PGShort.ZERO : v == (short) 1 ? PGShort.ONE : new PGShort(value);
    }
    
    public static PGType castToServerType( Short val, int targetType ) throws PSQLException
    {
        try
        {
            switch ( targetType )
            {
	            case Types.BIT:
	                return PGBoolean.valueOf(val != (short) 0);
	            
	            case Types.SMALLINT:
	            case Types.TINYINT:
	                return PGShort.valueOf(val);
	            case Types.REAL:
	                return PGFloat.valueOf(val.floatValue());
	            case Types.DOUBLE:
	            case Types.FLOAT:
	                return PGDouble.valueOf(val.doubleValue());
	            case Types.VARCHAR:
	            case Types.LONGVARCHAR:                
	                return PGString.valueOf( val.toString() );
	            case Types.DECIMAL:
	            case Types.NUMERIC:
	                return PGBigDecimal.valueOf( new BigDecimal( val.toString()));
	            default:
	                return PGUnknown.valueOf(val);
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
