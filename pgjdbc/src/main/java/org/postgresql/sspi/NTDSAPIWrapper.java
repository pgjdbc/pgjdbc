/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.sspi;

import com.sun.jna.LastErrorException;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;

public class NTDSAPIWrapper {

  static final NTDSAPIWrapper instance = new NTDSAPIWrapper();

  /**
   * Convenience wrapper for NTDSAPI DsMakeSpn with Java friendly string and exception handling.
   *
   * @param serviceClass See MSDN
   * @param serviceName See MSDN
   * @param instanceName See MSDN
   * @param instancePort See MSDN
   * @param referrer See MSDN
   * @return SPN generated
   * @throws LastErrorException If buffer too small or parameter incorrect
   * @see <a href="https://msdn.microsoft.com/en-us/library/ms676007(v=vs.85).aspx">
   *     https://msdn.microsoft.com/en-us/library/ms676007(v=vs.85).aspx</a>
   */
  public String DsMakeSpn(String serviceClass, String serviceName, String instanceName,
      short instancePort, String referrer) throws LastErrorException {
    IntByReference spnLength = new IntByReference(2048);
    char[] spn = new char[spnLength.getValue()];

    final int ret =
        NTDSAPI.instance.DsMakeSpnW(
            new WString(serviceClass),
            new WString(serviceName),
            instanceName == null ? null : new WString(instanceName),
            instancePort,
            referrer == null ? null : new WString(referrer),
            spnLength,
            spn);

    if (ret != NTDSAPI.ERROR_SUCCESS) {
      /* Should've thrown LastErrorException, but just in case */
      throw new RuntimeException("NTDSAPI DsMakeSpn call failed with " + ret);
    }

    return new String(spn, 0, spnLength.getValue());
  }
}
