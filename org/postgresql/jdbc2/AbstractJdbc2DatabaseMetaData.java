package org.postgresql.jdbc2;

import java.sql.SQLException;

public abstract class AbstractJdbc2DatabaseMetaData extends org.postgresql.jdbc1.AbstractJdbc1DatabaseMetaData
{

	public AbstractJdbc2DatabaseMetaData(AbstractJdbc2Connection conn)
	{
		super(conn);
	}



	// ** JDBC 2 Extensions **

	/*
	 * Does the database support the given result set type?
	 *
	 * @param type - defined in java.sql.ResultSet
	 * @return true if so; false otherwise
	 * @exception SQLException - if a database access error occurs
	 */
	public boolean supportsResultSetType(int type) throws SQLException
	{
		// The only type we don't support
		return type != java.sql.ResultSet.TYPE_SCROLL_SENSITIVE;
	}


	/*
	 * Does the database support the concurrency type in combination
	 * with the given result set type?
	 *
	 * @param type - defined in java.sql.ResultSet
	 * @param concurrency - type defined in java.sql.ResultSet
	 * @return true if so; false otherwise
	 * @exception SQLException - if a database access error occurs
	*/
	public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException
	{
		// These combinations are not supported!
		if (type == java.sql.ResultSet.TYPE_SCROLL_SENSITIVE)
			return false;

		// We do support Updateable ResultSets
		if (concurrency == java.sql.ResultSet.CONCUR_UPDATABLE)
			return true;

		// Everything else we do
		return true;
	}


	/* lots of unsupported stuff... */
	public boolean ownUpdatesAreVisible(int type) throws SQLException
	{
		return true;
	}

	public boolean ownDeletesAreVisible(int type) throws SQLException
	{
		return true;
	}

	public boolean ownInsertsAreVisible(int type) throws SQLException
	{
		// indicates that
		return true;
	}

	public boolean othersUpdatesAreVisible(int type) throws SQLException
	{
		return false;
	}

	public boolean othersDeletesAreVisible(int i) throws SQLException
	{
		return false;
	}

	public boolean othersInsertsAreVisible(int type) throws SQLException
	{
		return false;
	}

	public boolean updatesAreDetected(int type) throws SQLException
	{
		return false;
	}

	public boolean deletesAreDetected(int i) throws SQLException
	{
		return false;
	}

	public boolean insertsAreDetected(int type) throws SQLException
	{
		return false;
	}

	/*
	 * Indicates whether the driver supports batch updates.
	 */
	public boolean supportsBatchUpdates() throws SQLException
	{
		return true;
	}

    /**
     *
     * @param catalog String
     * @param schemaPattern String
     * @param typeNamePattern String
     * @param types int[]
     * @throws SQLException
     * @return ResultSet
	 */
	public java.sql.ResultSet getUDTs(String catalog,
									  String schemaPattern,
									  String typeNamePattern,
									  int[] types
									 ) throws SQLException
	{
        String sql = "select "
                   + "null as type_cat, n.nspname as type_schem, t.typname as type_name,  null as class_name, "
                   + "CASE WHEN t.typtype='c' then "+ java.sql.Types.STRUCT + " else " + java.sql.Types.DISTINCT +" end as data_type, pg_catalog.obj_description(t.oid, 'pg_type')  "
                   + "as remarks, CASE WHEN t.typtype = 'd' then  (select CASE";

        for ( int i=0; i < AbstractJdbc2Connection.jdbc2Types.length; i++ )
        {
            sql += " when typname = '"+ AbstractJdbc2Connection.jdbc2Types[i] + "' then " + AbstractJdbc2Connection.jdbc2Typei[i] ;
        }

        sql += " else " + java.sql.Types.OTHER + " end from pg_type where oid=t.typbasetype) "
            + "else null end as base_type "
            + "from pg_catalog.pg_type t, pg_catalog.pg_namespace n where t.typnamespace = n.oid and n.nspname != 'pg_catalog' and n.nspname != 'pg_toast'";



		String toAdd = "";
        if ( types != null )
        {
            toAdd += " and (false ";
            for (int i = 0; i < types.length; i++)
            {
                switch (types[i] )
                {
                    case java.sql.Types.STRUCT:
                        toAdd += " or t.typtype = 'c'";
                        break;
                    case java.sql.Types.DISTINCT:
                        toAdd += " or t.typtype = 'd'";
                        break;
                }
            }
            toAdd += " ) ";
        } else {
	    toAdd += " and t.typtype IN ('c','d') ";
	}
        // spec says that if typeNamePattern is a fully qualified name
	// then the schema and catalog are ignored

        if (typeNamePattern != null)
        {
            // search for qualifier
            int firstQualifier = typeNamePattern.indexOf('.') ;
            int secondQualifier = typeNamePattern.lastIndexOf('.');

            if ( firstQualifier != -1 ) // if one of them is -1 they both will be
            {
                if ( firstQualifier != secondQualifier )
                {
                    // we have a catalog.schema.typename, ignore catalog
                    schemaPattern =  typeNamePattern.substring(firstQualifier+1, secondQualifier);
                }
                else
                {
                    // we just have a schema.typename
                    schemaPattern = typeNamePattern.substring(0,firstQualifier);
                }
                // strip out just the typeName
                typeNamePattern = typeNamePattern.substring(secondQualifier+1);
            }
            toAdd += " and t.typname like '" + escapeQuotes(typeNamePattern) + "'";
        }

        // schemaPattern may have been modified above
        if ( schemaPattern != null)
        {
			toAdd += " and n.nspname like '" + escapeQuotes(schemaPattern) +"'";
        }
		sql += toAdd;
        sql += " order by data_type, type_schem, type_name";
		java.sql.ResultSet rs = createMetaDataStatement().executeQuery(sql);

		return rs;
	}


	/*
	 * Retrieves the connection that produced this metadata object.
	 *
	 * @return the connection that produced this metadata object
	 */
	public java.sql.Connection getConnection() throws SQLException
	{
		return (java.sql.Connection)connection;
	}

	/* I don't find these in the spec!?! */

	public boolean rowChangesAreDetected(int type) throws SQLException
	{
		return false;
	}

	public boolean rowChangesAreVisible(int type) throws SQLException
	{
		return false;
	}
	
	protected java.sql.Statement createMetaDataStatement() throws SQLException
	{
		return ((AbstractJdbc2Connection)connection).createStatement(java.sql.ResultSet.TYPE_SCROLL_INSENSITIVE, java.sql.ResultSet.CONCUR_READ_ONLY);
	}

}
