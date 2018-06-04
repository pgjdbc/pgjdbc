package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.postgresql.test.TestUtil;

/**
 * Executes the query with the outer join syntax. The joined tables are encapsulated with parenthesis. 
 * 
 * <b>Note:</b> This query has worked up to driver version 9.4.1211 . Encapsulation with parenthesis is used by third party
 * like CrystalReports.  
 */
public class OuterJoinSyntaxTest
{
  /**
   * The name of the test first table.
   */
  public static String MY_TABLE = "mytable";
  
  /**
   * The name of the test second table.
   */
  public static String YOUR_TABLE = "yourtable";
  
  /**
   * The name of the test third table.
   */
  public static String OUR_TABLE = "ourtable";
  
  /**
   * The connection to the test database.
   */
  private Connection connection;
  
  /**
   * Prepares the test environment. This means:
   * <ul>
   * <li>open a connection to the test database</li>
   * <li>create the tables, that are used by this test</li>
   * <li>fill data into the test tables</li>
   * </ul>
   * 
   * @throws Exception
   */
  @Before
  public void setUp() 
  throws Exception 
  {
    connection = TestUtil.openDB();
    
    TestUtil.createTable(connection, MY_TABLE, "id int not null, text varchar(255) not null");
    TestUtil.createTable(connection, YOUR_TABLE, "id int not null, text varchar(255) not null");
    TestUtil.createTable(connection, OUR_TABLE, "id int not null, text varchar(255) not null");
    
    fillTestTable(
        connection, 
        MY_TABLE, 
        new Object[][] {
            {1, MY_TABLE + "text_01"},
            {2, MY_TABLE + "text_02"},
            {3, MY_TABLE + "text_03"},
        });
    
    fillTestTable(
        connection, 
        YOUR_TABLE, 
        new Object[][] {
            {11, YOUR_TABLE + "text_11"},
            {12, YOUR_TABLE + "text_12"},
            {13, YOUR_TABLE + "text_13"},
        });

    fillTestTable(
        connection, 
        OUR_TABLE, 
        new Object[][] {
            {21, OUR_TABLE + "text_21"},
            {22, OUR_TABLE + "text_22"},
            {23, OUR_TABLE + "text_23"},
        });
  }
  
  /**
   * Drops the tables, that have been created by this test.
   * 
   * @throws Exception
   */
  @After
  public void tearDown()
      throws Exception
  {
    TestUtil.dropTable(connection, MY_TABLE);
    TestUtil.dropTable(connection, YOUR_TABLE);
    TestUtil.dropTable(connection, OUR_TABLE);
    TestUtil.closeDB(connection);
  }
  
  /**
   * Executes the query with the outer join syntax. The joined tables are encapsulated with parenthesis. 
   * 
   * <b>Note:</b> This query has worked up to driver version 9.4.1211 . Encapsulation with parenthesis is used by third party
   * like CrystalReports.  
   *    
   * @throws Exception
   */
  @Test
  public void testExecuteOuterJoinQuery()
  throws Exception
  {
    // execute the statement
    Statement _statement = null;
    try
    {
      _statement = connection.createStatement();  

      ResultSet _rs = null;
      try
      {
        // Here is the test query with a nested outer join, that is encapsulated in parenthesis
        _rs = _statement.executeQuery("SELECT * FROM   {oj (" + MY_TABLE + " m LEFT OUTER JOIN " + YOUR_TABLE + " y ON m.id=y.id) "
            + "LEFT OUTER JOIN " + OUR_TABLE + " o ON m.id=o.id}");
        
        assertTrue(_rs.next());
      }
      finally 
      {
        if (_rs != null)
        {
          _rs.close();
        }
      }
    }
    finally 
    {
      if (_statement != null)
      {
        _statement.close();
      }
    }
  }
  
  /**
   * Inserts a data row with the given values to the specified test table.
   * 
   * @param theConnection the connection to the test database.
   * @param theTableName the name of the test table.
   * @param theValues the data to insert.
   * <ol>
   * <li>the value for the ID column</li>
   * <li>the value for the test column</li>
   * </ol>
   * 
   * @throws SQLException
   */
  private static void fillTestTable(
      final Connection theConnection, 
      final String theTableName, 
      final Object[][] theValues)
  throws SQLException
  {
    PreparedStatement _statement = null;
    
    try
    {
      _statement = theConnection.prepareStatement(
          "insert into " + theTableName + " (id, text) values (?,?)");  


      for (final Object[] _currRow : theValues)
      {
        _statement.setInt(1, (Integer)_currRow[0]);
        _statement.setString(2, (String)_currRow[1]);
        
        _statement.executeUpdate();
      }
    }
    finally 
    {
      if (_statement != null)
      {
        _statement.close();
      }
    }
  }
}
