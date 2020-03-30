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
package org.apache.twill.internal.zookeeper;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import org.apache.twill.api.ElectionHandler;
import org.apache.twill.zookeeper.ZKClientService;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test for {@link LeaderElection}.
 */
public class LeaderElectionTest {

  private static final Logger LOG = LoggerFactory.getLogger(LeaderElectionTest.class);

  @ClassRule
  public static TemporaryFolder tmpFolder = new TemporaryFolder();

  private static InMemoryZKServer zkServer;

  @Test(timeout = 150000)
  public void testElection() throws ExecutionException, InterruptedException, BrokenBarrierException {
    ExecutorService executor = Executors.newCachedThreadPool();

    int participantCount = 5;
    final CyclicBarrier barrier = new CyclicBarrier(participantCount + 1);
    final Semaphore leaderSem = new Semaphore(0);
    final Semaphore followerSem = new Semaphore(0);
    final CountDownLatch[] stopLatch = new CountDownLatch[participantCount];
    final List<ZKClientService> zkClients = Lists.newArrayList();

    try {
      final AtomicInteger currentLeader = new AtomicInteger(-1);
      for (int i = 0; i < participantCount; i++) {
        final ZKClientService zkClient = ZKClientService.Builder.of(zkServer.getConnectionStr()).build();
        zkClient.startAsync();
        zkClient.awaitRunning();
        stopLatch[i] = new CountDownLatch(1);
        zkClients.add(zkClient);

        final int idx = i;
        executor.submit(new Runnable() {
          @Override
          public void run() {
            try {
              barrier.await();

              LeaderElection leaderElection = new LeaderElection(zkClient, "/test", new ElectionHandler() {
                @Override
                public void leader() {
                  currentLeader.set(idx);
                  leaderSem.release();
                }

                @Override
                public void follower() {
                  followerSem.release();
                }
              });
              leaderElection.startAsync();

              stopLatch[idx].await(10, TimeUnit.SECONDS);
              leaderElection.stopAsync();
              leaderElection.awaitTerminated();

            } catch (Exception e) {
              LOG.error(e.getMessage(), e);
            }
          }
        });
      }

      barrier.await();
      leaderSem.tryAcquire(10, TimeUnit.SECONDS);
      followerSem.tryAcquire(participantCount - 1, 10, TimeUnit.SECONDS);

      // Continuously stopping leader until there is one left.
      for (int i = 0; i < participantCount - 1; i++) {
        stopLatch[currentLeader.get()].countDown();
        leaderSem.tryAcquire(10, TimeUnit.SECONDS);
        followerSem.tryAcquire(10, TimeUnit.SECONDS);
      }

      stopLatch[currentLeader.get()].countDown();

    } finally {
      executor.shutdown();
      executor.awaitTermination(5L, TimeUnit.SECONDS);

      for (ZKClientService zkClient : zkClients) {
        zkClient.stopAsync();
        zkClient.awaitTerminated();
      }
    }
  }

  @Test(timeout = 150000)
  public void testCancel() throws InterruptedException, IOException {
    List<LeaderElection> leaderElections = Lists.newArrayList();
    List<ZKClientService> zkClients = Lists.newArrayList();

    // Creates two participants
    final Semaphore leaderSem = new Semaphore(0);
    final Semaphore followerSem = new Semaphore(0);
    final AtomicInteger leaderIdx = new AtomicInteger();

    try {
      for (int i = 0; i < 2; i++) {
        ZKClientService zkClient = ZKClientService.Builder.of(zkServer.getConnectionStr()).build();
        zkClient.startAsync();
        zkClient.awaitRunning();

        zkClients.add(zkClient);

        final int finalI = i;
        leaderElections.add(new LeaderElection(zkClient, "/testCancel", new ElectionHandler() {
          @Override
          public void leader() {
            leaderIdx.set(finalI);
            leaderSem.release();
          }

          @Override
          public void follower() {
            followerSem.release();
          }
        }));
      }

      for (LeaderElection leaderElection : leaderElections) {
        leaderElection.startAsync();
      }

      leaderSem.tryAcquire(10, TimeUnit.SECONDS);
      followerSem.tryAcquire(10, TimeUnit.SECONDS);

      int leader = leaderIdx.get();
      int follower = 1 - leader;

      // Kill the follower session
      KillZKSession.kill(zkClients.get(follower).getZooKeeperSupplier().get(),
                         zkClients.get(follower).getConnectString(), 20000);

      // Cancel the leader
      leaderElections.get(leader).stopAsync();
      leaderElections.get(leader).awaitTerminated();

      // Now follower should still be able to become leader.
      leaderSem.tryAcquire(30, TimeUnit.SECONDS);

      leader = leaderIdx.get();
      follower = 1 - leader;

      // Create another participant (use the old leader zkClient)
      leaderElections.set(follower, new LeaderElection(zkClients.get(follower), "/testCancel", new ElectionHandler() {
        @Override
        public void leader() {
          leaderSem.release();
        }

        @Override
        public void follower() {
          followerSem.release();
        }
      }));
      leaderElections.get(follower).startAsync();

      // Cancel the follower first.
      leaderElections.get(follower).stopAsync();
      leaderElections.get(follower).awaitTerminated();

      // Cancel the leader.
      leaderElections.get(leader).stopAsync();
      leaderElections.get(leader).awaitTerminated();
      
      // Since the follower has been cancelled before leader, there should be no leader.
      Assert.assertFalse(leaderSem.tryAcquire(10, TimeUnit.SECONDS));
    } finally {
      for (ZKClientService zkClient : zkClients) {
        zkClient.stopAsync();
        zkClient.awaitTerminated();
      }
    }
  }

