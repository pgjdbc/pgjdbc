@groovy.transform.CompileStatic
class Kerberos {
    String krb5BinDir
    String krb5SbinDir
    String host     = 'auth-test-localhost.postgresql.example.com'
    String hostaddr = '127.0.0.1'
    String realm    = 'EXAMPLE.COM'
    String krb5Conf
    String kdcConf
    String kdcCache
    String krb5Log
    String kdcLog
    String krb5Config  = 'krb5-config'
    String kinit       = 'kinit'
    String kdb5Util    = 'kdb5_util'
    String kadminLocal = 'kadmin.local'
    String krb5kdc     = 'krb5kdc'
    String[] env
    // find a free port
    int kdcPort
    String kdcDataDir
    String kdcPidfile
    String keytab
    String krb5Version = '1.16'
    Process krb5Process


    public String[] getEnvironment() {
        return env
    }
    public void getBinDir() {
        String osName = "uname -s".execute().text

        if (osName.toLowerCase() =~ 'darwin')
        {
            krb5BinDir  = '/usr/local/opt/krb5/bin'
            krb5SbinDir = '/usr/local/opt/krb5/sbin'
        }
        else if (osName.toLowerCase() =~ 'freebsd')
        {
            krb5BinDir  = '/usr/local/bin'
            krb5SbinDir = '/usr/local/sbin'
        }
        else if (osName.toLowerCase() =~ 'linux')
        {
            krb5BinDir  = '/usr/bin'
            krb5SbinDir = '/usr/sbin'
        }
    }

    public void setupKerberos(String testLib) {

        if ( krb5BinDir && new File(krb5BinDir).exists() )
        {
            krb5Config = "$krb5BinDir/$krb5Config"
            kinit      = "$krb5BinDir/$kinit"
        }
        if ( krb5SbinDir && new File(krb5SbinDir).exists() )
        {
            kdb5Util    = "$krb5SbinDir/$kdb5Util"
            kadminLocal = "$krb5SbinDir/$kadminLocal"
            krb5kdc     = "$krb5SbinDir/$krb5kdc"
        }



        krb5Conf   = "$testLib/tmp_check/krb5.conf"
        kdcConf    = "$testLib/tmp_check/kdc.conf"
        kdcCache   = "$testLib/tmp_check/krb5cc"
        krb5Log    = "$testLib/tmp_check/log/krb5libs.log"
        kdcLog     = "$testLib/tmp_check/log/krb5kdc.log"
        // find a free port
        kdcPort    = Util.findPort()
        kdcDataDir = "$testLib/tmp_check/krb5kdc"
        kdcPidfile = "$testLib/tmp_check/krb5kdc.pid"
        keytab     = "$testLib/tmp_check/krb5.keytab"
        krb5Version = '1.16'

        println "setting up Kerberos"

        String tmp = "$krb5Config --version".execute().text
        if (tmp.startsWith('heimdal')) {
            println "Heimdal not supported"
        }
        tmp =~ 'Kerberos 5 release ([0-9]+\\.[0-9]+)'
        new File("$testLib/tmp_check").mkdir()
        new File("$testLib/tmp_check/log").mkdir()

        new File(krb5Conf).with { f->
            if (f.exists()) {
                f.delete()
            }
            f.createNewFile()

        }
        new File(kdcConf).with { f->
            if (f.exists()) {
                f.delete()
            }
            f.createNewFile()

        }
        Util.appendToFile( krb5Conf,
"""[logging]
default = FILE:$krb5Log
kdc = FILE:$kdcLog

[libdefaults]
default_realm = $realm
canonicalize = true


[realms]
    $realm = {
    kdc = $hostaddr:$kdcPort
 }
"""
        )

        Util.appendToFile( kdcConf,"[kdcdefaults]")


        // For new-enough versions of krb5, use the _listen settings rather
        // than the _ports settings so that we can bind to localhost only.

        if (krb5Version >= '1.15')
        {
            Util.appendToFile(kdcConf,
"""
kdc_listen = $hostaddr:$kdcPort
kdc_tcp_listen = $hostaddr:$kdcPort
"""                     )
        }
        else
        {
            Util.appendToFile( kdcConf,
"""
kdc_ports = $kdcPort
kdc_tcp_ports = $kdcPort
"""                     )
        }
        Util.appendToFile(
                kdcConf,
"""
[realms]
$realm = {
    database_name = $kdcDataDir/principal
    admin_keytab = FILE:$kdcDataDir/kadm5.keytab
    acl_file = $kdcDataDir/kadm5.acl
    key_stash_file = $kdcDataDir/_k5.$realm
}"""                )

    }
    public Process runKerberos() {

        mkdir(kdcDataDir)

        env = ["KRB5_CONFIG=$krb5Conf", "KRB5_KDC_PROFILE=$kdcConf", "KRB5CCNAME=$kdcCache"]

        String service_principal = "postgres/$host" // should really get postgres from configuration file

        Process p = "$kdb5Util create -s -P secret0".execute(env,null)
        p.waitForProcessOutput(System.out, System.err)

        String test1Password = 'secret1'

        p = ["$kadminLocal", "-q", "addprinc -pw $test1Password test1" ].execute(env, null)
        p.waitForProcessOutput(System.out, System.err)

        p = ["$kadminLocal", "-q", "addprinc -randkey $service_principal"].execute(env,null)
        p.waitForProcessOutput(System.out, System.err)

        p = ["$kadminLocal", "-q", "ktadd -k $keytab $service_principal"].execute(env,null)
        p.waitForProcessOutput(System.out, System.err)

        new Thread() {
            @Override
            void run() {
                krb5Process = "$krb5kdc -P $kdcPidfile".execute(env,null)
                krb5Process.waitForProcessOutput(System.out, System.err)
            }
        }.start()
        while (krb5Process == null){
            Thread.sleep(100)
        }
        p = "$kinit test1".execute(env, null)
        p.withWriter { w ->
            w.println(test1Password)
        }
        p.waitForProcessOutput(System.out, System.err)

        return krb5Process

    }


    public void mkdir(String newDir){

        new File(newDir).with {dir->
            if ( dir.exists() ){
                dir.deleteDir()
            }
            dir.mkdir()
            dir.deleteOnExit()
        }
    }
    public void destroy() {
        new File(kdcPidfile).with {f->
            Process p = "kill -TERM ${f.text}".execute()
            p.waitForProcessOutput(System.out, System.err)
        }
        new File(keytab).with {k->
            if (k.exists()) {
                k.delete()
            }
        }
    }

    public Process startKerberos() {
        getBinDir()
        String curDir = new File('.').getAbsolutePath()
        setupKerberos(curDir)
        // tell java where we want it to look
        System.setProperty("java.security.krb5.conf", krb5Conf)
        runKerberos()
    }

    public void showConfig() {
        println "krb config file: "
        new File(krb5Conf).readLines().each {l->
            println l
        }
    }

    public void showKdc() {
        println "kdc config file: "
        new File(kdcConf).readLines().each {l->
            println l
        }
    }
    public static void main(String []args) {
        Kerberos kerberos = new Kerberos()
        kerberos.getBinDir()
        String curDir = new File('.').getAbsolutePath()
        kerberos.setupKerberos(curDir)
        kerberos.runKerberos()
        kerberos.destroy();
        println(kerberos.krb5BinDir)
        println(kerberos.krb5SbinDir)
    }
}
