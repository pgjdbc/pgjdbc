/*-------------------------------------------------------------------------
*
* Copyright (c) 2008-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package legacy.org.postgresql.gss;

import legacy.org.postgresql.core.Logger;
import legacy.org.postgresql.core.PGStream;
import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PSQLException;
import legacy.org.postgresql.util.PSQLState;
import legacy.org.postgresql.util.ServerErrorMessage;
import org.ietf.jgss.*;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;
import java.io.IOException;
import java.security.PrivilegedAction;
import java.sql.SQLException;


public class MakeGSS
{

    public static void authenticate(PGStream pgStream, String host, String user, String password, String jaasApplicationName, String kerberosServerName, Logger logger) throws IOException, SQLException
    {
        if (logger.logDebug())
            logger.debug(" <=BE AuthenticationReqGSS");

        Object result = null;

        if (jaasApplicationName == null)
            jaasApplicationName = "pgjdbc";
        if (kerberosServerName == null)
            kerberosServerName = "postgres";

        try {
            LoginContext lc = new LoginContext(jaasApplicationName, new GSSCallbackHandler(user, password));
            lc.login();

            Subject sub = lc.getSubject();
            PrivilegedAction action = new GssAction(pgStream, host, user, password, kerberosServerName, logger);
            result = Subject.doAs(sub, action);
        } catch (Exception e) {
            throw new PSQLException(GT.tr("GSS Authentication failed"), PSQLState.CONNECTION_FAILURE, e);
        }

        if (result instanceof IOException)
            throw (IOException)result;
        else if (result instanceof SQLException)
            throw (SQLException)result;
        else if (result != null)
            throw new PSQLException(GT.tr("GSS Authentication failed"), PSQLState.CONNECTION_FAILURE, (Exception)result);

    }

}

class GssAction implements PrivilegedAction
{
    private final PGStream pgStream;
    private final String host;
    private final String user;
    private final String password;
    private final String kerberosServerName;
    private final Logger logger;

    public GssAction(PGStream pgStream, String host, String user, String password, String kerberosServerName, Logger logger)
    {
        this.pgStream = pgStream;
        this.host = host;
        this.user = user;
        this.password = password;
        this.kerberosServerName = kerberosServerName;
        this.logger = logger;
    }

    public Object run() {

        try {

            org.ietf.jgss.Oid desiredMechs[] = new org.ietf.jgss.Oid[1];
            desiredMechs[0] = new org.ietf.jgss.Oid("1.2.840.113554.1.2.2");


            GSSManager manager = GSSManager.getInstance();

            GSSName clientName = manager.createName(user, GSSName.NT_USER_NAME);
            GSSCredential clientCreds = manager.createCredential(clientName, 8*3600, desiredMechs, GSSCredential.INITIATE_ONLY);

            GSSName serverName = manager.createName(kerberosServerName + "@" + host, GSSName.NT_HOSTBASED_SERVICE);

            GSSContext secContext = manager.createContext(serverName, desiredMechs[0], clientCreds, GSSContext.DEFAULT_LIFETIME);
            secContext.requestMutualAuth(true);

            byte inToken[] = new byte[0];
            byte outToken[] = null;

            boolean established = false;
            while (!established) {
                outToken = secContext.initSecContext(inToken, 0, inToken.length);


                if (outToken != null) {
                    if (logger.logDebug())
                        logger.debug(" FE=> Password(GSS Authentication Token)");

                    pgStream.SendChar('p');
                    pgStream.SendInteger4(4 + outToken.length);
                    pgStream.Send(outToken);
                    pgStream.flush();
                }

                if (!secContext.isEstablished()) {
                    int response = pgStream.ReceiveChar();
                    // Error
                    if (response == 'E') {
                        int l_elen = pgStream.ReceiveInteger4();
                        ServerErrorMessage l_errorMsg = new ServerErrorMessage(pgStream.ReceiveString(l_elen - 4), logger.getLogLevel());

                        if (logger.logDebug())
                            logger.debug(" <=BE ErrorMessage(" + l_errorMsg + ")");

                        return new PSQLException(l_errorMsg);

                    } else if (response == 'R') {

                        if (logger.logDebug())
                            logger.debug(" <=BE AuthenticationGSSContinue");

                        int len = pgStream.ReceiveInteger4();
                        int type = pgStream.ReceiveInteger4();
                        // should check type = 8
                        inToken = pgStream.Receive(len - 8);
                    } else {
                        // Unknown/unexpected message type.
                        return new PSQLException(GT.tr("Protocol error.  Session setup failed."), PSQLState.CONNECTION_UNABLE_TO_CONNECT);
                    }
                } else {
                    established = true;
                }
            }

        } catch (IOException e) {
            return e;
        } catch (GSSException gsse) {
            return new PSQLException(GT.tr("GSS Authentication failed"), PSQLState.CONNECTION_FAILURE, gsse);
        }

        return null;
    }
}

