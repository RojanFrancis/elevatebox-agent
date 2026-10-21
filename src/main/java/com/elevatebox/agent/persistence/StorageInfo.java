package com.elevatebox.agent.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

/**
 * Says which database the app is really connected to.
 *
 * <p>Spring Boot quietly falls back to a built-in H2 database when no database settings are
 * given. That is convenient on a laptop and a trap on a server: the app starts, saves calls, and
 * loses them at the next restart. This class makes the fallback visible, in the startup log and
 * on the leads page.
 *
 * <p>Only the product name and the address without query string or credentials are exposed.
 */
@Component
@RequiredArgsConstructor
public class StorageInfo {

    private final DataSource dataSource;

    public Description describe() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            String product = meta.getDatabaseProductName();
            return new Description(product, sanitize(meta.getURL()), "H2".equalsIgnoreCase(product));
        } catch (Exception e) {
            return new Description("unknown", "could not read the database details", false);
        }
    }

    /** Drops credentials in the address and everything after the first ? or ; (options may hold secrets). */
    static String sanitize(String url) {
        if (url == null) {
            return "";
        }
        String withoutUser = url.replaceAll("://[^/@]*@", "://");
        int cut = withoutUser.length();
        for (char separator : new char[] {'?', ';'}) {
            int index = withoutUser.indexOf(separator);
            if (index >= 0 && index < cut) {
                cut = index;
            }
        }
        return withoutUser.substring(0, cut);
    }

    /**
     * @param product   database product name, for example MySQL or H2
     * @param location  the address without credentials or options
     * @param temporary true for the built-in H2 database, whose contents do not survive a redeploy
     */
    public record Description(String product, String location, boolean temporary) {}
}
