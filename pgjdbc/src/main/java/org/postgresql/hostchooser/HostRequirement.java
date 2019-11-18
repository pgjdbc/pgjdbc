/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

/**
 * Describes the required server type.
 */
public enum HostRequirement {
  any {
    public boolean allowConnectingTo(HostStatus status) {
      return status != HostStatus.ConnectFail;
    }
  },
  master {
    public boolean allowConnectingTo(HostStatus status) {
      return status == HostStatus.Master || status == HostStatus.ConnectOK;
    }
  },
  secondary {
    public boolean allowConnectingTo(HostStatus status) {
      return status == HostStatus.Secondary || status == HostStatus.ConnectOK;
    }
  },
  preferSecondary {
    public boolean allowConnectingTo(HostStatus status) {
      return status != HostStatus.ConnectFail;
    }
  };

  public abstract boolean allowConnectingTo(HostStatus status);

  /**
   * <p>The postgreSQL project has decided not to use the term slave to refer to alternate servers.
   * secondary or standby is preferred. We have arbitrarily chosen secondary.
   * As of Jan 2018 in order not to break existint code we are going to accept both slave or
   * secondary for names of alternate servers.</p>
   *
   * <p>The current policy is to keep accepting this silently but not document slave, or slave preferSlave</p>
   *
   * @param targetServerType the value of {@code targetServerType} connection property
   * @return HostRequirement
   */

  public static HostRequirement getTargetServerType(String targetServerType) {
    String allowSlave = targetServerType.replace("lave", "econdary");
    return valueOf(allowSlave);
  }

}
