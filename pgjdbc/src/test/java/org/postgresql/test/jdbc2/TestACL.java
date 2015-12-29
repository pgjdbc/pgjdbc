package org.postgresql.test.jdbc2;

import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PgDatabaseMetaData;

import junit.framework.TestCase;

public class TestACL extends TestCase {

  @Override
  protected void setUp() throws Exception {
    // TODO Auto-generated method stub
    super.setUp();
  }

  @Override
  protected void tearDown() throws Exception {
    // TODO Auto-generated method stub
    super.tearDown();
  }

  public void testParseACL() {
    PgConnection _a = null;
    PgDatabaseMetaData a = new PgDatabaseMetaData(_a) {
    };
    a.parseACL("{jurka=arwdRxt/jurka,permuser=rw*/jurka}", "jurka");
    a.parseACL("{jurka=a*r*w*d*R*x*t*/jurka,permuser=rw*/jurka}", "jurka");
    a.parseACL("{=,jurka=arwdRxt,permuser=rw}", "jurka");
    a.parseACL("{jurka=arwdRxt/jurka,permuser=rw*/jurka,grantuser=w/permuser}", "jurka");
    a.parseACL("{jurka=a*r*w*d*R*x*t*/jurka,permuser=rw*/jurka,grantuser=w/permuser}", "jurka");
    a.parseACL(
        "{jurka=arwdRxt/jurka,permuser=rw*/jurka,grantuser=w/permuser,\"group permgroup=a/jurka\"}",
        "jurka");
    a.parseACL(
        "{jurka=a*r*w*d*R*x*t*/jurka,permuser=rw*/jurka,grantuser=w/permuser,\"group permgroup=a/jurka\"}",
        "jurka");
  }
}
