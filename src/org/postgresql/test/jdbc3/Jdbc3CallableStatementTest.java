/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc3;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import junit.framework.TestCase;

/**
 * @author davec
 *
 */
public class Jdbc3CallableStatementTest extends TestCase
{
        
    Connection con;

    /* (non-Javadoc)
     * @see junit.framework.TestCase#setUp()
     */
    protected void setUp() throws Exception
    {
        con = TestUtil.openDB();
        Statement stmt = con.createStatement ();
        stmt.execute("create temp table numeric_tab (MAX_VAL NUMERIC(30,15), MIN_VAL NUMERIC(30,15), NULL_VAL NUMERIC(30,15) NULL)");
        stmt.execute("insert into numeric_tab values ( 999999999999999,0.000000000000001, null)");
        stmt.execute("CREATE OR REPLACE FUNCTION myiofunc(a INOUT int, b OUT int) AS 'BEGIN b := a; a := 1; END;' LANGUAGE plpgsql");
        stmt.execute("CREATE OR REPLACE FUNCTION myif(a INOUT int, b IN int) AS 'BEGIN a := b; END;' LANGUAGE plpgsql");

        stmt.execute("create or replace function "
                    			 + "Numeric_Proc( OUT IMAX NUMERIC(30,15), OUT IMIN NUMERIC(30,15), OUT INUL NUMERIC(30,15))  as "
                    			 + "'begin " 
                    			 + 	"select max_val into imax from numeric_tab;"
                    			 + 	"select min_val into imin from numeric_tab;"
                    			 + 	"select null_val into inul from numeric_tab;"
                    			 		
                    			 + " end;' "
                    			 + "language plpgsql;");
        
        stmt.execute( "CREATE OR REPLACE FUNCTION test_somein_someout("
                + "pa IN int4,"
                + "pb OUT varchar,"  
                + "pc OUT int8)"
                + " AS "
                
                + "'begin "
                + "pb := ''out'';"
                + "pc := pa + 1;"
                + "end;'"

                + "LANGUAGE plpgsql VOLATILE;"

                 );  
        stmt.execute("CREATE OR REPLACE FUNCTION test_allinout("
                + "pa INOUT int4,"
                + "pb INOUT varchar," 
                + "pc INOUT int8)"
                + " AS " 
                + "'begin "
                + "pa := pa + 1;"
                + "pb := ''foo out'';"
                + "pc := pa + 1;"
                + "end;'"
                + "LANGUAGE plpgsql VOLATILE;"
            );
        
        
    }
    /* (non-Javadoc)
     * @see junit.framework.TestCase#tearDown()
     */
    protected void tearDown() throws Exception
    {
        Statement stmt = con.createStatement();
        stmt.execute("drop function Numeric_Proc(out decimal, out decimal, out decimal)");
        stmt.execute("drop function test_somein_someout(int4)");
        stmt.execute("drop function test_allinout( inout int4, inout varchar, inout int8)");
        stmt.execute("drop function myiofunc(a INOUT int, b OUT int) ");
        stmt.execute("drop function myif(a INOUT int, b IN int)");
        stmt.close();
        TestUtil.closeDB(con);
    }

