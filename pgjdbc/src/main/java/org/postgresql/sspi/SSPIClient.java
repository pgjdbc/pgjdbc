/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.sspi;

import org.postgresql.core.PGStream;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import com.sun.jna.LastErrorException;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Sspi;
import com.sun.jna.platform.win32.Sspi.SecBufferDesc;
import com.sun.jna.platform.win32.Win32Exception;
import waffle.windows.auth.IWindowsCredentialsHandle;
import waffle.windows.auth.impl.WindowsCredentialsHandleImpl;
import waffle.windows.auth.impl.WindowsSecurityContextImpl;

import java.io.IOException;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>Use Waffle-JNI to support SSPI authentication when PgJDBC is running on a Windows client and
 * talking to a Windows server.</p>
 *
 * <p>SSPI is not supported on a non-Windows client.</p>
 *
 * @author craig
 */
public class SSPIClient implements ISSPIClient {

  public static String SSPI_DEFAULT_SPN_SERVICE_CLASS = "POSTGRES";

  private static final Logger LOGGER = Logger.getLogger(SSPIClient.class.getName());
  private final PGStream pgStream;
  private final String spnServiceClass;
  private final boolean enableNegotiate;

  private IWindowsCredentialsHandle clientCredentials;
  private WindowsSecurityContextImpl sspiContext;
  private String targetName;


  /**
   * <p>Instantiate an SSPIClient for authentication of a connection.</p>
   *
   * <p>SSPIClient is not re-usable across connections.</p>
   *
   * <p>It is safe to instantiate SSPIClient even if Waffle and JNA are missing or on non-Windows
   * platforms, however you may not call any methods other than isSSPISupported().</p>
   *
   * @param pgStream PostgreSQL connection stream
   * @param spnServiceClass SSPI SPN service class, defaults to POSTGRES if null
   * @param enableNegotiate enable negotiate
   */
  public SSPIClient(PGStream pgStream, String spnServiceClass, boolean enableNegotiate) {
    this.pgStream = pgStream;

    /* If blank or unspecified, SPN service class should be POSTGRES */
    if (spnServiceClass != null && spnServiceClass.isEmpty()) {
      spnServiceClass = null;
    }
    if (spnServiceClass == null) {
      spnServiceClass = SSPI_DEFAULT_SPN_SERVICE_CLASS;
    }
    this.spnServiceClass = spnServiceClass;

    /* If we're forcing Kerberos (no spnego), disable SSPI negotiation */
    this.enableNegotiate = enableNegotiate;
  }

  /**
   * Test whether we can attempt SSPI authentication. If false, do not attempt to call any other
   * SSPIClient methods.
   *
   * @return true if it's safe to attempt SSPI authentication
   */
  public boolean isSSPISupported() {
    try {
      /*
       * SSPI is windows-only. Attempt to use JNA to identify the platform. If Waffle is missing we
       * won't have JNA and this will throw a NoClassDefFoundError.
       */
      if (!Platform.isWindows()) {
        LOGGER.log(Level.FINE, "SSPI not supported: non-Windows host");
        return false;
      }
      /* Waffle must be on the CLASSPATH */
      Class.forName("waffle.windows.auth.impl.WindowsSecurityContextImpl");
      return true;
    } catch (NoClassDefFoundError ex) {
      LOGGER.log(Level.WARNING, "SSPI unavailable (no Waffle/JNA libraries?)", ex);
      return false;
    } catch (ClassNotFoundException ex) {
      LOGGER.log(Level.WARNING, "SSPI unavailable (no Waffle/JNA libraries?)", ex);
      return false;
    }
  }

  private String makeSPN() throws PSQLException {
    final HostSpec hs = pgStream.getHostSpec();

    try {
      return NTDSAPIWrapper.instance.DsMakeSpn(spnServiceClass, hs.getHost(), null,
          (short) hs.getPort(), null);
    } catch (LastErrorException ex) {
      throw new PSQLException("SSPI setup failed to determine SPN",
          PSQLState.CONNECTION_UNABLE_TO_CONNECT, ex);
    }
  }


