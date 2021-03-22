import org.junit.Assert
import org.postgresql.PGProperty
import org.postgresql.jdbc.GSSEncMode


import java.sql.Connection

@groovy.transform.CompileStatic
class TestPostgres {

    public static void main(String[] args) {
        new TestPostgres().testKerberos()
    }

    public void testKerberos() {
        String host='127.0.0.1'
        String superUser = System.getProperty("user.name");
        String superPass = 'test'
        PgJDBC pgJDBC

        Kerberos kerberos = new Kerberos()
        kerberos.startKerberos()
        Map environment = System.getenv()
        // +2 for the kerberos environment
        String [] currentEnvironment = new String[environment.size() + 2]
        environment.eachWithIndex {e,i->
            currentEnvironment[i] = "${e.key}=${e.value}"
        }
        currentEnvironment[environment.size()] = kerberos.env[0]
        currentEnvironment[environment.size() + 1] = kerberos.env[1]

        Postgres postgres = new Postgres('/usr/lib/postgresql/12/bin/', '/tmp/pggss')
        /*
        make sure we can connect
         */
        postgres.writePgHBA("host    all             all             127.0.0.1/32            trust")
        if (postgres.waitForHBA(5000) ) {
            Process p = postgres.startPostgres(currentEnvironment)
            sleep(2000)
            pgJDBC = new PgJDBC(host, postgres.getPort());
            pgJDBC.addProperty(PGProperty.GSS_ENC_MODE, GSSEncMode.DISABLE.value)
            pgJDBC.createUser(superUser, superPass, 'test1', 'secret1')
            pgJDBC.createDatabase(superUser, superPass, 'test1', 'test')
            postgres.enableGSS('127.0.0.1', 'hostgssenc', 'map=mymap')
            postgres.enableMyMap('EXAMPLE.COM')
            postgres.setKeyTabLocation(kerberos.getKeytab())
            postgres.reload()
            pgJDBC.addProperty(PGProperty.GSS_ENC_MODE, GSSEncMode.REQUIRE.value)
            pgJDBC.addProperty(PGProperty.JAAS_LOGIN, true)
            pgJDBC.addProperty(PGProperty.JAAS_APPLICATION_NAME, "pgjdbc")

            try {
                Connection connection;
                try {
                    connection = pgJDBC.tryConnect('test', 'auth-test-localhost.postgresql.example.com', postgres.getPort(), 'test1', 'secret1')
                    if (pgJDBC.select(connection, "SELECT gss_authenticated AND encrypted from pg_stat_gssapi where pid = pg_backend_pid()")) {
                        System.err.println 'GSS authenticated and encrypted Connection succeeded'
                    } else {
                        Assert.fail 'GSS authenticated and encrypted Connection failed'
                    }
                } catch( Exception ex ) {
                    System.err.println "PG HBA.conf: \n ${postgres.readPgHBA()}"
                    ex.printStackTrace()
                } finally {
                    connection?.close()

                }

                postgres.enableGSS('127.0.0.1', 'hostnogssenc', 'map=mymap')
                postgres.reload()
                pgJDBC.addProperty(PGProperty.GSS_ENC_MODE, GSSEncMode.DISABLE.value)

                try {
                    connection = pgJDBC.tryConnect('test', 'auth-test-localhost.postgresql.example.com', postgres.getPort(), 'test1', 'secret1')

                    if (pgJDBC.select(connection, "SELECT gss_authenticated AND not encrypted from pg_stat_gssapi where pid = pg_backend_pid()")) {
                        System.err.println 'GSS authenticated and not encrypted Connection succeeded'
                    } else {
                        Assert.fail 'GSS authenticated and not encrypted Connection failed'
                    }
                }catch( Exception ex ) {
                    System.err.println "PG HBA.conf: \n ${postgres.readPgHBA()}"
                    ex.printStackTrace()


                } finally {
                    if (!connection) {
                        System.err.println "PG HBA.conf: \n ${postgres.readPgHBA()}"
                    }
                    connection?.close()
                }
            } finally {
                !p.destroy()
                !kerberos.destroy()
            }
        } else {
            System.err.println("Unable to create pg_hba.conf")
            System.exit(-1)
        }

    }
}
