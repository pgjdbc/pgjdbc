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
public class PGFloat implements PGType
{
    static final PGFloat ZERO = new PGFloat(0f);

    static final PGFloat ONE = new PGFloat(1f);

    private Float val;

    private PGFloat( Float x )
    {
        val = x;
    }

    static final PGFloat valueOf(final Float value) {
        final float v = value; // Prevent double auto-unboxing
        return v == 0f ? PGFloat.ZERO : v == 1f ? PGFloat.ONE : new PGFloat(value);
    }

    public static PGType castToServerType( Float val, int targetType ) throws PSQLException
    {
        try
        {
            switch ( targetType )
            {
	            case Types.BIT:
	                return PGBoolean.valueOf(val != 0f);

	            case Types.BIGINT:
	                return PGLong.valueOf(val.longValue());
	            case Types.INTEGER:
	                return PGInteger.valueOf(val.intValue());
	            case Types.SMALLINT:
	            case Types.TINYINT:
	                return PGShort.valueOf(val.shortValue());
	            case Types.VARCHAR:
	            case Types.LONGVARCHAR:
	                return PGString.valueOf( val.toString() );
	            case Types.DOUBLE:
	            case Types.FLOAT:
	                return PGDouble.valueOf(val.doubleValue());
	            case Types.REAL:
	                return PGFloat.valueOf( val );
	            case Types.DECIMAL:
	            case Types.NUMERIC:
	                return PGBigDecimal.valueOf( new BigDecimal( val.toString()));
	            default:
	                return PGUnknown.valueOf(val);
            }
        }
        catch( Exception ex)
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