    public void testSomeInOut() throws Throwable
    {
        CallableStatement call = con.prepareCall( "{ call test_somein_someout(?,?,?) }" ) ;
        
        call.registerOutParameter(2,Types.VARCHAR);
        call.registerOutParameter(3,Types.BIGINT);
        call.setInt(1,20);
        call.execute();
    
    }
    public void testNotEnoughParameters() throws Throwable
    {
        
        CallableStatement cs = con.prepareCall("{call myiofunc(?,?)}");
        cs.setInt(1,2);
        cs.registerOutParameter(2,Types.INTEGER);
        try
        {
            cs.execute();
            fail("Should throw an exception ");
        }
        catch( SQLException ex)
        {
            assertTrue(ex.getSQLState().equalsIgnoreCase(PSQLState.SYNTAX_ERROR.getState()));
        }
        
    }
    public void testTooManyParameters() throws Throwable
    {

        CallableStatement cs = con.prepareCall("{call myif(?,?)}");
        try
        {
            cs.setInt(1,1);
            cs.setInt(2,2);
            cs.registerOutParameter(1,Types.INTEGER);
            cs.registerOutParameter(2,Types.INTEGER);
            cs.execute();
            fail("should throw an exception");
        }
        catch( SQLException ex )
        {
            assertTrue(ex.getSQLState().equalsIgnoreCase(PSQLState.SYNTAX_ERROR.getState()));
        }
        
    }
    public void testAllInOut() throws Throwable
    {
        
        CallableStatement call = con.prepareCall( "{ call test_allinout(?,?,?) }" ) ;
        
        call.registerOutParameter(1,Types.INTEGER);
        call.registerOutParameter(2,Types.VARCHAR);
        call.registerOutParameter(3,Types.BIGINT);
        call.setInt(1,20);
        call.setString(2,"hi");
        call.setInt(3,123);
        call.execute();
        call.getInt(1);
        call.getString(2);
        call.getLong(3);
   
    }

