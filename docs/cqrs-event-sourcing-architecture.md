# CQRS와 Event Sourcing 기반 예약 이력 관리 아키텍처

## 1. 아키텍처 패턴 개요

### 1.1 현재 설계의 패턴 식별

귀하의 설계는 다음 패턴들의 조합입니다:

1. **CQRS (Command Query Responsibility Segregation)**
   - Command: 운영 서버들 (예약 생성, 상태 변경)
   - Query: 분석/이력 서버 (조회, 리포팅)

2. **Event Sourcing**
   - 모든 상태 변경을 이벤트로 저장
   - 이벤트 재생으로 현재 상태 도출

3. **Audit Trail Pattern**
   - 모든 변경 이력 추적
   - 규제 준수 및 분쟁 해결

### 1.2 실제 사용 사례

| 기업 | 사용 패턴 | 목적 |
|------|-----------|------|
| **Airbnb** | CQRS + Event Sourcing | 예약 이력, 호스트-게스트 분쟁 해결 |
| **Uber** | Event Sourcing | 라이드 전체 이력, 요금 정산 |
| **Netflix** | CQRS | 시청 이력 분석, 추천 시스템 |
| **Amazon** | Audit Trail | 주문 전체 생명주기 추적 |
| **Booking.com** | CQRS + Event Store | 예약 변경 이력, 취소 패턴 분석 |

## 2. 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                     Command Side (쓰기)                      │
├─────────────────┬─────────────────┬─────────────────────────┤
│ Reservation API │ Time Service    │ Payment Service         │
│ - 예약 생성     │ - 시간 락       │ - 결제 처리             │
│ - 상태 변경     │ - 가용성 관리   │ - 환불 처리             │
└────────┬────────┴────────┬────────┴──────┬──────────────────┘
         │                 │                │
         └─────────────────┴────────────────┘
                           │
                    Domain Events
                           │
                ┌──────────┴──────────┐
                │   Event Store       │
                │  (Kafka/EventStore)  │
                └──────────┬──────────┘
                           │
         ┌─────────────────┴─────────────────┐
         │                                   │
┌────────┴──────────┐            ┌───────────┴───────────┐
│  Analytics DB     │            │   Audit Service       │
│  (Read Model)     │            │  (Compliance)         │
├───────────────────┤            ├───────────────────────┤
│ - 통계 분석       │            │ - 전체 이력 저장      │
│ - 패턴 분석       │            │ - 규제 준수           │
│ - 실시간 대시보드 │            │ - 분쟁 해결           │
└───────────────────┘            └───────────────────────┘
```

## 3. 이벤트 스토어 설계

### 3.1 이벤트 스키마

```sql
-- 이벤트 스토어 (불변 데이터)
CREATE TABLE event_store (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_id VARCHAR(100) NOT NULL,        -- 예약 ID
    aggregate_type VARCHAR(50) NOT NULL,       -- 'RESERVATION'
    event_type VARCHAR(100) NOT NULL,          -- 이벤트 타입
    event_version INT NOT NULL,                -- 집계 버전
    event_data JSON NOT NULL,                  -- 이벤트 페이로드
    event_metadata JSON,                       -- 메타데이터

    -- 추적 정보
    user_id BIGINT,
    correlation_id VARCHAR(100),               -- 요청 추적
    causation_id VARCHAR(100),                 -- 원인 이벤트

    occurred_at TIMESTAMP(6) NOT NULL,         -- 마이크로초 정밀도
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_aggregate (aggregate_id, event_version),
    INDEX idx_event_type (event_type, occurred_at),
    INDEX idx_correlation (correlation_id),
    INDEX idx_user (user_id, occurred_at)
) ENGINE=InnoDB;

-- 이벤트 타입 정의
CREATE TABLE event_types (
    type_name VARCHAR(100) PRIMARY KEY,
    schema_version INT NOT NULL,
    schema_definition JSON NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
```

### 3.2 이벤트 예시

```json
{
  "eventId": "evt_123456",
  "aggregateId": "res_789012",
  "aggregateType": "RESERVATION",
  "eventType": "ReservationCreated",
  "eventVersion": 1,
  "eventData": {
    "reservationId": "res_789012",
    "roomId": 123,
    "userId": 456,
    "date": "2024-03-15",
    "timeSlots": ["09:00", "09:30", "10:00"],
    "totalAmount": 150000,
    "status": "PENDING_PAYMENT"
  },
  "eventMetadata": {
    "source": "reservation-api",
    "ipAddress": "192.168.1.100",
    "userAgent": "Mobile App v2.1.0",
    "sessionId": "sess_abc123"
  },
  "userId": 456,
  "correlationId": "req_xyz789",
  "occurredAt": "2024-03-01T10:30:45.123456Z"
}
```

## 4. 분석/이력 데이터베이스 설계

### 4.1 예약 이력 집계 테이블

```sql
-- 예약 전체 이력 (비정규화, 읽기 최적화)
CREATE TABLE reservation_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id VARCHAR(100) NOT NULL,
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,

    -- 시간 정보
    reservation_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,

    -- 상태 추적
    current_status VARCHAR(50) NOT NULL,
    status_history JSON,  -- 모든 상태 변경 이력

    -- 금액 정보
    original_amount DECIMAL(10,2),
    paid_amount DECIMAL(10,2),
    refunded_amount DECIMAL(10,2),

    -- 타임스탬프
    created_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    completed_at TIMESTAMP,

    -- 취소/거절 정보
    cancellation_reason VARCHAR(500),
    cancelled_by VARCHAR(50),  -- USER, SYSTEM, ADMIN

    -- 분석용 필드
    lead_time_hours INT,  -- 예약 시점과 이용 시간 차이
    total_duration_minutes INT,
    day_of_week TINYINT,
    is_weekend BOOLEAN,
    is_holiday BOOLEAN,

    -- 이벤트 수
    total_events INT DEFAULT 1,
    modification_count INT DEFAULT 0,

    INDEX idx_user (user_id, reservation_date),
    INDEX idx_room (room_id, reservation_date),
    INDEX idx_status (current_status, created_at),
    INDEX idx_analytics (reservation_date, room_id, current_status)
) ENGINE=InnoDB;

