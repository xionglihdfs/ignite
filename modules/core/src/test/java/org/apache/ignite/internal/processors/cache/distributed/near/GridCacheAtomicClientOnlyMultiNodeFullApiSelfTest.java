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

package org.apache.ignite.internal.processors.cache.distributed.near;

import com.google.common.collect.*;
import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.events.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;

import javax.cache.expiry.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static java.util.concurrent.TimeUnit.*;
import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheDistributionMode.*;
import static org.apache.ignite.events.IgniteEventType.*;

/**
 *
 */
public class GridCacheAtomicClientOnlyMultiNodeFullApiSelfTest extends GridCacheNearOnlyMultiNodeFullApiSelfTest {
    /** {@inheritDoc} */
    @Override protected CacheConfiguration cacheConfiguration(String gridName) throws Exception {
        CacheConfiguration cfg = super.cacheConfiguration(gridName);

        if (cfg.getDistributionMode() == NEAR_ONLY)
            cfg.setDistributionMode(CLIENT_ONLY);
        else
            cfg.setDistributionMode(PARTITIONED_ONLY);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected CacheAtomicityMode atomicityMode() {
        return ATOMIC;
    }

    /** {@inheritDoc} */
    @Override protected boolean lockingEnabled() {
        return false;
    }

    /** {@inheritDoc} */
    @Override protected boolean txEnabled() {
        return false;
    }

    /** {@inheritDoc} */
    @Override protected CacheDistributionMode distributionMode() {
        return PARTITIONED_ONLY;
    }

    /** {@inheritDoc} */
    @Override public void testSize() throws Exception {
        IgniteCache<String, Integer> cache = jcache();

        int size = 10;

        Map<String, Integer> map = U.newLinkedHashMap(size);

        for (int i = 0; i < size; i++)
            map.put("key" + i, i);

        cache.putAll(map);

        affinityNodes(); // Just to ack cache configuration to log..

        checkKeySize(map.keySet());

        checkSize(map.keySet());

        int fullCacheSize = 0;

        for (int i = 0; i < gridCount(); i++)
            fullCacheSize += jcache(i).localSize();

        assertEquals("Invalid cache size", fullCacheSize, cache.size());
    }

    /** {@inheritDoc} */
    @Override public void testClear() throws Exception {
        IgniteCache<String, Integer> nearCache = jcache();
        IgniteCache<String, Integer> primary = fullCache();

        Collection<String> keys = primaryKeysForCache(primary, 3);

        Map<String, Integer> vals = new HashMap<>();

        int i = 0;

        for (String key : keys) {
            nearCache.put(key, i);

            vals.put(key, i);

            i++;
        }

        for (String key : keys)
            assertEquals(null, nearCache.localPeek(key));

        nearCache.clear();

        for (String key : keys)
            assertNull(nearCache.localPeek(key));

        for (Map.Entry<String, Integer> entry : vals.entrySet())
            nearCache.put(entry.getKey(), entry.getValue());

        for (String key : keys)
            assertEquals(null, nearCache.localPeek(key));

    }

    /** {@inheritDoc} */
    @Override public void testClearKeys() throws Exception {
        IgniteCache<String, Integer> nearCache = jcache();
        IgniteCache<String, Integer> primary = fullCache();

        Collection<String> keys = primaryKeysForCache(primary, 3);

        for (String key : keys)
            assertNull(nearCache.get(key));

        String lastKey = F.last(keys);

        Collection<String> subKeys = new ArrayList<>(keys);

        subKeys.remove(lastKey);

        Map<String, Integer> vals = new HashMap<>(keys.size());

        int i = 0;

        for (String key : keys)
            vals.put(key, i++);

        nearCache.putAll(vals);

        for (String subKey : subKeys)
            nearCache.clear(Collections.singleton(subKey));

        for (String key : subKeys) {
            assertNull(nearCache.localPeek(key));
            assertNotNull(primary.localPeek(key));
        }

        assertEquals(null, nearCache.localPeek(lastKey));
    }

    /** {@inheritDoc} */
    @Override public void testEvictExpired() throws Exception {
        IgniteCache<String, Integer> cache = jcache();

        String key = primaryKeysForCache(cache, 1).get(0);

        cache.put(key, 1);

        assertEquals((Integer)1, cache.get(key));

        long ttl = 500;

        grid(0).jcache(null).
            withExpiryPolicy(new TouchedExpiryPolicy(new Duration(MILLISECONDS, ttl))).put(key, 1);

        Thread.sleep(ttl + 100);

        // Expired entry should not be swapped.
        cache.localEvict(Collections.singleton(key));

        assertNull(cache.localPeek(key));

        cache.localPromote(Collections.singleton(key));

        assertNull(cache.localPeek(key));

        assertTrue(cache.localSize() == 0);

        // Force reload on primary node.
        for (int i = 0; i < gridCount(); i++) {
            if (ignite(i).affinity(null).isPrimary(ignite(i).cluster().localNode(), key))
                load(jcache(i), key, true);
        }

        // Will do near get request.
        load(cache, key, true);

        assertEquals(null, cache.localPeek(key));
    }

    /** {@inheritDoc} */
    @Override public void testEvictAll() throws Exception {
        IgniteCache<String, Integer> cache = jcache();

        List<String> keys = primaryKeysForCache(cache, 3);

        String key1 = keys.get(0);
        String key2 = keys.get(1);
        String key3 = keys.get(2);

        cache.put(key1, 1);
        cache.put(key2, 2);
        cache.put(key3, 3);

        assert cache.localPeek(key1) == null;
        assert cache.localPeek(key2) == null;
        assert cache.localPeek(key3) == null;

        cache.localEvict(F.asList(key1, key2));

        assert cache.localPeek(key1) == null;
        assert cache.localPeek(key2) == null;
        assert cache.localPeek(key3) == null;

        loadAll(cache, ImmutableSet.of(key1, key2), true);

        assert cache.localPeek(key1) == null;
        assert cache.localPeek(key2) == null;
        assert cache.localPeek(key3) == null;

        cache.localEvict(F.asList(key1, key2));

        assert cache.localPeek(key1) == null;
        assert cache.localPeek(key2) == null;
        assert cache.localPeek(key3) == null;

        loadAll(cache, ImmutableSet.of(key1, key2), true);

        assert cache.localPeek(key1) == null;
        assert cache.localPeek(key2) == null;
        assert cache.localPeek(key3) == null;

        cache.localEvict(new HashSet<>(keys));

        assert cache.localPeek(key1) == null;
        assert cache.localPeek(key2) == null;
        assert cache.localPeek(key3) == null;
    }

    /** {@inheritDoc} */
    @Override public void testPeekExpired() throws Exception {
        IgniteCache<String, Integer> c = jcache();

        String key = primaryKeysForCache(c, 1).get(0);

        info("Using key: " + key);

        c.put(key, 1);

        assertEquals(null, c.localPeek(key));

        long ttl = 500;

        grid(0).jcache(null).
            withExpiryPolicy(new TouchedExpiryPolicy(new Duration(MILLISECONDS, ttl))).put(key, 1);

        Thread.sleep(ttl + 100);

        assert c.localPeek(key) == null;

        assert c.localSize() == 0 : "Cache is not empty.";
    }

    /** {@inheritDoc} */
    @Override public void testUnswap() throws Exception {
        IgniteCache<String, Integer> cache = jcache();

        List<String> keys = primaryKeysForCache(cache, 3);

        String k1 = keys.get(0);
        String k2 = keys.get(1);
        String k3 = keys.get(2);

        cache.put(k1, 1);
        cache.put(k2, 2);
        cache.put(k3, 3);

        final AtomicInteger swapEvts = new AtomicInteger(0);
        final AtomicInteger unswapEvts = new AtomicInteger(0);

        Collection<String> locKeys = new HashSet<>();

        for (int i = 0; i < gridCount(); i++) {
            grid(i).events().localListen(new IgnitePredicate<IgniteEvent>() {
                @Override public boolean apply(IgniteEvent evt) {
                    info("Received event: " + evt);

                    switch (evt.type()) {
                        case EVT_CACHE_OBJECT_SWAPPED:
                            swapEvts.incrementAndGet();

                            break;
                        case EVT_CACHE_OBJECT_UNSWAPPED:
                            unswapEvts.incrementAndGet();

                            break;
                    }

                    return true;
                }
            }, EVT_CACHE_OBJECT_SWAPPED, EVT_CACHE_OBJECT_UNSWAPPED);
        }

        cache.localEvict(Collections.<String>singleton(k2));
        cache.localEvict(Collections.<String>singleton(k3));

        assert !cache.containsKey(k1);
        assert !cache.containsKey(k2);
        assert !cache.containsKey(k3);

        int cnt = 0;

        if (locKeys.contains(k2)) {
            cache.localPromote(Collections.singleton(k2));

            assertEquals((Integer)2, cache.localPeek(k2));

            cnt++;
        }
        else {
            cache.localPromote(Collections.singleton(k2));

            assertNull(cache.localPeek(k2));
        }


        if (locKeys.contains(k3)) {
            cache.localPromote(Collections.singleton(k3));

            assertEquals((Integer)3, cache.localPeek(k3));

            cnt++;
        }
        else {
            cache.localPromote(Collections.singleton(k3));

            assertNull(cache.localPeek(k3));
        }

        assertEquals(cnt, swapEvts.get());
        assertEquals(cnt, unswapEvts.get());

        cache.localEvict(Collections.<String>singleton(k1));

        assertEquals((Integer)1, cache.get(k1));

        if (locKeys.contains(k1))
            cnt++;

        assertEquals(cnt, swapEvts.get());
        assertEquals(cnt, unswapEvts.get());

        cache.clear();

        // Check with multiple arguments.
        cache.put(k1, 1);
        cache.put(k2, 2);
        cache.put(k3, 3);

        swapEvts.set(0);
        unswapEvts.set(0);

        cache.localEvict(Collections.<String>singleton(k2));
        cache.localEvict(Collections.<String>singleton(k3));

        assert !cache.containsKey(k1);
        assert !cache.containsKey(k2);
        assert !cache.containsKey(k3);

        cache.localPromote(ImmutableSet.of(k2, k3));

        cnt = 0;

        if (locKeys.contains(k2))
            cnt++;

        if (locKeys.contains(k3))
            cnt++;

        assertEquals(cnt, swapEvts.get());
        assertEquals(cnt, unswapEvts.get());
    }
}
