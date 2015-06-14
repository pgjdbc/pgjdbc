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
public class PGDouble  implements PGType
{
    static final PGDouble ZERO = new PGDouble(0d);

    static final PGDouble ONE = new PGDouble(1d);

    private Double val;

    private PGDouble( Double x )
    {
        val = x;
    }

    static final PGDouble valueOf(final Double value) {
        final double v = value; // Prevent double auto-unboxing
        return v == 0d ? PGDouble.ZERO : v == 1d ? PGDouble.ONE : new PGDouble(value);
    }

    public static PGType castToServerType( Double val, int targetType ) throws PSQLException
    {
        try
        {
            switch ( targetType )
            {
	            case Types.BIT:
	                return PGBoolean.valueOf(val != 0d);
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
	            case Types.DOUBLE:
	            case Types.FLOAT:
	                return PGDouble.valueOf( val );
	            case Types.REAL:
	                return PGFloat.valueOf(val.floatValue());
	            case Types.DECIMAL:
	            case Types.NUMERIC:
	                return PGBigDecimal.valueOf( new BigDecimal( val.toString()));
	            default:
	                return PGUnknown.valueOf(val);
            }
        }
        catch( Exception ex )
        {
            throw new PSQLException(GT.tr("Cannot convert an instance of {0} to type {1}", new Object[]{val.getClass().getName(),"Types.OTHER"}), PSQLState.INVALID_PARAMETER_TYPE, ex);
        }
    }
    @Override
    public String toString()
    {
        return val.toString();
    }
}
