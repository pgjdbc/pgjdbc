package org.postgresql.jdbc;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class TypeInfoCacheTest {
  @Test
  @Disabled("This is intended to be run manually")
  public void generateBaseTypes() throws SQLException {
    try(Connection con = TestUtil.openDB();
        PreparedStatement ps = con.prepareStatement(
            "with pgjdbc_mapping(typname, sql_type_name, java_class) as (\n"
                + "    select *\n"
                + "    from (\n"
                + "    values\n"
                + "         ('int2', 'SMALLINT', 'Integer'),\n"
                + "         ('int4', 'INTEGER', 'Integer'),\n"
                + "         ('oid', 'BIGINT', 'Long'),\n"
                + "         ('int8', 'BIGINT', 'Long'),\n"
                + "         ('money', 'DOUBLE', 'org.postgresql.util.PGmoney' /* or Double? */),\n"
                + "         ('numeric', 'NUMERIC', 'BigDecimal'),\n"
                + "         ('float4', 'REAL', 'Float'),\n"
                + "         ('float8', 'DOUBLE', 'Double'),\n"
                + "         ('har', 'CHAR', 'String'),\n"
                + "         ('bpchar', 'CHAR', 'String'),\n"
                + "         ('varchar', 'VARCHAR', 'String'),\n"
                + "         ('varbit', 'OTHER', 'String'),\n"
                + "         ('text', 'VARCHAR', 'String'),\n"
                + "         ('name', 'VARCHAR', 'String'),\n"
                + "         ('bytea', 'BINARY', 'byte[]'),\n"
                + "         ('bool', 'BIT', 'Boolean'),\n"
                + "         ('bit', 'BIT', 'Boolean'),\n"
                + "         ('date', 'DATE', 'java.sql.Date'),\n"
                + "         ('time', 'TIME', 'java.sql.Time'),\n"
                + "         ('timetz', 'TIME', 'java.sql.Time'),\n"
                + "         ('timestamp', 'TIMESTAMP', 'java.sql.Timestamp'),\n"
                + "         ('timestamptz', 'TIMESTAMP', 'java.sql.Timestamp'),\n"
                + "         ('refcursor', 'REF_CURSOR', 'java.sql.ResultSet'),\n"
                + "         ('json', 'OTHER', 'org.postgresql.util.PGobject'),\n"
                + "         ('box', 'OTHER', 'org.postgresql.geometric.PGbox'),\n"
                + "         ('circle', 'OTHER', 'org.postgresql.geometric.PGcircle'),\n"
                + "         ('line', 'OTHER', 'org.postgresql.geometric.PGline'),\n"
                + "         ('lseg', 'OTHER', 'org.postgresql.geometric.PGlseg'),\n"
                + "         ('path', 'OTHER', 'org.postgresql.geometric.PGpath'),\n"
                + "         ('point', 'OTHER', 'org.postgresql.geometric.PGpoint'),\n"
                + "         ('polygon', 'OTHER', 'org.postgresql.geometric.PGpolygon'),\n"
                + "         ('interval', 'OTHER', 'org.postgresql.util.PGInterval'),\n"
                + "         ('hstore', 'OTHER', 'java.util.Map'),\n"
                + "         ('uuid', 'OTHER', 'java.util.UUID'),\n"
                + "         ('xml', 'SQLXML', 'java.sql.SQLXML')\n"
                + "             ) as pgjdbc_mapping(typname, sql_type_name, java_class)\n"
                + ")\n"
                + "select 'new PgType(new ObjectName(\"'||nspname||'\", \"'||typname||'\")'\n"
                + "       ||', \"'||pg_catalog.format_type(oid, null)||'\"'\n"
                + "       ||', \"'||oid_constant_name||', Types.'||sql_type_name||', '||java_class||'.class'\n"
                + "       ||', '||subelement_constant_name\n"
                + "       ||', '||array_oid_constant_name||'),'\n"
                + " from (\n"
                + "  select ns.nspname\n"
                + "       , x.oid\n"
                + "       , x.typname, x.sql_type_name, x.java_class\n"
                + "       , x.oid_constant_name\n"
                + "       , x.subelement_constant_name\n"
                + "       , x.array_oid_constant_name\n"
                + "  , element_type.typelem\n"
                + "    from pgjdbc_mapping pgjdbc join pg_catalog.pg_type element_type using(typname)\n"
                + "    left join pg_catalog.pg_type subelement_type on (subelement_type.oid = element_type.typelem and subelement_type.oid <> 0)\n"
                + "    left join pg_catalog.pg_type array_type on (array_type.oid = element_type.typarray and array_type.oid <> 0)\n"
                + "    cross join lateral (\n"
                + "        select element_type.typname element_typname, 1 ordr\n"
                + "             , element_type.typname, pgjdbc.sql_type_name, pgjdbc.java_class\n"
                + "             , element_type.oid\n"
                + "             , element_type.typnamespace\n"
                + "             , 'Oid.'||upper(element_type.typname) oid_constant_name\n"
                + "             , 'Oid.'||coalesce(upper(subelement_type.typname), 'UNSPECIFIED') subelement_constant_name\n"
                + "             , 'Oid.'||upper(element_type.typname)||'_ARRAY' array_oid_constant_name\n"
                + "        union all\n"
                + "        select element_type.typname element_typname, 2 ordr\n"
                + "             , array_type.typname, 'ARRAY', 'Array'\n"
                + "             , array_type.oid\n"
                + "             , element_type.typnamespace\n"
                + "             , 'Oid.'||upper(element_type.typname)||'_ARRAY' oid_constant_name\n"
                + "             , 'Oid.'||upper(element_type.typname) subelement_constant_name\n"
                + "             , case when array_type.typarray = 0 then 'Oid.UNSPECIFIED' else array_type.typarray::text end array_oid_constant_name\n"
                + "    ) as x\n"
                + "    join pg_catalog.pg_namespace ns on (ns.oid = x.typnamespace)\n"
                + "    order by element_typname, ordr\n"
                + "  ) mapping");
        ResultSet rs = ps.executeQuery()) {
      while(rs.next()) {
        System.out.println(rs.getString(1));
      }
    }
  }
}
