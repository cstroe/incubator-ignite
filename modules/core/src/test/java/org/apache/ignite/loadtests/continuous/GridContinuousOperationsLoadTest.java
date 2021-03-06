/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.loadtests.continuous;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.query.*;
import org.apache.ignite.events.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.query.continuous.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.jetbrains.annotations.*;
import org.jsr166.*;

import javax.cache.event.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.apache.ignite.events.EventType.*;
import static org.apache.ignite.loadtests.util.GridLoadTestArgs.*;
import static org.apache.ignite.testframework.GridLoadTestUtils.*;
import static org.apache.ignite.testframework.GridTestUtils.*;

/**
 */
public class GridContinuousOperationsLoadTest {
    /**
     * Main method.
     *
     * @param args Command line arguments.
     * @throws Exception If error occurs.
     */
    public static void main(String[] args) throws Exception {
        final String cfgPath = args.length > 0 ? args[0] : "examples/config/example-cache.xml";
        final String cacheName = getStringProperty(CACHE_NAME, "partitioned");
        final Integer valSize = getIntProperty(VALUE_SIZE, 1024);
        final Integer threadsCnt = getIntProperty(THREADS_CNT, 8);
        final Integer testDurSec = getIntProperty(TEST_DUR_SEC, 180);

        final Integer filterSkipProb = getIntProperty("FILTER_SKIP_PROBABILITY", 10, new C1<Integer, String>() {
            @Nullable @Override public String apply(Integer val) {
                if (val < 0 || val > 100)
                    return "The value should be between 1 and 100.";

                return null;
            }
        });

        final boolean useQry = getBooleanProperty("IGNITE_USE_QUERIES", true);
        final int bufSize = getIntProperty("IGNITE_BUFFER_SIZE", 1);
        final long timeInterval = getLongProperty("IGNITE_TIME_INTERVAL", 0);
        final int parallelCnt = getIntProperty("IGNITE_PARALLEL_COUNT", 8);
        final int keyRange = getIntProperty("IGNITE_KEY_RANGE", 100000);
        final long updSleepMs = getLongProperty("IGNITE_UPDATE_SLEEP_MS", 0);
        final long filterSleepMs = getLongProperty("IGNITE_FILTER_SLEEP_MS", 0);
        final long cbSleepMs = getLongProperty("IGNITE_CALLBACK_SLEEP_MS", 0);

        X.println("The test will start with the following parameters:");

        dumpProperties(System.out);

        try (Ignite ignite = Ignition.start(cfgPath)) {
            final IgniteCache<Object, Object> cache = ignite.cache(cacheName);

            if (cache == null)
                throw new IgniteCheckedException("Cache is not configured: " + cacheName);

            // Continuous query manager, used to monitor queue size.
            final CacheContinuousQueryManager contQryMgr =
                ((GridCacheAdapter)((GridCacheProxyImpl)cache).cache()).context().continuousQueries();

            if (contQryMgr == null)
                throw new IgniteCheckedException("Could not access CacheContinuousQueryManager");

            final AtomicBoolean stop = new AtomicBoolean(); // Stop flag.
            final AtomicLong cbCntr = new AtomicLong();     // Callback counter.
            final AtomicLong updCntr = new AtomicLong();    // Update counter.

            for (int i = 0; i < parallelCnt; i++) {
                if (useQry) {
                    ContinuousQuery<Object, Object> qry = new ContinuousQuery<>();

                    qry.setLocalListener(new CacheEntryUpdatedListener<Object,Object>() {
                        @Override public void onUpdated(Iterable<CacheEntryEvent<?,?>> evts) {
                            if (cbSleepMs > 0) {
                                try {
                                    U.sleep(cbSleepMs);
                                }
                                catch (IgniteInterruptedCheckedException e) {
                                    throw new IgniteException(e);
                                }
                            }

                            for (CacheEntryEvent<?,?> ignored : evts)
                                cbCntr.incrementAndGet();
                        }
                    });

                    qry.setRemoteFilter(new CacheEntryEventSerializableFilter<Object,Object>() {
                        @Override public boolean evaluate(CacheEntryEvent<?,?> evt) {
                            if (filterSleepMs > 0) {
                                try {
                                    U.sleep(filterSleepMs);
                                }
                                catch (IgniteInterruptedCheckedException e) {
                                    throw new IgniteException(e);
                                }
                            }

                            return Math.random() * 100 >= filterSkipProb;
                        }
                    });

                    qry.setPageSize(bufSize);
                    qry.setTimeInterval(timeInterval);

                    cache.query(qry);
                }
                else {
                    ignite.events().remoteListen(
                        bufSize,
                        timeInterval,
                        true,
                        new PX2<UUID, Event>() {
                            @Override public boolean applyx(UUID uuid, Event evt)
                                throws IgniteInterruptedCheckedException {
                                if (cbSleepMs > 0)
                                    U.sleep(cbSleepMs);

                                cbCntr.incrementAndGet();

                                return true; // Continue listening.
                            }
                        },
                        new PX1<Event>() {
                            @Override public boolean applyx(Event evt) throws IgniteInterruptedCheckedException {
                                if (filterSleepMs > 0)
                                    U.sleep(filterSleepMs);

                                return Math.random() * 100 >= filterSkipProb;
                            }
                        },
                        EVT_CACHE_OBJECT_PUT
                    );
                }
            }

            // Start collector thread.
            startDaemon(new Runnable() {
                @Override public void run() {
                    try {
                        while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                            long cbCntr0 = cbCntr.get();
                            long updCntr0 = updCntr.get();

                            U.sleep(1000);

                            long cbDelta = cbCntr.get() - cbCntr0;
                            long updDelta = updCntr.get() - updCntr0;

                            X.println("Stats [entriesPerSec=" + cbDelta +
                                ", updatesPerSec=" + updDelta + ']');
                        }
                    }
                    catch (IgniteInterruptedCheckedException ignored) {
                        // No-op.
                    }
                }
            });

            X.println("Starting " + threadsCnt + " generator thread(s).");

            // Start generator threads.
            IgniteInternalFuture<Long> genFut = runMultiThreadedAsync(new Callable<Object>() {
                @Override public Object call() throws Exception {
                    byte[] val = new byte[valSize];
                    ThreadLocalRandom8 rnd = ThreadLocalRandom8.current();

                    while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                        Integer key = rnd.nextInt(keyRange);

                        cache.put(key, val);

                        updCntr.incrementAndGet();

                        if (updSleepMs > 0)
                            U.sleep(updSleepMs);
                    }

                    return true;
                }
            }, threadsCnt, "load-test-generator");

            U.sleep(testDurSec * 1000);

            stop.set(true);

            genFut.get();
        }
    }
}
