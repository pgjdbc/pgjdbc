/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.postgresql.test.TestUtil.closeDB;

import org.postgresql.PGProperty;
import org.postgresql.hostchooser.CandidateHost;
import org.postgresql.hostchooser.HostChooser;
import org.postgresql.hostchooser.HostRequirement;
import org.postgresql.test.TestUtil;
import org.postgresql.util.HostSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Properties;

class HostChooserPluginTest {

    private static final String user = TestUtil.getUser();
    private static final String password = TestUtil.getPassword();
    private static final String host1 = TestUtil.getServer();
    private static final int port1 = TestUtil.getPort();
    private static final String host2 = getSecondaryServer();
    private static final int port2 = getSecondaryPort();

    private String host1Ip;
    private String host2Ip;
    private Connection con;

    @BeforeAll
    static void setUpClass() {
        assumeTrue(isReplicationInstanceAvailable());
    }

    @BeforeEach
    void setUp() throws Exception {

        con = TestUtil.openDB();
        this.host1Ip = getRemoteHostSpec();
        closeDB(con);

        con = openSecondaryDB();
        this.host2Ip = getRemoteHostSpec();
        closeDB(con);
    }

    public static class DummyHostChooserPlugin implements HostChooser{

        String url;
        Properties props;
        HostRequirement targetServerType;
        int firstHostConnectionCount = 10;

        @Override
        public Iterator<CandidateHost> iterator() {
            ArrayList<CandidateHost> host = new ArrayList<CandidateHost>();
            if (firstHostConnectionCount > 0) {
                host.add(new CandidateHost(new HostSpec(host1, port1), this.targetServerType));
            } else {
                host.add(new CandidateHost(new HostSpec(host2, port2), this.targetServerType));
            }
            Iterator<CandidateHost> it = host.iterator();
            return it;
        }

        @Override
        public void init(String url, Properties info, HostRequirement targetServerType) {
            this.url = url;
            this.props = info;
            this.targetServerType = targetServerType;
        }

        @Override
        public void registerSuccess(String host) {
            if (host == host1){
                firstHostConnectionCount -= 1;
            }
        }

        @Override
        public void registerFailure(String host, Exception ex) {
            //Does Nothing for this test implementation
        }

        @Override
        public void registerDisconnect(String host) {
            //Does Nothing for this test implementation
        }

        @Override
        public boolean isHostDrainingConnections(String host) {
            return false;
        }

        @Override
        public long getConnectionTimeout(String host) {
            return 0;
        }

        @Override
        public boolean isInbuilt() {
            return false;
        }
    }

    private static boolean isReplicationInstanceAvailable() {
        try {
            Connection connection = openSecondaryDB();
            closeDB(connection);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Connection openSecondaryDB() throws Exception {
        TestUtil.initDriver();

        Properties props = userAndPassword();

        return DriverManager.getConnection(TestUtil.getURL(getSecondaryServer(), getSecondaryPort()), props);
    }

    private static Properties userAndPassword() {
        Properties props = new Properties();

        PGProperty.USER.set(props, TestUtil.getUser());
        PGProperty.PASSWORD.set(props, TestUtil.getPassword());
        return props;
    }

    private static String getSecondaryServer() {
        return System.getProperty("secondaryServer1", TestUtil.getServer());
    }

    private static int getSecondaryPort() {
        return Integer.parseInt(System.getProperty("secondaryPort1", String.valueOf(TestUtil.getPort() + 1)));
    }

    private void assertRemote(String expectedHost) throws SQLException {
        assertEquals(expectedHost, getRemoteHostSpec());
    }

    private String getRemoteHostSpec() throws SQLException {
        ResultSet rs = con.createStatement()
                .executeQuery("select inet_server_addr() || ':' || inet_server_port()");
        rs.next();
        return rs.getString(1);
    }

    @Test
    void testHostChooserPlugin() throws SQLException {

        Properties props = new Properties();
        props.setProperty(PGProperty.HOST_CHOOSER_IMPL.getName(), DummyHostChooserPlugin.class.getName());
        PGProperty.USER.set(props, user);
        PGProperty.PASSWORD.set(props, password);

        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:postgresql://");
        sb.append(host1);
        sb.append(":");
        sb.append(port1);
        sb.append("/");
        sb.append(TestUtil.getDatabase());

        for (int i = 0; i < 20; i++) {
            con = DriverManager.getConnection(sb.toString(), props);
            if (i < 10) {
                assertRemote(host1Ip);
            } else {
                assertRemote(host2Ip);
            }
        }

    }

}
