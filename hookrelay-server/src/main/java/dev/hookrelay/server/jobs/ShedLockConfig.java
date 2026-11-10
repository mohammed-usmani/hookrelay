package dev.hookrelay.server.jobs;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Jobs run on every instance's scheduler; the shedlock row lets exactly one of them proceed. */
@Configuration
@EnableSchedulerLock(interceptMode = EnableSchedulerLock.InterceptMode.PROXY_METHOD, defaultLockAtMostFor = "PT10M")
class ShedLockConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime() // instances' clocks may disagree; the database's is the one truth
                .build());
    }
}