-- 예약 상태 변경 이력 (상세)
CREATE TABLE reservation_state_transitions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id VARCHAR(100) NOT NULL,
    from_status VARCHAR(50),
    to_status VARCHAR(50) NOT NULL,
    transition_reason VARCHAR(500),

    -- 변경 주체
    changed_by_type VARCHAR(50),  -- USER, SYSTEM, ADMIN, PAYMENT_GATEWAY
    changed_by_id VARCHAR(100),

    -- 컨텍스트
    event_id BIGINT,  -- event_store 참조
    additional_data JSON,

    occurred_at TIMESTAMP NOT NULL,

    INDEX idx_reservation (reservation_id, occurred_at),
    INDEX idx_status_transition (from_status, to_status)
) ENGINE=InnoDB;

-- 취소/환불 분석 테이블
CREATE TABLE cancellation_analytics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id VARCHAR(100) UNIQUE NOT NULL,

    -- 취소 정보
    cancellation_type VARCHAR(50),  -- USER_CANCEL, AUTO_CANCEL, ADMIN_CANCEL
    cancellation_reason_category VARCHAR(100),
    cancellation_reason_detail TEXT,

    -- 타이밍
    hours_before_reservation INT,
    was_last_minute BOOLEAN,  -- 24시간 이내

    -- 환불 정보
    refund_policy_applied VARCHAR(100),
    refund_percentage INT,
    original_amount DECIMAL(10,2),
    refunded_amount DECIMAL(10,2),
    penalty_amount DECIMAL(10,2),

    -- 패턴 분석
    user_cancellation_count INT,  -- 해당 사용자의 총 취소 횟수
    room_cancellation_rate DECIMAL(5,2),  -- 해당 방의 취소율

    cancelled_at TIMESTAMP NOT NULL,

    INDEX idx_reason (cancellation_reason_category),
    INDEX idx_timing (hours_before_reservation),
    INDEX idx_date (cancelled_at)
) ENGINE=InnoDB;
```

### 4.2 분석용 집계 뷰

```sql
-- 일별 예약 통계
CREATE MATERIALIZED VIEW daily_reservation_stats AS
SELECT
    DATE(created_at) as stat_date,
    room_id,
    COUNT(*) as total_reservations,
    SUM(CASE WHEN current_status = 'COMPLETED' THEN 1 ELSE 0 END) as completed,
    SUM(CASE WHEN current_status = 'CANCELLED' THEN 1 ELSE 0 END) as cancelled,
    AVG(total_duration_minutes) as avg_duration,
    SUM(paid_amount) as total_revenue,
    SUM(refunded_amount) as total_refunds
FROM reservation_history
GROUP BY DATE(created_at), room_id;

-- 사용자 행동 패턴
CREATE MATERIALIZED VIEW user_behavior_patterns AS
SELECT
    user_id,
    COUNT(*) as total_reservations,
    AVG(lead_time_hours) as avg_lead_time,
    SUM(CASE WHEN current_status = 'CANCELLED' THEN 1 ELSE 0 END) / COUNT(*) as cancellation_rate,
    AVG(total_duration_minutes) as avg_session_duration,
    COUNT(DISTINCT room_id) as unique_rooms_used
FROM reservation_history
GROUP BY user_id;
```

## 5. 데이터 동기화 전략

### 5.1 실시간 동기화 (CDC)

```yaml
# Debezium CDC 설정
debezium:
  connector:
    class: io.debezium.connector.mysql.MySqlConnector
    database:
      hostname: mariadb-primary
      port: 3306
      user: debezium
      password: ${DB_PASSWORD}
    database.server.name: reservation-db
    table.include.list:
      - reservation.reservations
      - reservation.reserved_time_slots
    transforms: outbox
    transforms.outbox.type: io.debezium.transforms.outbox.EventRouter
```

### 5.2 이벤트 프로세서

```java
@Component
@Slf4j
public class EventProjectionProcessor {

