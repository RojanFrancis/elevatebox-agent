package com.elevatebox.agent.persistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Logs which database the app connected to, with a loud warning for the temporary built-in one. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseStartupLogger {

    private final StorageInfo storageInfo;

    @EventListener(ApplicationReadyEvent.class)
    public void logDatabase() {
        StorageInfo.Description database = storageInfo.describe();
        if (database.temporary()) {
            log.warn("Using the built-in {} database at {}. Calls saved here are lost when the app restarts "
                    + "or redeploys. Set DB_URL, DB_USERNAME and DB_PASSWORD to keep them.",
                    database.product(), database.location());
        } else {
            log.info("Database: {} at {}", database.product(), database.location());
        }
    }
}
