/*
 * Created on May 15, 2005
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package legacy.org.postgresql.core.types;

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
public class PGBoolean implements PGType 
{
    private Boolean val;
    
    public PGBoolean(Boolean x)
    {
        val = x;
    }
    public static PGType castToServerType( Boolean val, int targetType ) throws PSQLException
    {
        try
        {
        switch ( targetType )
        {
            case Types.BIGINT:
                return new PGLong(new Long( val.booleanValue()==true?1:0 ));
            case Types.INTEGER:
                return new PGInteger( new Integer(  val.booleanValue()==true?1:0  ) );
            case Types.SMALLINT:
            case Types.TINYINT:
                return new PGShort( new Short( val.booleanValue()==true?(short)1:(short)0  ) );
            case Types.VARCHAR:
            case Types.LONGVARCHAR:                
                return new PGString( val.booleanValue()==true?"true":"false" );
            case Types.DOUBLE:
            case Types.FLOAT:
            		return new PGDouble( new Double(val.booleanValue()==true?1:0));
            case Types.REAL:
        		return new PGFloat( new Float(val.booleanValue()==true?1:0));
            case Types.NUMERIC:
            case Types.DECIMAL:
                return new PGBigDecimal( new java.math.BigDecimal(val.booleanValue()==true?1:0));
            
            case Types.BIT:
                return new PGBoolean( val );
            default:
                return new PGUnknown( val );
        }
        }
        catch(Exception ex)
        {
            throw new PSQLException(GT.tr("Cannot convert an instance of {0} to type {1}", new Object[]{val.getClass().getName(),"Types.OTHER"}), PSQLState.INVALID_PARAMETER_TYPE, ex);
        }
    }
    public String toString()
    {
        return val.booleanValue()==true?"true":"false";
    }
    
}
