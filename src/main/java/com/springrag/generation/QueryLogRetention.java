package com.springrag.generation;

import com.springrag.common.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes queries_log rows older than QUERY_LOG_RETENTION_DAYS (0 = keep forever). */
@Component
public class QueryLogRetention {

    private static final Logger log = LoggerFactory.getLogger(QueryLogRetention.class);

    private final JdbcTemplate jdbc;
    private final RagProperties props;

    public QueryLogRetention(JdbcTemplate jdbc, RagProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Scheduled(cron = "0 17 3 * * *")
    public int purge() {
        int days = props.queryLog().retentionDays();
        if (days <= 0) {
            return 0;
        }
        int deleted = jdbc.update("DELETE FROM queries_log WHERE created_at < now() - make_interval(days => ?)", days);
        if (deleted > 0) {
            log.info("Deleted {} queries_log row(s) older than {} days", deleted, days);
        }
        return deleted;
    }
}
