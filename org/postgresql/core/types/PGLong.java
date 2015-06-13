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
    static final PGLong ZERO = new PGLong(0L);

    static final PGLong ONE = new PGLong(1L);

    private Long val;

    private PGLong( Long x )
    {
        val = x;
    }

    static final PGLong valueOf(final Long value) {
        final long v = value; // Prevent double auto-unboxing
        return v == 0L ? PGLong.ZERO : v == 1L ? PGLong.ONE : new PGLong(value);
    }

    public static PGType castToServerType(Long val, int targetType ) throws PSQLException
    {
        try
        {
            switch ( targetType )
            {
	            case Types.BIT:
	                return PGBoolean.valueOf(val != 0L);
	            case Types.REAL:
	                return PGFloat.valueOf(val.floatValue());
	            case Types.FLOAT:
	            case Types.DOUBLE:
	                return PGDouble.valueOf(val.doubleValue());
	            case Types.VARCHAR:
	            case Types.LONGVARCHAR:
	                return PGString.valueOf(val.toString());
	            case Types.BIGINT:
	                return PGLong.valueOf(val);
	            case Types.INTEGER:
	                return PGInteger.valueOf(val.intValue());
	            case Types.SMALLINT:
	            case Types.TINYINT:
	                return PGShort.valueOf(val.shortValue());
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