  /**
   * Respond to an authentication request from the back-end for SSPI authentication (AUTH_REQ_SSPI).
   *
   * @throws SQLException on SSPI authentication handshake failure
   * @throws IOException on network I/O issues
   */
  public void startSSPI() throws SQLException, IOException {

    /*
     * We usually use SSPI negotiation (spnego), but it's disabled if the client asked for GSSPI and
     * usespngo isn't explicitly turned on.
     */
    final String securityPackage = enableNegotiate ? "negotiate" : "kerberos";

    LOGGER.log(Level.FINEST, "Beginning SSPI/Kerberos negotiation with SSPI package: {0}", securityPackage);

    try {
      /*
       * Acquire a handle for the local Windows login credentials for the current user
       *
       * See AcquireCredentialsHandle
       * (http://msdn.microsoft.com/en-us/library/windows/desktop/aa374712%28v=vs.85%29.aspx)
       *
       * This corresponds to pg_SSPI_startup in libpq/fe-auth.c .
       */
      try {
        clientCredentials = WindowsCredentialsHandleImpl.getCurrent(securityPackage);
        clientCredentials.initialize();
      } catch (Win32Exception ex) {
        throw new PSQLException("Could not obtain local Windows credentials for SSPI",
            PSQLState.CONNECTION_UNABLE_TO_CONNECT /* TODO: Should be authentication error */, ex);
      }

      try {
        targetName = makeSPN();

        LOGGER.log(Level.FINEST, "SSPI target name: {0}", targetName);

        sspiContext = new WindowsSecurityContextImpl();
        sspiContext.setPrincipalName(targetName);
        sspiContext.setCredentialsHandle(clientCredentials);
        sspiContext.setSecurityPackage(securityPackage);
        sspiContext.initialize(null, null, targetName);
      } catch (Win32Exception ex) {
        throw new PSQLException("Could not initialize SSPI security context",
            PSQLState.CONNECTION_UNABLE_TO_CONNECT /* TODO: Should be auth error */, ex);
      }

      sendSSPIResponse(sspiContext.getToken());
      LOGGER.log(Level.FINEST, "Sent first SSPI negotiation message");
    } catch (NoClassDefFoundError ex) {
      throw new PSQLException(
          "SSPI cannot be used, Waffle or its dependencies are missing from the classpath",
          PSQLState.NOT_IMPLEMENTED, ex);
    }
  }

  /**
   * Continue an existing authentication conversation with the back-end in resonse to an
   * authentication request of type AUTH_REQ_GSS_CONT.
   *
   * @param msgLength Length of message to read, excluding length word and message type word
   * @throws SQLException if something wrong happens
   * @throws IOException if something wrong happens
   */
  public void continueSSPI(int msgLength) throws SQLException, IOException {

    if (sspiContext == null) {
      throw new IllegalStateException("Cannot continue SSPI authentication that we didn't begin");
    }

    LOGGER.log(Level.FINEST, "Continuing SSPI negotiation");

    /* Read the response token from the server */
    byte[] receivedToken = pgStream.receive(msgLength);

    SecBufferDesc continueToken = new SecBufferDesc(Sspi.SECBUFFER_TOKEN, receivedToken);

    sspiContext.initialize(sspiContext.getHandle(), continueToken, targetName);

    /*
     * Now send the response token. If negotiation is complete there may be zero bytes to send, in
     * which case we shouldn't send a reply as the server is not expecting one; see fe-auth.c in
     * libpq for details.
     */
    byte[] responseToken = sspiContext.getToken();
    if (responseToken.length > 0) {
      sendSSPIResponse(responseToken);
      LOGGER.log(Level.FINEST, "Sent SSPI negotiation continuation message");
    } else {
      LOGGER.log(Level.FINEST, "SSPI authentication complete, no reply required");
    }
  }

  private void sendSSPIResponse(byte[] outToken) throws IOException {
    /*
     * The sspiContext now contains a token we can send to the server to start the handshake. Send a
     * 'password' message containing the required data; the server knows we're doing SSPI
     * negotiation and will deal with it appropriately.
     */
    pgStream.sendChar('p');
    pgStream.sendInteger4(4 + outToken.length);
    pgStream.send(outToken);
    pgStream.flush();
  }

  /**
   * Clean up native win32 resources after completion or failure of SSPI authentication. This
   * SSPIClient instance becomes unusable after disposal.
   */
  public void dispose() {
    if (sspiContext != null) {
      sspiContext.dispose();
      sspiContext = null;
    }
    if (clientCredentials != null) {
      clientCredentials.dispose();
      clientCredentials = null;
    }
  }
}
