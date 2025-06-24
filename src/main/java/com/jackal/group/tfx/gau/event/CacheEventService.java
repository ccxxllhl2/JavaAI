package com.jackal.group.tfx.gau.event;

import com.jackal.group.tfx.gau.util.CacheUtil;
import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class CacheEventService {
    private final MessagePublisher messagePublisher;

    private final R2dbcEntityTemplate entityTemplate;

    public String tryCache(String key) {
        var res = entityTemplate.getDatabaseClient().sql("select details from jira_confluence_cache where key=:key").bind("key", key).fetch().first().map(row -> row.get("details"));
        return (String) CacheUtil.monoBlock(res);
    }

    @PostConstruct
    public void init() {
        new MessageSubscriber(messagePublisher.getMessages(), msg -> {
            if (msg instanceof CacheEvent cache) {
                log.info("Saving CacheEvent!");
                var sql = """
                    INSERT INTO jira_confluence_cache (key, raw_key, details) VALUES (:key, :rawKey, :details) on conflict (key)
                     do update set details = excluded.details, last_updated_time = current_timestamp
                    """;
                r2dbcEntityTemplate.getDatabaseClient().sql(sql)
                        .bind("key", cache.getKey())
                        .bind("raw", cache.getRawKey())
                        .bind("details", cache.getDetails())
                        .fetch()
                        .rowsUpdated()
                        .subscribe();
            }
        });
    }
}
