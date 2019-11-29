/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.sspi;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

interface NTDSAPI extends StdCallLibrary {

  NTDSAPI instance = (NTDSAPI) Native.loadLibrary("NTDSAPI", NTDSAPI.class);

  /**
   * <p>Wrap DsMakeSpn</p>
   *
   * <p>To get the String result, call
   *
   * <pre>
   * new String(buf, 0, spnLength)
   * </pre>
   *
   * on the byte[] buffer passed to 'spn' after testing to ensure ERROR_SUCCESS.</p>
   *
   * @param serviceClass SPN service class (in)
   * @param serviceName SPN service name (in)
   * @param instanceName SPN instance name (in, null ok)
   * @param instancePort SPN port number (in, 0 to omit)
   * @param referrer SPN referer (in, null ok)
   * @param spnLength Size of 'spn' buffer (in), actul length of spn created including null
   *        terminator (out)
   * @param spn SPN buffer (in/out)
   * @return Error code ERROR_SUCCESS, ERROR_BUFFER_OVERFLOW or ERROR_INVALID_PARAMETER
   * @see <a href="https://msdn.microsoft.com/en-us/library/ms676007(v=vs.85).aspx">
   *     https://msdn.microsoft.com/en-us/library/ms676007(v=vs.85).aspx</a>
   */
  int DsMakeSpnW(WString serviceClass, /* in */
      WString serviceName, /* in */
      WString instanceName, /* in, optional, may be null */
      short instancePort, /* in */
      WString referrer, /* in, optional, may be null */
      IntByReference spnLength, /* in: length of buffer spn; out: chars written */
      char[] spn /* out string */
  ) throws LastErrorException;

  int ERROR_SUCCESS = 0;
  int ERROR_INVALID_PARAMETER = 87;
  int ERROR_BUFFER_OVERFLOW = 111;
}
