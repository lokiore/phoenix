package org.apache.phoenix.end2end;

import org.apache.hadoop.hbase.TableName;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.TestUtil;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import static org.apache.phoenix.exception.SQLExceptionCode.CANNOT_SET_OR_ALTER_PHOENIX_TTL;
import static org.apache.phoenix.exception.SQLExceptionCode.CANNOT_SET_OR_ALTER_PROPERTY_FOR_INDEX;
import static org.apache.phoenix.exception.SQLExceptionCode.PHOENIX_TTL_SUPPORTED_FOR_TABLES_ONLY;
import static org.apache.phoenix.exception.SQLExceptionCode.VIEW_WITH_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@Category(ParallelStatsDisabledTest.class)
public class TTLAsPhoenixTTLIT extends ParallelStatsDisabledIT{

    private static final long DEFAULT_TTL_FOR_TEST = 86400;

    /**
     * test TTL is being set as PhoenixTTL when PhoenixTTL is enabled.
     */
    @Test
    public void testCreateTableWithTTL() throws Exception {
        try (Connection conn = DriverManager.getConnection(getUrl());) {
            assertEquals("TTL is not set correctly at Phoenix level", DEFAULT_TTL_FOR_TEST,
                    conn.unwrap(PhoenixConnection.class).getTable(new PTableKey(null,
                            createTableWithOrWithOutTTLAsItsProperty(conn, true))).getPhoenixTTL());
        }
    }

    /**
     * Tests that when: 1) DDL has both pk as well as key value columns 2) Key value columns have
     *      * both default and explicit column family names 3) TTL specifier doesn't have column family
     *      * name. Then it should not affect TTL being set at Phoenix Level.
     */
    @Test
    public void  testCreateTableWithTTLWithDifferentColumnFamilies() throws  Exception {
        String tableName = generateUniqueName();
        String ddl =
                "create table IF NOT EXISTS  " + tableName + "  (" + " id char(1) NOT NULL,"
                        + " col1 integer NOT NULL," + " b.col2 bigint," + " col3 bigint, "
                        + " CONSTRAINT NAME_PK PRIMARY KEY (id, col1)"
                        + " ) TTL=86400";
        Connection conn = DriverManager.getConnection(getUrl());
        conn.createStatement().execute(ddl);
        assertTTLValueOfTableOrView(conn.unwrap(PhoenixConnection.class), DEFAULT_TTL_FOR_TEST, tableName);
    }

    @Test
    public void testSettingTTLAsAlterTableCommand() throws Exception {
        try (Connection conn = DriverManager.getConnection(getUrl(), new Properties());
             PhoenixConnection pConn = conn.unwrap(PhoenixConnection.class);){
            String tableName = createTableWithOrWithOutTTLAsItsProperty(conn, false);
            //Checking Default TTL in case of PhoenixTTLEnabled
            assertTTLValueOfTableOrView(conn.unwrap(PhoenixConnection.class), PhoenixDatabaseMetaData.PHOENIX_TTL_NOT_DEFINED, tableName);
            String ddl = "ALTER TABLE  " + tableName
                    + " SET TTL=1000";
            conn.createStatement().execute(ddl);
            assertTTLValueOfTableOrView(conn.unwrap(PhoenixConnection.class), 1000, tableName);
        }
    }

