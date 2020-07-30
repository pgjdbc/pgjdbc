@groovy.transform.CompileStatic
class Postgres {
    String binPath
    String dataPath
    String hostName = '127.0.0.1'
    int port

    public Postgres() {
        setupPaths('/usr/local/pgsql/12/bin/','/tmp/pgdata11')
        initDB()
    }
    public Postgres(String binDir, String dataDir) {
        setupPaths(binDir, dataDir)
        initDB()
    }

    public void setupPaths(String binPath = '/usr/local/psql/bin', String dataPath = '/tmp/pgdata' ) {
        this.binPath = binPath
        this.dataPath = dataPath
    }

    public void initDB() {
        new File(dataPath).with { f->
            if (!f.exists()) {
                println "Initializing db at $dataPath"
                Process p = "$binPath/initdb --auth=trust -D $dataPath".execute()
                p.waitForProcessOutput(System.out, System.err)
            }
        }
    }

    public Process runPostgres(String[] environment ) {
        // -i to enable tcp connections since java needs them anyway
        println "executing postgres datapath: $dataPath, host: $hostName, port: $port"
        String exec = "$binPath/postgres -h $hostName -k /tmp -p $port -i -D $dataPath"
        Process p

        new Thread() {
            @Override
            void run() {
                println exec
                p = exec.execute(environment, null)
                p.waitForProcessOutput(System.out, System.err)

            }
        }.start()
        while (p == null){
            Thread.sleep(100)
        }
        return p
    }
    public void writePgIdent(String text) {
        Util.appendToFile("$dataPath/pg_ident.conf", text, true)
    }

    public boolean waitForHBA( int milliseconds ) {
        long now = System.nanoTime();
        while ( System.nanoTime() < (now + (milliseconds * 1E6)) ) {
            if (new File("$dataPath/pg_hba.conf").exists()) {
                return true
            }
        }
        return false
    }
    public void writePgHBA(String text) {
        Util.appendToFile("$dataPath/pg_hba.conf", text, true)
    }
    public String readPgHBA() {
        Util.readFile("$dataPath/pg_hba.conf")
    }
    public void writePgConf(String text) {
        Util.appendToFile("$dataPath/postgresql.conf", text, false)
    }
    public void setKeyTabLocation(String location) {
        writePgConf("krb_server_keyfile = '$location'")
    }
    public void enableGSS(String hostAddress, String mode, String options) {
        writePgHBA("$mode all all $hostAddress/32 gss map=mymap")
    }
    public void enableMyMap(String realm) {
        writePgIdent("mymap  /^(.*)@$realm\$  \\1");
    }

    public Process startPostgres(String []environment) {
        port= Util.findPort()
        // postgres.writePgHBA("host all all $postgres.hostName/32 gss map=mymap")
        runPostgres(environment)
    }

    public void reload() {
        Process p = "$binPath/pg_ctl -D $dataPath reload".execute()
        p.waitForProcessOutput(System.out, System.err)
    }

    public static void main( String[] args) {
        Postgres postgres = new Postgres()
        postgres.setupPaths('/usr/local/pgsql/12/bin/','/tmp/pgdata11')
        postgres.initDB()
        postgres.port= Util.findPort()
        // postgres.writePgHBA("host all all $postgres.hostName/32 gss map=mymap")
        Process p = postgres.runPostgres()
        p.destroy()

        println("Process is ${p.isAlive()?'running':'stopped'}" )
    }
}