  @Test(timeout = 150000)
  public void testDisconnect() throws IOException, InterruptedException {
    File zkDataDir = tmpFolder.newFolder();
    InMemoryZKServer ownZKServer = InMemoryZKServer.builder().setDataDir(zkDataDir).build();
    ownZKServer.startAsync();
    ownZKServer.awaitRunning();
    try {
      ZKClientService zkClient = ZKClientService.Builder.of(ownZKServer.getConnectionStr()).build();
      zkClient.startAsync();
      zkClient.awaitRunning();

      try {
        final Semaphore leaderSem = new Semaphore(0);
        final Semaphore followerSem = new Semaphore(0);

        LeaderElection leaderElection = new LeaderElection(zkClient, "/testDisconnect", new ElectionHandler() {
          @Override
          public void leader() {
            leaderSem.release();
          }

          @Override
          public void follower() {
            followerSem.release();
          }
        });
        leaderElection.startAsync();

        leaderSem.tryAcquire(20, TimeUnit.SECONDS);

        int zkPort = ownZKServer.getLocalAddress().getPort();

        // Disconnect by shutting the server and restart it on the same port
        ownZKServer.stopAsync();
        ownZKServer.awaitTerminated();

        // Right after disconnect, it should become follower
        followerSem.tryAcquire(20, TimeUnit.SECONDS);

        ownZKServer = InMemoryZKServer.builder().setDataDir(zkDataDir).setPort(zkPort).build();
        ownZKServer.startAsync();
        ownZKServer.awaitRunning();

        // Right after reconnect, it should be leader again.
        leaderSem.tryAcquire(20, TimeUnit.SECONDS);

        // Now disconnect it again, but then cancel it before reconnect, it shouldn't become leader
        ownZKServer.stopAsync();
        ownZKServer.awaitTerminated();

        // Right after disconnect, it should become follower
        followerSem.tryAcquire(20, TimeUnit.SECONDS);

        leaderElection.stopAsync();

        ownZKServer = InMemoryZKServer.builder().setDataDir(zkDataDir).setPort(zkPort).build();
        ownZKServer.startAsync();
        ownZKServer.awaitRunning();

        leaderElection.awaitTerminated();

        // After reconnect, it should not be leader
        Assert.assertFalse(leaderSem.tryAcquire(10, TimeUnit.SECONDS));
      } finally {
        zkClient.stopAsync();
        zkClient.awaitTerminated();
      }
    } finally {
      ownZKServer.stopAsync();
      ownZKServer.awaitTerminated();
    }
  }

  @Test
  public void testRace() throws InterruptedException {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    final AtomicInteger leaderCount = new AtomicInteger(0);
    final CountDownLatch completeLatch = new CountDownLatch(2);

    // Starts two threads and try to compete for leader and immediate drop leadership.
    // This is to test the case when a follower tries to watch for leader node, but the leader is already gone
    for (int i = 0; i < 2; i++) {
      final ZKClientService zkClient = ZKClientService.Builder.of(zkServer.getConnectionStr()).build();
      zkClient.startAsync();
      zkClient.awaitRunning();
      executor.execute(new Runnable() {
        @Override
        public void run() {
          try {
            for (int i = 0; i < 1000; i++) {
              final CountDownLatch leaderLatch = new CountDownLatch(1);
              LeaderElection election = new LeaderElection(zkClient, "/testRace", new ElectionHandler() {
                @Override
                public void leader() {
                  leaderCount.incrementAndGet();
                  leaderLatch.countDown();
                }

                @Override
                public void follower() {
                  // no-op
                }
              });
              election.startAsync();
              election.awaitRunning();
              Uninterruptibles.awaitUninterruptibly(leaderLatch);
              election.stopAsync();
              election.awaitTerminated();
            }
            completeLatch.countDown();
          } finally {
            zkClient.stopAsync();
            zkClient.awaitTerminated();
          }
        }
      });
    }

    try {
      Assert.assertTrue(completeLatch.await(2, TimeUnit.MINUTES));
    } finally {
      executor.shutdownNow();
    }
  }

  @BeforeClass
  public static void init() throws IOException {
    zkServer = InMemoryZKServer.builder().setDataDir(tmpFolder.newFolder()).build();
    zkServer.startAsync();
    zkServer.awaitRunning();
  }

  @AfterClass
  public static void finish() {
    zkServer.stopAsync();
    zkServer.awaitTerminated();
  }
}