    @Test
    public void testSettingTTLForIndexes() throws Exception {
        try (Connection conn = DriverManager.getConnection(getUrl())){
            String tableName = createTableWithOrWithOutTTLAsItsProperty(conn, true);

            //By default, Indexes should set TTL what Base Table has
            createIndexOnTableOrViewProvidedWithTTL(conn, tableName, PTable.IndexType.LOCAL, false);
            createIndexOnTableOrViewProvidedWithTTL(conn, tableName, PTable.IndexType.GLOBAL, false);
            List<PTable> indexes = conn.unwrap(PhoenixConnection.class).getTable(
                    new PTableKey(null, tableName)).getIndexes();
            for (PTable index : indexes) {
                assertTTLValueOfIndex(DEFAULT_TTL_FOR_TEST, index);;
            }

            tableName = createTableWithOrWithOutTTLAsItsProperty(conn, false);

            String localIndexName = createIndexOnTableOrViewProvidedWithTTL(conn, tableName, PTable.IndexType.LOCAL, false);
            String globalIndexName = createIndexOnTableOrViewProvidedWithTTL(conn, tableName, PTable.IndexType.GLOBAL, false);
            indexes = conn.unwrap(PhoenixConnection.class).getTable(new PTableKey(null, tableName)).getIndexes();
            for (PTable index : indexes) {
                assertTTLValueOfIndex(PhoenixDatabaseMetaData.PHOENIX_TTL_NOT_DEFINED, index);
            }

            //Test setting TTL as index property not allowed while creating them or setting them explicitly.
            try {
                conn.createStatement().execute("ALTER TABLE " + localIndexName + " SET TTL = 1000");
                fail();
            } catch (SQLException sqe) {
                assertEquals("Should fail with cannot set or alter property for index",
                        CANNOT_SET_OR_ALTER_PROPERTY_FOR_INDEX.getErrorCode(), sqe.getErrorCode());
            }

            try {
                conn.createStatement().execute("ALTER TABLE " + globalIndexName + " SET TTL = 1000");
                fail();
            } catch (SQLException sqe) {
                assertEquals("Should fail with cannot set or alter property for index",
                        CANNOT_SET_OR_ALTER_PROPERTY_FOR_INDEX.getErrorCode(), sqe.getErrorCode());
            }

            try {
                createIndexOnTableOrViewProvidedWithTTL(conn, tableName, PTable.IndexType.LOCAL, true);
                fail();
            } catch (SQLException sqe) {
                assertEquals("Should fail with cannot set or alter property for index",
                        CANNOT_SET_OR_ALTER_PROPERTY_FOR_INDEX.getErrorCode(), sqe.getErrorCode());
            }

            try {
                createIndexOnTableOrViewProvidedWithTTL(conn, tableName, PTable.IndexType.GLOBAL, true);
                fail();
            } catch (SQLException sqe) {
                assertEquals("Should fail with cannot set or alter property for index",
                        CANNOT_SET_OR_ALTER_PROPERTY_FOR_INDEX.getErrorCode(), sqe.getErrorCode());
            }

        }
    }


    @Test
    public void testSettingTTLForViews() throws Exception {
        try (Connection conn = DriverManager.getConnection(getUrl())) {
            String tenantID = generateUniqueName();
            String tenantID1 = generateUniqueName();

            Properties props = new Properties();
            props.setProperty(PhoenixRuntime.TENANT_ID_ATTRIB, tenantID);
            Connection tenantConn = DriverManager.getConnection(getUrl(), props);

            Properties props1 = new Properties();
            props1.setProperty(PhoenixRuntime.TENANT_ID_ATTRIB, tenantID1);
            Connection tenantConn1 = DriverManager.getConnection(getUrl(), props1);

            String tableName = createTableWithOrWithOutTTLAsItsProperty(conn, true);

            //View gets TTL value from its hierarchy
            String viewName = createViewOnTableWithTTL(conn, tableName, false);
            assertTTLValueOfTableOrView(conn.unwrap(PhoenixConnection.class),
                    DEFAULT_TTL_FOR_TEST, viewName);

            //Index on Global View should get TTL from View.
            createIndexOnTableOrViewProvidedWithTTL(conn, viewName, PTable.IndexType.GLOBAL,
                    false);
            createIndexOnTableOrViewProvidedWithTTL(conn, viewName, PTable.IndexType.LOCAL,
                    false);
            List<PTable> indexes = conn.unwrap(PhoenixConnection.class).getTable(
                    new PTableKey(null, viewName)).getIndexes();
            for (PTable index : indexes) {
                assertTTLValueOfIndex(DEFAULT_TTL_FOR_TEST, index);
            }

            //Child View gets TTL from parent View which gets from Table.
            String childView = createViewOnViewWithTTL(tenantConn, viewName, false);
            assertTTLValueOfTableOrView(tenantConn.unwrap(PhoenixConnection.class),
                    DEFAULT_TTL_FOR_TEST, childView);

            String childView1 = createViewOnViewWithTTL(tenantConn1, viewName, false);
            assertTTLValueOfTableOrView(tenantConn1.unwrap(PhoenixConnection.class),
                    DEFAULT_TTL_FOR_TEST, childView1);

            //Setting TTL on Views should not be allowed.

            try {
                createViewOnTableWithTTL(conn, tableName, true);
                fail();
            } catch (SQLException sqe) {
                assertEquals("Should fail with TTL supported for tables only",
                        PHOENIX_TTL_SUPPORTED_FOR_TABLES_ONLY.getErrorCode(), sqe.getErrorCode());
            }

            try {
                conn.createStatement().execute("ALTER VIEW " + viewName + " SET TTL = 1000");
                fail();
            } catch (SQLException sqe) {
                assertEquals("Cannot Set or Alter TTL on Views",
                        VIEW_WITH_PROPERTIES.getErrorCode(), sqe.getErrorCode());
            }

            try {
                createIndexOnTableOrViewProvidedWithTTL(conn, viewName, PTable.IndexType.GLOBAL,true);
                fail();
            } catch (SQLException sqe) {
                assertEquals("Should fail with Cannot set or Alter property for index",
                        CANNOT_SET_OR_ALTER_PROPERTY_FOR_INDEX.getErrorCode(), sqe.getErrorCode());
            }
        }
    }