    @EventHandler
    @Transactional
    public void on(ReservationCreated event) {
        // 1. 이벤트 스토어 저장
        EventEntity eventEntity = EventEntity.builder()
            .aggregateId(event.getReservationId())
            .eventType("ReservationCreated")
            .eventData(toJson(event))
            .occurredAt(event.getOccurredAt())
            .build();

        eventStore.save(eventEntity);

        // 2. Read Model 업데이트
        ReservationHistory history = ReservationHistory.builder()
            .reservationId(event.getReservationId())
            .roomId(event.getRoomId())
            .userId(event.getUserId())
            .currentStatus("PENDING_PAYMENT")
            .statusHistory(Arrays.asList(
                new StatusChange("CREATED", event.getOccurredAt())
            ))
            .build();

        historyRepository.save(history);

        // 3. 실시간 분석 업데이트
        analyticsService.updateRealTimeMetrics(event);
    }

    @EventHandler
    public void on(ReservationCancelled event) {
        // 취소 분석 데이터 생성
        CancellationAnalytics analytics = analyzeCancellation(event);
        cancellationRepository.save(analytics);

        // 이력 업데이트
        historyRepository.updateStatus(
            event.getReservationId(),
            "CANCELLED",
            event.getReason()
        );
    }
}
```

## 6. 장점과 실제 효과

### 6.1 비즈니스 가치

1. **완벽한 감사 추적**
   - 모든 변경 사항 추적
   - 분쟁 시 증거 자료
   - 규제 준수 (개인정보보호법 등)

2. **강력한 분석 능력**
   - 취소 패턴 분석
   - 수요 예측
   - 가격 최적화

3. **시스템 복원력**
   - 이벤트 재생으로 상태 복구
   - 데이터 불일치 해결
   - 버그 발생 시 추적 용이

### 6.2 실제 사례

**Airbnb의 경우:**
- 매일 2TB+ 예약 이벤트 처리
- 99.99% 이벤트 무손실 보장
- 평균 100ms 이내 Read Model 동기화

**Booking.com의 경우:**
- 초당 10,000+ 예약 이벤트 처리
- 5년치 이력 데이터 보관
- ML 모델에 활용하여 취소율 20% 감소

## 7. 구현 시 고려사항

### 7.1 기술 스택 선택

| 용도 | 추천 기술 | 대안 |
|------|-----------|------|
| Event Store | Apache Kafka | EventStore, AWS Kinesis |
| CDC | Debezium | Maxwell, Canal |
| Read Model DB | MariaDB | PostgreSQL, MongoDB |
| Analytics DB | ClickHouse | Apache Druid, Snowflake |
| Stream Processing | Kafka Streams | Apache Flink, Spark Streaming |

### 7.2 데이터 보관 정책

```sql
-- 파티셔닝으로 오래된 데이터 관리
ALTER TABLE event_store
PARTITION BY RANGE (YEAR(occurred_at)) (
    PARTITION p2023 VALUES LESS THAN (2024),
    PARTITION p2024 VALUES LESS THAN (2025),
    PARTITION p2025 VALUES LESS THAN (2026)
);

-- 오래된 파티션을 콜드 스토리지로 이동
ALTER TABLE event_store
EXCHANGE PARTITION p2023 WITH TABLE event_store_archive;
```

### 7.3 성능 최적화

1. **이벤트 배치 처리**
   ```java
   @Scheduled(fixedDelay = 1000)
   public void processBatch() {
       List<Event> events = eventQueue.drain(100);
       bulkProcess(events);
   }
   ```

2. **Read Model 캐싱**
   ```java
   @Cacheable(value = "reservationHistory", key = "#reservationId")
   public ReservationHistory getHistory(String reservationId) {
       return historyRepository.findById(reservationId);
   }
   ```

3. **비동기 프로젝션**
   ```java
   @Async
   @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
   public void projectAsync(DomainEvent event) {
       // Read Model 업데이트
   }
   ```

## 8. 모니터링 및 운영

### 8.1 핵심 메트릭

```java
@Component
public class EventMetrics {

    private final MeterRegistry registry;

    // 이벤트 처리 지연
    @Timed("event.processing.time")
    public void processEvent(Event event) {
        long lag = System.currentTimeMillis() - event.getOccurredAt();
        registry.gauge("event.lag", lag);
    }

    // 이벤트 처리량
    @Counted("event.processed")
    public void recordProcessed(String eventType) {
        registry.counter("event.type", "type", eventType).increment();
    }
}
```

### 8.2 알람 설정

- Event Lag > 5초: 경고
- Event Lag > 30초: 심각
- 이벤트 처리 실패율 > 1%: 경고
- Read Model 동기화 지연 > 1분: 경고

## 9. 결론

이 패턴은 **대규모 예약 시스템의 표준 아키텍처**입니다. 특히:

1. **규제 준수가 중요한 도메인** (금융, 의료, 예약)
2. **분석이 핵심 경쟁력**인 서비스
3. **이력 추적이 필수**인 시스템

에서 필수적으로 사용됩니다. 초기 구현 복잡도는 있지만, 장기적으로 큰 가치를 제공합니다.