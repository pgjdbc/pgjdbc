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
class PGString implements PGType
{
    static final PGString FALSE = new PGString("false");

    static final PGString TRUE = new PGString("true");

    private String val;

    private PGString( String x )
    {
        val = x;
    }

    static final PGString valueOf(final String value) {
        return new PGString(value);
    }

    /* (non-Javadoc)
     * @see org.postgresql.types.PGType#castToServerType(int)
     */
    public static PGType castToServerType(String val, int targetType) throws PSQLException
    {
        try
        {
            switch (targetType )
            {
	            case Types.BIT:
	            {
	                if ( val.equalsIgnoreCase("true") || val.equalsIgnoreCase("1") || val.equalsIgnoreCase("t"))
	                    return PGBoolean.TRUE;
	                if ( val.equalsIgnoreCase("false") || val.equalsIgnoreCase("0") || val.equalsIgnoreCase("f"))
	                    return PGBoolean.FALSE;
	            }

	            return PGBoolean.FALSE;

	            case Types.VARCHAR:
	            case Types.LONGVARCHAR:
	                return PGString.valueOf(val);
	            case Types.BIGINT:
	                return PGLong.valueOf(Long.parseLong(val));
	            case Types.INTEGER:
	                return PGInteger.valueOf(Integer.parseInt(val));
	            case Types.TINYINT:
	                return PGShort.valueOf(Short.parseShort(val));
	            case Types.FLOAT:
	            case Types.DOUBLE:
	                return PGDouble.valueOf(Double.parseDouble(val));
	            case Types.REAL:
	                return PGFloat.valueOf(Float.parseFloat(val));
	            case Types.NUMERIC:
	            case Types.DECIMAL:
	                return PGBigDecimal.valueOf( new BigDecimal( val));
	            default:
	                return PGUnknown.valueOf( val );

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
        return val;
    }

}
