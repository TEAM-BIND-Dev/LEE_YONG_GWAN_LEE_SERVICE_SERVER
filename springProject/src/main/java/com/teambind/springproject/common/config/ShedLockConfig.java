package com.teambind.springproject.common.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ShedLock 설정.
 * <p>
 * 분산 환경에서 스케줄 작업의 중복 실행을 방지하기 위한 Lock Provider 설정.
 * <p>
 * Redis를 백엔드로 사용하여 다음을 보장한다:
 * <p>
 * <p>
 * 동일 작업은 한 인스턴스에서만 실행
 * lockAtMostFor: 작업 실패 시 자동 Lock 해제 (데드락 방지)
 * lockAtLeastFor: 최소 실행 간격 보장 (너무 빠른 재실행 방지)
 *
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M") // 기본 최대 Lock 시간: 10분
public class ShedLockConfig {
	
	/**
	 * Redis 기반 Lock Provider를 생성한다.
	 * <p>
	 * Redis Key 형식: "shedlock:{taskName}"
	 *
	 * @param connectionFactory Redis 연결 팩토리
	 * @return Redis Lock Provider
	 */
	@Bean
	public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
		return new RedisLockProvider(connectionFactory, "time-service");
	}
}
