package dev.hookrelay.server.ops;

import dev.hookrelay.server.endpoint.CircuitState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * System-wide gauges read from the database at scrape time (every instance reports the same
 * numbers; dashboards use max()). Queue depths come from RabbitMQ's own Prometheus plugin.
 */
@Component
class OpsMetrics implements MeterBinder {

    private final JdbcTemplate jdbc;

    OpsMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("hookrelay.outbox.backlog", () -> number(
                        "select count(*) from outbox where published_at is null"))
                .description("Queue messages written but not yet confirmed by the broker")
                .register(registry);
        Gauge.builder("hookrelay.outbox.lag", () -> number("""
                        select coalesce(timestampdiff(microsecond, min(created_at), utc_timestamp(6)), 0) / 1e6
                        from outbox where published_at is null"""))
                .description("Age of the oldest unpublished outbox message")
                .baseUnit("seconds")
                .register(registry);
        for (CircuitState state : CircuitState.values()) {
            Gauge.builder("hookrelay.endpoints.circuit", () -> number(
                            "select count(*) from endpoints where deleted_at is null and circuit_state = '" + state + "'"))
                    .description("Live endpoints by circuit breaker state")
                    .tag("state", state.name())
                    .register(registry);
        }
    }

    private double number(String sql) {
        Number n = jdbc.queryForObject(sql, Number.class);
        return n == null ? 0 : n.doubleValue();
    }
}
