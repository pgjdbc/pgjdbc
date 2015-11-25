/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc3;

import junit.framework.TestCase;
import org.postgresql.test.TestUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class LargeDdlStatementHangTest extends TestCase {

    private Connection _conn;
    String migration;

    public LargeDdlStatementHangTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        File file = new File("./org/postgresql/test/sql/hanging_migration_1.sql");
        System.out.println(file.getAbsoluteFile());
        FileInputStream fis = new FileInputStream(file);
        byte[] content = new byte[(int)file.length()];
        int position = fis.read(content, 0, content.length);
        for (int i = position; i < file.length(); i++) {
            content[i] = (byte)fis.read();
        }
        migration = new String(content, "UTF-8");
        _conn = TestUtil.openDB();
    }

    protected void tearDown() throws SQLException {
        _conn.createStatement().execute("DROP SCHEMA IF EXITS \"AggregateOneEntityBinary\" CASCADE;"
                + "DROP SCHEMA IF EXISTS \"-NGS-\"");
        TestUtil.closeDB(_conn);
    }

    public void tryExecuteLargeScript() throws SQLException, IOException {
		Statement stmt = _conn.createStatement();
		stmt.execute(migration);
		stmt.close();
	}
	
}