    private void assertTTLValueOfTableOrView(PhoenixConnection conn, long expected, String name) throws SQLException {
        assertEquals("TTL value did not match :-", expected,
                conn.getTable(new PTableKey(conn.getTenantId(), name)).getPhoenixTTL());
    }

    private void assertTTLValueOfIndex(long expected, PTable index) {
        assertEquals("TTL value is not what expected :-", expected, index.getPhoenixTTL());
    }


    private String createTableWithOrWithOutTTLAsItsProperty(Connection conn, boolean withTTL) throws SQLException {
        String tableName = generateUniqueName();
        conn.createStatement().execute("CREATE TABLE IF NOT EXISTS  " + tableName + "  ("
                + " ID INTEGER NOT NULL,"
                + " COL1 INTEGER NOT NULL,"
                + " COL2 bigint NOT NULL,"
                + " CREATED_DATE DATE,"
                + " CREATION_TIME BIGINT,"
                + " CONSTRAINT NAME_PK PRIMARY KEY (ID, COL1, COL2))"
                + ( withTTL ? " TTL = " + DEFAULT_TTL_FOR_TEST : ""));
        return tableName;
    }

    private String createIndexOnTableOrViewProvidedWithTTL(Connection conn, String baseTableOrViewName, PTable.IndexType indexType,
                                                           boolean withTTL) throws SQLException {
        switch (indexType) {
            case LOCAL:
                String localIndexName = baseTableOrViewName + "_Local_" + generateUniqueName();
                conn.createStatement().execute("CREATE LOCAL INDEX " + localIndexName + " ON " +
                        baseTableOrViewName + " (COL1) " + (withTTL ? "TTL = 1000" : ""));
                return localIndexName;

            case GLOBAL:
                String globalIndexName = baseTableOrViewName + "_Global_" + generateUniqueName();
                conn.createStatement().execute("CREATE INDEX " + globalIndexName + " ON " +
                        baseTableOrViewName + " (COL1) " + (withTTL ? "TTL = 1000" : ""));
                return globalIndexName;

            default:
                return baseTableOrViewName;
        }
    }

    private String createViewOnTableWithTTL(Connection conn, String baseTableName,
                                            boolean withTTL) throws SQLException {
        String viewName = "VIEW_" + baseTableName + "_" + generateUniqueName();
        conn.createStatement().execute("CREATE VIEW " + viewName
                + " (" + generateUniqueName() + " SMALLINT) as select * from "
                + baseTableName + " where id > 1 "
                + (withTTL ? "TTL = 1000" : "") );
        return viewName;
    }

    private String createViewOnViewWithTTL(Connection conn, String parentViewName,
                                           boolean withTTL) throws SQLException {
        String childView = parentViewName + "_" + generateUniqueName();
        conn.createStatement().execute("CREATE VIEW " + childView +
                " (E BIGINT, F BIGINT) AS SELECT * FROM " + parentViewName);
        return childView;
    }

}