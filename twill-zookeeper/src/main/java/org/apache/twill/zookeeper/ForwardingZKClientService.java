/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.twill.zookeeper;

import com.google.common.base.Supplier;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service;

import org.apache.zookeeper.ZooKeeper;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 */
public abstract class ForwardingZKClientService extends ForwardingZKClient implements ZKClientService {

  private final ZKClientService delegate;

  protected ForwardingZKClientService(ZKClientService delegate) {
    super(delegate);
    this.delegate = delegate;
  }

  @Override
  public Supplier<ZooKeeper> getZooKeeperSupplier() {
    return delegate.getZooKeeperSupplier();
  }

  @Override
  public boolean isRunning() {
    return delegate.isRunning();
  }

  @Override
  public State state() {
    return delegate.state();
  }

  @Override
  public void addListener(Listener listener, Executor executor) {
    delegate.addListener(listener, executor);
  }

  @Override
  public Service startAsync() {
    return delegate.startAsync();
  }

  @Override
  public Service stopAsync() {
    return delegate.stopAsync();
  }

  @Override
  public void awaitRunning() {
    delegate.awaitRunning();
  }

  @Override
  public void awaitRunning(long timeout, TimeUnit unit) throws TimeoutException {
    delegate.awaitRunning();
  }

  @Override
  public void awaitTerminated() {
    delegate.awaitTerminated();
  }

  @Override
  public void awaitTerminated(long timeout, TimeUnit unit) throws TimeoutException {
    delegate.awaitTerminated(timeout, unit);
  }

  @Override
  public Throwable failureCause() {
    return delegate.failureCause();
  }
}
