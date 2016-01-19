/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end.index;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HBaseIOException;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.SimpleRegionObserver;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.end2end.BaseHBaseManagedTimeIT;
import org.apache.phoenix.end2end.Shadower;
import org.apache.phoenix.query.BaseTest;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.TestUtil;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;


@RunWith(Parameterized.class)
public class ImmutableIndexIT extends BaseHBaseManagedTimeIT {

    private final boolean localIndex;
    private final String tableDDLOptions;
    private final String tableName;
    private final String indexName;
    private final String fullTableName;
    private final String fullIndexName;

    private static String TABLE_NAME;
    private static String INDEX_DDL;
    public static final AtomicInteger NUM_ROWS = new AtomicInteger(1);

    public ImmutableIndexIT(boolean localIndex, boolean transactional) {
        this.localIndex = localIndex;
        StringBuilder optionBuilder = new StringBuilder("IMMUTABLE_ROWS=true");
        if (transactional) {
            optionBuilder.append(", TRANSACTIONAL=true");
        }
        this.tableDDLOptions = optionBuilder.toString();
        this.tableName = TestUtil.DEFAULT_DATA_TABLE_NAME + ( transactional ?  "_TXN" : "");
        this.indexName = "IDX" + ( transactional ?  "_TXN" : "");
        this.fullTableName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, tableName);
        this.fullIndexName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, indexName);
    }

    @BeforeClass
    @Shadower(classBeingShadowed = BaseHBaseManagedTimeIT.class)
    public static void doSetup() throws Exception {
        Map<String, String> serverProps = Maps.newHashMapWithExpectedSize(1);
        serverProps.put("hbase.coprocessor.region.classes", CreateIndexRegionObserver.class.getName());
        Map<String, String> clientProps = Maps.newHashMapWithExpectedSize(2);
        clientProps.put(QueryServices.TRANSACTIONS_ENABLED, "true");
        setUpTestDriver(new ReadOnlyProps(serverProps.entrySet().iterator()), new ReadOnlyProps(clientProps.entrySet().iterator()));
    }

    @Parameters(name="localIndex = {0} , transactional = {1}")
    public static Collection<Boolean[]> data() {
        return Arrays.asList(new Boolean[][] {     
            { false, true }, { true, true }
        });
    }


    @Test
    public void testCreateIndexDuringUpsertSelect() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        props.setProperty(QueryServices.MUTATE_BATCH_SIZE_ATTRIB, Integer.toString(100));
        TABLE_NAME = fullTableName + "_testCreateIndexDuringUpsertSelect";
        String ddl ="CREATE TABLE " + TABLE_NAME + BaseTest.TEST_TABLE_SCHEMA + tableDDLOptions;
        INDEX_DDL = "CREATE " + (localIndex ? "LOCAL" : "") + " INDEX IF NOT EXISTS " + indexName + " ON " + TABLE_NAME
                + " (long_pk, varchar_pk)"
                + " INCLUDE (long_col1, long_col2)";

        Connection conn = DriverManager.getConnection(getUrl(), props);
        try {
            conn.setAutoCommit(false);
            Statement stmt = conn.createStatement();
            stmt.execute(ddl);

            upsertRows(conn, TABLE_NAME, 220);
            conn.commit();

            // run the upsert select and also create an index
            conn.setAutoCommit(true);
            String upsertSelect = "UPSERT INTO " + TABLE_NAME + "(varchar_pk, char_pk, int_pk, long_pk, decimal_pk, date_pk) " + 
                    "SELECT varchar_pk||'_upsert_select', char_pk, int_pk, long_pk, decimal_pk, date_pk FROM "+ TABLE_NAME;    
            conn.createStatement().execute(upsertSelect);

            ResultSet rs;
            rs = conn.createStatement().executeQuery("SELECT /*+ NO_INDEX */ COUNT(*) FROM " + TABLE_NAME);
            assertTrue(rs.next());
            assertEquals(440,rs.getInt(1));
            rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + TABLE_NAME);
            assertTrue(rs.next());
            assertEquals(440,rs.getInt(1));
        }
        finally {
            conn.close();
        }
    }

    // used to create an index while a batch of rows are being written
    public static class CreateIndexRegionObserver extends SimpleRegionObserver {
        @Override
        public void postPut(ObserverContext<RegionCoprocessorEnvironment> c,
                Put put, WALEdit edit, final Durability durability)
                        throws HBaseIOException {
            String tableName = c.getEnvironment().getRegion().getRegionInfo()
                    .getTable().getNameAsString();
            if (tableName.equalsIgnoreCase(TABLE_NAME)
                    // create the index after the second batch of 1000 rows
                    && Bytes.startsWith(put.getRow(), Bytes.toBytes("varchar200_upsert_select"))) {
                try {
                    Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
                    try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
                        conn.createStatement().execute(INDEX_DDL);
                    }
                } catch (SQLException e) {
                    throw new DoNotRetryIOException(e);
                } 
            }
        }
    }

    private static class UpsertRunnable implements Runnable {
        private static final int NUM_ROWS_IN_BATCH = 10000;
        private final String fullTableName;

        public UpsertRunnable(String fullTableName) {
            this.fullTableName = fullTableName;
        }

        public void run() {
            Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
            try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
                while (true) {
                    // write a large batch of rows
                    boolean fistRowInBatch = true;
                    for (int i=0; i<NUM_ROWS_IN_BATCH; ++i) {
                        BaseTest.upsertRow(conn, fullTableName, NUM_ROWS.intValue(), fistRowInBatch);
                        NUM_ROWS.incrementAndGet();
                        fistRowInBatch = false;
                    }
                    conn.commit();
                    Thread.sleep(500);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    public void testCreateIndexWhileUpsertingData() throws Exception {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        String ddl ="CREATE TABLE " + fullTableName + BaseTest.TEST_TABLE_SCHEMA + tableDDLOptions;
        String indexDDL = "CREATE " + (localIndex ? "LOCAL" : "") + " INDEX IF NOT EXISTS " + indexName + " ON " + fullTableName
                + " (long_pk, varchar_pk)"
                + " INCLUDE (long_col1, long_col2)";
        int numThreads = 3;
        try (Connection conn = DriverManager.getConnection(getUrl(), props)) {
            conn.setAutoCommit(false);
            Statement stmt = conn.createStatement();
            stmt.execute(ddl);

            ExecutorService threadPool = Executors.newFixedThreadPool(numThreads);
            List<Future<?>> futureList = Lists.newArrayListWithExpectedSize(numThreads);
            for (int i =0; i<numThreads; ++i) {
                futureList.add(threadPool.submit(new UpsertRunnable(fullTableName)));
            }
            // upsert some rows before creating the index 
            Thread.sleep(5000);

            // create the index 
            try (Connection conn2 = DriverManager.getConnection(getUrl(), props)) {
                conn2.setAutoCommit(false);
                Statement stmt2 = conn2.createStatement();
                stmt2.execute(indexDDL);
                conn2.commit();
            }

            // upsert some rows after creating the index
            Thread.sleep(1000);
            // cancel the running threads
            for (Future<?> future : futureList) {
                future.cancel(true);
            }
            threadPool.shutdownNow();
            threadPool.awaitTermination(30, TimeUnit.SECONDS);
            Thread.sleep(1000);

            ResultSet rs;
            rs = conn.createStatement().executeQuery("SELECT /*+ NO_INDEX */ COUNT(*) FROM " + fullTableName);
            assertTrue(rs.next());
            int dataTableRowCount = rs.getInt(1);
            rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + fullIndexName);
            assertTrue(rs.next());
            int indexTableRowCount = rs.getInt(1);
            assertEquals("Data and Index table should have the same number of rows ", dataTableRowCount, indexTableRowCount);
        }
    }

}