    public void testNumeric() throws Throwable
    {
        
        CallableStatement call = con.prepareCall( "{ call Numeric_Proc(?,?,?) }" ) ;
    
        call.registerOutParameter(1,Types.NUMERIC,15);
        call.registerOutParameter(2,Types.NUMERIC,15);
        call.registerOutParameter(3,Types.NUMERIC,15);
    
        call.executeUpdate();
        java.math.BigDecimal ret = call.getBigDecimal(1);
        assertTrue ("correct return from getNumeric () should be 999999999999999.000000000000000 but returned " + ret.toString(),
                                      ret.equals (new java.math.BigDecimal("999999999999999.000000000000000")));
    
        ret=call.getBigDecimal(2);
        assertTrue ("correct return from getNumeric ()",
                                  ret.equals (new java.math.BigDecimal("0.000000000000001")));
        try
        {
            ret = call.getBigDecimal(3);
        }catch(NullPointerException ex)
        {
            assertTrue("This should be null",call.wasNull());
        }
    
        
        
    }
    public void testGetObjectDecimal() throws Throwable
    {
        try
        {
            Statement stmt = con.createStatement();
            stmt.execute("create temp table decimal_tab ( max_val numeric(30,15), min_val numeric(30,15), nul_val numeric(30,15) )");
            stmt.execute("insert into decimal_tab values (999999999999999.000000000000000,0.000000000000001,null)");
    
            boolean ret = stmt.execute("create or replace function "
       			 + "decimal_proc( OUT pmax numeric, OUT pmin numeric, OUT nval numeric)  as "
       			 + "'begin " 
       			 + 	"select max_val into pmax from decimal_tab;"
       			+ 	"select min_val into pmin from decimal_tab;"
      			 + 	"select nul_val into nval from decimal_tab;"
      			 		
       			 + " end;' "
       			 + "language plpgsql;");
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call decimal_proc(?,?,?) }");
            cstmt.registerOutParameter(1, Types.DECIMAL);
            cstmt.registerOutParameter(2, Types.DECIMAL );
            cstmt.registerOutParameter(3, Types.DECIMAL );
            cstmt.executeUpdate();
            BigDecimal val = (BigDecimal)cstmt.getObject(1);
            assertTrue( val.compareTo(new BigDecimal("999999999999999.000000000000000")) == 0 );
            val = ( BigDecimal )cstmt.getObject(2);
            assertTrue( val.compareTo( new BigDecimal( "0.000000000000001")) == 0);
            val = ( BigDecimal )cstmt.getObject(3);
            assertTrue( val == null );
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function decimal_proc()");
            }
            catch (Exception ex){}
        }
    }

    public void testVarcharBool() throws Throwable
    {
        try
        {
	        Statement stmt = con.createStatement();
	        stmt.execute("create temp table vartab( max_val text, min_val text)");
	        stmt.execute("insert into vartab values ('a','b')");
	        boolean ret = stmt.execute("create or replace function "
	   			 + "updatevarchar( in imax text, in imin text)  returns int as "
	   			 + "'begin " 
	   			 + 	"update vartab set max_val = imax;"
	   			 + 	"update vartab set min_val = imin;"
	   			 +  "return 0;"
	   			 + " end;' "
	   			 + "language plpgsql;");
	        stmt.close();
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            String str = Boolean.TRUE.toString();
            CallableStatement cstmt = con.prepareCall("{ call updatevarchar(?,?) }");
            cstmt.setObject(1,Boolean.TRUE,  Types.VARCHAR);
            cstmt.setObject(2,Boolean.FALSE, Types.VARCHAR);
            
            cstmt.executeUpdate();
            cstmt.close();
            ResultSet rs = con.createStatement().executeQuery("select * from vartab");
            assertTrue(rs.next());
            assertTrue( rs.getString(1).equals(Boolean.TRUE.toString()) );
            
            assertTrue( rs.getString(2).equals(Boolean.FALSE.toString()) );
            rs.close();
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function updatevarchar(text,text)");
            }
            catch (Exception ex){}
        }
    }
    public void testInOut() throws Throwable
    {
        try
        {
	        Statement stmt = con.createStatement();
	        stmt.execute(createBitTab);
	        stmt.execute(insertBitTab);
	        boolean ret = stmt.execute("create or replace function "
	   			 + "insert_bit( inout IMAX boolean, inout IMIN boolean, inout INUL boolean)  as "
	   			 + "'begin " 
	   			 + 	"insert into bit_tab values( imax, imin, inul);"
	   			 + 	"select max_val into imax from bit_tab;"
	   			 + 	"select min_val into imin from bit_tab;"
	   			 + 	"select null_val into inul from bit_tab;"		
	   			 + " end;' "
	   			 + "language plpgsql;");
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call insert_bit(?,?,?) }");
            cstmt.setObject(1,"true",  Types.BIT);
            cstmt.setObject(2,"false", Types.BIT);
            cstmt.setNull(3,Types.BIT);
            cstmt.registerOutParameter(1, Types.BIT);
            cstmt.registerOutParameter(2, Types.BIT);
            cstmt.registerOutParameter(3, Types.BIT);
            cstmt.executeUpdate();
            
            assertTrue( cstmt.getBoolean(1) == true );
            assertTrue( cstmt.getBoolean(2) == false );
            cstmt.getBoolean(3);
            assertTrue(cstmt.wasNull());
        }
        
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function insert_bit(boolean, boolean, boolean)");
            }
            catch (Exception ex){}
        }
    }
    private final String createBitTab = "create temp table bit_tab ( max_val boolean, min_val boolean, null_val boolean )";
    private final String insertBitTab = "insert into bit_tab values (true,false,null)";
    
    public void testSetObjectBit() throws Throwable
    {
        try
        {
	        Statement stmt = con.createStatement();
	        stmt.execute(createBitTab);
	        stmt.execute(insertBitTab);
	        boolean ret = stmt.execute("create or replace function "
	   			 + "update_bit( in IMAX boolean, in IMIN boolean, in INUL boolean) returns int as "
	   			 + "'begin " 
	   			 + 	"update bit_tab set  max_val = imax;"
	   			 + 	"update bit_tab set  min_val = imin;"
	   			 + 	"update bit_tab set  min_val = inul;"
	   			 +  " return 0;"		
	   			 + " end;' "
	   			 + "language plpgsql;");
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call update_bit(?,?,?) }");
            cstmt.setObject(1,"true",  Types.BIT);
            cstmt.setObject(2,"false", Types.BIT);
            cstmt.setNull(3,Types.BIT);
            cstmt.executeUpdate();
            cstmt.close();
            ResultSet rs = con.createStatement().executeQuery("select * from bit_tab");
            
            assertTrue( rs.next() );
            assertTrue( rs.getBoolean(1) == true );
            assertTrue( rs.getBoolean(2) == false );
            rs.getBoolean(3);
            assertTrue( rs.wasNull() );
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function update_bit(boolean, boolean, boolean)");
            }
            catch (Exception ex){}
        }
    }
    public void testGetObjectLongVarchar() throws Throwable
    {
        try
        {
        Statement stmt = con.createStatement();
        stmt.execute("create temp table longvarchar_tab ( t text, null_val text )");
        stmt.execute("insert into longvarchar_tab values ('testdata',null)");
        boolean ret = stmt.execute("create or replace function "
   			 + "longvarchar_proc( OUT pcn text, OUT nval text)  as "
   			 + "'begin " 
   			 + 	"select t into pcn from longvarchar_tab;"
   			 + 	"select null_val into nval from longvarchar_tab;"
  			 		
   			 + " end;' "
   			 + "language plpgsql;");
        
        ret = stmt.execute("create or replace function "
      			 + "lvarchar_in_name( IN pcn text) returns int as "
       			 + "'begin " 
       			 + 	"update longvarchar_tab set t=pcn;"
      			 +  "return 0;"		
       			 + " end;' "
       			 + "language plpgsql;");
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call longvarchar_proc(?,?) }");
            cstmt.registerOutParameter(1, Types.LONGVARCHAR);
            cstmt.registerOutParameter(2, Types.LONGVARCHAR );
            cstmt.executeUpdate();
            String val = (String)cstmt.getObject(1);
            assertTrue( val.equals("testdata")  );
            val = ( String )cstmt.getObject(2);
            assertTrue( val == null );
            cstmt.close();
            cstmt = con.prepareCall("{ call lvarchar_in_name(?) }");
            String maxFloat = "3.4E38";
            cstmt.setObject( 1, new Float(maxFloat), Types.LONGVARCHAR);
            cstmt.executeUpdate();
            cstmt.close();
            Statement stmt = con.createStatement();
            ResultSet rs = stmt.executeQuery("select * from longvarchar_tab");
            assertTrue(rs.next());
            String rval = (String)rs.getObject(1);
            assertEquals( rval.trim(), maxFloat.trim());
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function longvarchar_proc()");
                dstmt.execute("drop function lvarchar_in_name(text)");
            }
            catch (Exception ex){}
        }
    }
    public void testGetBytes01() throws Throwable
    {
        byte [] testdata = "TestData".getBytes();
        try
        {
	        Statement stmt = con.createStatement();
	        stmt.execute("create temp table varbinary_tab ( vbinary bytea, null_val bytea )");
	        boolean ret = stmt.execute("create or replace function "
	   			 + "varbinary_proc( OUT pcn bytea, OUT nval bytea)  as "
	   			 + "'begin " 
	   			 + 	"select vbinary into pcn from varbinary_tab;"
	   			 + 	"select null_val into nval from varbinary_tab;"
	  			 		
	   			 + " end;' "
	   			 + "language plpgsql;");
	        stmt.close();
	        PreparedStatement pstmt = con.prepareStatement("insert into varbinary_tab values (?,?)");
	        pstmt.setBytes( 1, testdata);
	        pstmt.setBytes( 2, null );
	        
	        pstmt.executeUpdate();
	        pstmt.close();
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call varbinary_proc(?,?) }");
            cstmt.registerOutParameter(1, Types.VARBINARY);
            cstmt.registerOutParameter(2, Types.VARBINARY );
            cstmt.executeUpdate();
            byte [] retval = cstmt.getBytes(1);
            for( int i = 0; i < testdata.length; i++)
            {
                assertTrue( testdata[i] == retval[i]  );
            }
            
            retval = cstmt.getBytes(2);
            assertTrue( retval == null );
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function varbinary_proc()");
            }
            catch (Exception ex){}
        }
    }
    private final String createDecimalTab = "create temp table decimal_tab ( max_val float, min_val float, null_val float )";
    private final String insertDecimalTab = "insert into decimal_tab values (1.0E125,1.0E-130,null)";
    private final String createFloatProc = "create or replace function "
			 + "float_proc( OUT IMAX float, OUT IMIN float, OUT INUL float)  as "
   			 + "'begin " 
   			 + 	"select max_val into imax from decimal_tab;"
   			 + 	"select min_val into imin from decimal_tab;"
   			 + 	"select null_val into inul from decimal_tab;"
   			 + " end;' "
   			 + "language plpgsql;";
    
    private final String createUpdateFloat = "create or replace function "
    			+ "updatefloat_proc ( IN maxparm float, IN minparm float ) returns int as "
    			+ "'begin "
    			+ "update decimal_tab set max_val=maxparm;"
    			+ "update decimal_tab set min_val=minparm;"
    			+ "return 0;"
    			+ " end;' "
    			+ "language plpgsql;";

    private final String createRealTab = "create temp table real_tab ( max_val float(25), min_val float(25), null_val float(25) )";
    private final String insertRealTab = "insert into real_tab values (1.0E37,1.0E-37, null)";
    
   	private final String dropFloatProc = "drop function float_proc()";
   	private final String createUpdateReal = "create or replace function "
		+ "update_real_proc ( IN maxparm float(25), IN minparm float(25) ) returns int as "
		+ "'begin "
		+ "update real_tab set max_val=maxparm;"
		+ "update real_tab set min_val=minparm;"
		+ "return 0;"
		+ " end;' "
		+ "language plpgsql;";
   	private final String dropUpdateReal = "drop function update_real_proc(float, float)";
   	private final double [] doubleValues =  {1.0E125, 1.0E-130};
   	private final float [] realValues = {(float)1.0E37,(float)1.0E-37};
   	private final int []intValues = {2147483647,-2147483648};
   	
    public void testUpdateReal() throws Throwable
    {
        try
        {
	        Statement stmt = con.createStatement();
	        stmt.execute(createRealTab);
	        boolean ret = stmt.execute(createUpdateReal);
	        
	        stmt.execute(insertRealTab);
	        stmt.close();
	        
	        
	    }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call update_real_proc(?,?) }");
            BigDecimal val = new BigDecimal( intValues[0] );
            float x = val.floatValue();
            cstmt.setObject( 1, val, Types.REAL );
            val = new BigDecimal( intValues[1]);
            cstmt.setObject( 2, val, Types.REAL );
            cstmt.executeUpdate();
            cstmt.close();
            ResultSet rs = con.createStatement().executeQuery("select * from real_tab");
            assertTrue ( rs.next() );
            Float oVal = new Float( intValues[0]);
            Float rVal = new Float(rs.getObject(1).toString());
            assertTrue ( oVal.equals(rVal) );
            oVal = new Float( intValues[1] );
            rVal = new Float(rs.getObject(2).toString());
            assertTrue ( oVal.equals(rVal) );
            rs.close();
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute(dropUpdateReal);
                dstmt.close();
            }
            catch (Exception ex){}
        }
    }   	
    public void testUpdateDecimal() throws Throwable
    {
        try
        {
	        Statement stmt = con.createStatement();
	        stmt.execute(createDecimalTab);
	        boolean ret = stmt.execute(createUpdateFloat);
	        stmt.close();
	        PreparedStatement pstmt = con.prepareStatement("insert into decimal_tab values (?,?)");
	        // note these are reversed on purpose
	        pstmt.setDouble(1, doubleValues[1]);
	        pstmt.setDouble(2, doubleValues[0]);
	        
	        pstmt.executeUpdate();
	        pstmt.close();
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call updatefloat_proc(?,?) }");
            cstmt.setDouble(1, doubleValues[0]);
            cstmt.setDouble(2, doubleValues[1]);
            cstmt.executeUpdate();
            cstmt.close();
            ResultSet rs = con.createStatement().executeQuery("select * from decimal_tab");
            assertTrue ( rs.next() );
            assertTrue ( rs.getDouble(1) == doubleValues[0]);
            assertTrue ( rs.getDouble(2) == doubleValues[1]);
            rs.close();
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function updatefloat_proc(float, float)");
            }
            catch (Exception ex){}
        }
    }
    public void testGetBytes02() throws Throwable
    {
        byte [] testdata = "TestData".getBytes();
        try
        {
	        Statement stmt = con.createStatement();
	        stmt.execute("create temp table longvarbinary_tab ( vbinary bytea, null_val bytea )");
	        boolean ret = stmt.execute("create or replace function "
	   			 + "longvarbinary_proc( OUT pcn bytea, OUT nval bytea)  as "
	   			 + "'begin " 
	   			 + 	"select vbinary into pcn from longvarbinary_tab;"
	   			 + 	"select null_val into nval from longvarbinary_tab;"
	  			 		
	   			 + " end;' "
	   			 + "language plpgsql;");
	        stmt.close();
	        PreparedStatement pstmt = con.prepareStatement("insert into longvarbinary_tab values (?,?)");
	        pstmt.setBytes( 1, testdata);
	        pstmt.setBytes( 2, null );
	        
	        pstmt.executeUpdate();
	        pstmt.close();
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call longvarbinary_proc(?,?) }");
            cstmt.registerOutParameter(1, Types.LONGVARBINARY);
            cstmt.registerOutParameter(2, Types.LONGVARBINARY );
            cstmt.executeUpdate();
            byte [] retval = cstmt.getBytes(1);
            for( int i = 0; i < testdata.length; i++)
            {
                assertTrue( testdata[i] == retval[i]  );
            }
            
            retval = cstmt.getBytes(2);
            assertTrue( retval == null );
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function longvarbinary_proc()");
            }
            catch (Exception ex){}
        }
    }
    
   	
    public void testGetObjectFloat() throws Throwable
    {
        try
        {
        Statement stmt = con.createStatement();
        stmt.execute( createDecimalTab );
        stmt.execute( insertDecimalTab );
        boolean ret = stmt.execute(createFloatProc);
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call float_proc(?,?,?) }");
            cstmt.registerOutParameter(1,java.sql.Types.FLOAT);
            cstmt.registerOutParameter(2,java.sql.Types.FLOAT);
            cstmt.registerOutParameter(3,java.sql.Types.FLOAT);
            cstmt.executeUpdate();
            Double val = (Double)cstmt.getObject(1);
            assertTrue( val.doubleValue() == doubleValues[0] );
            
            val = (Double)cstmt.getObject(2);
            assertTrue( val.doubleValue() == doubleValues[1]);
            
            val = (Double)cstmt.getObject(3);            
            assertTrue( cstmt.wasNull() );
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute(dropFloatProc);
            }
            catch (Exception ex){}
        }
    }

    public void testGetDouble01() throws Throwable
    {
        try
        {
	        Statement stmt = con.createStatement();
	        stmt.execute("create temp table d_tab ( max_val float, min_val float, null_val float )");
	        stmt.execute("insert into d_tab values (1.0E125,1.0E-130,null)");
	        boolean ret = stmt.execute("create or replace function "
	   			 + "double_proc( OUT IMAX float, OUT IMIN float, OUT INUL float)  as "
	   			 + "'begin " 
	   			 + 	"select max_val into imax from d_tab;"
	   			 + 	"select min_val into imin from d_tab;"
	   			 + 	"select null_val into inul from d_tab;"
	   			 		
	   			 + " end;' "
	   			 + "language plpgsql;");
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call double_proc(?,?,?) }");
            cstmt.registerOutParameter(1,java.sql.Types.DOUBLE);
            cstmt.registerOutParameter(2,java.sql.Types.DOUBLE);
            cstmt.registerOutParameter(3,java.sql.Types.DOUBLE);
            cstmt.executeUpdate();
            assertTrue( cstmt.getDouble(1) == 1.0E125 );
            assertTrue( cstmt.getDouble(2) == 1.0E-130);
            cstmt.getDouble(3);
            assertTrue( cstmt.wasNull() );
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function double_proc()");
            }
            catch (Exception ex){}
        }
    }
    public void testGetDoubleAsReal() throws Throwable
    {
        try
        {
	        Statement stmt = con.createStatement();
	        stmt.execute("create temp table d_tab ( max_val float, min_val float, null_val float )");
	        stmt.execute("insert into d_tab values (3.4E38,1.4E-45,null)");
	        boolean ret = stmt.execute("create or replace function "
	   			 + "double_proc( OUT IMAX float, OUT IMIN float, OUT INUL float)  as "
	   			 + "'begin " 
	   			 + 	"select max_val into imax from d_tab;"
	   			 + 	"select min_val into imin from d_tab;"
	   			 + 	"select null_val into inul from d_tab;"
	   			 		
	   			 + " end;' "
	   			 + "language plpgsql;");
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call double_proc(?,?,?) }");
            cstmt.registerOutParameter(1,java.sql.Types.REAL);
            cstmt.registerOutParameter(2,java.sql.Types.REAL);
            cstmt.registerOutParameter(3,java.sql.Types.REAL);
            cstmt.executeUpdate();
            assertTrue( cstmt.getFloat(1) == 3.4E38f);
            assertTrue( cstmt.getFloat(2) == 1.4E-45f);
            cstmt.getFloat(3);
            assertTrue( cstmt.wasNull() );
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function double_proc()");
            }
            catch (Exception ex){}
        }
    }
    public void testGetShort01() throws Throwable
    {
        try
        {
	        Statement stmt = con.createStatement();
	        stmt.execute("create temp table short_tab ( max_val int2, min_val int2, null_val int2 )");
	        stmt.execute("insert into short_tab values (32767,-32768,null)");
	        boolean ret = stmt.execute("create or replace function "
	   			 + "short_proc( OUT IMAX int2, OUT IMIN int2, OUT INUL int2)  as "
	   			 + "'begin " 
	   			 + 	"select max_val into imax from short_tab;"
	   			 + 	"select min_val into imin from short_tab;"
	   			 + 	"select null_val into inul from short_tab;"
	   			 		
	   			 + " end;' "
	   			 + "language plpgsql;");
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call short_proc(?,?,?) }");
            cstmt.registerOutParameter(1,java.sql.Types.SMALLINT);
            cstmt.registerOutParameter(2,java.sql.Types.SMALLINT);
            cstmt.registerOutParameter(3,java.sql.Types.SMALLINT);
            cstmt.executeUpdate();
            assertTrue ( cstmt.getShort(1) == 32767);
            assertTrue ( cstmt.getShort(2) == -32768);
            cstmt.getShort(3);
            assertTrue( cstmt.wasNull() );
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function short_proc()");
            }
            catch (Exception ex){}
        }
    }
    public void testGetInt01() throws Throwable
    {
        try
        {
        Statement stmt = con.createStatement();
        stmt.execute("create temp table i_tab ( max_val int, min_val int, null_val int )");
        stmt.execute("insert into i_tab values (2147483647,-2147483648,null)");
        boolean ret = stmt.execute("create or replace function "
   			 + "int_proc( OUT IMAX int, OUT IMIN int, OUT INUL int)  as "
   			 + "'begin " 
   			 + 	"select max_val into imax from i_tab;"
   			 + 	"select min_val into imin from i_tab;"
   			 + 	"select null_val into inul from i_tab;"
   			 		
   			 + " end;' "
   			 + "language plpgsql;");
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call int_proc(?,?,?) }");
            cstmt.registerOutParameter(1,java.sql.Types.INTEGER);
            cstmt.registerOutParameter(2,java.sql.Types.INTEGER);
            cstmt.registerOutParameter(3,java.sql.Types.INTEGER);
            cstmt.executeUpdate();
            assertTrue( cstmt.getInt(1) == 2147483647);
            assertTrue( cstmt.getInt(2) == -2147483648);
            cstmt.getInt(3);
            assertTrue( cstmt.wasNull() );
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function int_proc()");
            }
            catch (Exception ex){}
        }
    }
    public void testGetLong01() throws Throwable
    {
        try
        {
        Statement stmt = con.createStatement();
        stmt.execute("create temp table l_tab ( max_val int8, min_val int8, null_val int8 )");
        stmt.execute("insert into l_tab values (9223372036854775807,-9223372036854775808,null)");
        boolean ret = stmt.execute("create or replace function "
   			 + "bigint_proc( OUT IMAX int8, OUT IMIN int8, OUT INUL int8)  as "
   			 + "'begin " 
   			 + 	"select max_val into imax from l_tab;"
   			 + 	"select min_val into imin from l_tab;"
   			 + 	"select null_val into inul from l_tab;"
   			 		
   			 + " end;' "
   			 + "language plpgsql;");
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call bigint_proc(?,?,?) }");
            cstmt.registerOutParameter(1,java.sql.Types.BIGINT);
            cstmt.registerOutParameter(2,java.sql.Types.BIGINT);
            cstmt.registerOutParameter(3,java.sql.Types.BIGINT);
            cstmt.executeUpdate();
            assertTrue(cstmt.getLong( 1 ) == 9223372036854775807l );
            assertTrue( cstmt.getLong(2) == -9223372036854775808l );
            cstmt.getLong(3);
            assertTrue( cstmt.wasNull() );
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function bigint_proc()");
            }
            catch (Exception ex){}
        }
    }
    
    public void testGetBoolean01() throws Throwable
    {
        try
        {
	        Statement stmt = con.createStatement();
	        stmt.execute(createBitTab);
	        stmt.execute(insertBitTab);
	        boolean ret = stmt.execute("create or replace function "
	   			 + "bit_proc( OUT IMAX boolean, OUT IMIN boolean, OUT INUL boolean)  as "
	   			 + "'begin " 
	   			 + 	"select max_val into imax from bit_tab;"
	   			 + 	"select min_val into imin from bit_tab;"
	   			 + 	"select null_val into inul from bit_tab;"
	   			 		
	   			 + " end;' "
	   			 + "language plpgsql;");
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call bit_proc(?,?,?) }");
            cstmt.registerOutParameter(1,java.sql.Types.BIT);
            cstmt.registerOutParameter(2,java.sql.Types.BIT);
            cstmt.registerOutParameter(3,java.sql.Types.BIT);
            cstmt.executeUpdate();
            assertTrue(cstmt.getBoolean( 1 ) );
            assertTrue( cstmt.getBoolean(2) == false );
            cstmt.getBoolean(3);
            assertTrue( cstmt.wasNull() );
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function bit_proc()");
            }
            catch (Exception ex){}
        }
    }
    public void testGetByte01() throws Throwable
    {
        try
        {
        Statement stmt = con.createStatement();
        stmt.execute("create temp table byte_tab ( max_val int2, min_val int2, null_val int2 )");
        stmt.execute("insert into byte_tab values (127,-128,null)");
        boolean ret = stmt.execute("create or replace function "
   			 + "byte_proc( OUT IMAX int2, OUT IMIN int2, OUT INUL int2)  as "
   			 + "'begin " 
   			 + 	"select max_val into imax from byte_tab;"
   			 + 	"select min_val into imin from byte_tab;"
   			 + 	"select null_val into inul from byte_tab;"
   			 		
   			 + " end;' "
   			 + "language plpgsql;");
        }
        catch (Exception ex)
        {
            fail ( ex.getMessage());
            throw ex;
        }
        try 
        {
            CallableStatement cstmt = con.prepareCall("{ call byte_proc(?,?,?) }");
            cstmt.registerOutParameter(1,java.sql.Types.TINYINT);
            cstmt.registerOutParameter(2,java.sql.Types.TINYINT);
            cstmt.registerOutParameter(3,java.sql.Types.TINYINT);
            cstmt.executeUpdate();
            assertTrue(cstmt.getByte( 1 ) == 127 );
            assertTrue( cstmt.getByte(2) == -128 );
            cstmt.getByte(3);
            assertTrue( cstmt.wasNull() );
        }
        catch ( Exception ex )
        {
            fail(ex.getMessage());
        }
        finally
        {
            try
            {
                Statement dstmt = con.createStatement();
                dstmt.execute("drop function byte_proc()");
            }
            catch (Exception ex){}
        }
    }           

    public void testMultipleOutExecutions() throws SQLException
    {
        CallableStatement cs = con.prepareCall("{call myiofunc(?, ?)}");
        for (int i=0; i<10; i++) {
            cs.registerOutParameter(1, Types.INTEGER);
            cs.registerOutParameter(2, Types.INTEGER);
            cs.setInt(1, i);
            cs.execute();
            assertEquals(1, cs.getInt(1));
            assertEquals(i, cs.getInt(2));
            cs.clearParameters();
        }
    }

}
