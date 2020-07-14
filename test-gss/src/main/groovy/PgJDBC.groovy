import org.postgresql.PGProperty

import javax.xml.transform.stream.StreamResult
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

@groovy.transform.CompileStatic
public class PgJDBC {

    String host
    int port

    Properties properties = new Properties()

    public PgJDBC() {

    }
    public PgJDBC(String host, int port) {
        this.host = host
        this.port = port
    }

    public void addProperty(PGProperty pgProperty, Object value) {
        if (value instanceof String) {
            pgProperty.set(properties, (String)value)
        }else if (value instanceof Boolean ) {
            pgProperty.set(properties, (Boolean)value)
        }

    }

    public void tryConnect(String dataBase, String host, int port, String user, String password) {
        String url = "jdbc:postgresql://$host:$port/$dataBase"
        PGProperty.USER.set(properties,user)
        PGProperty.PASSWORD.set(properties,password)
        Connection conn = DriverManager.getConnection(url,properties)
        conn.close()
    }

    public void createUser(String superuser, String superPass, String user, String password) {
        String url = "jdbc:postgresql://$host:$port/postgres"
        PGProperty.USER.set(properties, superuser)
        PGProperty.PASSWORD.set(properties,superPass)
        Connection conn = DriverManager.getConnection(url,properties)
        ResultSet rs = conn.createStatement().executeQuery("select * from pg_user where usename = '$user'")
        if (!rs.next()) {
            conn.createStatement().execute("create user $user with password '$password'")
        }
        conn.close()
    }

    public void createDatabase(String superuser, String superPass, String owner, String database) {
        String url = "jdbc:postgresql://$host:$port/postgres"
        PGProperty.USER.set(properties, superuser)
        PGProperty.PASSWORD.set(properties,superPass)
        Connection conn = DriverManager.getConnection(url,properties)
        ResultSet rs = conn.createStatement().executeQuery("select * from pg_database where datname = '$database'")
        if (!rs.next()) {
            conn.createStatement().execute("create database $database owner '$owner'")
        }
        conn.close()
    }
    public static void main(String[] args ){
        PgJDBC pgJDBC = new PgJDBC()
        pgJDBC.tryConnect("test", "localhost", 5432, "test", "test")
    }
}
