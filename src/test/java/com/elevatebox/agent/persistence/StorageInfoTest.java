package com.elevatebox.agent.persistence;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageInfoTest {

    private static StorageInfo databaseReporting(String product, String url) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn(product);
        when(meta.getURL()).thenReturn(url);
        return new StorageInfo(dataSource);
    }

    @Test
    void mysqlIsReportedWithoutOptionsOrSecrets() throws Exception {
        StorageInfo info = databaseReporting("MySQL",
                "jdbc:mysql://host.example:4000/elevatebox_db?sslMode=VERIFY_IDENTITY&password=hunter2");

        StorageInfo.Description description = info.describe();

        assertEquals("MySQL", description.product());
        assertEquals("jdbc:mysql://host.example:4000/elevatebox_db", description.location());
        assertFalse(description.temporary());
        assertFalse(description.location().contains("hunter2"));
    }

    @Test
    void h2IsFlaggedAsTemporary() throws Exception {
        StorageInfo.Description description =
                databaseReporting("H2", "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1").describe();

        assertTrue(description.temporary());
        assertEquals("jdbc:h2:mem:testdb", description.location());
    }

    @Test
    void credentialsInTheAddressAreRemoved() {
        assertEquals("jdbc:mysql://host/db", StorageInfo.sanitize("jdbc:mysql://user:secret@host/db?x=1"));
        assertEquals("", StorageInfo.sanitize(null));
    }

    @Test
    void anUnreachableDatabaseDoesNotThrow() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));

        StorageInfo.Description description = new StorageInfo(dataSource).describe();

        assertEquals("unknown", description.product());
        assertFalse(description.temporary());
    }
}
