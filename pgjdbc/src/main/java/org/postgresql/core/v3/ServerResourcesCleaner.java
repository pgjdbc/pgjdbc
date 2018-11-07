/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import java.io.IOException;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * This class serves as a base, generic class, to track and eventually close
 * (deallocate) server-side resources such as prepared queries or portals.
 *
 * <p>At the driver level, these resources may be cleaned either automatically
 * (if freed by the JVM) or when their close() method is called. In either
 * case, a {@link PhantomReference} to the resource ("owner") is used.
 *
 * <p>If the resource object becomes unreachable (see {@link Reference}) before
 * being closed, the corresponding PhantomReference is automatically added to a
 * ReferenceQueue, which will be later inspected by the driver to instruct the
 * server to deallocate related resources. Note that reference enqueuing may
 * happen at a later point than the resource deallocation (some JVMs will only
 * do this at a GC event, for example).
 *
 * <p>If the resource is freed by calling its close() method, it is placed on a
 * internal queue of closed resources, and the driver will call the logic to
 * deallocate those server-side.
 *
 * <p>This class encapsulates this logic and aides in creating simple concrete
 * implementations for {@link SimpleQuery} and {@link Portal}. Typically these
 * implementations may periodically call this class methods to traverse both
 * the VM deallocated resources, as well as those closed, and call the
 * appropriate server-side deallcation method (to be provided by subclasses).
 *
 * @author Alvaro Hernandez aht@ongres.com
 *
 */
public abstract class ServerResourcesCleaner<T>  {
  private final Map<PhantomReference<T>, String> referenceNameMap = new ConcurrentHashMap<PhantomReference<T>, String>();
  private final ReferenceQueue<T> referenceQueue = new ReferenceQueue<T>();
  private final Queue<PhantomReference<T>> closedQueue = new ConcurrentLinkedQueue<PhantomReference<T>>();

  public PhantomReference<T> registerObject(T object, String name) {
    PhantomReference<T> reference = new PhantomReference<T>(object, referenceQueue);
    referenceNameMap.put(reference, name);

    return reference;
  }

  public void registerClosedObject(PhantomReference<T> reference) {
    closedQueue.add(reference);
  }

  public void processDeadObjects() throws IOException {
    Reference<? extends T> deadObject;
    while ((deadObject = referenceQueue.poll()) != null) {
      cleanDeadObjectReference(deadObject);
    }

    while ((deadObject = closedQueue.poll()) != null) {
      cleanDeadObjectReference(deadObject);
    }
  }

  private void cleanDeadObjectReference(Reference<? extends T> deadObject) throws IOException {
    String name = referenceNameMap.remove(deadObject);
    if (null == name) {
      return; // name has already been processed
    }

    onReferenceCleanup(name);
    deadObject.clear();
  }

  protected abstract void onReferenceCleanup(String name) throws IOException;
}
