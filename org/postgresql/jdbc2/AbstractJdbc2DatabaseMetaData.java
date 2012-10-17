/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import java.sql.*;
import java.util.*;

import org.postgresql.core.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.Driver;
import org.postgresql.util.GT;

public abstract class AbstractJdbc2DatabaseMetaData
{

    public AbstractJdbc2DatabaseMetaData(AbstractJdbc2Connection conn)
    {
        this.connection = conn;
    }


    private static final String keywords = "abort,acl,add,aggregate,append,archive," +
                                           "arch_store,backward,binary,boolean,change,cluster," +
                                           "copy,database,delimiter,delimiters,do,extend," +
                                           "explain,forward,heavy,index,inherits,isnull," +
                                           "light,listen,load,merge,nothing,notify," +
                                           "notnull,oids,purge,rename,replace,retrieve," +
                                           "returns,rule,recipe,setof,stdin,stdout,store," +
                                           "vacuum,verbose,version";

    protected final AbstractJdbc2Connection connection; // The connection association

    private int NAMEDATALEN = 0; // length for name datatype
    private int INDEX_MAX_KEYS = 0; // maximum number of keys in an index.

    protected int getMaxIndexKeys() throws SQLException {
        if (INDEX_MAX_KEYS == 0)
        {
            String sql;
            if (connection.haveMinimumServerVersion("8.0")) {
                sql = "SELECT setting FROM pg_catalog.pg_settings WHERE name='max_index_keys'";
            } else {
                String from;
                if (connection.haveMinimumServerVersion("7.3"))
                {
                    from = "pg_catalog.pg_namespace n, pg_catalog.pg_type t1, pg_catalog.pg_type t2 WHERE t1.typnamespace=n.oid AND n.nspname='pg_catalog' AND ";
                }
                else
                {
                    from = "pg_type t1, pg_type t2 WHERE ";
                }
                sql = "SELECT t1.typlen/t2.typlen FROM " + from + " t1.typelem=t2.oid AND t1.typname='oidvector'";
            }
            ResultSet rs = connection.createStatement().executeQuery(sql);
            if (!rs.next())
            {
                throw new PSQLException(GT.tr("Unable to determine a value for MaxIndexKeys due to missing system catalog data."), PSQLState.UNEXPECTED_ERROR);
            }
            INDEX_MAX_KEYS = rs.getInt(1);
            rs.close();
        }
        return INDEX_MAX_KEYS;
    }

    protected int getMaxNameLength() throws SQLException {
        if (NAMEDATALEN == 0)
        {
            String sql;
            if (connection.haveMinimumServerVersion("7.3"))
            {
                sql = "SELECT t.typlen FROM pg_catalog.pg_type t, pg_catalog.pg_namespace n WHERE t.typnamespace=n.oid AND t.typname='name' AND n.nspname='pg_catalog'";
            }
            else
            {
                sql = "SELECT typlen FROM pg_type WHERE typname='name'";
            }
            ResultSet rs = connection.createStatement().executeQuery(sql);
            if (!rs.next())
            {
                throw new PSQLException(GT.tr("Unable to find name datatype in the system catalogs."), PSQLState.UNEXPECTED_ERROR);
            }
            NAMEDATALEN = rs.getInt("typlen");
            rs.close();
        }
        return NAMEDATALEN - 1;
    }


    /*
     * Can all the procedures returned by getProcedures be called
     * by the current user?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean allProceduresAreCallable() throws SQLException
    {
        return true;  // For now...
    }

    /*
     * Can all the tables returned by getTable be SELECTed by
     * the current user?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean allTablesAreSelectable() throws SQLException
    {
        return true;  // For now...
    }

    /*
     * What is the URL for this database?
     *
     * @return the url or null if it cannott be generated
     * @exception SQLException if a database access error occurs
     */
    public String getURL() throws SQLException
    {
        return connection.getURL();
    }

    /*
     * What is our user name as known to the database?
     *
     * @return our database user name
     * @exception SQLException if a database access error occurs
     */
    public String getUserName() throws SQLException
    {
        return connection.getUserName();
    }

    /*
     * Is the database in read-only mode?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isReadOnly() throws SQLException
    {
        return connection.isReadOnly();
    }

    /*
     * Are NULL values sorted high?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean nullsAreSortedHigh() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.2");
    }

    /*
     * Are NULL values sorted low?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean nullsAreSortedLow() throws SQLException
    {
        return false;
    }

    /*
     * Are NULL values sorted at the start regardless of sort order?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean nullsAreSortedAtStart() throws SQLException
    {
        return false;
    }

    /*
     * Are NULL values sorted at the end regardless of sort order?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean nullsAreSortedAtEnd() throws SQLException
    {
        return !connection.haveMinimumServerVersion("7.2");
    }

    /*
     * What is the name of this database product - we hope that it is
     * PostgreSQL, so we return that explicitly.
     *
     * @return the database product name
     * @exception SQLException if a database access error occurs
     */
    public String getDatabaseProductName() throws SQLException
    {
        return "PostgreSQL";
    }

    /*
     * What is the version of this database product.
     *
     * @return the database version
     * @exception SQLException if a database access error occurs
     */
    public String getDatabaseProductVersion() throws SQLException
    {
        return connection.getDBVersionNumber();
    }

    /*
     * What is the name of this JDBC driver?  If we don't know this
     * we are doing something wrong!
     *
     * @return the JDBC driver name
     * @exception SQLException why?
     */
    public String getDriverName() throws SQLException
    {
        return "PostgreSQL Native Driver";
    }

    /*
     * What is the version string of this JDBC driver? Again, this is
     * static.
     *
     * @return the JDBC driver name.
     * @exception SQLException why?
     */
    public String getDriverVersion() throws SQLException
    {
        return Driver.getVersion();
    }

    /*
     * What is this JDBC driver's major version number?
     *
     * @return the JDBC driver major version
     */
    public int getDriverMajorVersion()
    {
        return Driver.MAJORVERSION;
    }

    /*
     * What is this JDBC driver's minor version number?
     *
     * @return the JDBC driver minor version
     */
    public int getDriverMinorVersion()
    {
        return Driver.MINORVERSION;
    }

    /*
     * Does the database store tables in a local file? No - it
     * stores them in a file on the server.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean usesLocalFiles() throws SQLException
    {
        return false;
    }

    /*
     * Does the database use a file for each table?  Well, not really,
     * since it doesnt use local files.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean usesLocalFilePerTable() throws SQLException
    {
        return false;
    }

    /*
     * Does the database treat mixed case unquoted SQL identifiers
     * as case sensitive and as a result store them in mixed case?
     * A JDBC-Compliant driver will always return false.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsMixedCaseIdentifiers() throws SQLException
    {
        return false;
    }

    /*
     * Does the database treat mixed case unquoted SQL identifiers as
     * case insensitive and store them in upper case?
     *
     * @return true if so
     */
    public boolean storesUpperCaseIdentifiers() throws SQLException
    {
        return false;
    }

    /*
     * Does the database treat mixed case unquoted SQL identifiers as
     * case insensitive and store them in lower case?
     *
     * @return true if so
     */
    public boolean storesLowerCaseIdentifiers() throws SQLException
    {
        return true;
    }

    /*
     * Does the database treat mixed case unquoted SQL identifiers as
     * case insensitive and store them in mixed case?
     *
     * @return true if so
     */
    public boolean storesMixedCaseIdentifiers() throws SQLException
    {
        return false;
    }

    /*
     * Does the database treat mixed case quoted SQL identifiers as
     * case sensitive and as a result store them in mixed case?  A
     * JDBC compliant driver will always return true.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException
    {
        return true;
    }

    /*
     * Does the database treat mixed case quoted SQL identifiers as
     * case insensitive and store them in upper case?
     *
     * @return true if so
     */
    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException
    {
        return false;
    }

    /*
     * Does the database treat mixed case quoted SQL identifiers as case
     * insensitive and store them in lower case?
     *
     * @return true if so
     */
    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException
    {
        return false;
    }

    /*
     * Does the database treat mixed case quoted SQL identifiers as case
     * insensitive and store them in mixed case?
     *
     * @return true if so
     */
    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException
    {
        return false;
    }

    /*
     * What is the string used to quote SQL identifiers?  This returns
     * a space if identifier quoting isn't supported.  A JDBC Compliant
     * driver will always use a double quote character.
     *
     * @return the quoting string
     * @exception SQLException if a database access error occurs
     */
    public String getIdentifierQuoteString() throws SQLException
    {
        return "\"";
    }

    /*
     * Get a comma separated list of all a database's SQL keywords that
     * are NOT also SQL92 keywords.
     *
     * <p>Within PostgreSQL, the keywords are found in
     * src/backend/parser/keywords.c
     *
     * <p>For SQL Keywords, I took the list provided at
     * <a href="http://web.dementia.org/~shadow/sql/sql3bnf.sep93.txt">
     * http://web.dementia.org/~shadow/sql/sql3bnf.sep93.txt</a>
     * which is for SQL3, not SQL-92, but it is close enough for
     * this purpose.
     *
     * @return a comma separated list of keywords we use
     * @exception SQLException if a database access error occurs
     */
    public String getSQLKeywords() throws SQLException
    {
        return keywords;
    }

    /**
     * get supported escaped numeric functions
     * @return a comma separated list of function names
     */
    public String getNumericFunctions() throws SQLException
    {
        return EscapedFunctions.ABS+','+EscapedFunctions.ACOS+
        ','+EscapedFunctions.ASIN+','+EscapedFunctions.ATAN+
        ','+EscapedFunctions.ATAN2+','+EscapedFunctions.CEILING+
        ','+EscapedFunctions.COS+','+EscapedFunctions.COT+
        ','+EscapedFunctions.DEGREES+','+EscapedFunctions.EXP+
        ','+EscapedFunctions.FLOOR+','+EscapedFunctions.LOG+
        ','+EscapedFunctions.LOG10+','+EscapedFunctions.MOD+
        ','+EscapedFunctions.PI+','+EscapedFunctions.POWER+
        ','+EscapedFunctions.RADIANS+
        ','+EscapedFunctions.ROUND+','+EscapedFunctions.SIGN+
        ','+EscapedFunctions.SIN+','+EscapedFunctions.SQRT+
        ','+EscapedFunctions.TAN+','+EscapedFunctions.TRUNCATE;
        
    }

    public String getStringFunctions() throws SQLException
    {
        String funcs = EscapedFunctions.ASCII+','+EscapedFunctions.CHAR+
        ','+EscapedFunctions.CONCAT+
        ','+EscapedFunctions.LCASE+','+EscapedFunctions.LEFT+
        ','+EscapedFunctions.LENGTH+
        ','+EscapedFunctions.LTRIM+','+EscapedFunctions.REPEAT+
        ','+EscapedFunctions.RTRIM+
        ','+EscapedFunctions.SPACE+','+EscapedFunctions.SUBSTRING+
        ','+EscapedFunctions.UCASE;

        // Currently these don't work correctly with parameterized
        // arguments, so leave them out.  They reorder the arguments
        // when rewriting the query, but no translation layer is provided,
        // so a setObject(N, obj) will not go to the correct parameter.
        //','+EscapedFunctions.INSERT+','+EscapedFunctions.LOCATE+
        //','+EscapedFunctions.RIGHT+
 
        if (connection.haveMinimumServerVersion("7.3")) {
            funcs += ','+EscapedFunctions.REPLACE;
        }

        return funcs;
    }

    public String getSystemFunctions() throws SQLException
    {
        if (connection.haveMinimumServerVersion("7.3")){
            return EscapedFunctions.DATABASE+','+EscapedFunctions.IFNULL+
                ','+EscapedFunctions.USER;
        } else {
            return EscapedFunctions.IFNULL+
            ','+EscapedFunctions.USER;
        }
    }

    public String getTimeDateFunctions() throws SQLException
    {
        String timeDateFuncs = EscapedFunctions.CURDATE+','+EscapedFunctions.CURTIME+
        ','+EscapedFunctions.DAYNAME+','+EscapedFunctions.DAYOFMONTH+
        ','+EscapedFunctions.DAYOFWEEK+','+EscapedFunctions.DAYOFYEAR+
        ','+EscapedFunctions.HOUR+','+EscapedFunctions.MINUTE+
        ','+EscapedFunctions.MONTH+
        ','+EscapedFunctions.MONTHNAME+','+EscapedFunctions.NOW+
        ','+EscapedFunctions.QUARTER+','+EscapedFunctions.SECOND+
        ','+EscapedFunctions.WEEK+','+EscapedFunctions.YEAR;

        if (connection.haveMinimumServerVersion("8.0")) {
            timeDateFuncs += ','+EscapedFunctions.TIMESTAMPADD;
        }

        //+','+EscapedFunctions.TIMESTAMPDIFF;

        return timeDateFuncs;
    }

    /*
     * This is the string that can be used to escape '_' and '%' in
     * a search string pattern style catalog search parameters
     *
     * @return the string used to escape wildcard characters
     * @exception SQLException if a database access error occurs
     */
    public String getSearchStringEscape() throws SQLException
    {
        // This method originally returned "\\\\" assuming that it
        // would be fed directly into pg's input parser so it would
        // need two backslashes.  This isn't how it's supposed to be
        // used though.  If passed as a PreparedStatement parameter
        // or fed to a DatabaseMetaData method then double backslashes
        // are incorrect.  If you're feeding something directly into
        // a query you are responsible for correctly escaping it.
        // With 8.2+ this escaping is a little trickier because you
        // must know the setting of standard_conforming_strings, but
        // that's not our problem.

        return "\\";
    }

    /*
     * Get all the "extra" characters that can be used in unquoted
     * identifier names (those beyond a-zA-Z0-9 and _)
     *
     * <p>Postgresql allows any high-bit character to be used
     * in an unquoted identifer, so we can't possibly list them all.
     *
     * From the file src/backend/parser/scan.l, an identifier is
     * ident_start [A-Za-z\200-\377_]
     * ident_cont  [A-Za-z\200-\377_0-9\$]
     * identifier  {ident_start}{ident_cont}* 
     *
     * @return a string containing the extra characters
     * @exception SQLException if a database access error occurs
     */
    public String getExtraNameCharacters() throws SQLException
    {
        return "";
    }

    /*
     * Is "ALTER TABLE" with an add column supported?
     * Yes for PostgreSQL 6.1
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsAlterTableWithAddColumn() throws SQLException
    {
        return true;
    }

    /*
     * Is "ALTER TABLE" with a drop column supported?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsAlterTableWithDropColumn() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.3");
    }

    /*
     * Is column aliasing supported?
     *
     * <p>If so, the SQL AS clause can be used to provide names for
     * computed columns or to provide alias names for columns as
     * required.  A JDBC Compliant driver always returns true.
     *
     * <p>e.g.
     *
     * <br><pre>
     * select count(C) as C_COUNT from T group by C;
     *
     * </pre><br>
     * should return a column named as C_COUNT instead of count(C)
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsColumnAliasing() throws SQLException
    {
        return true;
    }

    /*
     * Are concatenations between NULL and non-NULL values NULL?  A
     * JDBC Compliant driver always returns true
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean nullPlusNonNullIsNull() throws SQLException
    {
        return true;
    }

    public boolean supportsConvert() throws SQLException
    {
        return false;
    }

    public boolean supportsConvert(int fromType, int toType) throws SQLException
    {
        return false;
    }

    /*
     * Are table correlation names supported? A JDBC Compliant
     * driver always returns true.
     *
     * @return true if so; false otherwise
     * @exception SQLException - if a database access error occurs
     */
    public boolean supportsTableCorrelationNames() throws SQLException
    {
        return true;
    }

    /*
     * If table correlation names are supported, are they restricted to
     * be different from the names of the tables?
     *
     * @return true if so; false otherwise
     * @exception SQLException - if a database access error occurs
     */
    public boolean supportsDifferentTableCorrelationNames() throws SQLException
    {
        return false;
    }

    /*
     * Are expressions in "ORDER BY" lists supported?
     *
     * <br>e.g. select * from t order by a + b;
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsExpressionsInOrderBy() throws SQLException
    {
        return true;
    }

    /*
     * Can an "ORDER BY" clause use columns not in the SELECT?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsOrderByUnrelated() throws SQLException
    {
        return connection.haveMinimumServerVersion("6.4");
    }

    /*
     * Is some form of "GROUP BY" clause supported?
     * I checked it, and yes it is.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsGroupBy() throws SQLException
    {
        return true;
    }

    /*
     * Can a "GROUP BY" clause use columns not in the SELECT?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsGroupByUnrelated() throws SQLException
    {
        return connection.haveMinimumServerVersion("6.4");
    }

    /*
     * Can a "GROUP BY" clause add columns not in the SELECT provided
     * it specifies all the columns in the SELECT? Does anyone actually
     * understand what they mean here?
     *
     * (I think this is a subset of the previous function. -- petere)
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsGroupByBeyondSelect() throws SQLException
    {
        return connection.haveMinimumServerVersion("6.4");
    }

    /*
     * Is the escape character in "LIKE" clauses supported?  A
     * JDBC compliant driver always returns true.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsLikeEscapeClause() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.1");
    }

    /*
     * Are multiple ResultSets from a single execute supported?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsMultipleResultSets() throws SQLException
    {
        return true;
    }

    /*
     * Can we have multiple transactions open at once (on different
     * connections?)
     * I guess we can have, since Im relying on it.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsMultipleTransactions() throws SQLException
    {
        return true;
    }

    /*
     * Can columns be defined as non-nullable. A JDBC Compliant driver
     * always returns true.
     *
     * <p>This changed from false to true in v6.2 of the driver, as this
     * support was added to the backend.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsNonNullableColumns() throws SQLException
    {
        return true;
    }

    /*
     * Does this driver support the minimum ODBC SQL grammar.  This
     * grammar is defined at:
     *
     * <p><a href="http://www.microsoft.com/msdn/sdk/platforms/doc/odbc/src/intropr.htm">http://www.microsoft.com/msdn/sdk/platforms/doc/odbc/src/intropr.htm</a>
     *
     * <p>In Appendix C.  From this description, we seem to support the
     * ODBC minimal (Level 0) grammar.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsMinimumSQLGrammar() throws SQLException
    {
        return true;
    }

    /*
     * Does this driver support the Core ODBC SQL grammar. We need
     * SQL-92 conformance for this.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsCoreSQLGrammar() throws SQLException
    {
        return false;
    }

    /*
     * Does this driver support the Extended (Level 2) ODBC SQL
     * grammar.  We don't conform to the Core (Level 1), so we can't
     * conform to the Extended SQL Grammar.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsExtendedSQLGrammar() throws SQLException
    {
        return false;
    }

    /*
     * Does this driver support the ANSI-92 entry level SQL grammar?
     * All JDBC Compliant drivers must return true. We currently
     * report false until 'schema' support is added.  Then this
     * should be changed to return true, since we will be mostly
     * compliant (probably more compliant than many other databases)
     * And since this is a requirement for all JDBC drivers we
     * need to get to the point where we can return true.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsANSI92EntryLevelSQL() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.3");
    }

    /*
     * Does this driver support the ANSI-92 intermediate level SQL
     * grammar?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsANSI92IntermediateSQL() throws SQLException
    {
        return false;
    }

    /*
     * Does this driver support the ANSI-92 full SQL grammar?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsANSI92FullSQL() throws SQLException
    {
        return false;
    }

    /*
     * Is the SQL Integrity Enhancement Facility supported?
     * Our best guess is that this means support for constraints
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsIntegrityEnhancementFacility() throws SQLException
    {
        return true;
    }

    /*
     * Is some form of outer join supported?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsOuterJoins() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.1");
    }

    /*
     * Are full nexted outer joins supported?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsFullOuterJoins() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.1");
    }

    /*
     * Is there limited support for outer joins?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsLimitedOuterJoins() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.1");
    }

    /*
     * What is the database vendor's preferred term for "schema"?
     * PostgreSQL doesn't have schemas, but when it does, we'll use the
     * term "schema".
     *
     * @return the vendor term
     * @exception SQLException if a database access error occurs
     */
    public String getSchemaTerm() throws SQLException
    {
        return "schema";
    }

