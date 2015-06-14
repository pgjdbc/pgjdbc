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
public class PGBoolean implements PGType
{
    static final PGBoolean TRUE = new PGBoolean(Boolean.TRUE);

    static final PGBoolean FALSE = new PGBoolean(Boolean.FALSE);

    private Boolean val;

    private PGBoolean(Boolean x)
    {
        val = x;
    }

    static final PGBoolean valueOf(final Boolean value) {
        return Boolean.FALSE.equals(value) ? PGBoolean.FALSE : Boolean.TRUE.equals(value) ? PGBoolean.TRUE : new PGBoolean(value);
    }

    public static PGType castToServerType( Boolean val, int targetType ) throws PSQLException
    {
        try
        {
        switch ( targetType )
        {
            case Types.BIGINT:
                return val == true ? PGLong.ONE : PGLong.ZERO;
            case Types.INTEGER:
                return val == true ? PGInteger.ONE : PGInteger.ZERO;
            case Types.SMALLINT:
            case Types.TINYINT:
                return val == true ? PGShort.ONE : PGShort.ZERO;
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
                return val == true ? PGString.TRUE : PGString.FALSE;
            case Types.DOUBLE:
            case Types.FLOAT:
            		return val == true ? PGDouble.ONE : PGDouble.ZERO;
            case Types.REAL:
        		return val == true ? PGFloat.ONE : PGFloat.ZERO;
            case Types.NUMERIC:
            case Types.DECIMAL:
                return val == true ? PGBigDecimal.ONE : PGBigDecimal.ZERO;

            case Types.BIT:
                return PGBoolean.valueOf(val);
            default:
                return PGUnknown.valueOf( val );
        }
        }
        catch(Exception ex)
        {
            throw new PSQLException(GT.tr("Cannot convert an instance of {0} to type {1}", new Object[]{val.getClass().getName(),"Types.OTHER"}), PSQLState.INVALID_PARAMETER_TYPE, ex);
        }
    }
    @Override
    public String toString()
    {
        return val ==true?"true":"false";
    }

}
