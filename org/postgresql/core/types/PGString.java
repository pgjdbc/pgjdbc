/*
 * Created on May 15, 2005
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package org.postgresql.core.types;

import java.math.BigDecimal;
import java.sql.Types;

import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * @author davec
 *
 * TODO To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
public class PGString implements PGType
{

    String val;
    public PGString( String x )
    {
        val = x;
    }
    /* (non-Javadoc)
     * @see org.postgresql.types.PGType#castToServerType(int)
     */
    public PGType castToServerType(int targetType) throws PSQLException
    {
        try
        {
            switch (targetType )
            {
            case Types.BOOLEAN:
            case Types.BIT:
            {
                if ( val.equalsIgnoreCase("true") || val.equalsIgnoreCase("1") || val.equalsIgnoreCase("t"))
                    return new PGBoolean( Boolean.TRUE );
                if ( val.equalsIgnoreCase("false") || val.equalsIgnoreCase("0") || val.equalsIgnoreCase("f"))
                    return new PGBoolean( Boolean.FALSE);
            }
            
            return new PGBoolean( Boolean.FALSE);
            
            case Types.VARCHAR:
                return this;
            case Types.BIGINT:
                return new PGLong( new Long(Long.parseLong( val )));
            case Types.INTEGER:
                return new PGInteger( new Integer(Integer.parseInt( val )));
            case Types.TINYINT:
                return new PGShort( new Short( Short.parseShort( val )));
            case Types.FLOAT:
            case Types.DOUBLE:
                return new PGDouble( new Double(Double.parseDouble( val )));
            case Types.REAL:
                return new PGFloat( new Float( Float.parseFloat( val )));
            case Types.NUMERIC:
            case Types.DECIMAL:
                return new PGBigDecimal( new BigDecimal( val));
            default:
                return new PGUnknown( val );
            
            }
        }
        catch( Exception ex )
        {
            throw new PSQLException("Error", new PSQLState(""),ex);
        }
    }
    public String toString()
    {
        return val;
    }

}