    /*
     * What is the database vendor's preferred term for "procedure"?
     * Traditionally, "function" has been used.
     *
     * @return the vendor term
     * @exception SQLException if a database access error occurs
     */
    public String getProcedureTerm() throws SQLException
    {
        return "function";
    }

    /*
     * What is the database vendor's preferred term for "catalog"?
     *
     * @return the vendor term
     * @exception SQLException if a database access error occurs
     */
    public String getCatalogTerm() throws SQLException
    {
        return "database";
    }

    /*
     * Does a catalog appear at the start of a qualified table name?
     * (Otherwise it appears at the end).
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isCatalogAtStart() throws SQLException
    {
        return true;
    }

    /*
     * What is the Catalog separator.
     *
     * @return the catalog separator string
     * @exception SQLException if a database access error occurs
     */
    public String getCatalogSeparator() throws SQLException
    {
        return ".";
    }

    /*
     * Can a schema name be used in a data manipulation statement?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsSchemasInDataManipulation() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.3");
    }

    /*
     * Can a schema name be used in a procedure call statement?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsSchemasInProcedureCalls() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.3");
    }

    /*
     * Can a schema be used in a table definition statement?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsSchemasInTableDefinitions() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.3");
    }

    /*
     * Can a schema name be used in an index definition statement?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsSchemasInIndexDefinitions() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.3");
    }

    /*
     * Can a schema name be used in a privilege definition statement?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.3");
    }

    /*
     * Can a catalog name be used in a data manipulation statement?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsCatalogsInDataManipulation() throws SQLException
    {
        return false;
    }

    /*
     * Can a catalog name be used in a procedure call statement?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsCatalogsInProcedureCalls() throws SQLException
    {
        return false;
    }

    /*
     * Can a catalog name be used in a table definition statement?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsCatalogsInTableDefinitions() throws SQLException
    {
        return false;
    }

    /*
     * Can a catalog name be used in an index definition?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException
    {
        return false;
    }

    /*
     * Can a catalog name be used in a privilege definition statement?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException
    {
        return false;
    }

    /*
     * We support cursors for gets only it seems.  I dont see a method
     * to get a positioned delete.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsPositionedDelete() throws SQLException
    {
        return false;   // For now...
    }

    /*
     * Is positioned UPDATE supported?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsPositionedUpdate() throws SQLException
    {
        return false;   // For now...
    }

    /*
     * Is SELECT for UPDATE supported?
     *
     * @return true if so; false otherwise
     * @exception SQLException - if a database access error occurs
     */
    public boolean supportsSelectForUpdate() throws SQLException
    {
        return connection.haveMinimumServerVersion("6.5");
    }

    /*
     * Are stored procedure calls using the stored procedure escape
     * syntax supported?
     *
     * @return true if so; false otherwise
     * @exception SQLException - if a database access error occurs
     */
    public boolean supportsStoredProcedures() throws SQLException
    {
        return true;
    }

    /*
     * Are subqueries in comparison expressions supported? A JDBC
     * Compliant driver always returns true.
     *
     * @return true if so; false otherwise
     * @exception SQLException - if a database access error occurs
     */
    public boolean supportsSubqueriesInComparisons() throws SQLException
    {
        return true;
    }

    /*
     * Are subqueries in 'exists' expressions supported? A JDBC
     * Compliant driver always returns true.
     *
     * @return true if so; false otherwise
     * @exception SQLException - if a database access error occurs
     */
    public boolean supportsSubqueriesInExists() throws SQLException
    {
        return true;
    }

    /*
     * Are subqueries in 'in' statements supported? A JDBC
     * Compliant driver always returns true.
     *
     * @return true if so; false otherwise
     * @exception SQLException - if a database access error occurs
     */
    public boolean supportsSubqueriesInIns() throws SQLException
    {
        return true;
    }

    /*
     * Are subqueries in quantified expressions supported? A JDBC
     * Compliant driver always returns true.
     *
     * (No idea what this is, but we support a good deal of
     * subquerying.)
     *
     * @return true if so; false otherwise
     * @exception SQLException - if a database access error occurs
     */
    public boolean supportsSubqueriesInQuantifieds() throws SQLException
    {
        return true;
    }

