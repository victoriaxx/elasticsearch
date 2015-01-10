/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.indices.settings;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.lucene.util.LuceneTestCase.Slow;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.node.stats.NodeStats;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.index.merge.policy.TieredMergePolicyProvider;
import org.elasticsearch.index.merge.scheduler.ConcurrentMergeSchedulerProvider;
import org.elasticsearch.index.merge.scheduler.MergeSchedulerModule;
import org.elasticsearch.index.store.support.AbstractIndexStore;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.junit.Test;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertThrows;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class UpdateSettingsTests extends ElasticsearchIntegrationTest {

    @Test
    public void testOpenCloseUpdateSettings() throws Exception {
        createIndex("test");
        try {
            client().admin().indices().prepareUpdateSettings("test")
                    .setSettings(ImmutableSettings.settingsBuilder()
                            .put("index.refresh_interval", -1) // this one can change
                            .put("index.cache.filter.type", "none") // this one can't
                    )
                    .execute().actionGet();
            fail();
        } catch (ElasticsearchIllegalArgumentException e) {
            // all is well
        }

        IndexMetaData indexMetaData = client().admin().cluster().prepareState().execute().actionGet().getState().metaData().index("test");
        assertThat(indexMetaData.settings().get("index.refresh_interval"), nullValue());
        assertThat(indexMetaData.settings().get("index.cache.filter.type"), nullValue());

        // Now verify via dedicated get settings api:
        GetSettingsResponse getSettingsResponse = client().admin().indices().prepareGetSettings("test").get();
        assertThat(getSettingsResponse.getSetting("test", "index.refresh_interval"), nullValue());
        assertThat(getSettingsResponse.getSetting("test", "index.cache.filter.type"), nullValue());

        client().admin().indices().prepareUpdateSettings("test")
                .setSettings(ImmutableSettings.settingsBuilder()
                        .put("index.refresh_interval", -1) // this one can change
                )
                .execute().actionGet();

        indexMetaData = client().admin().cluster().prepareState().execute().actionGet().getState().metaData().index("test");
        assertThat(indexMetaData.settings().get("index.refresh_interval"), equalTo("-1"));
        // Now verify via dedicated get settings api:
        getSettingsResponse = client().admin().indices().prepareGetSettings("test").get();
        assertThat(getSettingsResponse.getSetting("test", "index.refresh_interval"), equalTo("-1"));

        // now close the index, change the non dynamic setting, and see that it applies

        // Wait for the index to turn green before attempting to close it
        ClusterHealthResponse health = client().admin().cluster().prepareHealth().setTimeout("30s").setWaitForEvents(Priority.LANGUID).setWaitForGreenStatus().execute().actionGet();
        assertThat(health.isTimedOut(), equalTo(false));

        client().admin().indices().prepareClose("test").execute().actionGet();

        client().admin().indices().prepareUpdateSettings("test")
                .setSettings(ImmutableSettings.settingsBuilder()
                        .put("index.refresh_interval", "1s") // this one can change
                        .put("index.cache.filter.type", "none") // this one can't
                )
                .execute().actionGet();

        indexMetaData = client().admin().cluster().prepareState().execute().actionGet().getState().metaData().index("test");
        assertThat(indexMetaData.settings().get("index.refresh_interval"), equalTo("1s"));
        assertThat(indexMetaData.settings().get("index.cache.filter.type"), equalTo("none"));

        // Now verify via dedicated get settings api:
        getSettingsResponse = client().admin().indices().prepareGetSettings("test").get();
        assertThat(getSettingsResponse.getSetting("test", "index.refresh_interval"), equalTo("1s"));
        assertThat(getSettingsResponse.getSetting("test", "index.cache.filter.type"), equalTo("none"));
    }

    @Test
    public void testEngineGCDeletesSetting() throws InterruptedException {
        createIndex("test");
        client().prepareIndex("test", "type", "1").setSource("f", 1).get(); // set version to 1
        client().prepareDelete("test", "type", "1").get(); // sets version to 2
        client().prepareIndex("test", "type", "1").setSource("f", 2).setVersion(2).get(); // delete is still in cache this should work & set version to 3
        client().admin().indices().prepareUpdateSettings("test")
                .setSettings(ImmutableSettings.settingsBuilder()
                        .put("index.gc_deletes", 0)
                ).get();

        client().prepareDelete("test", "type", "1").get(); // sets version to 4
        Thread.sleep(300); // wait for cache time to change TODO: this needs to be solved better. To be discussed.
        assertThrows(client().prepareIndex("test", "type", "1").setSource("f", 3).setVersion(4), VersionConflictEngineException.class); // delete is should not be in cache

    }

    private static class MockAppender extends AppenderSkeleton {
        public boolean sawIndexWriterMessage;
        public boolean sawFlushDeletes;
        public boolean sawMergeThreadPaused;
        public boolean sawUpdateMaxThreadCount;
        public boolean sawUpdateAutoThrottle;

        @Override
        protected void append(LoggingEvent event) {
            String message = event.getMessage().toString();
            if (event.getLevel() == Level.TRACE &&
                event.getLoggerName().endsWith("lucene.iw")) {
                sawFlushDeletes |= message.contains("IW: apply all deletes during flush");
                sawMergeThreadPaused |= message.contains("CMS: pause thread");
            }
            if (event.getLevel() == Level.INFO && message.contains("updating [index.merge.scheduler.max_thread_count] from [10000] to [1]")) {
                sawUpdateMaxThreadCount = true;
            }
            if (event.getLevel() == Level.INFO && message.contains("updating [index.merge.scheduler.auto_throttle] from [true] to [false]")) {
                sawUpdateAutoThrottle = true;
            }
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }

        @Override
        public void close() {
        }
    }

    @Test
    public void testUpdateAutoThrottleSettings() {

        MockAppender mockAppender = new MockAppender();
        Logger rootLogger = Logger.getRootLogger();
        Level savedLevel = rootLogger.getLevel();
        rootLogger.addAppender(mockAppender);
        rootLogger.setLevel(Level.TRACE);

        try {
            // No throttling at first, only 1 non-replicated shard, force lots of merging:
            assertAcked(prepareCreate("test")
                        .setSettings(ImmutableSettings.builder()
                                     .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, "1")
                                     .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, "0")
                                     .put(TieredMergePolicyProvider.INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE, "2")
                                     .put(TieredMergePolicyProvider.INDEX_MERGE_POLICY_SEGMENTS_PER_TIER, "2")
                                     .put(ConcurrentMergeSchedulerProvider.MAX_THREAD_COUNT, "1")
                                     .put(ConcurrentMergeSchedulerProvider.MAX_MERGE_COUNT, "2")
                                     ));

            // Disable auto throttle:
            client()
                .admin()
                .indices()
                .prepareUpdateSettings("test")
                .setSettings(ImmutableSettings.builder()
                             .put(ConcurrentMergeSchedulerProvider.AUTO_THROTTLE, "no"))
                .get();

            assertTrue(mockAppender.sawUpdateAutoThrottle);

            // Make sure setting says it is in fact changed:
            GetSettingsResponse getSettingsResponse = client().admin().indices().prepareGetSettings("test").get();
            assertThat(getSettingsResponse.getSetting("test", ConcurrentMergeSchedulerProvider.AUTO_THROTTLE), equalTo("no"));
        } finally {
            rootLogger.removeAppender(mockAppender);
            rootLogger.setLevel(savedLevel);
        }
    }

    // #6882: make sure we can change index.merge.scheduler.max_thread_count live
    @Test
    public void testUpdateMergeMaxThreadCount() {

        MockAppender mockAppender = new MockAppender();
        Logger rootLogger = Logger.getRootLogger();
        Level savedLevel = rootLogger.getLevel();
        rootLogger.addAppender(mockAppender);
        rootLogger.setLevel(Level.TRACE);

        try {

            assertAcked(prepareCreate("test")
                        .setSettings(ImmutableSettings.builder()
                                     .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, "1")
                                     .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, "0")
                                     .put(TieredMergePolicyProvider.INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE, "2")
                                     .put(TieredMergePolicyProvider.INDEX_MERGE_POLICY_SEGMENTS_PER_TIER, "2")
                                     .put(MergeSchedulerModule.MERGE_SCHEDULER_TYPE_KEY, ConcurrentMergeSchedulerProvider.class)
                                     .put(ConcurrentMergeSchedulerProvider.MAX_THREAD_COUNT, "10000")
                                     .put(ConcurrentMergeSchedulerProvider.MAX_MERGE_COUNT, "10000")
                                     ));

            assertFalse(mockAppender.sawUpdateMaxThreadCount);

            // Now make a live change to reduce allowed merge threads:
            client()
                .admin()
                .indices()
                .prepareUpdateSettings("test")
                .setSettings(ImmutableSettings.builder()
                             .put(ConcurrentMergeSchedulerProvider.MAX_THREAD_COUNT, "1")
                             )
                .get();

            // Make sure we log the change:
            assertTrue(mockAppender.sawUpdateMaxThreadCount);

            client()
                .admin()
                .indices()
                .prepareUpdateSettings("test")
                .setSettings(ImmutableSettings.builder()
                             .put(ConcurrentMergeSchedulerProvider.MAX_THREAD_COUNT, "3")
                             )
                .get();

            // Wait for merges to finish
            client().admin().indices().prepareOptimize("test").setWaitForMerge(true).get();

        } finally {
            rootLogger.removeAppender(mockAppender);
            rootLogger.setLevel(savedLevel);
        }
    }
}
