package org.postgresql.test;

import java.util.Properties;

import org.postgresql.test.jdbc2.CursorFetchTest;

public class CursorFetchBinaryTest extends CursorFetchTest {
    public CursorFetchBinaryTest(String name)
    {
        super(name);
    }

    @Override
    protected void updateProperties(Properties props)
    {
        forceBinary(props);
    }
}