    /*
     * Are correlated subqueries supported? A JDBC Compliant driver
     * always returns true.
     *
     * (a.k.a. subselect in from?)
     *
     * @return true if so; false otherwise
     * @exception SQLException - if a database access error occurs
     */
    public boolean supportsCorrelatedSubqueries() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.1");
    }

    /*
     * Is SQL UNION supported?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsUnion() throws SQLException
    {
        return true; // since 6.3
    }

    /*
     * Is SQL UNION ALL supported?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsUnionAll() throws SQLException
    {
        return connection.haveMinimumServerVersion("7.1");
    }

    /*
     * In PostgreSQL, Cursors are only open within transactions.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsOpenCursorsAcrossCommit() throws SQLException
    {
        return false;
    }

    /*
     * Do we support open cursors across multiple transactions?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsOpenCursorsAcrossRollback() throws SQLException
    {
        return false;
    }

    /*
     * Can statements remain open across commits?  They may, but
     * this driver cannot guarentee that.  In further reflection.
     * we are talking a Statement object here, so the answer is
     * yes, since the Statement is only a vehicle to ExecSQL()
     *
     * @return true if they always remain open; false otherwise
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsOpenStatementsAcrossCommit() throws SQLException
    {
        return true;
    }

    /*
     * Can statements remain open across rollbacks?  They may, but
     * this driver cannot guarentee that.  In further contemplation,
     * we are talking a Statement object here, so the answer is yes,
     * since the Statement is only a vehicle to ExecSQL() in Connection
     *
     * @return true if they always remain open; false otherwise
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsOpenStatementsAcrossRollback() throws SQLException
    {
        return true;
    }

    /*
     * How many hex characters can you have in an inline binary literal
     *
     * @return the max literal length
     * @exception SQLException if a database access error occurs
     */
    public int getMaxBinaryLiteralLength() throws SQLException
    {
        return 0; // no limit
    }

    /*
     * What is the maximum length for a character literal
     * I suppose it is 8190 (8192 - 2 for the quotes)
     *
     * @return the max literal length
     * @exception SQLException if a database access error occurs
     */
    public int getMaxCharLiteralLength() throws SQLException
    {
        return 0; // no limit
    }

    /*
     * Whats the limit on column name length.
     *
     * @return the maximum column name length
     * @exception SQLException if a database access error occurs
     */
    public int getMaxColumnNameLength() throws SQLException
    {
        return getMaxNameLength();
    }

    /*
     * What is the maximum number of columns in a "GROUP BY" clause?
     *
     * @return the max number of columns
     * @exception SQLException if a database access error occurs
     */
    public int getMaxColumnsInGroupBy() throws SQLException
    {
        return 0; // no limit
    }

    /*
     * What's the maximum number of columns allowed in an index?
     *
     * @return max number of columns
     * @exception SQLException if a database access error occurs
     */
    public int getMaxColumnsInIndex() throws SQLException
    {
        return getMaxIndexKeys();
    }

    /*
     * What's the maximum number of columns in an "ORDER BY clause?
     *
     * @return the max columns
     * @exception SQLException if a database access error occurs
     */
    public int getMaxColumnsInOrderBy() throws SQLException
    {
        return 0; // no limit
    }

    /*
     * What is the maximum number of columns in a "SELECT" list?
     *
     * @return the max columns
     * @exception SQLException if a database access error occurs
     */
    public int getMaxColumnsInSelect() throws SQLException
    {
        return 0; // no limit
    }

    /*
     * What is the maximum number of columns in a table? From the
     * CREATE TABLE reference page...
     *
     * <p>"The new class is created as a heap with no initial data.  A
     * class can have no more than 1600 attributes (realistically,
     * this is limited by the fact that tuple sizes must be less than
     * 8192 bytes)..."
     *
     * @return the max columns
     * @exception SQLException if a database access error occurs
     */
    public int getMaxColumnsInTable() throws SQLException
    {
        return 1600;
    }

    /*
     * How many active connection can we have at a time to this
     * database?  Well, since it depends on postmaster, which just
     * does a listen() followed by an accept() and fork(), its
     * basically very high.  Unless the system runs out of processes,
     * it can be 65535 (the number of aux. ports on a TCP/IP system).
     * I will return 8192 since that is what even the largest system
     * can realistically handle,
     *
     * @return the maximum number of connections
     * @exception SQLException if a database access error occurs
     */
    public int getMaxConnections() throws SQLException
    {
        return 8192;
    }

    /*
     * What is the maximum cursor name length
     *
     * @return max cursor name length in bytes
     * @exception SQLException if a database access error occurs
     */
    public int getMaxCursorNameLength() throws SQLException
    {
        return getMaxNameLength();
    }

    /*
     * Retrieves the maximum number of bytes for an index, including all
     * of the parts of the index.
     *
     * @return max index length in bytes, which includes the composite
     * of all the constituent parts of the index; a result of zero means
     * that there is no limit or the limit is not known
     * @exception SQLException if a database access error occurs
     */
    public int getMaxIndexLength() throws SQLException
    {
        return 0; // no limit (larger than an int anyway)
    }

    public int getMaxSchemaNameLength() throws SQLException
    {
        return getMaxNameLength();
    }

    /*
     * What is the maximum length of a procedure name
     *
     * @return the max name length in bytes
     * @exception SQLException if a database access error occurs
     */
    public int getMaxProcedureNameLength() throws SQLException
    {
        return getMaxNameLength();
    }

    public int getMaxCatalogNameLength() throws SQLException
    {
        return getMaxNameLength();
    }

    /*
     * What is the maximum length of a single row?
     *
     * @return max row size in bytes
     * @exception SQLException if a database access error occurs
     */
    public int getMaxRowSize() throws SQLException
    {
        if (connection.haveMinimumServerVersion("7.1"))
            return 1073741824; // 1 GB
        else
            return 8192;  // XXX could be altered
    }

    /*
     * Did getMaxRowSize() include LONGVARCHAR and LONGVARBINARY
     * blobs?  We don't handle blobs yet
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException
    {
        return false;
    }

    /*
     * What is the maximum length of a SQL statement?
     *
     * @return max length in bytes
     * @exception SQLException if a database access error occurs
     */
    public int getMaxStatementLength() throws SQLException
    {
        if (connection.haveMinimumServerVersion("7.0"))
            return 0;  // actually whatever fits in size_t
        else
            return 16384;
    }

    /*
     * How many active statements can we have open at one time to
     * this database? We're only limited by Java heap space really.
     *
     * @return the maximum
     * @exception SQLException if a database access error occurs
     */
    public int getMaxStatements() throws SQLException
    {
        return 0;
    }

    /*
     * What is the maximum length of a table name
     *
     * @return max name length in bytes
     * @exception SQLException if a database access error occurs
     */
    public int getMaxTableNameLength() throws SQLException
    {
        return getMaxNameLength();
    }

    /*
     * What is the maximum number of tables that can be specified
     * in a SELECT?
     *
     * @return the maximum
     * @exception SQLException if a database access error occurs
     */
    public int getMaxTablesInSelect() throws SQLException
    {
        return 0; // no limit
    }

    /*
     * What is the maximum length of a user name
     *
     * @return the max name length in bytes
     * @exception SQLException if a database access error occurs
     */
    public int getMaxUserNameLength() throws SQLException
    {
        return getMaxNameLength();
    }


    /*
     * What is the database's default transaction isolation level?
     *
     * @return the default isolation level
     * @exception SQLException if a database access error occurs
     * @see Connection
     */
    public int getDefaultTransactionIsolation() throws SQLException
    {
        return Connection.TRANSACTION_READ_COMMITTED;
    }

    /*
     * Are transactions supported? If not, commit and rollback are noops
     * and the isolation level is TRANSACTION_NONE.  We do support
     * transactions.
     *
     * @return true if transactions are supported
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsTransactions() throws SQLException
    {
        return true;
    }

    /*
     * Does the database support the given transaction isolation level?
     * We only support TRANSACTION_SERIALIZABLE and TRANSACTION_READ_COMMITTED
     * before 8.0; from 8.0 READ_UNCOMMITTED and REPEATABLE_READ are accepted aliases
     * for READ_COMMITTED.
     *
     * @param level the values are defined in java.sql.Connection
     * @return true if so
     * @exception SQLException if a database access error occurs
     * @see Connection
     */
    public boolean supportsTransactionIsolationLevel(int level) throws SQLException
    {
        if (level == Connection.TRANSACTION_SERIALIZABLE ||
            level == Connection.TRANSACTION_READ_COMMITTED)
            return true;
        else if (connection.haveMinimumServerVersion("8.0") && (level == Connection.TRANSACTION_READ_UNCOMMITTED || level == Connection.TRANSACTION_REPEATABLE_READ))
            return true;
        else
            return false;
    }

    /*
     * Are both data definition and data manipulation transactions
     * supported?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException
    {
        return true;
    }

    /*
     * Are only data manipulation statements withing a transaction
     * supported?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException
    {
        return false;
    }

    /*
     * Does a data definition statement within a transaction force
     * the transaction to commit?  I think this means something like:
     *
     * <p><pre>
     * CREATE TABLE T (A INT);
     * INSERT INTO T (A) VALUES (2);
     * BEGIN;
     * UPDATE T SET A = A + 1;
     * CREATE TABLE X (A INT);
     * SELECT A FROM T INTO X;
     * COMMIT;
     * </pre><p>
     *
     * does the CREATE TABLE call cause a commit?  The answer is no.
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException
    {
        return false;
    }

    /*
     * Is a data definition statement within a transaction ignored?
     *
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException
    {
        return false;
    }

    /**
     * Turn the provided value into a valid string literal for
     * direct inclusion into a query.  This includes the single quotes
     * needed around it.
     */
    protected String escapeQuotes(String s) throws SQLException {
        StringBuffer sb = new StringBuffer();
        if (!connection.getStandardConformingStrings() && connection.haveMinimumServerVersion("8.1")) {
            sb.append("E");
        }
        sb.append("'");
        sb.append(connection.escapeString(s));
        sb.append("'");
        return sb.toString();
    }

    /*
     * Get a description of stored procedures available in a catalog
     *
     * <p>Only procedure descriptions matching the schema and procedure
     * name criteria are returned. They are ordered by PROCEDURE_SCHEM
     * and PROCEDURE_NAME
     *
     * <p>Each procedure description has the following columns:
     * <ol>
     * <li><b>PROCEDURE_CAT</b> String => procedure catalog (may be null)
     * <li><b>PROCEDURE_SCHEM</b> String => procedure schema (may be null)
     * <li><b>PROCEDURE_NAME</b> String => procedure name
     * <li><b>Field 4</b> reserved (make it null)
     * <li><b>Field 5</b> reserved (make it null)
     * <li><b>Field 6</b> reserved (make it null)
     * <li><b>REMARKS</b> String => explanatory comment on the procedure
     * <li><b>PROCEDURE_TYPE</b> short => kind of procedure
     * <ul>
     *   <li> procedureResultUnknown - May return a result
     * <li> procedureNoResult - Does not return a result
     * <li> procedureReturnsResult - Returns a result
     *   </ul>
     * </ol>
     *
     * @param catalog - a catalog name; "" retrieves those without a
     * catalog; null means drop catalog name from criteria
     * @param schemaParrern - a schema name pattern; "" retrieves those
     * without a schema - we ignore this parameter
     * @param procedureNamePattern - a procedure name pattern
     * @return ResultSet - each row is a procedure description
     * @exception SQLException if a database access error occurs
     */
    public java.sql.ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException
    {
        return getProcedures(2, catalog, schemaPattern, procedureNamePattern);
    }

    protected java.sql.ResultSet getProcedures(int jdbcVersion, String catalog, String schemaPattern, String procedureNamePattern) throws SQLException
    {
        String sql;
        if (connection.haveMinimumServerVersion("7.3"))
        {
            sql = "SELECT NULL AS PROCEDURE_CAT, n.nspname AS PROCEDURE_SCHEM, p.proname AS PROCEDURE_NAME, NULL, NULL, NULL, d.description AS REMARKS, " + java.sql.DatabaseMetaData.procedureReturnsResult + " AS PROCEDURE_TYPE ";
            if (jdbcVersion >= 4) {
                sql += ", p.proname || '_' || p.oid AS SPECIFIC_NAME ";
            }
            sql += " FROM pg_catalog.pg_namespace n, pg_catalog.pg_proc p " +
                  " LEFT JOIN pg_catalog.pg_description d ON (p.oid=d.objoid) " +
                  " LEFT JOIN pg_catalog.pg_class c ON (d.classoid=c.oid AND c.relname='pg_proc') " +
                  " LEFT JOIN pg_catalog.pg_namespace pn ON (c.relnamespace=pn.oid AND pn.nspname='pg_catalog') " +
                  " WHERE p.pronamespace=n.oid ";
            if (schemaPattern != null && !"".equals(schemaPattern))
            {
                sql += " AND n.nspname LIKE " + escapeQuotes(schemaPattern);
            }
            if (procedureNamePattern != null)
            {
                sql += " AND p.proname LIKE " + escapeQuotes(procedureNamePattern);
            }
            sql += " ORDER BY PROCEDURE_SCHEM, PROCEDURE_NAME, p.oid::text ";
        }
        else if (connection.haveMinimumServerVersion("7.1"))
        {
            sql = "SELECT NULL AS PROCEDURE_CAT, NULL AS PROCEDURE_SCHEM, p.proname AS PROCEDURE_NAME, NULL, NULL, NULL, d.description AS REMARKS, " + java.sql.DatabaseMetaData.procedureReturnsResult + " AS PROCEDURE_TYPE ";
            if (jdbcVersion >= 4) {
                sql += ", p.proname || '_' || p.oid AS SPECIFIC_NAME ";
            }
            sql += " FROM pg_proc p " +
                  " LEFT JOIN pg_description d ON (p.oid=d.objoid) ";
            if (connection.haveMinimumServerVersion("7.2"))
            {
                sql += " LEFT JOIN pg_class c ON (d.classoid=c.oid AND c.relname='pg_proc') ";
            }
            if (procedureNamePattern != null)
            {
                sql += " WHERE p.proname LIKE " + escapeQuotes(procedureNamePattern);
            }
            sql += " ORDER BY PROCEDURE_NAME, p.oid::text ";
        }
        else
        {
            sql = "SELECT NULL AS PROCEDURE_CAT, NULL AS PROCEDURE_SCHEM, p.proname AS PROCEDURE_NAME, NULL, NULL, NULL, NULL AS REMARKS, " + java.sql.DatabaseMetaData.procedureReturnsResult + " AS PROCEDURE_TYPE ";
            if (jdbcVersion >= 4) {
                sql += ", p.proname || '_' || p.oid AS SPECIFIC_NAME ";
            }
            sql += " FROM pg_proc p ";
            if (procedureNamePattern != null)
            {
                sql += " WHERE p.proname LIKE " + escapeQuotes(procedureNamePattern);
            }
            sql += " ORDER BY PROCEDURE_NAME, p.oid::text ";
        }
        return createMetaDataStatement().executeQuery(sql);
    }

    /*
     * Get a description of a catalog's stored procedure parameters
     * and result columns.
     *
     * <p>Only descriptions matching the schema, procedure and parameter
     * name criteria are returned. They are ordered by PROCEDURE_SCHEM
     * and PROCEDURE_NAME. Within this, the return value, if any, is
     * first. Next are the parameter descriptions in call order. The
     * column descriptions follow in column number order.
     *
     * <p>Each row in the ResultSet is a parameter description or column
     * description with the following fields:
     * <ol>
     * <li><b>PROCEDURE_CAT</b> String => procedure catalog (may be null)
     * <li><b>PROCEDURE_SCHE</b>M String => procedure schema (may be null)
     * <li><b>PROCEDURE_NAME</b> String => procedure name
     * <li><b>COLUMN_NAME</b> String => column/parameter name
     * <li><b>COLUMN_TYPE</b> Short => kind of column/parameter:
     * <ul><li>procedureColumnUnknown - nobody knows
     * <li>procedureColumnIn - IN parameter
     * <li>procedureColumnInOut - INOUT parameter
     * <li>procedureColumnOut - OUT parameter
     * <li>procedureColumnReturn - procedure return value
     * <li>procedureColumnResult - result column in ResultSet
     * </ul>
     * <li><b>DATA_TYPE</b> short => SQL type from java.sql.Types
     * <li><b>TYPE_NAME</b> String => Data source specific type name
     * <li><b>PRECISION</b> int => precision
     * <li><b>LENGTH</b> int => length in bytes of data
     * <li><b>SCALE</b> short => scale
     * <li><b>RADIX</b> short => radix
     * <li><b>NULLABLE</b> short => can it contain NULL?
     * <ul><li>procedureNoNulls - does not allow NULL values
     * <li>procedureNullable - allows NULL values
     * <li>procedureNullableUnknown - nullability unknown
     * <li><b>REMARKS</b> String => comment describing parameter/column
     * </ol>
     * @param catalog This is ignored in org.postgresql, advise this is set to null
     * @param schemaPattern
     * @param procedureNamePattern a procedure name pattern
     * @param columnNamePattern a column name pattern, this is currently ignored because postgresql does not name procedure parameters.
     * @return each row is a stored procedure parameter or column description
     * @exception SQLException if a database-access error occurs
     * @see #getSearchStringEscape
     */ 
    // Implementation note: This is required for Borland's JBuilder to work
    public java.sql.ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException
    {
        return getProcedureColumns(2, catalog, schemaPattern, procedureNamePattern, columnNamePattern);
    }

    protected java.sql.ResultSet getProcedureColumns(int jdbcVersion, String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException
    {
        int columns = 13;
        if (jdbcVersion >= 4) {
            columns += 7;
        }
        Field f[] = new Field[columns];
        List v = new ArrayList();  // The new ResultSet tuple stuff

        f[0] = new Field("PROCEDURE_CAT", Oid.VARCHAR);
        f[1] = new Field("PROCEDURE_SCHEM", Oid.VARCHAR);
        f[2] = new Field("PROCEDURE_NAME", Oid.VARCHAR);
        f[3] = new Field("COLUMN_NAME", Oid.VARCHAR);
        f[4] = new Field("COLUMN_TYPE", Oid.INT2);
        f[5] = new Field("DATA_TYPE", Oid.INT2);
        f[6] = new Field("TYPE_NAME", Oid.VARCHAR);
        f[7] = new Field("PRECISION", Oid.INT4);
        f[8] = new Field("LENGTH", Oid.INT4);
        f[9] = new Field("SCALE", Oid.INT2);
        f[10] = new Field("RADIX", Oid.INT2);
        f[11] = new Field("NULLABLE", Oid.INT2);
        f[12] = new Field("REMARKS", Oid.VARCHAR);
        if (jdbcVersion >= 4) {
            f[13] = new Field("COLUMN_DEF", Oid.VARCHAR);
            f[14] = new Field("SQL_DATA_TYPE", Oid.INT4);
            f[15] = new Field("SQL_DATETIME_SUB", Oid.INT4);
            f[16] = new Field("CHAR_OCTECT_LENGTH", Oid.INT4);
            f[17] = new Field("ORDINAL_POSITION", Oid.INT4);
            f[18] = new Field("IS_NULLABLE", Oid.VARCHAR);
            f[19] = new Field("SPECIFIC_NAME", Oid.VARCHAR);
        }

        String sql;
        if (connection.haveMinimumServerVersion("7.3"))
        {
            sql = "SELECT n.nspname,p.proname,p.prorettype,p.proargtypes, t.typtype,t.typrelid ";

            if (connection.haveMinimumServerVersion("8.1"))
                sql += ", p.proargnames, p.proargmodes, p.proallargtypes  ";
            else if (connection.haveMinimumServerVersion("8.0"))
                sql += ", p.proargnames, NULL AS proargmodes, NULL AS proallargtypes ";
            else
                sql += ", NULL AS proargnames, NULL AS proargmodes, NULL AS proallargtypes ";
            sql += ", p.oid "
                + " FROM pg_catalog.pg_proc p, pg_catalog.pg_namespace n, pg_catalog.pg_type t "
                + " WHERE p.pronamespace=n.oid AND p.prorettype=t.oid ";
            if (schemaPattern != null && !"".equals(schemaPattern))
            {
                sql += " AND n.nspname LIKE " + escapeQuotes(schemaPattern);
            }
            if (procedureNamePattern != null)
            {
                sql += " AND p.proname LIKE " + escapeQuotes(procedureNamePattern);
            }
            sql += " ORDER BY n.nspname, p.proname, p.oid::text ";
        }
        else
        {
            sql = "SELECT NULL AS nspname,p.proname,p.prorettype,p.proargtypes,t.typtype,t.typrelid, NULL AS proargnames, NULL AS proargmodes, NULL AS proallargtypes, p.oid " +
                  " FROM pg_proc p,pg_type t " +
                  " WHERE p.prorettype=t.oid ";
            if (procedureNamePattern != null)
            {
                sql += " AND p.proname LIKE " + escapeQuotes(procedureNamePattern);
            }
            sql += " ORDER BY p.proname, p.oid::text ";
        }

        byte isnullableUnknown[] = new byte[0];

        ResultSet rs = connection.createStatement().executeQuery(sql);
        while (rs.next())
        {
            byte schema[] = rs.getBytes("nspname");
            byte procedureName[] = rs.getBytes("proname");
            byte specificName[] = connection.encodeString(rs.getString("proname") + "_" + rs.getString("oid"));
            int returnType = (int)rs.getLong("prorettype");
            String returnTypeType = rs.getString("typtype");
            int returnTypeRelid = (int)rs.getLong("typrelid");

            String strArgTypes = rs.getString("proargtypes");
            StringTokenizer st = new StringTokenizer(strArgTypes);
            List argTypes = new ArrayList();
            while (st.hasMoreTokens())
            {
                argTypes.add(new Long(st.nextToken()));
            }

            String argNames[] = null;
            Array argNamesArray = rs.getArray("proargnames");
            if (argNamesArray != null)
                argNames = (String[])argNamesArray.getArray();

            String argModes[] = null;
            Array argModesArray = rs.getArray("proargmodes");
            if (argModesArray != null)
                argModes = (String[])argModesArray.getArray();

            int numArgs = argTypes.size();

            Long allArgTypes[] = null;
            Array allArgTypesArray = rs.getArray("proallargtypes");
            if (allArgTypesArray != null) {
                // Depending on what the user has selected we'll get
                // either long[] or Long[] back, and there's no
                // obvious way for the driver to override this for
                // it's own usage.
                if (connection.haveMinimumCompatibleVersion("8.3")) {
                    allArgTypes = (Long[])allArgTypesArray.getArray();
                } else {
                    long tempAllArgTypes[] = (long[])allArgTypesArray.getArray();
                    allArgTypes = new Long[tempAllArgTypes.length];
                    for (int i=0; i<tempAllArgTypes.length; i++) {
                        allArgTypes[i] = new Long(tempAllArgTypes[i]);
                    }
                }
                numArgs = allArgTypes.length;
            }

            // decide if we are returning a single column result.
            if (returnTypeType.equals("b") || returnTypeType.equals("d") || (returnTypeType.equals("p") && argModesArray == null))
            {
                byte[][] tuple = new byte[columns][];
                tuple[0] = null;
                tuple[1] = schema;
                tuple[2] = procedureName;
                tuple[3] = connection.encodeString("returnValue");
                tuple[4] = connection.encodeString(Integer.toString(java.sql.DatabaseMetaData.procedureColumnReturn));
                tuple[5] = connection.encodeString(Integer.toString(connection.getTypeInfo().getSQLType(returnType)));
                tuple[6] = connection.encodeString(connection.getTypeInfo().getPGType(returnType));
                tuple[7] = null;
                tuple[8] = null;
                tuple[9] = null;
                tuple[10] = null;
                tuple[11] = connection.encodeString(Integer.toString(java.sql.DatabaseMetaData.procedureNullableUnknown));
                tuple[12] = null;
                if (jdbcVersion >= 4) {
                    tuple[17] = connection.encodeString(Integer.toString(0));
                    tuple[18] = isnullableUnknown;
                    tuple[19] = specificName;
                }
                v.add(tuple);
            }

            // Add a row for each argument.
            for (int i = 0; i < numArgs; i++)
            {
                byte[][] tuple = new byte[columns][];
                tuple[0] = null;
                tuple[1] = schema;
                tuple[2] = procedureName;

                if (argNames != null)
                    tuple[3] = connection.encodeString(argNames[i]);
                else
                    tuple[3] = connection.encodeString("$" + (i + 1));

                int columnMode = DatabaseMetaData.procedureColumnIn;
                if (argModes != null && argModes[i].equals("o"))
                    columnMode = DatabaseMetaData.procedureColumnOut;
                else if (argModes != null && argModes[i].equals("b"))
                    columnMode = DatabaseMetaData.procedureColumnInOut;

                tuple[4] = connection.encodeString(Integer.toString(columnMode));

                int argOid;
                if (allArgTypes != null)
                    argOid = allArgTypes[i].intValue();
                else
                    argOid = ((Long)argTypes.get(i)).intValue();

                tuple[5] = connection.encodeString(Integer.toString(connection.getTypeInfo().getSQLType(argOid)));
                tuple[6] = connection.encodeString(connection.getTypeInfo().getPGType(argOid));
                tuple[7] = null;
                tuple[8] = null;
                tuple[9] = null;
                tuple[10] = null;
                tuple[11] = connection.encodeString(Integer.toString(DatabaseMetaData.procedureNullableUnknown));
                tuple[12] = null;
                if (jdbcVersion >= 4) {
                    tuple[17] = connection.encodeString(Integer.toString(i+1));
                    tuple[18] = isnullableUnknown;
                    tuple[19] = specificName;
                }
                v.add(tuple);
            }

            // if we are returning a multi-column result.
            if (returnTypeType.equals("c") || (returnTypeType.equals("p") && argModesArray != null))
            {
                String columnsql = "SELECT a.attname,a.atttypid FROM ";
                if (connection.haveMinimumServerVersion("7.3")) {
                    columnsql += "pg_catalog.";
                }
                columnsql += "pg_attribute a WHERE a.attrelid = " + returnTypeRelid + " AND a.attnum > 0 ORDER BY a.attnum ";
                ResultSet columnrs = connection.createStatement().executeQuery(columnsql);
                while (columnrs.next())
                {
                    int columnTypeOid = (int)columnrs.getLong("atttypid");
                    byte[][] tuple = new byte[columns][];
                    tuple[0] = null;
                    tuple[1] = schema;
                    tuple[2] = procedureName;
                    tuple[3] = columnrs.getBytes("attname");
                    tuple[4] = connection.encodeString(Integer.toString(java.sql.DatabaseMetaData.procedureColumnResult));
                    tuple[5] = connection.encodeString(Integer.toString(connection.getTypeInfo().getSQLType(columnTypeOid)));
                    tuple[6] = connection.encodeString(connection.getTypeInfo().getPGType(columnTypeOid));
                    tuple[7] = null;
                    tuple[8] = null;
                    tuple[9] = null;
                    tuple[10] = null;
                    tuple[11] = connection.encodeString(Integer.toString(java.sql.DatabaseMetaData.procedureNullableUnknown));
                    tuple[12] = null;
                    if (jdbcVersion >= 4) {
                        tuple[17] = connection.encodeString(Integer.toString(0));
                        tuple[18] = isnullableUnknown;
                        tuple[19] = specificName;
                    }
                    v.add(tuple);
                }
                columnrs.close();
            }
        }
        rs.close();

        return (ResultSet)((BaseStatement)createMetaDataStatement()).createDriverResultSet(f, v);
    }

    /*
     * Get a description of tables available in a catalog.
     *
     * <p>Only table descriptions matching the catalog, schema, table
     * name and type criteria are returned. They are ordered by
     * TABLE_TYPE, TABLE_SCHEM and TABLE_NAME.
     *
     * <p>Each table description has the following columns:
     *
     * <ol>
     * <li><b>TABLE_CAT</b> String => table catalog (may be null)
     * <li><b>TABLE_SCHEM</b> String => table schema (may be null)
     * <li><b>TABLE_NAME</b> String => table name
     * <li><b>TABLE_TYPE</b> String => table type. Typical types are "TABLE",
     * "VIEW", "SYSTEM TABLE", "GLOBAL TEMPORARY", "LOCAL
     * TEMPORARY", "ALIAS", "SYNONYM".
     * <li><b>REMARKS</b> String => explanatory comment on the table
     * </ol>
     *
     * <p>The valid values for the types parameter are:
     * "TABLE", "INDEX", "SEQUENCE", "VIEW", "TYPE"
     * "SYSTEM TABLE", "SYSTEM INDEX", "SYSTEM VIEW",
     * "SYSTEM TOAST TABLE", "SYSTEM TOAST INDEX",
     * "TEMPORARY TABLE", "TEMPORARY VIEW", "TEMPORARY INDEX",
     * "TEMPORARY SEQUENCE", "FOREIGN TABLE".
     *
     * @param catalog a catalog name; For org.postgresql, this is ignored, and
     * should be set to null
     * @param schemaPattern a schema name pattern
     * @param tableNamePattern a table name pattern. For all tables this should be "%"
     * @param types a list of table types to include; null returns
     * all types
     * @return each row is a table description
     * @exception SQLException if a database-access error occurs.
     */
    public java.sql.ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String types[]) throws SQLException
    {
        String select;
        String orderby;
        String useSchemas;
        if (connection.haveMinimumServerVersion("7.3"))
        {
            useSchemas = "SCHEMAS";
            select = "SELECT NULL AS TABLE_CAT, n.nspname AS TABLE_SCHEM, c.relname AS TABLE_NAME, " +
                     " CASE n.nspname ~ '^pg_' OR n.nspname = 'information_schema' " +
                     " WHEN true THEN CASE " +
                     " WHEN n.nspname = 'pg_catalog' OR n.nspname = 'information_schema' THEN CASE c.relkind " +
                     "  WHEN 'r' THEN 'SYSTEM TABLE' " +
                     "  WHEN 'v' THEN 'SYSTEM VIEW' " +
                     "  WHEN 'i' THEN 'SYSTEM INDEX' " +
                     "  ELSE NULL " +
                     "  END " +
                     " WHEN n.nspname = 'pg_toast' THEN CASE c.relkind " +
                     "  WHEN 'r' THEN 'SYSTEM TOAST TABLE' " +
                     "  WHEN 'i' THEN 'SYSTEM TOAST INDEX' " +
                     "  ELSE NULL " +
                     "  END " +
                     " ELSE CASE c.relkind " +
                     "  WHEN 'r' THEN 'TEMPORARY TABLE' " +
                     "  WHEN 'i' THEN 'TEMPORARY INDEX' " +
                     "  WHEN 'S' THEN 'TEMPORARY SEQUENCE' " +
                     "  WHEN 'v' THEN 'TEMPORARY VIEW' " +
                     "  ELSE NULL " +
                     "  END " +
                     " END " +
                     " WHEN false THEN CASE c.relkind " +
                     " WHEN 'r' THEN 'TABLE' " +
                     " WHEN 'i' THEN 'INDEX' " +
                     " WHEN 'S' THEN 'SEQUENCE' " +
                     " WHEN 'v' THEN 'VIEW' " +
                     " WHEN 'c' THEN 'TYPE' " +
                     " WHEN 'f' THEN 'FOREIGN TABLE' " +
                     " ELSE NULL " +
                     " END " +
                     " ELSE NULL " +
                     " END " +
                     " AS TABLE_TYPE, d.description AS REMARKS " +
                     " FROM pg_catalog.pg_namespace n, pg_catalog.pg_class c " +
                     " LEFT JOIN pg_catalog.pg_description d ON (c.oid = d.objoid AND d.objsubid = 0) " +
                     " LEFT JOIN pg_catalog.pg_class dc ON (d.classoid=dc.oid AND dc.relname='pg_class') " +
                     " LEFT JOIN pg_catalog.pg_namespace dn ON (dn.oid=dc.relnamespace AND dn.nspname='pg_catalog') " +
                     " WHERE c.relnamespace = n.oid ";
            if (schemaPattern != null && !"".equals(schemaPattern))
            {
                select += " AND n.nspname LIKE " + escapeQuotes(schemaPattern);
            }
            orderby = " ORDER BY TABLE_TYPE,TABLE_SCHEM,TABLE_NAME ";
        }
        else
        {
            useSchemas = "NOSCHEMAS";
            String tableType = "" +
                               " CASE c.relname ~ '^pg_' " +
                               " WHEN true THEN CASE c.relname ~ '^pg_toast_' " +
                               " WHEN true THEN CASE c.relkind " +
                               "  WHEN 'r' THEN 'SYSTEM TOAST TABLE' " +
                               "  WHEN 'i' THEN 'SYSTEM TOAST INDEX' " +
                               "  ELSE NULL " +
                               "  END " +
                               " WHEN false THEN CASE c.relname ~ '^pg_temp_' " +
                               "  WHEN true THEN CASE c.relkind " +
                               "   WHEN 'r' THEN 'TEMPORARY TABLE' " +
                               "   WHEN 'i' THEN 'TEMPORARY INDEX' " +
                               "   WHEN 'S' THEN 'TEMPORARY SEQUENCE' " +
                               "   WHEN 'v' THEN 'TEMPORARY VIEW' " +
                               "   ELSE NULL " +
                               "   END " +
                               "  WHEN false THEN CASE c.relkind " +
                               "   WHEN 'r' THEN 'SYSTEM TABLE' " +
                               "   WHEN 'v' THEN 'SYSTEM VIEW' " +
                               "   WHEN 'i' THEN 'SYSTEM INDEX' " +
                               "   ELSE NULL " +
                               "   END " +
                               "  ELSE NULL " +
                               "  END " +
                               " ELSE NULL " +
                               " END " +
                               " WHEN false THEN CASE c.relkind " +
                               " WHEN 'r' THEN 'TABLE' " +
                               " WHEN 'i' THEN 'INDEX' " +
                               " WHEN 'S' THEN 'SEQUENCE' " +
                               " WHEN 'v' THEN 'VIEW' " +
                               " WHEN 'c' THEN 'TYPE' " +
                               " ELSE NULL " +
                               " END " +
                               " ELSE NULL " +
                               " END ";
            orderby = " ORDER BY TABLE_TYPE,TABLE_NAME ";
            if (connection.haveMinimumServerVersion("7.2"))
            {
                select = "SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, c.relname AS TABLE_NAME, " + tableType + " AS TABLE_TYPE, d.description AS REMARKS " +
                         " FROM pg_class c " +
                         " LEFT JOIN pg_description d ON (c.oid=d.objoid AND d.objsubid = 0) " +
                         " LEFT JOIN pg_class dc ON (d.classoid = dc.oid AND dc.relname='pg_class') " +
                         " WHERE true ";
            }
            else if (connection.haveMinimumServerVersion("7.1"))
            {
                select = "SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, c.relname AS TABLE_NAME, " + tableType + " AS TABLE_TYPE, d.description AS REMARKS " +
                         " FROM pg_class c " +
                         " LEFT JOIN pg_description d ON (c.oid=d.objoid) " +
                         " WHERE true ";
            }
            else
            {
                select = "SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, c.relname AS TABLE_NAME, " + tableType + " AS TABLE_TYPE, NULL AS REMARKS " +
                         " FROM pg_class c " +
                         " WHERE true ";
            }
        }

        if (tableNamePattern != null && !"".equals(tableNamePattern))
        {
            select += " AND c.relname LIKE " + escapeQuotes(tableNamePattern);
        }
        if (types != null) {
            select += " AND (false ";
            for (int i = 0; i < types.length; i++)
            {
                Map clauses = (Map)tableTypeClauses.get(types[i]);
                if (clauses != null)
                {
                    String clause = (String)clauses.get(useSchemas);
                    select += " OR ( " + clause + " ) ";
                }
            }
            select += ") ";
        }
        String sql = select + orderby;

        return createMetaDataStatement().executeQuery(sql);
    }

    private static final Map tableTypeClauses;
    static {
        tableTypeClauses = new HashMap();
        Map ht = new HashMap();
        tableTypeClauses.put("TABLE", ht);
        ht.put("SCHEMAS", "c.relkind = 'r' AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'");
        ht.put("NOSCHEMAS", "c.relkind = 'r' AND c.relname !~ '^pg_'");
        ht = new HashMap();
        tableTypeClauses.put("VIEW", ht);
        ht.put("SCHEMAS", "c.relkind = 'v' AND n.nspname <> 'pg_catalog' AND n.nspname <> 'information_schema'");
        ht.put("NOSCHEMAS", "c.relkind = 'v' AND c.relname !~ '^pg_'");
        ht = new HashMap();
        tableTypeClauses.put("INDEX", ht);
        ht.put("SCHEMAS", "c.relkind = 'i' AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'");
        ht.put("NOSCHEMAS", "c.relkind = 'i' AND c.relname !~ '^pg_'");
        ht = new HashMap();
        tableTypeClauses.put("SEQUENCE", ht);
        ht.put("SCHEMAS", "c.relkind = 'S'");
        ht.put("NOSCHEMAS", "c.relkind = 'S'");
        ht = new HashMap();
        tableTypeClauses.put("TYPE", ht);
        ht.put("SCHEMAS", "c.relkind = 'c' AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'");
        ht.put("NOSCHEMAS", "c.relkind = 'c' AND c.relname !~ '^pg_'");
        ht = new HashMap();
        tableTypeClauses.put("SYSTEM TABLE", ht);
        ht.put("SCHEMAS", "c.relkind = 'r' AND (n.nspname = 'pg_catalog' OR n.nspname = 'information_schema')");
        ht.put("NOSCHEMAS", "c.relkind = 'r' AND c.relname ~ '^pg_' AND c.relname !~ '^pg_toast_' AND c.relname !~ '^pg_temp_'");
        ht = new HashMap();
        tableTypeClauses.put("SYSTEM TOAST TABLE", ht);
        ht.put("SCHEMAS", "c.relkind = 'r' AND n.nspname = 'pg_toast'");
        ht.put("NOSCHEMAS", "c.relkind = 'r' AND c.relname ~ '^pg_toast_'");
        ht = new HashMap();
        tableTypeClauses.put("SYSTEM TOAST INDEX", ht);
        ht.put("SCHEMAS", "c.relkind = 'i' AND n.nspname = 'pg_toast'");
        ht.put("NOSCHEMAS", "c.relkind = 'i' AND c.relname ~ '^pg_toast_'");
        ht = new HashMap();
        tableTypeClauses.put("SYSTEM VIEW", ht);
        ht.put("SCHEMAS", "c.relkind = 'v' AND (n.nspname = 'pg_catalog' OR n.nspname = 'information_schema') ");
        ht.put("NOSCHEMAS", "c.relkind = 'v' AND c.relname ~ '^pg_'");
        ht = new HashMap();
        tableTypeClauses.put("SYSTEM INDEX", ht);
        ht.put("SCHEMAS", "c.relkind = 'i' AND (n.nspname = 'pg_catalog' OR n.nspname = 'information_schema') ");
        ht.put("NOSCHEMAS", "c.relkind = 'v' AND c.relname ~ '^pg_' AND c.relname !~ '^pg_toast_' AND c.relname !~ '^pg_temp_'");
        ht = new HashMap();
        tableTypeClauses.put("TEMPORARY TABLE", ht);
        ht.put("SCHEMAS", "c.relkind = 'r' AND n.nspname ~ '^pg_temp_' ");
        ht.put("NOSCHEMAS", "c.relkind = 'r' AND c.relname ~ '^pg_temp_' ");
        ht = new HashMap();
        tableTypeClauses.put("TEMPORARY INDEX", ht);
        ht.put("SCHEMAS", "c.relkind = 'i' AND n.nspname ~ '^pg_temp_' ");
        ht.put("NOSCHEMAS", "c.relkind = 'i' AND c.relname ~ '^pg_temp_' ");
        ht = new HashMap();
        tableTypeClauses.put("TEMPORARY VIEW", ht);
        ht.put("SCHEMAS", "c.relkind = 'v' AND n.nspname ~ '^pg_temp_' ");
        ht.put("NOSCHEMAS", "c.relkind = 'v' AND c.relname ~ '^pg_temp_' ");
        ht = new HashMap();
        tableTypeClauses.put("TEMPORARY SEQUENCE", ht);
        ht.put("SCHEMAS", "c.relkind = 'S' AND n.nspname ~ '^pg_temp_' ");
        ht.put("NOSCHEMAS", "c.relkind = 'S' AND c.relname ~ '^pg_temp_' ");
        ht = new HashMap();
        tableTypeClauses.put("FOREIGN TABLE", ht);
        ht.put("SCHEMAS", "c.relkind = 'f'");
        ht.put("NOSCHEMAS", "c.relkind = 'f'");
    }

    /*
     * Get the schema names available in this database.  The results
     * are ordered by schema name.
     *
     * <P>The schema column is:
     * <OL>
     * <LI><B>TABLE_SCHEM</B> String => schema name
     * </OL>
     *
     * @return ResultSet each row has a single String column that is a
     * schema name
     */
    public java.sql.ResultSet getSchemas() throws SQLException
    {
        return getSchemas(2, null, null);
    }

    protected ResultSet getSchemas(int jdbcVersion, String catalog, String schemaPattern) throws SQLException {
        String sql;
        // Show only the users temp schemas, but not other peoples
        // because they can't access any objects in them.
        if (connection.haveMinimumServerVersion("7.3"))
        {
            // 7.3 can't extract elements from an array returned by
            // a function, so we've got to coerce it to text and then
            // hack it up with a regex.
            String tempSchema = "substring(textin(array_out(pg_catalog.current_schemas(true))) from '{(pg_temp_[0-9]+),')";
            if (connection.haveMinimumServerVersion("7.4")) {
                tempSchema = "(pg_catalog.current_schemas(true))[1]";
            }
            sql = "SELECT nspname AS TABLE_SCHEM ";
            if (jdbcVersion >= 3)
                sql += ", NULL AS TABLE_CATALOG ";
            sql += " FROM pg_catalog.pg_namespace WHERE nspname <> 'pg_toast' AND (nspname !~ '^pg_temp_' OR nspname = " + tempSchema + ") AND (nspname !~ '^pg_toast_temp_' OR nspname = replace(" + tempSchema + ", 'pg_temp_', 'pg_toast_temp_')) ";
            if (schemaPattern != null && !"".equals(schemaPattern))
            {
                sql += " AND nspname LIKE " + escapeQuotes(schemaPattern);
            }
            sql += " ORDER BY TABLE_SCHEM";
        }
        else
        {
            sql = "SELECT ''::text AS TABLE_SCHEM ";
            if (jdbcVersion >= 3) {
                sql += ", NULL AS TABLE_CATALOG ";
            }
            if (schemaPattern != null)
            {
                sql += " WHERE ''::text LIKE " + escapeQuotes(schemaPattern);
            }
        }
        return createMetaDataStatement().executeQuery(sql);
    }

    /*
     * Get the catalog names available in this database.  The results
     * are ordered by catalog name.
     *
     * Postgresql does not support multiple catalogs from a single
     * connection, so to reduce confusion we only return the current
     * catalog.
     *
     * <P>The catalog column is:
     * <OL>
     * <LI><B>TABLE_CAT</B> String => catalog name
     * </OL>
     *
     * @return ResultSet each row has a single String column that is a
     * catalog name
     */
    public java.sql.ResultSet getCatalogs() throws SQLException
    {
        Field f[] = new Field[1];
        List v = new ArrayList();
        f[0] = new Field("TABLE_CAT", Oid.VARCHAR);
        byte[][] tuple = new byte[1][];
        tuple[0] = connection.encodeString(connection.getCatalog());
        v.add(tuple);

        return (ResultSet) ((BaseStatement)createMetaDataStatement()).createDriverResultSet(f, v);
    }

    /*
     * Get the table types available in this database. The results
     * are ordered by table type.
     *
     * <P>The table type is:
     * <OL>
     * <LI><B>TABLE_TYPE</B> String => table type.  Typical types are "TABLE",
     *   "VIEW", "SYSTEM TABLE", "GLOBAL TEMPORARY",
     *   "LOCAL TEMPORARY", "ALIAS", "SYNONYM".
     * </OL>
     *
     * @return ResultSet each row has a single String column that is a
     * table type
     */
    public java.sql.ResultSet getTableTypes() throws SQLException
    {
        String types[] = new String[tableTypeClauses.size()];
        Iterator e = tableTypeClauses.keySet().iterator();
        int i = 0;
        while (e.hasNext())
        {
            types[i++] = (String)e.next();
        }
        sortStringArray(types);

        Field f[] = new Field[1];
        List v = new ArrayList();
        f[0] = new Field("TABLE_TYPE", Oid.VARCHAR);
        for (i = 0; i < types.length; i++)
        {
            byte[][] tuple = new byte[1][];
            tuple[0] = connection.encodeString(types[i]);
            v.add(tuple);
        }

        return (ResultSet) ((BaseStatement)createMetaDataStatement()).createDriverResultSet(f, v);
    }

    protected java.sql.ResultSet getColumns(int jdbcVersion, String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException
    {
        int numberOfFields;
        if (jdbcVersion >= 4) {
            numberOfFields = 23;
        } else if (jdbcVersion >= 3) {
            numberOfFields = 22;
        } else {
            numberOfFields = 18;
        }
        List v = new ArrayList();  // The new ResultSet tuple stuff
        Field f[] = new Field[numberOfFields];  // The field descriptors for the new ResultSet

        f[0] = new Field("TABLE_CAT", Oid.VARCHAR);
        f[1] = new Field("TABLE_SCHEM", Oid.VARCHAR);
        f[2] = new Field("TABLE_NAME", Oid.VARCHAR);
        f[3] = new Field("COLUMN_NAME", Oid.VARCHAR);
        f[4] = new Field("DATA_TYPE", Oid.INT2);
        f[5] = new Field("TYPE_NAME", Oid.VARCHAR);
        f[6] = new Field("COLUMN_SIZE", Oid.INT4);
        f[7] = new Field("BUFFER_LENGTH", Oid.VARCHAR);
        f[8] = new Field("DECIMAL_DIGITS", Oid.INT4);
        f[9] = new Field("NUM_PREC_RADIX", Oid.INT4);
        f[10] = new Field("NULLABLE", Oid.INT4);
        f[11] = new Field("REMARKS", Oid.VARCHAR);
        f[12] = new Field("COLUMN_DEF", Oid.VARCHAR);
        f[13] = new Field("SQL_DATA_TYPE", Oid.INT4);
        f[14] = new Field("SQL_DATETIME_SUB", Oid.INT4);
        f[15] = new Field("CHAR_OCTET_LENGTH", Oid.VARCHAR);
        f[16] = new Field("ORDINAL_POSITION", Oid.INT4);
        f[17] = new Field("IS_NULLABLE", Oid.VARCHAR);

        if (jdbcVersion >= 3) {
            f[18] = new Field("SCOPE_CATLOG", Oid.VARCHAR);
            f[19] = new Field("SCOPE_SCHEMA", Oid.VARCHAR);
            f[20] = new Field("SCOPE_TABLE", Oid.VARCHAR);
            f[21] = new Field("SOURCE_DATA_TYPE", Oid.INT2);
        }

        if (jdbcVersion >= 4) {
            f[22] = new Field("IS_AUTOINCREMENT", Oid.VARCHAR);
        }

        String sql;
        if (connection.haveMinimumServerVersion("7.3"))
        {
            // a.attnum isn't decremented when preceding columns are dropped,
            // so the only way to calculate the correct column number is with
            // window functions, new in 8.4.
            // 
            // We want to push as much predicate information below the window
            // function as possible (schema/table names), but must leave
            // column name outside so we correctly count the other columns.
            //
            if (connection.haveMinimumServerVersion("8.4"))
                sql = "SELECT * FROM (";
            else
                sql = "";

            sql += "SELECT n.nspname,c.relname,a.attname,a.atttypid,a.attnotnull OR (t.typtype = 'd' AND t.typnotnull) AS attnotnull,a.atttypmod,a.attlen,";
            
            if (connection.haveMinimumServerVersion("8.4"))
                sql += "row_number() OVER (PARTITION BY a.attrelid ORDER BY a.attnum) AS attnum, ";
            else
                sql += "a.attnum,";
            
            sql += "pg_catalog.pg_get_expr(def.adbin, def.adrelid) AS adsrc,dsc.description,t.typbasetype,t.typtype " +
                  " FROM pg_catalog.pg_namespace n " +
                  " JOIN pg_catalog.pg_class c ON (c.relnamespace = n.oid) " +
                  " JOIN pg_catalog.pg_attribute a ON (a.attrelid=c.oid) " +
                  " JOIN pg_catalog.pg_type t ON (a.atttypid = t.oid) " +
                  " LEFT JOIN pg_catalog.pg_attrdef def ON (a.attrelid=def.adrelid AND a.attnum = def.adnum) " +
                  " LEFT JOIN pg_catalog.pg_description dsc ON (c.oid=dsc.objoid AND a.attnum = dsc.objsubid) " +
                  " LEFT JOIN pg_catalog.pg_class dc ON (dc.oid=dsc.classoid AND dc.relname='pg_class') " +
                  " LEFT JOIN pg_catalog.pg_namespace dn ON (dc.relnamespace=dn.oid AND dn.nspname='pg_catalog') " +
                  " WHERE a.attnum > 0 AND NOT a.attisdropped ";

            if (schemaPattern != null && !"".equals(schemaPattern))
            {
                sql += " AND n.nspname LIKE " + escapeQuotes(schemaPattern);
            }

            if (tableNamePattern != null && !"".equals(tableNamePattern))
            {
                sql += " AND c.relname LIKE " + escapeQuotes(tableNamePattern);
            }

            if (connection.haveMinimumServerVersion("8.4"))
                sql += ") c WHERE true ";

        }
        else if (connection.haveMinimumServerVersion("7.2"))
        {
            sql = "SELECT NULL::text AS nspname,c.relname,a.attname,a.atttypid,a.attnotnull,a.atttypmod,a.attlen,a.attnum,pg_get_expr(def.adbin,def.adrelid) AS adsrc,dsc.description,NULL::oid AS typbasetype,t.typtype " +
                  " FROM pg_class c " +
                  " JOIN pg_attribute a ON (a.attrelid=c.oid) " +
                  " JOIN pg_type t ON (a.atttypid = t.oid) " +
                  " LEFT JOIN pg_attrdef def ON (a.attrelid=def.adrelid AND a.attnum = def.adnum) " +
                  " LEFT JOIN pg_description dsc ON (c.oid=dsc.objoid AND a.attnum = dsc.objsubid) " +
                  " LEFT JOIN pg_class dc ON (dc.oid=dsc.classoid AND dc.relname='pg_class') " +
                  " WHERE a.attnum > 0 ";
        }
        else if (connection.haveMinimumServerVersion("7.1"))
        {
            sql = "SELECT NULL::text AS nspname,c.relname,a.attname,a.atttypid,a.attnotnull,a.atttypmod,a.attlen,a.attnum,def.adsrc,dsc.description,NULL::oid AS typbasetype, 'b' AS typtype  " +
                  " FROM pg_class c " +
                  " JOIN pg_attribute a ON (a.attrelid=c.oid) " +
                  " LEFT JOIN pg_attrdef def ON (a.attrelid=def.adrelid AND a.attnum = def.adnum) " +
                  " LEFT JOIN pg_description dsc ON (a.oid=dsc.objoid) " +
                  " WHERE a.attnum > 0 ";
        }
        else
        {
            // if < 7.1 then don't get defaults or descriptions.
            sql = "SELECT NULL::text AS nspname,c.relname,a.attname,a.atttypid,a.attnotnull,a.atttypmod,a.attlen,a.attnum,NULL AS adsrc,NULL AS description,NULL AS typbasetype, 'b' AS typtype " +
                  " FROM pg_class c, pg_attribute a " +
                  " WHERE a.attrelid=c.oid AND a.attnum > 0 ";
        }

        if (!connection.haveMinimumServerVersion("7.3") && tableNamePattern != null && !"".equals(tableNamePattern))
        {
            sql += " AND c.relname LIKE " + escapeQuotes(tableNamePattern);
        }
        if (columnNamePattern != null && !"".equals(columnNamePattern))
        {
            sql += " AND attname LIKE " + escapeQuotes(columnNamePattern);
        }
        sql += " ORDER BY nspname,c.relname,attnum ";

        ResultSet rs = connection.createStatement().executeQuery(sql);
        while (rs.next())
        {
            byte[][] tuple = new byte[numberOfFields][];
            int typeOid = (int)rs.getLong("atttypid");
            int typeMod = rs.getInt("atttypmod");

            tuple[0] = null;     // Catalog name, not supported
            tuple[1] = rs.getBytes("nspname"); // Schema
            tuple[2] = rs.getBytes("relname"); // Table name
            tuple[3] = rs.getBytes("attname"); // Column name

            String typtype = rs.getString("typtype");
            int sqlType;
            if ("c".equals(typtype)) {
                sqlType = Types.STRUCT;
            } else if ("d".equals(typtype)) {
                sqlType = Types.DISTINCT;
            } else {
                sqlType = connection.getTypeInfo().getSQLType(typeOid);
            }

            tuple[4] = connection.encodeString(Integer.toString(sqlType));
            String pgType = connection.getTypeInfo().getPGType(typeOid);
            tuple[5] = connection.encodeString(pgType); // Type name
            tuple[7] = null;      // Buffer length


            String defval = rs.getString("adsrc");

            if ( defval != null )
            {
                if ( pgType.equals("int4") )
                {
                    if (defval.indexOf("nextval(") != -1)
                        tuple[5] = connection.encodeString("serial"); // Type name == serial
                }
                else if ( pgType.equals("int8") )
                {
                    if (defval.indexOf("nextval(") != -1)
                        tuple[5] = connection.encodeString("bigserial"); // Type name == bigserial
                }
            }

            int decimalDigits = connection.getTypeInfo().getScale(typeOid, typeMod);
            int columnSize = connection.getTypeInfo().getPrecision(typeOid, typeMod);
            if (columnSize == 0) {
                columnSize = connection.getTypeInfo().getDisplaySize(typeOid, typeMod);
            }

            tuple[6] = connection.encodeString(Integer.toString(columnSize));
            tuple[8] = connection.encodeString(Integer.toString(decimalDigits));

            // Everything is base 10 unless we override later.
            tuple[9] = connection.encodeString("10");

            if (pgType.equals("bit") || pgType.equals("varbit"))
            {
                tuple[9] = connection.encodeString("2");
            }

            tuple[10] = connection.encodeString(Integer.toString(rs.getBoolean("attnotnull") ? java.sql.DatabaseMetaData.columnNoNulls : java.sql.DatabaseMetaData.columnNullable)); // Nullable
            tuple[11] = rs.getBytes("description");    // Description (if any)
            tuple[12] = rs.getBytes("adsrc");    // Column default
            tuple[13] = null;      // sql data type (unused)
            tuple[14] = null;      // sql datetime sub (unused)
            tuple[15] = tuple[6];     // char octet length
            tuple[16] = connection.encodeString(String.valueOf(rs.getInt("attnum"))); // ordinal position
            tuple[17] = connection.encodeString(rs.getBoolean("attnotnull") ? "NO" : "YES"); // Is nullable

            if (jdbcVersion >= 3) {
                int baseTypeOid = (int) rs.getLong("typbasetype");

                tuple[18] = null; // SCOPE_CATLOG
                tuple[19] = null; // SCOPE_SCHEMA
                tuple[20] = null; // SCOPE_TABLE
                tuple[21] = baseTypeOid == 0 ? null : connection.encodeString(Integer.toString(connection.getTypeInfo().getSQLType(baseTypeOid))); // SOURCE_DATA_TYPE
            }

            if (jdbcVersion >= 4) {
                String autoinc = "NO";
                if (defval != null && defval.indexOf("nextval(") != -1) {
                    autoinc = "YES";
                }
                tuple[22] = connection.encodeString(autoinc);
            }

            v.add(tuple);
        }
        rs.close();

        return (ResultSet) ((BaseStatement)createMetaDataStatement()).createDriverResultSet(f, v);
    }

    /*
     * Get a description of table columns available in a catalog.
     *
     * <P>Only column descriptions matching the catalog, schema, table
     * and column name criteria are returned.  They are ordered by
     * TABLE_SCHEM, TABLE_NAME and ORDINAL_POSITION.
     *
     * <P>Each column description has the following columns:
     * <OL>
     * <LI><B>TABLE_CAT</B> String => table catalog (may be null)
     * <LI><B>TABLE_SCHEM</B> String => table schema (may be null)
     * <LI><B>TABLE_NAME</B> String => table name
     * <LI><B>COLUMN_NAME</B> String => column name
     * <LI><B>DATA_TYPE</B> short => SQL type from java.sql.Types
     * <LI><B>TYPE_NAME</B> String => Data source dependent type name
     * <LI><B>COLUMN_SIZE</B> int => column size. For char or date
     *  types this is the maximum number of characters, for numeric or
     *  decimal types this is precision.
     * <LI><B>BUFFER_LENGTH</B> is not used.
     * <LI><B>DECIMAL_DIGITS</B> int => the number of fractional digits
     * <LI><B>NUM_PREC_RADIX</B> int => Radix (typically either 10 or 2)
     * <LI><B>NULLABLE</B> int => is NULL allowed?
     *  <UL>
     *  <LI> columnNoNulls - might not allow NULL values
     *  <LI> columnNullable - definitely allows NULL values
     *  <LI> columnNullableUnknown - nullability unknown
     *  </UL>
     * <LI><B>REMARKS</B> String => comment describing column (may be null)
     * <LI><B>COLUMN_DEF</B> String => default value (may be null)
     * <LI><B>SQL_DATA_TYPE</B> int => unused
     * <LI><B>SQL_DATETIME_SUB</B> int => unused
     * <LI><B>CHAR_OCTET_LENGTH</B> int => for char types the
     *   maximum number of bytes in the column
     * <LI><B>ORDINAL_POSITION</B> int => index of column in table
     *  (starting at 1)
     * <LI><B>IS_NULLABLE</B> String => "NO" means column definitely
     *  does not allow NULL values; "YES" means the column might
     *  allow NULL values. An empty string means nobody knows.
     * </OL>
     *
     * @param catalog a catalog name; "" retrieves those without a catalog
     * @param schemaPattern a schema name pattern; "" retrieves those
     * without a schema
     * @param tableNamePattern a table name pattern
     * @param columnNamePattern a column name pattern
     * @return ResultSet each row is a column description
     * @see #getSearchStringEscape
     */
    public java.sql.ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException
    {
        return getColumns(2, catalog, schemaPattern, tableNamePattern, columnNamePattern);
    }

    /*
     * Get a description of the access rights for a table's columns.
     *
     * <P>Only privileges matching the column name criteria are
     * returned.  They are ordered by COLUMN_NAME and PRIVILEGE.
     *
     * <P>Each privilige description has the following columns:
     * <OL>
     * <LI><B>TABLE_CAT</B> String => table catalog (may be null)
     * <LI><B>TABLE_SCHEM</B> String => table schema (may be null)
     * <LI><B>TABLE_NAME</B> String => table name
     * <LI><B>COLUMN_NAME</B> String => column name
     * <LI><B>GRANTOR</B> => grantor of access (may be null)
     * <LI><B>GRANTEE</B> String => grantee of access
     * <LI><B>PRIVILEGE</B> String => name of access (SELECT,
     *  INSERT, UPDATE, REFRENCES, ...)
     * <LI><B>IS_GRANTABLE</B> String => "YES" if grantee is permitted
     *  to grant to others; "NO" if not; null if unknown
     * </OL>
     *
     * @param catalog a catalog name; "" retrieves those without a catalog
     * @param schema a schema name; "" retrieves those without a schema
     * @param table a table name
     * @param columnNamePattern a column name pattern
     * @return ResultSet each row is a column privilege description
     * @see #getSearchStringEscape
     */
    public java.sql.ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException
    {
        Field f[] = new Field[8];
        List v = new ArrayList();

        if (table == null)
            table = "%";

        if (columnNamePattern == null)
            columnNamePattern = "%";

        f[0] = new Field("TABLE_CAT", Oid.VARCHAR);
        f[1] = new Field("TABLE_SCHEM", Oid.VARCHAR);
        f[2] = new Field("TABLE_NAME", Oid.VARCHAR);
        f[3] = new Field("COLUMN_NAME", Oid.VARCHAR);
        f[4] = new Field("GRANTOR", Oid.VARCHAR);
        f[5] = new Field("GRANTEE", Oid.VARCHAR);
        f[6] = new Field("PRIVILEGE", Oid.VARCHAR);
        f[7] = new Field("IS_GRANTABLE", Oid.VARCHAR);

        String sql;
        if (connection.haveMinimumServerVersion("8.4"))
        {
            sql = "SELECT n.nspname,c.relname,r.rolname,c.relacl,a.attacl,a.attname " +
                  " FROM pg_catalog.pg_namespace n, pg_catalog.pg_class c, pg_catalog.pg_roles r, pg_catalog.pg_attribute a " +
                  " WHERE c.relnamespace = n.oid " +
                  " AND c.relowner = r.oid " +
                  " AND c.oid = a.attrelid " +
                  " AND c.relkind = 'r' " +
                  " AND a.attnum > 0 AND NOT a.attisdropped ";
            if (schema != null && !"".equals(schema))
            {
                sql += " AND n.nspname = " + escapeQuotes(schema);
            }
        }
        else if (connection.haveMinimumServerVersion("7.3"))
        {
            sql = "SELECT n.nspname,c.relname,r.rolname,c.relacl,a.attname " +
                  " FROM pg_catalog.pg_namespace n, pg_catalog.pg_class c, pg_catalog.pg_roles r, pg_catalog.pg_attribute a " +
                  " WHERE c.relnamespace = n.oid " +
                  " AND c.relowner = r.oid " +
                  " AND c.oid = a.attrelid " +
                  " AND c.relkind = 'r' " +
                  " AND a.attnum > 0 AND NOT a.attisdropped ";
            if (schema != null && !"".equals(schema))
            {
                sql += " AND n.nspname = " + escapeQuotes(schema);
            }
        }
        else
        {
            sql = "SELECT NULL::text AS nspname,c.relname,u.usename,c.relacl,a.attname " +
                  "FROM pg_class c, pg_user u,pg_attribute a " +
                  " WHERE u.usesysid = c.relowner " +
                  " AND c.oid = a.attrelid " +
                  " AND a.attnum > 0 " +
                  " AND c.relkind = 'r' ";
        }

        sql += " AND c.relname = " + escapeQuotes(table);
        if (columnNamePattern != null && !"".equals(columnNamePattern))
        {
            sql += " AND a.attname LIKE " + escapeQuotes(columnNamePattern);
        }
        sql += " ORDER BY attname ";

        ResultSet rs = connection.createStatement().executeQuery(sql);
        while (rs.next())
        {
            byte schemaName[] = rs.getBytes("nspname");
            byte tableName[] = rs.getBytes("relname");
            byte column[] = rs.getBytes("attname");
            String owner = rs.getString("rolname");
            String relAcl = rs.getString("relacl");
            
            Map permissions = parseACL(relAcl, owner);
            
            if (connection.haveMinimumServerVersion("8.4"))
            {
                String acl = rs.getString("attacl");
                Map relPermissions = parseACL(acl, owner);
                permissions.putAll(relPermissions);
            }
            String permNames[] = new String[permissions.size()];
            Iterator e = permissions.keySet().iterator();
            int i = 0;
            while (e.hasNext())
            {
                permNames[i++] = (String)e.next();
            }
            sortStringArray(permNames);
            for (i = 0; i < permNames.length; i++)
            {
                byte[] privilege = connection.encodeString(permNames[i]);
                Map grantees = (Map)permissions.get(permNames[i]);
                String granteeUsers[] = new String[grantees.size()];
                Iterator g = grantees.keySet().iterator();
                int k = 0;
                while (g.hasNext()){
                	granteeUsers[k++] = (String)g.next();
                }                
                for (int j = 0; j < grantees.size(); j++)
                {
                	List grantor = (List)grantees.get(granteeUsers[j]);
                	String grantee = (String)granteeUsers[j];
                	for (int l = 0; l < grantor.size(); l++) {
                		String[] grants = (String[])grantor.get(l);                	
	                    String grantable = owner.equals(grantee) ? "YES" : grants[1];
                    byte[][] tuple = new byte[8][];
                    tuple[0] = null;
                    tuple[1] = schemaName;
                    tuple[2] = tableName;
                    tuple[3] = column;
	                    tuple[4] = connection.encodeString(grants[0]);
                    tuple[5] = connection.encodeString(grantee);
                    tuple[6] = privilege;
                    tuple[7] = connection.encodeString(grantable);
                    v.add(tuple);
                }
            }
        }
	}
        rs.close();

        return (ResultSet) ((BaseStatement)createMetaDataStatement()).createDriverResultSet(f, v);
    }

    /*
    * Get a description of the access rights for each table available
    * in a catalog.
    *
    * This method is currently unimplemented.
    *
    * <P>Only privileges matching the schema and table name
    * criteria are returned.  They are ordered by TABLE_SCHEM,
    * TABLE_NAME, and PRIVILEGE.
    *
    * <P>Each privilege description has the following columns:
    * <OL>
    * <LI><B>TABLE_CAT</B> String => table catalog (may be null)
    * <LI><B>TABLE_SCHEM</B> String => table schema (may be null)
    * <LI><B>TABLE_NAME</B> String => table name
    * <LI><B>GRANTOR</B> => grantor of access (may be null)
    * <LI><B>GRANTEE</B> String => grantee of access
    * <LI><B>PRIVILEGE</B> String => name of access (SELECT,
    *  INSERT, UPDATE, REFRENCES, ...)
    * <LI><B>IS_GRANTABLE</B> String => "YES" if grantee is permitted
    *  to grant to others; "NO" if not; null if unknown
    * </OL>
    *
    * @param catalog a catalog name; "" retrieves those without a catalog
    * @param schemaPattern a schema name pattern; "" retrieves those
    * without a schema
    * @param tableNamePattern a table name pattern
    * @return ResultSet each row is a table privilege description
    * @see #getSearchStringEscape
    */
    public java.sql.ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException
    {
        Field f[] = new Field[7];
        List v = new ArrayList();

        f[0] = new Field("TABLE_CAT", Oid.VARCHAR);
        f[1] = new Field("TABLE_SCHEM", Oid.VARCHAR);
        f[2] = new Field("TABLE_NAME", Oid.VARCHAR);
        f[3] = new Field("GRANTOR", Oid.VARCHAR);
        f[4] = new Field("GRANTEE", Oid.VARCHAR);
        f[5] = new Field("PRIVILEGE", Oid.VARCHAR);
        f[6] = new Field("IS_GRANTABLE", Oid.VARCHAR);

        String sql;
        if (connection.haveMinimumServerVersion("7.3"))
        {
            sql = "SELECT n.nspname,c.relname,r.rolname,c.relacl " +
                  " FROM pg_catalog.pg_namespace n, pg_catalog.pg_class c, pg_catalog.pg_roles r " +
                  " WHERE c.relnamespace = n.oid " +
                  " AND c.relowner = r.oid " +
                  " AND c.relkind = 'r' ";
            if (schemaPattern != null && !"".equals(schemaPattern))
            {
                sql += " AND n.nspname LIKE " + escapeQuotes(schemaPattern);
            }
        }
        else
        {
            sql = "SELECT NULL::text AS nspname,c.relname,u.usename,c.relacl " +
                  "FROM pg_class c, pg_user u " +
                  " WHERE u.usesysid = c.relowner " +
                  " AND c.relkind = 'r' ";
        }

        if (tableNamePattern != null && !"".equals(tableNamePattern))
        {
            sql += " AND c.relname LIKE " + escapeQuotes(tableNamePattern);
        }
        sql += " ORDER BY nspname, relname ";

        ResultSet rs = connection.createStatement().executeQuery(sql);
        while (rs.next())
        {
            byte schema[] = rs.getBytes("nspname");
            byte table[] = rs.getBytes("relname");
            String owner = rs.getString("rolname");
            String acl = rs.getString("relacl");
            Map permissions = parseACL(acl, owner);
            String permNames[] = new String[permissions.size()];
            Iterator e = permissions.keySet().iterator();
            int i = 0;
            while (e.hasNext())
            {
                permNames[i++] = (String)e.next();
            }
            sortStringArray(permNames);
            for (i = 0; i < permNames.length; i++)
            {
                byte[] privilege = connection.encodeString(permNames[i]);
                Map grantees = (Map)permissions.get(permNames[i]);
                String granteeUsers[] = new String[grantees.size()];
                Iterator g = grantees.keySet().iterator();
                int k = 0;
                while (g.hasNext()){
                	granteeUsers[k++] = (String)g.next();
                }
                for (int j = 0; j < granteeUsers.length; j++)
                {
                	List grants = (List)grantees.get(granteeUsers[j]);
                	String grantee = (String)granteeUsers[j];
                	for (int l = 0; l < grants.size(); l++) {
                		String[] grantTuple = (String[])grants.get(l);
                		// report the owner as grantor if it's missing
                        String grantor = grantTuple[0].equals(null) ? owner : grantTuple[0];
                        // owner always has grant privileges
                        String grantable = owner.equals(grantee) ? "YES" : grantTuple[1];
                    	byte[][] tuple = new byte[7][];
                    	tuple[0] = null;
                    	tuple[1] = schema;
                    	tuple[2] = table;
                        tuple[3] = connection.encodeString(grantor);
                    	tuple[4] = connection.encodeString(grantee);
                    	tuple[5] = privilege;
                    	tuple[6] = connection.encodeString(grantable);
                    	v.add(tuple);
                    		
                	}
                }
            }
        }
        rs.close();

        return (ResultSet) ((BaseStatement)createMetaDataStatement()).createDriverResultSet(f, v);
    }

    private static void sortStringArray(String s[]) {
        for (int i = 0; i < s.length - 1; i++)
        {
            for (int j = i + 1; j < s.length; j++)
            {
                if (s[i].compareTo(s[j]) > 0)
                {
                    String tmp = s[i];
                    s[i] = s[j];
                    s[j] = tmp;
                }
            }
        }
    }

    /**
     * Parse an String of ACLs into a List of ACLs.
     */
    private static List parseACLArray(String aclString) {
        List acls = new ArrayList();
        if (aclString == null || aclString.length() == 0)
        {
            return acls;
        }
        boolean inQuotes = false;
        // start at 1 because of leading "{"
        int beginIndex = 1;
        char prevChar = ' ';
        for (int i = beginIndex; i < aclString.length(); i++)
        {

            char c = aclString.charAt(i);
            if (c == '"' && prevChar != '\\')
            {
                inQuotes = !inQuotes;
            }
            else if (c == ',' && !inQuotes)
            {
                acls.add(aclString.substring(beginIndex, i));
                beginIndex = i + 1;
            }
            prevChar = c;
        }
        // add last element removing the trailing "}"
        acls.add(aclString.substring(beginIndex, aclString.length() - 1));

        // Strip out enclosing quotes, if any.
        for (int i = 0; i < acls.size(); i++)
        {
            String acl = (String)acls.get(i);
            if (acl.startsWith("\"") && acl.endsWith("\""))
            {
                acl = acl.substring(1, acl.length() - 1);
                acls.set(i, acl);
            }
        }
        return acls;
    }

    /**
     * Add the user described by the given acl to the Lists of users
     * with the privileges described by the acl.
     */
    private void addACLPrivileges(String acl, Map privileges) {
        int equalIndex = acl.lastIndexOf("=");
        int slashIndex = acl.lastIndexOf("/");
        if (equalIndex == -1)
            return;

        String user = acl.substring(0, equalIndex);
        String grantor = null;
        if (user.length() == 0)
        {
            user = "PUBLIC";
        }
        String privs;
        if (slashIndex != -1) {
        	privs = acl.substring(equalIndex + 1, slashIndex);
        	grantor = acl.substring(slashIndex + 1, acl.length());
        } else {
        	privs = acl.substring(equalIndex + 1, acl.length());
        }
        	
        for (int i = 0; i < privs.length(); i++)
        {
            char c = privs.charAt(i);
            if (c != '*') {
            String sqlpriv;
	            String grantable;
	            if ( i < privs.length()-1 && privs.charAt(i + 1) == '*') {
	            	grantable = "YES";
	            } else {
	            	grantable = "NO";
	            }
            switch (c)
            {
            case 'a':
                sqlpriv = "INSERT";
                break;
            case 'r':
                sqlpriv = "SELECT";
                break;
            case 'w':
                sqlpriv = "UPDATE";
                break;
            case 'd':
                sqlpriv = "DELETE";
                break;
            case 'D':
                sqlpriv = "TRUNCATE";
                break;
            case 'R':
                sqlpriv = "RULE";
                break;
            case 'x':
                sqlpriv = "REFERENCES";
                break;
            case 't':
                sqlpriv = "TRIGGER";
                break;
                // the following can't be granted to a table, but
                // we'll keep them for completeness.
            case 'X':
                sqlpriv = "EXECUTE";
                break;
            case 'U':
                sqlpriv = "USAGE";
                break;
            case 'C':
                sqlpriv = "CREATE";
                break;
            case 'T':
                sqlpriv = "CREATE TEMP";
                break;
            default:
                sqlpriv = "UNKNOWN";
            }
	            
	            Map usersWithPermission = (Map)privileges.get(sqlpriv);
	            String[] grant = {grantor, grantable}; 

	            if (usersWithPermission == null) {	           
	                usersWithPermission = new HashMap();
	                List permissionByGrantor = new ArrayList();
            		permissionByGrantor.add(grant);
	                usersWithPermission.put(user, permissionByGrantor);
                privileges.put(sqlpriv, usersWithPermission);
	            } else {
	            	List permissionByGrantor = (List)usersWithPermission.get(user);
	            	if (permissionByGrantor == null) {
	            		permissionByGrantor = new ArrayList();
	            		permissionByGrantor.add(grant);
	            		usersWithPermission.put(user,permissionByGrantor);
	            	} else {
	            		permissionByGrantor.add(grant);
	            	}	            
	            }
            }
        }
    }

    /**
     * Take the a String representing an array of ACLs and return
     * a Map mapping the SQL permission name to a List of
     * usernames who have that permission.
     */
    public Map parseACL(String aclArray, String owner) {
        if (aclArray == null)
        {
            //null acl is a shortcut for owner having full privs
            String perms = "arwdRxt";
            if (connection.haveMinimumServerVersion("8.2")) {
                // 8.2 Removed the separate RULE permission
                perms = "arwdxt";
            } else if (connection.haveMinimumServerVersion("8.4")) {
                // 8.4 Added a separate TRUNCATE permission
                perms = "arwdDxt";
            }
            aclArray = "{" + owner + "=" + perms + "/" + owner + "}";
        }

        List acls = parseACLArray(aclArray);
        Map privileges = new HashMap();
        for (int i = 0; i < acls.size(); i++)
        {
            String acl = (String)acls.get(i);
            addACLPrivileges(acl, privileges);
        }
        return privileges;
    }

    /*
     * Get a description of a table's optimal set of columns that
     * uniquely identifies a row. They are ordered by SCOPE.
     *
     * <P>Each column description has the following columns:
     * <OL>
     * <LI><B>SCOPE</B> short => actual scope of result
     *  <UL>
     *  <LI> bestRowTemporary - very temporary, while using row
     *  <LI> bestRowTransaction - valid for remainder of current transaction
     *  <LI> bestRowSession - valid for remainder of current session
     *  </UL>
     * <LI><B>COLUMN_NAME</B> String => column name
     * <LI><B>DATA_TYPE</B> short => SQL data type from java.sql.Types
     * <LI><B>TYPE_NAME</B> String => Data source dependent type name
     * <LI><B>COLUMN_SIZE</B> int => precision
     * <LI><B>BUFFER_LENGTH</B> int => not used
     * <LI><B>DECIMAL_DIGITS</B> short  => scale
     * <LI><B>PSEUDO_COLUMN</B> short => is this a pseudo column
     *  like an Oracle ROWID
     *  <UL>
     *  <LI> bestRowUnknown - may or may not be pseudo column
     *  <LI> bestRowNotPseudo - is NOT a pseudo column
     *  <LI> bestRowPseudo - is a pseudo column
     *  </UL>
     * </OL>
     *
     * @param catalog a catalog name; "" retrieves those without a catalog
     * @param schema a schema name; "" retrieves those without a schema
     * @param table a table name
     * @param scope the scope of interest; use same values as SCOPE
     * @param nullable include columns that are nullable?
     * @return ResultSet each row is a column description
     */ 
    // Implementation note: This is required for Borland's JBuilder to work
    public java.sql.ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException
    {
        Field f[] = new Field[8];
        List v = new ArrayList();  // The new ResultSet tuple stuff

        f[0] = new Field("SCOPE", Oid.INT2);
        f[1] = new Field("COLUMN_NAME", Oid.VARCHAR);
        f[2] = new Field("DATA_TYPE", Oid.INT2);
        f[3] = new Field("TYPE_NAME", Oid.VARCHAR);
        f[4] = new Field("COLUMN_SIZE", Oid.INT4);
        f[5] = new Field("BUFFER_LENGTH", Oid.INT4);
        f[6] = new Field("DECIMAL_DIGITS", Oid.INT2);
        f[7] = new Field("PSEUDO_COLUMN", Oid.INT2);

        /* At the moment this simply returns a table's primary key,
         * if there is one.  I believe other unique indexes, ctid,
         * and oid should also be considered. -KJ
         */

        String sql;
        if (connection.haveMinimumServerVersion("8.1"))
        {
            sql = "SELECT a.attname, a.atttypid, atttypmod "
                + "FROM pg_catalog.pg_class ct "
                + "  JOIN pg_catalog.pg_attribute a ON (ct.oid = a.attrelid) "
                + "  JOIN pg_catalog.pg_namespace n ON (ct.relnamespace = n.oid) "
                + "  JOIN (SELECT i.indexrelid, i.indrelid, i.indisprimary, "
                + "             information_schema._pg_expandarray(i.indkey) AS keys "
                + "        FROM pg_catalog.pg_index i) i "
                + "    ON (a.attnum = (i.keys).x AND a.attrelid = i.indrelid) "
                + "WHERE true ";
            if (schema != null && !"".equals(schema))
            {
                sql += " AND n.nspname = " + escapeQuotes(schema);
            }
        }
        else
        {
            String from;
            String where = "";
            if (connection.haveMinimumServerVersion("7.3"))
            {
                from = " FROM pg_catalog.pg_namespace n, pg_catalog.pg_class ct, pg_catalog.pg_class ci, pg_catalog.pg_attribute a, pg_catalog.pg_index i ";
                where = " AND ct.relnamespace = n.oid ";
                if (schema != null && !"".equals(schema))
                {
                    where += " AND n.nspname = " + escapeQuotes(schema);
                }
            }
            else
            {
                from = " FROM pg_class ct, pg_class ci, pg_attribute a, pg_index i ";
            }
            sql = "SELECT a.attname, a.atttypid, a.atttypmod " +
                     from +
                     " WHERE ct.oid=i.indrelid AND ci.oid=i.indexrelid " +
                     " AND a.attrelid=ci.oid " +
                     where;
        }

        sql += " AND ct.relname = " + escapeQuotes(table) +
                     " AND i.indisprimary " +
                     " ORDER BY a.attnum ";

        ResultSet rs = connection.createStatement().executeQuery(sql);
        while (rs.next())
        {
            byte tuple[][] = new byte[8][];
            int typeOid = (int)rs.getLong("atttypid");
            int typeMod = rs.getInt("atttypmod");
            int decimalDigits = connection.getTypeInfo().getScale(typeOid, typeMod);
            int columnSize = connection.getTypeInfo().getPrecision(typeOid, typeMod);
            if (columnSize == 0) {
                columnSize = connection.getTypeInfo().getDisplaySize(typeOid, typeMod);
            }
            tuple[0] = connection.encodeString(Integer.toString(scope));
            tuple[1] = rs.getBytes("attname");
            tuple[2] = connection.encodeString(Integer.toString(connection.getTypeInfo().getSQLType(typeOid)));
            tuple[3] = connection.encodeString(connection.getTypeInfo().getPGType(typeOid));
            tuple[4] = connection.encodeString(Integer.toString(columnSize));
            tuple[5] = null; // unused
            tuple[6] = connection.encodeString(Integer.toString(decimalDigits));
            tuple[7] = connection.encodeString(Integer.toString(java.sql.DatabaseMetaData.bestRowNotPseudo));
            v.add(tuple);
        }

        return (ResultSet) ((BaseStatement)createMetaDataStatement()).createDriverResultSet(f, v);
    }

    /*
     * Get a description of a table's columns that are automatically
     * updated when any value in a row is updated. They are
     * unordered.
     *
     * <P>Each column description has the following columns:
     * <OL>
     * <LI><B>SCOPE</B> short => is not used
     * <LI><B>COLUMN_NAME</B> String => column name
     * <LI><B>DATA_TYPE</B> short => SQL data type from java.sql.Types
     * <LI><B>TYPE_NAME</B> String => Data source dependent type name
     * <LI><B>COLUMN_SIZE</B> int => precision
     * <LI><B>BUFFER_LENGTH</B> int => length of column value in bytes
     * <LI><B>DECIMAL_DIGITS</B> short  => scale
     * <LI><B>PSEUDO_COLUMN</B> short => is this a pseudo column
     *  like an Oracle ROWID
     *  <UL>
     *  <LI> versionColumnUnknown - may or may not be pseudo column
     *  <LI> versionColumnNotPseudo - is NOT a pseudo column
     *  <LI> versionColumnPseudo - is a pseudo column
     *  </UL>
     * </OL>
     *
     * @param catalog a catalog name; "" retrieves those without a catalog
     * @param schema a schema name; "" retrieves those without a schema
     * @param table a table name
     * @return ResultSet each row is a column description
     */
    public java.sql.ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException
    {
        Field f[] = new Field[8];
        List v = new ArrayList();  // The new ResultSet tuple stuff

        f[0] = new Field("SCOPE", Oid.INT2);
        f[1] = new Field("COLUMN_NAME", Oid.VARCHAR);
        f[2] = new Field("DATA_TYPE", Oid.INT2);
        f[3] = new Field("TYPE_NAME", Oid.VARCHAR);
        f[4] = new Field("COLUMN_SIZE", Oid.INT4);
        f[5] = new Field("BUFFER_LENGTH", Oid.INT4);
        f[6] = new Field("DECIMAL_DIGITS", Oid.INT2);
        f[7] = new Field("PSEUDO_COLUMN", Oid.INT2);

        byte tuple[][] = new byte[8][];

        /* Postgresql does not have any column types that are
         * automatically updated like some databases' timestamp type.
         * We can't tell what rules or triggers might be doing, so we
         * are left with the system columns that change on an update.
         * An update may change all of the following system columns:
         * ctid, xmax, xmin, cmax, and cmin.  Depending on if we are
         * in a transaction and wether we roll it back or not the
         * only guaranteed change is to ctid. -KJ
         */

        tuple[0] = null;
        tuple[1] = connection.encodeString("ctid");
        tuple[2] = connection.encodeString(Integer.toString(connection.getTypeInfo().getSQLType("tid")));
        tuple[3] = connection.encodeString("tid");
        tuple[4] = null;
        tuple[5] = null;
        tuple[6] = null;
        tuple[7] = connection.encodeString(Integer.toString(java.sql.DatabaseMetaData.versionColumnPseudo));
        v.add(tuple);

        /* Perhaps we should check that the given
         * catalog.schema.table actually exists. -KJ
         */ 
        return (ResultSet) ((BaseStatement)createMetaDataStatement()).createDriverResultSet(f, v);
    }

    /*
     * Get a description of a table's primary key columns.  They
     * are ordered by COLUMN_NAME.
     *
     * <P>Each column description has the following columns:
     * <OL>
     * <LI><B>TABLE_CAT</B> String => table catalog (may be null)
     * <LI><B>TABLE_SCHEM</B> String => table schema (may be null)
     * <LI><B>TABLE_NAME</B> String => table name
     * <LI><B>COLUMN_NAME</B> String => column name
     * <LI><B>KEY_SEQ</B> short => sequence number within primary key
     * <LI><B>PK_NAME</B> String => primary key name (may be null)
     * </OL>
     *
     * @param catalog a catalog name; "" retrieves those without a catalog
     * @param schema a schema name pattern; "" retrieves those
     * without a schema
     * @param table a table name
     * @return ResultSet each row is a primary key column description
     */
    public java.sql.ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException
    {
        String sql;
        if (connection.haveMinimumServerVersion("8.1"))
        {
            sql = "SELECT NULL AS TABLE_CAT, n.nspname AS TABLE_SCHEM, "
                + "  ct.relname AS TABLE_NAME, a.attname AS COLUMN_NAME, "
                + "  (i.keys).n AS KEY_SEQ, ci.relname AS PK_NAME "
                + "FROM pg_catalog.pg_class ct "
                + "  JOIN pg_catalog.pg_attribute a ON (ct.oid = a.attrelid) "
                + "  JOIN pg_catalog.pg_namespace n ON (ct.relnamespace = n.oid) "
                + "  JOIN (SELECT i.indexrelid, i.indrelid, i.indisprimary, "
                + "             information_schema._pg_expandarray(i.indkey) AS keys "
                + "        FROM pg_catalog.pg_index i) i "
                + "    ON (a.attnum = (i.keys).x AND a.attrelid = i.indrelid) "
                + "  JOIN pg_catalog.pg_class ci ON (ci.oid = i.indexrelid) "
                + "WHERE true ";
            if (schema != null && !"".equals(schema))
            {
                sql += " AND n.nspname = " + escapeQuotes(schema);
            }
        } else {
            String select;
            String from;
            String where = "";

            if (connection.haveMinimumServerVersion("7.3"))
            {
                select = "SELECT NULL AS TABLE_CAT, n.nspname AS TABLE_SCHEM, ";
                from = " FROM pg_catalog.pg_namespace n, pg_catalog.pg_class ct, pg_catalog.pg_class ci, pg_catalog.pg_attribute a, pg_catalog.pg_index i ";
                where = " AND ct.relnamespace = n.oid ";
                if (schema != null && !"".equals(schema))
                {
                    where += " AND n.nspname = " + escapeQuotes(schema);
                }
            }
            else
            {
                select = "SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, ";
                from = " FROM pg_class ct, pg_class ci, pg_attribute a, pg_index i ";
            }

            sql = select +
                         " ct.relname AS TABLE_NAME, " +
                         " a.attname AS COLUMN_NAME, " +
                         " a.attnum AS KEY_SEQ, " +
                         " ci.relname AS PK_NAME " +
                         from +
                         " WHERE ct.oid=i.indrelid AND ci.oid=i.indexrelid " +
                         " AND a.attrelid=ci.oid " +
                        where;
        }

        if (table != null && !"".equals(table))
        {
            sql += " AND ct.relname = " + escapeQuotes(table);
        }

        sql += " AND i.indisprimary " +
                " ORDER BY table_name, pk_name, key_seq";

        return createMetaDataStatement().executeQuery(sql);
    }

    /**
     *
     * @param primaryCatalog
     * @param primarySchema
     * @param primaryTable if provided will get the keys exported by this table
     * @param foreignTable if provided will get the keys imported by this table
     * @return ResultSet
     * @throws SQLException
     */

    protected java.sql.ResultSet getImportedExportedKeys(String primaryCatalog, String primarySchema, String primaryTable, String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException
    {
        Field f[] = new Field[14];

        f[0] = new Field("PKTABLE_CAT", Oid.VARCHAR);
        f[1] = new Field("PKTABLE_SCHEM", Oid.VARCHAR);
        f[2] = new Field("PKTABLE_NAME", Oid.VARCHAR);
        f[3] = new Field("PKCOLUMN_NAME", Oid.VARCHAR);
        f[4] = new Field("FKTABLE_CAT", Oid.VARCHAR);
        f[5] = new Field("FKTABLE_SCHEM", Oid.VARCHAR);
        f[6] = new Field("FKTABLE_NAME", Oid.VARCHAR);
        f[7] = new Field("FKCOLUMN_NAME", Oid.VARCHAR);
        f[8] = new Field("KEY_SEQ", Oid.INT2);
        f[9] = new Field("UPDATE_RULE", Oid.INT2);
        f[10] = new Field("DELETE_RULE", Oid.INT2);
        f[11] = new Field("FK_NAME", Oid.VARCHAR);
        f[12] = new Field("PK_NAME", Oid.VARCHAR);
        f[13] = new Field("DEFERRABILITY", Oid.INT2);


        String select;
        String from;
        String where = "";

        /*
         * The addition of the pg_constraint in 7.3 table should have really
         * helped us out here, but it comes up just a bit short.
         * - The conkey, confkey columns aren't really useful without
         *   contrib/array unless we want to issues separate queries.
         * - Unique indexes that can support foreign keys are not necessarily
         *   added to pg_constraint.  Also multiple unique indexes covering
         *   the same keys can be created which make it difficult to determine
         *   the PK_NAME field.
         */

        if (connection.haveMinimumServerVersion("7.4"))
        {
            String sql = "SELECT NULL::text AS PKTABLE_CAT, pkn.nspname AS PKTABLE_SCHEM, pkc.relname AS PKTABLE_NAME, pka.attname AS PKCOLUMN_NAME, " +
                         "NULL::text AS FKTABLE_CAT, fkn.nspname AS FKTABLE_SCHEM, fkc.relname AS FKTABLE_NAME, fka.attname AS FKCOLUMN_NAME, " +
                         "pos.n AS KEY_SEQ, " +
                         "CASE con.confupdtype " +
                         " WHEN 'c' THEN " + DatabaseMetaData.importedKeyCascade +
                         " WHEN 'n' THEN " + DatabaseMetaData.importedKeySetNull +
                         " WHEN 'd' THEN " + DatabaseMetaData.importedKeySetDefault +
                         " WHEN 'r' THEN " + DatabaseMetaData.importedKeyRestrict +
                         " WHEN 'a' THEN " + DatabaseMetaData.importedKeyNoAction +
                         " ELSE NULL END AS UPDATE_RULE, " +
                         "CASE con.confdeltype " +
                         " WHEN 'c' THEN " + DatabaseMetaData.importedKeyCascade +
                         " WHEN 'n' THEN " + DatabaseMetaData.importedKeySetNull +
                         " WHEN 'd' THEN " + DatabaseMetaData.importedKeySetDefault +
                         " WHEN 'r' THEN " + DatabaseMetaData.importedKeyRestrict +
                         " WHEN 'a' THEN " + DatabaseMetaData.importedKeyNoAction +
                         " ELSE NULL END AS DELETE_RULE, " +
                         "con.conname AS FK_NAME, pkic.relname AS PK_NAME, " +
                         "CASE " +
                         " WHEN con.condeferrable AND con.condeferred THEN " + DatabaseMetaData.importedKeyInitiallyDeferred +
                         " WHEN con.condeferrable THEN " + DatabaseMetaData.importedKeyInitiallyImmediate +
                         " ELSE " + DatabaseMetaData.importedKeyNotDeferrable +
                         " END AS DEFERRABILITY " +
                         " FROM " +
                         " pg_catalog.pg_namespace pkn, pg_catalog.pg_class pkc, pg_catalog.pg_attribute pka, " +
                         " pg_catalog.pg_namespace fkn, pg_catalog.pg_class fkc, pg_catalog.pg_attribute fka, " +
                         " pg_catalog.pg_constraint con, ";
            if (connection.haveMinimumServerVersion("8.0")) {
                sql += " pg_catalog.generate_series(1, " + getMaxIndexKeys() + ") pos(n), ";
            } else {
                sql += " information_schema._pg_keypositions() pos(n), ";
            }
            sql += " pg_catalog.pg_depend dep, pg_catalog.pg_class pkic " +
                         " WHERE pkn.oid = pkc.relnamespace AND pkc.oid = pka.attrelid AND pka.attnum = con.confkey[pos.n] AND con.confrelid = pkc.oid " +
                         " AND fkn.oid = fkc.relnamespace AND fkc.oid = fka.attrelid AND fka.attnum = con.conkey[pos.n] AND con.conrelid = fkc.oid " +
                         " AND con.contype = 'f' AND con.oid = dep.objid AND pkic.oid = dep.refobjid AND pkic.relkind = 'i' AND dep.classid = 'pg_constraint'::regclass::oid AND dep.refclassid = 'pg_class'::regclass::oid ";
            if (primarySchema != null && !"".equals(primarySchema))
            {
                sql += " AND pkn.nspname = " + escapeQuotes(primarySchema);
            }
            if (foreignSchema != null && !"".equals(foreignSchema))
            {
                sql += " AND fkn.nspname = " + escapeQuotes(foreignSchema);
            }
            if (primaryTable != null && !"".equals(primaryTable))
            {
                sql += " AND pkc.relname = " + escapeQuotes(primaryTable);
            }
            if (foreignTable != null && !"".equals(foreignTable))
            {
                sql += " AND fkc.relname = " + escapeQuotes(foreignTable);
            }

            if (primaryTable != null)
            {
                sql += " ORDER BY fkn.nspname,fkc.relname,pos.n";
            }
            else
            {
                sql += " ORDER BY pkn.nspname,pkc.relname,pos.n";
            }

            return createMetaDataStatement().executeQuery(sql);
        }
        else if (connection.haveMinimumServerVersion("7.3"))
        {
            select = "SELECT DISTINCT n1.nspname as pnspname,n2.nspname as fnspname, ";
            from = " FROM pg_catalog.pg_namespace n1 " +
                   " JOIN pg_catalog.pg_class c1 ON (c1.relnamespace = n1.oid) " +
                   " JOIN pg_catalog.pg_index i ON (c1.oid=i.indrelid) " +
                   " JOIN pg_catalog.pg_class ic ON (i.indexrelid=ic.oid) " +
                   " JOIN pg_catalog.pg_attribute a ON (ic.oid=a.attrelid), " +
                   " pg_catalog.pg_namespace n2 " +
                   " JOIN pg_catalog.pg_class c2 ON (c2.relnamespace=n2.oid), " +
                   " pg_catalog.pg_trigger t1 " +
                   " JOIN pg_catalog.pg_proc p1 ON (t1.tgfoid=p1.oid), " +
                   " pg_catalog.pg_trigger t2 " +
                   " JOIN pg_catalog.pg_proc p2 ON (t2.tgfoid=p2.oid) ";
            if (primarySchema != null && !"".equals(primarySchema))
            {
                where += " AND n1.nspname = " + escapeQuotes(primarySchema);
            }
            if (foreignSchema != null && !"".equals(foreignSchema))
            {
                where += " AND n2.nspname = " + escapeQuotes(foreignSchema);
            }
        }
        else
        {
            select = "SELECT DISTINCT NULL::text as pnspname, NULL::text as fnspname, ";
            from = " FROM pg_class c1 " +
                   " JOIN pg_index i ON (c1.oid=i.indrelid) " +
                   " JOIN pg_class ic ON (i.indexrelid=ic.oid) " +
                   " JOIN pg_attribute a ON (ic.oid=a.attrelid), " +
                   " pg_class c2, " +
                   " pg_trigger t1 " +
                   " JOIN pg_proc p1 ON (t1.tgfoid=p1.oid), " +
                   " pg_trigger t2 " +
                   " JOIN pg_proc p2 ON (t2.tgfoid=p2.oid) ";
        }

        String sql = select
                     + "c1.relname as prelname, "
                     + "c2.relname as frelname, "
                     + "t1.tgconstrname, "
                     + "a.attnum as keyseq, "
                     + "ic.relname as fkeyname, "
                     + "t1.tgdeferrable, "
                     + "t1.tginitdeferred, "
                     + "t1.tgnargs,t1.tgargs, "
                     + "p1.proname as updaterule, "
                     + "p2.proname as deleterule "
                     + from
                     + "WHERE "
                     // isolate the update rule
                     + "(t1.tgrelid=c1.oid "
                     + "AND t1.tgisconstraint "
                     + "AND t1.tgconstrrelid=c2.oid "
                     + "AND p1.proname ~ '^RI_FKey_.*_upd$') "

                     + "AND "
                     // isolate the delete rule
                     + "(t2.tgrelid=c1.oid "
                     + "AND t2.tgisconstraint "
                     + "AND t2.tgconstrrelid=c2.oid "
                     + "AND p2.proname ~ '^RI_FKey_.*_del$') "

                     + "AND i.indisprimary "
                     + where;

        if (primaryTable != null)
        {
            sql += "AND c1.relname=" + escapeQuotes(primaryTable);
        }
        if (foreignTable != null)
        {
            sql += "AND c2.relname=" + escapeQuotes(foreignTable);
        }

        sql += "ORDER BY ";

        // orderby is as follows getExported, orders by FKTABLE,
        // getImported orders by PKTABLE
        // getCrossReference orders by FKTABLE, so this should work for both,
        // since when getting crossreference, primaryTable will be defined

        if (primaryTable != null)
        {
            if (connection.haveMinimumServerVersion("7.3"))
            {
                sql += "fnspname,";
            }
            sql += "frelname";
        }
        else
        {
            if (connection.haveMinimumServerVersion("7.3"))
            {
                sql += "pnspname,";
            }
            sql += "prelname";
        }

        sql += ",keyseq";

        ResultSet rs = connection.createStatement().executeQuery(sql);

        // returns the following columns
        // and some example data with a table defined as follows

        // create table people ( id int primary key);
        // create table policy ( id int primary key);
        // create table users  ( id int primary key, people_id int references people(id), policy_id int references policy(id))

        // prelname | frelname | tgconstrname | keyseq | fkeyName  | tgdeferrable | tginitdeferred
        //   1  |  2    |   3    |    4   |  5   |   6  |  7

        // people | users    | <unnamed>   |    1   | people_pkey |   f  |  f

        // | tgnargs |        tgargs           | updaterule    | deleterule
        // | 8  |       9            |    10     |   11
        // | 6  | <unnamed>\000users\000people\000UNSPECIFIED\000people_id\000id\000 | RI_FKey_noaction_upd | RI_FKey_noaction_del

        List tuples = new ArrayList();

        while ( rs.next() )
        {
            byte tuple[][] = new byte[14][];

            tuple[1] = rs.getBytes(1); //PKTABLE_SCHEM
            tuple[5] = rs.getBytes(2); //FKTABLE_SCHEM
            tuple[2] = rs.getBytes(3); //PKTABLE_NAME
            tuple[6] = rs.getBytes(4); //FKTABLE_NAME
            String fKeyName = rs.getString(5);
            String updateRule = rs.getString(12);

            if (updateRule != null )
            {
                // Rules look like this RI_FKey_noaction_del so we want to pull out the part between the 'Key_' and the last '_' s

                String rule = updateRule.substring(8, updateRule.length() - 4);

                int action = java.sql.DatabaseMetaData.importedKeyNoAction;

                if ( rule == null || "noaction".equals(rule) )
                    action = java.sql.DatabaseMetaData.importedKeyNoAction;
                if ("cascade".equals(rule))
                    action = java.sql.DatabaseMetaData.importedKeyCascade;
                else if ("setnull".equals(rule))
                    action = java.sql.DatabaseMetaData.importedKeySetNull;
                else if ("setdefault".equals(rule))
                    action = java.sql.DatabaseMetaData.importedKeySetDefault;
                else if ("restrict".equals(rule))
                    action = java.sql.DatabaseMetaData.importedKeyRestrict;

                tuple[9] = connection.encodeString(Integer.toString(action));

            }

            String deleteRule = rs.getString(13);

            if ( deleteRule != null )
            {

                String rule = deleteRule.substring(8, deleteRule.length() - 4);

                int action = java.sql.DatabaseMetaData.importedKeyNoAction;
                if ("cascade".equals(rule))
                    action = java.sql.DatabaseMetaData.importedKeyCascade;
                else if ("setnull".equals(rule))
                    action = java.sql.DatabaseMetaData.importedKeySetNull;
                else if ("setdefault".equals(rule))
                    action = java.sql.DatabaseMetaData.importedKeySetDefault;
                else if ("restrict".equals(rule))
                    action = java.sql.DatabaseMetaData.importedKeyRestrict;
                tuple[10] = connection.encodeString(Integer.toString(action));
            }


            int keySequence = rs.getInt(6); //KEY_SEQ

            // Parse the tgargs data
            String fkeyColumn = "";
            String pkeyColumn = "";
            String fkName = "";
            // Note, I am guessing at most of this, but it should be close
            // if not, please correct
            // the keys are in pairs and start after the first four arguments
            // the arguments are seperated by \000

            String targs = rs.getString(11);

            // args look like this
            //<unnamed>\000ww\000vv\000UNSPECIFIED\000m\000a\000n\000b\000
            // we are primarily interested in the column names which are the last items in the string

            List tokens = tokenize(targs, "\\000");
            if (tokens.size() > 0)
            {
                fkName = (String)tokens.get(0);
            }

            if (fkName.startsWith("<unnamed>"))
            {
                fkName = targs;
            }

            int element = 4 + (keySequence - 1) * 2;
            if (tokens.size() > element)
            {
                fkeyColumn = (String)tokens.get(element);
            }

            element++;
            if (tokens.size() > element)
            {
                pkeyColumn = (String)tokens.get(element);
            }

            tuple[3] = connection.encodeString(pkeyColumn); //PKCOLUMN_NAME
            tuple[7] = connection.encodeString(fkeyColumn); //FKCOLUMN_NAME

            tuple[8] = rs.getBytes(6); //KEY_SEQ
            tuple[11] = connection.encodeString(fkName); //FK_NAME this will give us a unique name for the foreign key
            tuple[12] = rs.getBytes(7); //PK_NAME

            // DEFERRABILITY
            int deferrability = java.sql.DatabaseMetaData.importedKeyNotDeferrable;
            boolean deferrable = rs.getBoolean(8);
            boolean initiallyDeferred = rs.getBoolean(9);
            if (deferrable)
            {
                if (initiallyDeferred)
                    deferrability = java.sql.DatabaseMetaData.importedKeyInitiallyDeferred;
                else
                    deferrability = java.sql.DatabaseMetaData.importedKeyInitiallyImmediate;
            }
            tuple[13] = connection.encodeString(Integer.toString(deferrability));

            tuples.add(tuple);
        }

        return (ResultSet) ((BaseStatement)createMetaDataStatement()).createDriverResultSet(f, tuples);
    }

    /*
     * Get a description of the primary key columns that are
     * referenced by a table's foreign key columns (the primary keys
     * imported by a table).  They are ordered by PKTABLE_CAT,
     * PKTABLE_SCHEM, PKTABLE_NAME, and KEY_SEQ.
     *
     * <P>Each primary key column description has the following columns:
     * <OL>
     * <LI><B>PKTABLE_CAT</B> String => primary key table catalog
     *  being imported (may be null)
     * <LI><B>PKTABLE_SCHEM</B> String => primary key table schema
     *  being imported (may be null)
     * <LI><B>PKTABLE_NAME</B> String => primary key table name
     *  being imported
     * <LI><B>PKCOLUMN_NAME</B> String => primary key column name
     *  being imported
     * <LI><B>FKTABLE_CAT</B> String => foreign key table catalog (may be null)
     * <LI><B>FKTABLE_SCHEM</B> String => foreign key table schema (may be null)
     * <LI><B>FKTABLE_NAME</B> String => foreign key table name
     * <LI><B>FKCOLUMN_NAME</B> String => foreign key column name
     * <LI><B>KEY_SEQ</B> short => sequence number within foreign key
     * <LI><B>UPDATE_RULE</B> short => What happens to
     *   foreign key when primary is updated:
     *  <UL>
     *  <LI> importedKeyCascade - change imported key to agree
     *     with primary key update
     *  <LI> importedKeyRestrict - do not allow update of primary
     *     key if it has been imported
     *  <LI> importedKeySetNull - change imported key to NULL if
     *     its primary key has been updated
     *  </UL>
     * <LI><B>DELETE_RULE</B> short => What happens to
     *  the foreign key when primary is deleted.
     *  <UL>
     *  <LI> importedKeyCascade - delete rows that import a deleted key
     *  <LI> importedKeyRestrict - do not allow delete of primary
     *     key if it has been imported
     *  <LI> importedKeySetNull - change imported key to NULL if
     *     its primary key has been deleted
     *  </UL>
     * <LI><B>FK_NAME</B> String => foreign key name (may be null)
     * <LI><B>PK_NAME</B> String => primary key name (may be null)
     * </OL>
     *
     * @param catalog a catalog name; "" retrieves those without a catalog
     * @param schema a schema name pattern; "" retrieves those
     * without a schema
     * @param table a table name
     * @return ResultSet each row is a primary key column description
     * @see #getExportedKeys
     */
    public java.sql.ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException
    {
        return getImportedExportedKeys(null, null, null, catalog, schema, table);
    }

    /*
     * Get a description of a foreign key columns that reference a
     * table's primary key columns (the foreign keys exported by a
     * table). They are ordered by FKTABLE_CAT, FKTABLE_SCHEM,
     * FKTABLE_NAME, and KEY_SEQ.
     *
     * This method is currently unimplemented.
     *
     * <P>Each foreign key column description has the following columns:
     * <OL>
     * <LI><B>PKTABLE_CAT</B> String => primary key table catalog (may be null)
     * <LI><B>PKTABLE_SCHEM</B> String => primary key table schema (may be null)
     * <LI><B>PKTABLE_NAME</B> String => primary key table name
     * <LI><B>PKCOLUMN_NAME</B> String => primary key column name
     * <LI><B>FKTABLE_CAT</B> String => foreign key table catalog (may be null)
     *  being exported (may be null)
     * <LI><B>FKTABLE_SCHEM</B> String => foreign key table schema (may be null)
     *  being exported (may be null)
     * <LI><B>FKTABLE_NAME</B> String => foreign key table name
     *  being exported
     * <LI><B>FKCOLUMN_NAME</B> String => foreign key column name
     *  being exported
     * <LI><B>KEY_SEQ</B> short => sequence number within foreign key
     * <LI><B>UPDATE_RULE</B> short => What happens to
     *   foreign key when primary is updated:
     *  <UL>
     *  <LI> importedKeyCascade - change imported key to agree
     *     with primary key update
     *  <LI> importedKeyRestrict - do not allow update of primary
     *     key if it has been imported
     *  <LI> importedKeySetNull - change imported key to NULL if
     *     its primary key has been updated
     *  </UL>
     * <LI><B>DELETE_RULE</B> short => What happens to
     *  the foreign key when primary is deleted.
     *  <UL>
     *  <LI> importedKeyCascade - delete rows that import a deleted key
     *  <LI> importedKeyRestrict - do not allow delete of primary
     *     key if it has been imported
     *  <LI> importedKeySetNull - change imported key to NULL if
     *     its primary key has been deleted
     *  </UL>
     * <LI><B>FK_NAME</B> String => foreign key identifier (may be null)
     * <LI><B>PK_NAME</B> String => primary key identifier (may be null)
     * </OL>
     *
     * @param catalog a catalog name; "" retrieves those without a catalog
     * @param schema a schema name pattern; "" retrieves those
     * without a schema
     * @param table a table name
     * @return ResultSet each row is a foreign key column description
     * @see #getImportedKeys
     */
    public java.sql.ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException
    {
        return getImportedExportedKeys(catalog, schema, table, null, null, null);
    }

    /*
     * Get a description of the foreign key columns in the foreign key
     * table that reference the primary key columns of the primary key
     * table (describe how one table imports another's key.) This
     * should normally return a single foreign key/primary key pair
     * (most tables only import a foreign key from a table once.)  They
     * are ordered by FKTABLE_CAT, FKTABLE_SCHEM, FKTABLE_NAME, and
     * KEY_SEQ.
     *
     * This method is currently unimplemented.
     *
     * <P>Each foreign key column description has the following columns:
     * <OL>
     * <LI><B>PKTABLE_CAT</B> String => primary key table catalog (may be null)
     * <LI><B>PKTABLE_SCHEM</B> String => primary key table schema (may be null)
     * <LI><B>PKTABLE_NAME</B> String => primary key table name
     * <LI><B>PKCOLUMN_NAME</B> String => primary key column name
     * <LI><B>FKTABLE_CAT</B> String => foreign key table catalog (may be null)
     *  being exported (may be null)
     * <LI><B>FKTABLE_SCHEM</B> String => foreign key table schema (may be null)
     *  being exported (may be null)
     * <LI><B>FKTABLE_NAME</B> String => foreign key table name
     *  being exported
     * <LI><B>FKCOLUMN_NAME</B> String => foreign key column name
     *  being exported
     * <LI><B>KEY_SEQ</B> short => sequence number within foreign key
     * <LI><B>UPDATE_RULE</B> short => What happens to
     *   foreign key when primary is updated:
     *  <UL>
     *  <LI> importedKeyCascade - change imported key to agree
     *     with primary key update
     *  <LI> importedKeyRestrict - do not allow update of primary
     *     key if it has been imported
     *  <LI> importedKeySetNull - change imported key to NULL if
     *     its primary key has been updated
     *  </UL>
     * <LI><B>DELETE_RULE</B> short => What happens to
     *  the foreign key when primary is deleted.
     *  <UL>
     *  <LI> importedKeyCascade - delete rows that import a deleted key
     *  <LI> importedKeyRestrict - do not allow delete of primary
     *     key if it has been imported
     *  <LI> importedKeySetNull - change imported key to NULL if
     *     its primary key has been deleted
     *  </UL>
     * <LI><B>FK_NAME</B> String => foreign key identifier (may be null)
     * <LI><B>PK_NAME</B> String => primary key identifier (may be null)
     * </OL>
     *
     * @param catalog a catalog name; "" retrieves those without a catalog
     * @param schema a schema name pattern; "" retrieves those
     * without a schema
     * @param table a table name
     * @return ResultSet each row is a foreign key column description
     * @see #getImportedKeys
     */
    public java.sql.ResultSet getCrossReference(String primaryCatalog, String primarySchema, String primaryTable, String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException
    {
        return getImportedExportedKeys(primaryCatalog, primarySchema, primaryTable, foreignCatalog, foreignSchema, foreignTable);
    }

    /*
     * Get a description of all the standard SQL types supported by
     * this database. They are ordered by DATA_TYPE and then by how
     * closely the data type maps to the corresponding JDBC SQL type.
     *
     * <P>Each type description has the following columns:
     * <OL>
     * <LI><B>TYPE_NAME</B> String => Type name
     * <LI><B>DATA_TYPE</B> short => SQL data type from java.sql.Types
     * <LI><B>PRECISION</B> int => maximum precision
     * <LI><B>LITERAL_PREFIX</B> String => prefix used to quote a literal
     *  (may be null)
     * <LI><B>LITERAL_SUFFIX</B> String => suffix used to quote a literal
     (may be null)
     * <LI><B>CREATE_PARAMS</B> String => parameters used in creating
     *  the type (may be null)
     * <LI><B>NULLABLE</B> short => can you use NULL for this type?
     *  <UL>
     *  <LI> typeNoNulls - does not allow NULL values
     *  <LI> typeNullable - allows NULL values
     *  <LI> typeNullableUnknown - nullability unknown
     *  </UL>
     * <LI><B>CASE_SENSITIVE</B> boolean=> is it case sensitive?
     * <LI><B>SEARCHABLE</B> short => can you use "WHERE" based on this type:
     *  <UL>
     *  <LI> typePredNone - No support
     *  <LI> typePredChar - Only supported with WHERE .. LIKE
     *  <LI> typePredBasic - Supported except for WHERE .. LIKE
     *  <LI> typeSearchable - Supported for all WHERE ..
     *  </UL>
     * <LI><B>UNSIGNED_ATTRIBUTE</B> boolean => is it unsigned?
     * <LI><B>FIXED_PREC_SCALE</B> boolean => can it be a money value?
     * <LI><B>AUTO_INCREMENT</B> boolean => can it be used for an
     *  auto-increment value?
     * <LI><B>LOCAL_TYPE_NAME</B> String => localized version of type name
     *  (may be null)
     * <LI><B>MINIMUM_SCALE</B> short => minimum scale supported
     * <LI><B>MAXIMUM_SCALE</B> short => maximum scale supported
     * <LI><B>SQL_DATA_TYPE</B> int => unused
     * <LI><B>SQL_DATETIME_SUB</B> int => unused
     * <LI><B>NUM_PREC_RADIX</B> int => usually 2 or 10
     * </OL>
     *
     * @return ResultSet each row is a SQL type description
     */
    public java.sql.ResultSet getTypeInfo() throws SQLException
    {

        Field f[] = new Field[18];
        List v = new ArrayList();  // The new ResultSet tuple stuff

        f[0] = new Field("TYPE_NAME", Oid.VARCHAR);
        f[1] = new Field("DATA_TYPE", Oid.INT2);
        f[2] = new Field("PRECISION", Oid.INT4);
        f[3] = new Field("LITERAL_PREFIX", Oid.VARCHAR);
        f[4] = new Field("LITERAL_SUFFIX", Oid.VARCHAR);
        f[5] = new Field("CREATE_PARAMS", Oid.VARCHAR);
        f[6] = new Field("NULLABLE", Oid.INT2);
        f[7] = new Field("CASE_SENSITIVE", Oid.BOOL);
        f[8] = new Field("SEARCHABLE", Oid.INT2);
        f[9] = new Field("UNSIGNED_ATTRIBUTE", Oid.BOOL);
        f[10] = new Field("FIXED_PREC_SCALE", Oid.BOOL);
        f[11] = new Field("AUTO_INCREMENT", Oid.BOOL);
        f[12] = new Field("LOCAL_TYPE_NAME", Oid.VARCHAR);
        f[13] = new Field("MINIMUM_SCALE", Oid.INT2);
        f[14] = new Field("MAXIMUM_SCALE", Oid.INT2);
        f[15] = new Field("SQL_DATA_TYPE", Oid.INT4);
        f[16] = new Field("SQL_DATETIME_SUB", Oid.INT4);
        f[17] = new Field("NUM_PREC_RADIX", Oid.INT4);

        String sql;
        if (connection.haveMinimumServerVersion("7.3"))
        {
            sql = "SELECT t.typname,t.oid FROM pg_catalog.pg_type t"
            	+ " JOIN pg_catalog.pg_namespace n ON (t.typnamespace = n.oid) "
            	+ " WHERE n.nspname != 'pg_toast'";
        }
        else
        {
            sql = "SELECT typname,oid FROM pg_type" +
            		" WHERE NOT (typname ~ '^pg_toast_') ";
        }

        ResultSet rs = connection.createStatement().executeQuery(sql);
        // cache some results, this will keep memory useage down, and speed
        // things up a little.
        byte bZero[] = connection.encodeString("0");
        byte b10[] = connection.encodeString("10");
        byte bf[] = connection.encodeString("f");
        byte bt[] = connection.encodeString("t");
        byte bliteral[] = connection.encodeString("'");
        byte bNullable[] = connection.encodeString(Integer.toString(java.sql.DatabaseMetaData.typeNullable));
        byte bSearchable[] = connection.encodeString(Integer.toString(java.sql.DatabaseMetaData.typeSearchable));

        while (rs.next())
        {
            byte[][] tuple = new byte[18][];
            String typname = rs.getString(1);
            int typeOid = (int)rs.getLong(2);

            tuple[0] = connection.encodeString(typname);
            tuple[1] = connection.encodeString(Integer.toString(connection.getTypeInfo().getSQLType(typname)));
            tuple[2] = connection.encodeString(Integer.toString(connection.getTypeInfo().getMaximumPrecision(typeOid)));

            if (connection.getTypeInfo().requiresQuoting(typeOid))
            {
                tuple[3] = bliteral;
                tuple[4] = bliteral;
            }

            tuple[6] = bNullable; // all types can be null
            tuple[7] = connection.getTypeInfo().isCaseSensitive(typeOid) ? bt : bf;
            tuple[8] = bSearchable; // any thing can be used in the WHERE clause
            tuple[9] = connection.getTypeInfo().isSigned(typeOid) ? bf : bt;
            tuple[10] = bf; // false for now - must handle money
            tuple[11] = bf; // false - it isn't autoincrement
            tuple[13] = bZero; // min scale is zero
            // only numeric can supports a scale.
            tuple[14] = (typeOid == Oid.NUMERIC) ? connection.encodeString("1000") : bZero;

            // 12 - LOCAL_TYPE_NAME is null
            // 15 & 16 are unused so we return null
            tuple[17] = b10; // everything is base 10
            v.add(tuple);

            // add pseudo-type serial, bigserial
            if ( typname.equals("int4") )
            {
                byte[][] tuple1 = (byte[][])tuple.clone();

                tuple1[0] = connection.encodeString("serial");
                tuple1[11] = bt;
                v.add(tuple1);
            }
            else if ( typname.equals("int8") )
            {
                byte[][] tuple1 = (byte[][])tuple.clone();

                tuple1[0] = connection.encodeString("bigserial");
                tuple1[11] = bt;
                v.add(tuple1);
            }

        }
        rs.close();

        return (ResultSet) ((BaseStatement)createMetaDataStatement()).createDriverResultSet(f, v);
    }

    /*
     * Get a description of a table's indices and statistics. They are
     * ordered by NON_UNIQUE, TYPE, INDEX_NAME, and ORDINAL_POSITION.
     *
     * <P>Each index column description has the following columns:
     * <OL>
     * <LI><B>TABLE_CAT</B> String => table catalog (may be null)
     * <LI><B>TABLE_SCHEM</B> String => table schema (may be null)
     * <LI><B>TABLE_NAME</B> String => table name
     * <LI><B>NON_UNIQUE</B> boolean => Can index values be non-unique?
     *  false when TYPE is tableIndexStatistic
     * <LI><B>INDEX_QUALIFIER</B> String => index catalog (may be null);
     *  null when TYPE is tableIndexStatistic
     * <LI><B>INDEX_NAME</B> String => index name; null when TYPE is
     *  tableIndexStatistic
     * <LI><B>TYPE</B> short => index type:
     *  <UL>
     *  <LI> tableIndexStatistic - this identifies table statistics that are
     *    returned in conjuction with a table's index descriptions
     *  <LI> tableIndexClustered - this is a clustered index
     *  <LI> tableIndexHashed - this is a hashed index
     *  <LI> tableIndexOther - this is some other style of index
     *  </UL>
     * <LI><B>ORDINAL_POSITION</B> short => column sequence number
     *  within index; zero when TYPE is tableIndexStatistic
     * <LI><B>COLUMN_NAME</B> String => column name; null when TYPE is
     *  tableIndexStatistic
     * <LI><B>ASC_OR_DESC</B> String => column sort sequence, "A" => ascending
     *  "D" => descending, may be null if sort sequence is not supported;
     *  null when TYPE is tableIndexStatistic
     * <LI><B>CARDINALITY</B> int => When TYPE is tableIndexStatisic then
     *  this is the number of rows in the table; otherwise it is the
     *  number of unique values in the index.
     * <LI><B>PAGES</B> int => When TYPE is  tableIndexStatisic then
     *  this is the number of pages used for the table, otherwise it
     *  is the number of pages used for the current index.
     * <LI><B>FILTER_CONDITION</B> String => Filter condition, if any.
     *  (may be null)
     * </OL>
     *
     * @param catalog a catalog name; "" retrieves those without a catalog
     * @param schema a schema name pattern; "" retrieves those without a schema
     * @param table a table name
     * @param unique when true, return only indices for unique values;
     *    when false, return indices regardless of whether unique or not
     * @param approximate when true, result is allowed to reflect approximate
     *    or out of data values; when false, results are requested to be
     *    accurate
     * @return ResultSet each row is an index column description
     */ 
    // Implementation note: This is required for Borland's JBuilder to work
    public java.sql.ResultSet getIndexInfo(String catalog, String schema, String tableName, boolean unique, boolean approximate) throws SQLException
    {
        /* This is a complicated function because we have three possible
         * situations:
         * <= 7.2 no schemas, single column functional index
         * 7.3 schemas, single column functional index
         * >= 7.4 schemas, multi-column expressional index
         * >= 8.3 supports ASC/DESC column info
         * >= 9.0 no longer renames index columns on a table column rename,
         *        so we must look at the table attribute names
         *
         * with the single column functional index we need an extra
         * join to the table's pg_attribute data to get the column
         * the function operates on.
         */
        String sql;
        if (connection.haveMinimumServerVersion("8.3"))
        {
            sql = "SELECT NULL AS TABLE_CAT, n.nspname AS TABLE_SCHEM, "
                + "  ct.relname AS TABLE_NAME, NOT i.indisunique AS NON_UNIQUE, "
                + "  NULL AS INDEX_QUALIFIER, ci.relname AS INDEX_NAME, "
                + "  CASE i.indisclustered "
                + "    WHEN true THEN " + java.sql.DatabaseMetaData.tableIndexClustered
                + "    ELSE CASE am.amname "
                + "      WHEN 'hash' THEN " + java.sql.DatabaseMetaData.tableIndexHashed
                + "      ELSE " + java.sql.DatabaseMetaData.tableIndexOther
                + "    END "
                + "  END AS TYPE, "
                + "  (i.keys).n AS ORDINAL_POSITION, "
                + "  pg_catalog.pg_get_indexdef(ci.oid, (i.keys).n, false) AS COLUMN_NAME, "
                + "  CASE am.amcanorder "
                + "    WHEN true THEN CASE i.indoption[(i.keys).n - 1] & 1 "
                + "      WHEN 1 THEN 'D' "
                + "      ELSE 'A' "
                + "    END "
                + "    ELSE NULL "
                + "  END AS ASC_OR_DESC, "
                + "  ci.reltuples AS CARDINALITY, "
                + "  ci.relpages AS PAGES, "
                + "  pg_catalog.pg_get_expr(i.indpred, i.indrelid) AS FILTER_CONDITION "
                + "FROM pg_catalog.pg_class ct "
                + "  JOIN pg_catalog.pg_namespace n ON (ct.relnamespace = n.oid) "
                + "  JOIN (SELECT i.indexrelid, i.indrelid, i.indoption, "
                + "          i.indisunique, i.indisclustered, i.indpred, "
                + "          i.indexprs, "
                + "          information_schema._pg_expandarray(i.indkey) AS keys "
                + "        FROM pg_catalog.pg_index i) i "
                + "    ON (ct.oid = i.indrelid) "
                + "  JOIN pg_catalog.pg_class ci ON (ci.oid = i.indexrelid) "
                + "  JOIN pg_catalog.pg_am am ON (ci.relam = am.oid) "
                + "WHERE true ";

            if (schema != null && !"".equals(schema))
            {
                sql += " AND n.nspname = " + escapeQuotes(schema);
            }
        } else {
            String select;
            String from;
            String where = "";

            if (connection.haveMinimumServerVersion("7.3"))
            {
                select = "SELECT NULL AS TABLE_CAT, n.nspname AS TABLE_SCHEM, ";
                from = " FROM pg_catalog.pg_namespace n, pg_catalog.pg_class ct, pg_catalog.pg_class ci, pg_catalog.pg_attribute a, pg_catalog.pg_am am ";
                where = " AND n.oid = ct.relnamespace ";

                if (!connection.haveMinimumServerVersion("7.4")) {
                    from += ", pg_catalog.pg_attribute ai, pg_catalog.pg_index i LEFT JOIN pg_catalog.pg_proc ip ON (i.indproc = ip.oid) ";
                    where += " AND ai.attnum = i.indkey[0] AND ai.attrelid = ct.oid ";
                } else {
                    from += ", pg_catalog.pg_index i ";
                }
                if (schema != null && ! "".equals(schema))
                {
                    where += " AND n.nspname = " + escapeQuotes(schema);
                }
            }
            else
            {
                select = "SELECT NULL AS TABLE_CAT, NULL AS TABLE_SCHEM, ";
                from = " FROM pg_class ct, pg_class ci, pg_attribute a, pg_am am, pg_attribute ai, pg_index i LEFT JOIN pg_proc ip ON (i.indproc = ip.oid) ";
                where = " AND ai.attnum = i.indkey[0] AND ai.attrelid = ct.oid ";
            }

            sql = select +
                     " ct.relname AS TABLE_NAME, NOT i.indisunique AS NON_UNIQUE, NULL AS INDEX_QUALIFIER, ci.relname AS INDEX_NAME, " +
                     " CASE i.indisclustered " +
                     " WHEN true THEN " + java.sql.DatabaseMetaData.tableIndexClustered +
                     " ELSE CASE am.amname " +
                     " WHEN 'hash' THEN " + java.sql.DatabaseMetaData.tableIndexHashed +
                     " ELSE " + java.sql.DatabaseMetaData.tableIndexOther +
                     " END " +
                     " END AS TYPE, " +
                     " a.attnum AS ORDINAL_POSITION, ";

            if( connection.haveMinimumServerVersion("7.4"))
            {
                sql += " CASE WHEN i.indexprs IS NULL THEN a.attname ELSE pg_catalog.pg_get_indexdef(ci.oid,a.attnum,false) END AS COLUMN_NAME, ";
            }
            else
            {
                sql += " CASE i.indproc WHEN 0 THEN a.attname ELSE ip.proname || '(' || ai.attname || ')' END AS COLUMN_NAME, ";
            }


            sql += " NULL AS ASC_OR_DESC, " +
                     " ci.reltuples AS CARDINALITY, " +
                     " ci.relpages AS PAGES, ";

            if( connection.haveMinimumServerVersion("7.3"))
            {
                sql += " pg_catalog.pg_get_expr(i.indpred, i.indrelid) AS FILTER_CONDITION ";
            }
            else if( connection.haveMinimumServerVersion("7.2"))
            {
                sql += " pg_get_expr(i.indpred, i.indrelid) AS FILTER_CONDITION ";
            }
            else
            {
                sql += " NULL AS FILTER_CONDITION ";
            }

            sql += from +
                     " WHERE ct.oid=i.indrelid AND ci.oid=i.indexrelid AND a.attrelid=ci.oid AND ci.relam=am.oid " +
                     where;
        }

        sql += " AND ct.relname = " + escapeQuotes(tableName);

        if (unique)
        {
            sql += " AND i.indisunique ";
        }
        sql += " ORDER BY NON_UNIQUE, TYPE, INDEX_NAME, ORDINAL_POSITION ";
        return createMetaDataStatement().executeQuery(sql);
    }

    /**
     * Tokenize based on words not on single characters.
     */
    private static List tokenize(String input, String delimiter) {
        List result = new ArrayList();
        int start = 0;
        int end = input.length();
        int delimiterSize = delimiter.length();

        while (start < end)
        {
            int delimiterIndex = input.indexOf(delimiter, start);
            if (delimiterIndex < 0)
            {
                result.add(input.substring(start));
                break;
            }
            else
            {
                String token = input.substring(start, delimiterIndex);
                result.add(token);
                start = delimiterIndex + delimiterSize;
            }
        }
        return result;
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
                     + "CASE WHEN t.typtype='c' then " + java.sql.Types.STRUCT + " else " + java.sql.Types.DISTINCT + " end as data_type, pg_catalog.obj_description(t.oid, 'pg_type')  "
                     + "as remarks, CASE WHEN t.typtype = 'd' then  (select CASE";

        for (Iterator i = connection.getTypeInfo().getPGTypeNamesWithSQLTypes(); i.hasNext();) {
            String pgType = (String)i.next();
            int sqlType = connection.getTypeInfo().getSQLType(pgType);
            sql += " when typname = " + escapeQuotes(pgType) + " then " + sqlType;
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
        }
        else
        {
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
                    schemaPattern = typeNamePattern.substring(firstQualifier + 1, secondQualifier);
                }
                else
                {
                    // we just have a schema.typename
                    schemaPattern = typeNamePattern.substring(0, firstQualifier);
                }
                // strip out just the typeName
                typeNamePattern = typeNamePattern.substring(secondQualifier + 1);
            }
            toAdd += " and t.typname like " + escapeQuotes(typeNamePattern);
        }

        // schemaPattern may have been modified above
        if ( schemaPattern != null)
        {
            toAdd += " and n.nspname like " + escapeQuotes(schemaPattern);
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
