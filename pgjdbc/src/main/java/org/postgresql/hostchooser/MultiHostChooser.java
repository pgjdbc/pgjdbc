/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import static java.util.Collections.shuffle;

import org.postgresql.PGProperty;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

/**
 * HostChooser that keeps track of known host statuses.
 */
class MultiHostChooser implements HostChooser {
  private HostSpec[] hostSpecs;
  private final HostRequirement targetServerType;
  private int hostRecheckTime;
  private boolean loadBalance;

  MultiHostChooser(HostSpec[] hostSpecs, HostRequirement targetServerType,
      Properties info) {
    this.hostSpecs = hostSpecs;
    this.targetServerType = targetServerType;
    try {
      hostRecheckTime = PGProperty.HOST_RECHECK_SECONDS.getInt(info) * 1000;
      loadBalance = PGProperty.LOAD_BALANCE_HOSTS.getBoolean(info);
    } catch (PSQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Iterator<CandidateHost> iterator() {
    Iterator<CandidateHost> res = candidateIterator();
    if (!res.hasNext()) {
      // In case all the candidate hosts are unavailable or do not match, try all the hosts just in case
      List<HostSpec> allHosts = Arrays.asList(hostSpecs);
      if (loadBalance) {
        allHosts = new ArrayList<HostSpec>(allHosts);
        Collections.shuffle(allHosts);
      }
      res = withReqStatus(targetServerType, allHosts).iterator();
    }
    return res;
  }

  private Iterator<CandidateHost> candidateIterator() {
    if (targetServerType != HostRequirement.preferSecondary) {
      return getCandidateHosts(targetServerType).iterator();
    }

    // preferSecondary tries to find secondary hosts first
    // Note: sort does not work here since there are "unknown" hosts,
    // and that "unknown" might turn out to be master, so we should discard that
    // if other secondaries exist
    List<CandidateHost> secondaries = getCandidateHosts(HostRequirement.secondary);
    List<CandidateHost> any = getCandidateHosts(HostRequirement.any);

    if (secondaries.isEmpty()) {
      return any.iterator();
    }

    if (any.isEmpty()) {
      return secondaries.iterator();
    }

    if (secondaries.get(secondaries.size() - 1).equals(any.get(0))) {
      // When the last secondary's hostspec is the same as the first in "any" list, there's no need
      // to attempt to connect it as "secondary"
      // Note: this is only an optimization
      secondaries = rtrim(1, secondaries);
    }
    return append(secondaries, any).iterator();
  }

  private List<CandidateHost> getCandidateHosts(HostRequirement hostRequirement) {
    List<HostSpec> candidates =
        GlobalHostStatusTracker.getCandidateHosts(hostSpecs, hostRequirement, hostRecheckTime);
    if (loadBalance) {
      shuffle(candidates);
    }
    return withReqStatus(hostRequirement, candidates);
  }

  private List<CandidateHost> withReqStatus(final HostRequirement requirement, final List<HostSpec> hosts) {
    return new AbstractList<CandidateHost>() {
      @Override
      public CandidateHost get(int index) {
        return new CandidateHost(hosts.get(index), requirement);
      }

      @Override
      public int size() {
        return hosts.size();
      }
    };
  }

  private <T> List<T> append(final List<T> a, final List<T> b) {
    return new AbstractList<T>() {
      @Override
      public T get(int index) {
        return index < a.size() ? a.get(index) : b.get(index - a.size());
      }

      @Override
      public int size() {
        return a.size() + b.size();
      }
    };
  }

  private <T> List<T> rtrim(final int size, final List<T> a) {
    return new AbstractList<T>() {
      @Override
      public T get(int index) {
        return a.get(index);
      }

      @Override
      public int size() {
        return Math.max(0, a.size() - size);
      }
    };
  }

}
