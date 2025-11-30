package com.teambind.springproject.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 테스트용 ShedLock 설정.
 *
 * Redis 대신 H2 인메모리 DB를 사용하여 ShedLock을 구현한다.
 * 테스트 환경에서 Redis 의존성을 제거하고 안정적인 테스트 실행을 보장한다.
 */
@TestConfiguration
public class TestShedLockConfig {

    @Bean
    @Primary
    public LockProvider lockProvider(DataSource dataSource) {
        // H2 인메모리 DB를 사용한 LockProvider
        // 테스트 시작 시 자동으로 테이블 생성
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime() // DB 시간 사용
                .build());
    }
}