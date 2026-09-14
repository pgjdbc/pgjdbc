/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import org.postgresql.core.PGStream;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

import java.io.IOException;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;

public class GssEncAction implements PrivilegedAction<@Nullable Exception>, Callable<@Nullable Exception> {
  private static final Logger LOGGER = Logger.getLogger(GssAction.class.getName());

  /**
   * Largest GSS token, in bytes, that the driver accepts during the encryption handshake. The
   * 4-byte length prefix in front of the token is not counted.
   *
   * <p>libpq ({@code fe-secure-gssapi.c} {@code pqsecure_open_gss}) and the backend
   * ({@code be-secure-gssapi.c} {@code secure_open_gssapi}) both reject a handshake token over
   * {@code PQ_GSS_AUTH_BUFFER_SIZE - sizeof(uint32)}, and this limit mirrors them. The
   * handshake cannot split a token across packets, so both ends have to accept whatever the
   * GSSAPI library produced, and upstream notes that a token reaches 64 kB on some
   * configurations. The encrypted stream that follows is limited by the smaller
   * {@code PQ_GSS_MAX_PACKET_SIZE}.</p>
   */
  private static final int MAX_GSS_AUTH_TOKEN_SIZE = 64 * 1024 - 4;

  /**
   * Largest number of round-trips the GSS encryption handshake may take. A Kerberos context is
   * established in two or three exchanges, so {@value #MAX_HANDSHAKE_ITERATIONS} leaves room for
   * any real handshake; it matches the limit the authentication exchange gets in
   * {@code ConnectionFactoryImpl}. The limit stops a server that answers every token with another
   * one from looping the client indefinitely before the connection is authenticated.
   */
  private static final int MAX_HANDSHAKE_ITERATIONS = 64;

  private final PGStream pgStream;
  private final String host;
  private final String user;
  private final String kerberosServerName;
  private final boolean useSpnego;
  private final boolean gssUseDefaultCreds;
  private final @Nullable Subject subject;
  @SuppressWarnings("unused")
  private final boolean logServerErrorDetail;

  public GssEncAction(PGStream pgStream, @Nullable Subject subject,
      String host, String user,
      String kerberosServerName, boolean useSpnego, boolean gssUseDefaultCreds,
      boolean logServerErrorDetail) {
    this.pgStream = pgStream;
    this.subject = subject;
    this.host = host;
    this.user = user;
    this.kerberosServerName = kerberosServerName;
    this.useSpnego = useSpnego;
    this.gssUseDefaultCreds = gssUseDefaultCreds;
    this.logServerErrorDetail = logServerErrorDetail;
  }

  private static boolean hasSpnegoSupport(GSSManager manager) throws GSSException {
    Oid spnego = new Oid("1.3.6.1.5.5.2");
    Oid[] mechs = manager.getMechs();

    for (Oid mech : mechs) {
      if (mech.equals(spnego)) {
        return true;
      }
    }

    return false;
  }

  @Override
  public @Nullable Exception run() {
    try {
      GSSManager manager = GSSManager.getInstance();
      GSSCredential clientCreds = null;
      Oid[] desiredMechs = new Oid[1];

      //Try to get credential from subject first.
      GSSCredential gssCredential = null;
      if (subject != null) {
        Set<GSSCredential> gssCreds = subject.getPrivateCredentials(GSSCredential.class);
        if (gssCreds != null && !gssCreds.isEmpty()) {
          gssCredential = gssCreds.iterator().next();
        }
      }

      //If failed to get credential from subject,
      //then call createCredential to create one.
      if (gssCredential == null) {
        if (useSpnego && hasSpnegoSupport(manager)) {
          desiredMechs[0] = new Oid("1.3.6.1.5.5.2");
        } else {
          desiredMechs[0] = new Oid("1.2.840.113554.1.2.2");
        }
        String principalName = this.user;
        if (subject != null) {
          Set<Principal> principals = subject.getPrincipals();
          Iterator<Principal> principalIterator = principals.iterator();

          Principal principal = null;
          if (principalIterator.hasNext()) {
            principal = principalIterator.next();
            principalName = principal.getName();
          }
        }

        if (gssUseDefaultCreds) {
          clientCreds = manager.createCredential(GSSCredential.INITIATE_ONLY);
        } else {
          GSSName clientName = manager.createName(principalName, GSSName.NT_USER_NAME);
          clientCreds = manager.createCredential(clientName, 8 * 3600, desiredMechs,
              GSSCredential.INITIATE_ONLY);
        }
      } else {
        desiredMechs[0] = new Oid("1.2.840.113554.1.2.2");
        clientCreds = gssCredential;
      }
      GSSName serverName =
          manager.createName(kerberosServerName + "@" + host, GSSName.NT_HOSTBASED_SERVICE);

      GSSContext secContext = manager.createContext(serverName, desiredMechs[0], clientCreds,
          GSSContext.DEFAULT_LIFETIME);
      secContext.requestMutualAuth(true);
      secContext.requestConf(true);
      secContext.requestInteg(true);

      return negotiate(secContext);
    } catch (IOException e) {
      return e;
    } catch (GSSException gsse) {
      return new PSQLException(GT.tr("GSS Authentication failed"), PSQLState.CONNECTION_FAILURE,
          gsse);
    }
  }

  /**
   * Exchanges tokens until the context is established, then installs it on the stream so that
   * every message after the handshake is encrypted.
   *
   * @param secContext the context to establish
   * @return {@code null} once the context is established, or, when the context is still not
   *     established after {@value #MAX_HANDSHAKE_ITERATIONS} round-trips, a
   *     {@code PSQLException} returned after the connection is marked broken
   * @throws IOException if the server's token length is negative or over
   *     {@value #MAX_GSS_AUTH_TOKEN_SIZE}, after the connection is marked broken, or if the
   *     stream fails
   * @throws GSSException if the GSS context rejects a token while it initiates the security
   *     context
   */
  @Nullable Exception negotiate(GSSContext secContext) throws IOException, GSSException {
    byte[] inToken = new byte[0];
    byte[] outToken = null;

    boolean established = false;
    int handshakeIterations = 0;
    while (!established) {
      if (++handshakeIterations > MAX_HANDSHAKE_ITERATIONS) {
        // A zero-length token is a valid continuation, so only this count ends a handshake that
        // the server never completes.
        return pgStream.markBroken(new PSQLException(GT.tr(
            "Protocol error. GSS encryption handshake did not complete within {0} round-trips.",
            String.valueOf(MAX_HANDSHAKE_ITERATIONS)), PSQLState.PROTOCOL_VIOLATION));
      }
      outToken = secContext.initSecContext(inToken, 0, inToken.length);

      if (outToken != null) {
        LOGGER.log(Level.FINEST, " FE=> Password(GSS Authentication Token)");

        pgStream.sendInteger4(outToken.length);
        pgStream.send(outToken);
        pgStream.flush();
      }

      if (!secContext.isEstablished()) {
        // The length field counts only the token bytes that follow.
        int len = pgStream.readUntrackedLength(
            "GSSEncryptionHandshakeToken", 0, MAX_GSS_AUTH_TOKEN_SIZE);
        inToken = pgStream.receive(len);
      } else {
        established = true;
        pgStream.setSecContext(secContext);
      }
    }

    return null;
  }

  @Override
  public @Nullable Exception call() throws Exception {
    return run();
  }
}
