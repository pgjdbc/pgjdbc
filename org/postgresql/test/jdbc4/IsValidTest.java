package org.postgresql.test.jdbc4;

import java.sql.*;
import junit.framework.TestCase;

import org.postgresql.test.TestUtil;

public class IsValidTest  extends TestCase {

    public void testIsValid() throws Exception {
        Connection _conn = TestUtil.openDB();
        try {
            assertTrue(_conn.isValid(0));
        } finally {
            TestUtil.closeDB(_conn);
        }
        assertFalse(_conn.isValid(0));
    }

}
