# Kafka 이벤트 순서 보장 및 에러 처리 전략

## 1. 문제 상황 분석

### 1.1 핵심 이슈
- **Kafka 클러스터**: 여러 브로커, 여러 파티션
- **제로 페이로드**: 최신 데이터만 수신 (과거 이벤트 무시)
- **순서 역전**: 취소 이벤트가 확정 이벤트보다 먼저 도착
- **중복 처리**: 네트워크 재시도로 인한 중복 이벤트

### 1.2 실제 시나리오
```
정상 순서: Created(T1) → Confirmed(T2) → Cancelled(T3)
역전 발생: Created(T1) → Cancelled(T3) → Confirmed(T2) ❌
```

## 2. 해결 전략

### 2.1 상태 기계 (State Machine) 기반 검증

```java
@Component
public class ReservationStateMachine {

    // 유효한 상태 전이 정의
    private static final Map<Status, Set<Status>> VALID_TRANSITIONS = Map.of(
        Status.CREATED, Set.of(Status.PENDING_PAYMENT, Status.CANCELLED),
        Status.PENDING_PAYMENT, Set.of(Status.CONFIRMED, Status.PAYMENT_FAILED, Status.CANCELLED),
        Status.CONFIRMED, Set.of(Status.CANCELLED, Status.COMPLETED),
        Status.CANCELLED, Set.of(), // 종료 상태
        Status.COMPLETED, Set.of()  // 종료 상태
    );

    public boolean canTransition(Status from, Status to) {
        return VALID_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    // 상태 우선순위 (높을수록 최종 상태)
    private static final Map<Status, Integer> STATUS_PRIORITY = Map.of(
        Status.CREATED, 1,
        Status.PENDING_PAYMENT, 2,
        Status.CONFIRMED, 3,
        Status.COMPLETED, 5,
        Status.CANCELLED, 4,
        Status.PAYMENT_FAILED, 4
    );

    public boolean isLaterStatus(Status current, Status incoming) {
        return STATUS_PRIORITY.get(incoming) > STATUS_PRIORITY.get(current);
    }
}
```

### 2.2 이벤트 버전 관리

```sql
-- 이벤트 버전 추적 테이블
CREATE TABLE event_versions (
    aggregate_id VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    current_version BIGINT NOT NULL,
    current_status VARCHAR(50) NOT NULL,
    last_event_timestamp TIMESTAMP(6) NOT NULL,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (aggregate_id, aggregate_type),
    INDEX idx_timestamp (last_event_timestamp)
) ENGINE=InnoDB;

-- 처리된 이벤트 추적 (멱등성)
CREATE TABLE processed_events (
    event_id VARCHAR(100) PRIMARY KEY,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_timestamp TIMESTAMP(6) NOT NULL,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_aggregate (aggregate_id, event_timestamp),
    INDEX idx_cleanup (processed_at) -- 정리용
) ENGINE=InnoDB;
```

### 2.3 이벤트 처리 구현

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class OrderedEventProcessor {

    private final ReservationStateMachine stateMachine;
    private final EventVersionRepository versionRepository;
    private final ProcessedEventRepository processedRepository;

    @KafkaListener(topics = "reservation-events")
    @Transactional
    public void processEvent(ConsumerRecord<String, ReservationEvent> record) {
        String eventId = record.headers().lastHeader("eventId").value();
        ReservationEvent event = record.value();

        try {
            // 1. 멱등성 체크
            if (isAlreadyProcessed(eventId)) {
                log.info("Event already processed: {}", eventId);
                return;
            }

            // 2. 현재 상태 조회
            EventVersion current = versionRepository
                .findById(event.getAggregateId())
                .orElse(EventVersion.initial(event.getAggregateId()));

            // 3. 타임스탬프 기반 순서 검증
            if (event.getTimestamp().isBefore(current.getLastEventTimestamp())) {
                log.warn("Out of order event detected. Current: {}, Incoming: {}",
                    current.getLastEventTimestamp(), event.getTimestamp());

                // 3-1. 보상 처리 필요 여부 확인
                if (requiresCompensation(current, event)) {
                    handleCompensation(current, event);
                    return;
                }

                // 3-2. 무시 가능한 이벤트
                log.info("Ignoring out of order event: {}", eventId);
                return;
            }

            // 4. 상태 전이 유효성 검증
            Status newStatus = event.getStatus();
            if (!stateMachine.canTransition(current.getStatus(), newStatus)) {
                log.error("Invalid state transition: {} -> {}",
                    current.getStatus(), newStatus);

                // 4-1. 상태 우선순위 체크
                if (stateMachine.isLaterStatus(current.getStatus(), newStatus)) {
                    // 더 나중 상태이므로 강제 업데이트
                    forceUpdateStatus(current, event);
                } else {
                    // 무효한 전이, 에러 이벤트 발행
                    publishErrorEvent(event, "Invalid state transition");
                }
                return;
            }

            // 5. 정상 처리
            processNormalEvent(current, event);

            // 6. 처리 완료 기록
            markAsProcessed(eventId, event);

        } catch (Exception e) {
            log.error("Failed to process event: {}", eventId, e);
            handleProcessingError(event, e);
        }
    }

    private boolean requiresCompensation(EventVersion current, ReservationEvent event) {
        // 취소가 확정보다 먼저 온 경우
        if (current.getStatus() == Status.CANCELLED &&
            event.getType() == EventType.CONFIRMED) {
            return true;
        }

        // 결제 실패가 확정보다 먼저 온 경우
        if (current.getStatus() == Status.PAYMENT_FAILED &&
            event.getType() == EventType.CONFIRMED) {
            return true;
        }

        return false;
    }

    private void handleCompensation(EventVersion current, ReservationEvent event) {
        log.warn("Compensation required for reservation: {}", event.getAggregateId());

        CompensationEvent compensation = CompensationEvent.builder()
            .originalEvent(event)
            .currentStatus(current.getStatus())
            .reason("Out of order event requiring compensation")
            .timestamp(Instant.now())
            .build();

        // 보상 이벤트 발행
        kafkaTemplate.send("compensation-events", compensation);

        // 알림 발송
        notificationService.sendAlert(
            "Compensation required for reservation: " + event.getAggregateId()
        );
    }
}
```

### 2.4 Kafka 설정 최적화

```yaml
# application.yml
spring:
  kafka:
    consumer:
      bootstrap-servers: ${KAFKA_BROKERS}
      group-id: reservation-processor
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        # 파티션 키로 aggregateId 사용 (동일 예약은 같은 파티션)
        partition.assignment.strategy: org.apache.kafka.clients.consumer.StickyAssignor

        # 최신 오프셋부터 시작 (제로 페이로드)
        auto.offset.reset: latest

        # 수동 오프셋 커밋
        enable.auto.commit: false

        # 세션 타임아웃
        session.timeout.ms: 30000

        # 멱등성 보장
        enable.idempotence: true

    producer:
      properties:
        # 순서 보장
        max.in.flight.requests.per.connection: 1

        # 재시도
        retries: 3

        # 멱등성
        enable.idempotence: true

# 토픽별 파티션 설정
kafka:
  topics:
    reservation-events:
      partitions: 10
      replication-factor: 3
      config:
        # 7일 보관
        retention.ms: 604800000
        # 압축
        compression.type: lz4
```

### 2.5 보상 트랜잭션 (Saga Pattern)

```java
@Component
@Slf4j
public class ReservationSaga {

    @SagaOrchestrationStart
    public void handle(ReservationCreated event) {
        SagaTransaction.start()
            .step("lock-time", () -> lockTimeSlots(event))
            .compensate(() -> releaseTimeSlots(event))

            .step("process-payment", () -> processPayment(event))
            .compensate(() -> refundPayment(event))

            .step("confirm-reservation", () -> confirmReservation(event))
            .compensate(() -> cancelReservation(event))

            .onError((error) -> {
                log.error("Saga failed for reservation: {}", event.getReservationId());
                publishCompensationEvent(event, error);
            })
            .execute();
    }

    @EventHandler
    public void handleOutOfOrder(OutOfOrderEvent event) {
        // 순서가 잘못된 이벤트 처리
        switch (event.getType()) {
            case CANCELLATION_BEFORE_CONFIRMATION:
                // 이미 취소된 예약에 대한 확정 시도
                revertConfirmation(event);
                break;

            case PAYMENT_AFTER_CANCELLATION:
                // 취소 후 결제 시도
                initiateRefund(event);
                break;

            default:
                log.warn("Unhandled out of order event: {}", event);
        }
    }
}
```

## 3. 에러 처리 매트릭스

### 3.1 상태 전이 에러 처리

| 현재 상태 | 들어온 이벤트 | 처리 방법 |
|----------|-------------|-----------|
| CANCELLED | CONFIRMED | 보상 트랜잭션 (환불 처리) |
| COMPLETED | CANCELLED | 거부 (완료된 예약 취소 불가) |
| CONFIRMED | PENDING_PAYMENT | 무시 (역방향 전이) |
| PAYMENT_FAILED | CONFIRMED | 재결제 프로세스 시작 |

### 3.2 타임스탬프 기반 처리

```java
@Component
public class TimestampBasedResolver {

    public Resolution resolve(Event current, Event incoming) {
        long timeDiff = ChronoUnit.SECONDS.between(
            current.getTimestamp(),
            incoming.getTimestamp()
        );

        if (Math.abs(timeDiff) < 5) {
            // 5초 이내: 동시 발생으로 간주
            return Resolution.USE_PRIORITY;
        } else if (timeDiff > 0) {
            // incoming이 더 최신
            return Resolution.ACCEPT_INCOMING;
        } else {
            // current가 더 최신
            return Resolution.KEEP_CURRENT;
        }
    }
}
```

## 4. 모니터링 및 알림

### 4.1 메트릭 수집

```java
@Component
public class EventOrderingMetrics {

    private final MeterRegistry meterRegistry;

    public void recordOutOfOrder(String eventType) {
        meterRegistry.counter("events.out_of_order",
            "type", eventType).increment();
    }

    public void recordCompensation(String reason) {
        meterRegistry.counter("events.compensation",
            "reason", reason).increment();
    }

    public void recordEventLag(Duration lag) {
        meterRegistry.timer("events.processing.lag").record(lag);
    }
}
```

### 4.2 알림 설정

```yaml
alerts:
  - name: high_out_of_order_rate
    condition: rate(events.out_of_order) > 0.05  # 5% 이상
    severity: WARNING

  - name: compensation_required
    condition: events.compensation > 0
    severity: CRITICAL
    action: page_oncall

  - name: event_lag_high
    condition: events.processing.lag > 30s
    severity: WARNING
```

## 5. 테스트 전략

### 5.1 순서 역전 시뮬레이션

```java
@Test
public void testOutOfOrderEventHandling() {
    // Given
    String reservationId = "RES123";

    // When - 순서 역전 발생
    processor.processEvent(createCancelledEvent(reservationId, T3));
    processor.processEvent(createConfirmedEvent(reservationId, T2));
    processor.processEvent(createCreatedEvent(reservationId, T1));

    // Then
    Reservation reservation = repository.findById(reservationId);
    assertThat(reservation.getStatus()).isEqualTo(Status.CANCELLED);

    // 보상 이벤트 확인
    verify(compensationService).handleCompensation(
        argThat(e -> e.getReservationId().equals(reservationId))
    );
}
```

### 5.2 부하 테스트

```java
@Test
public void testHighVolumeOrdering() {
    // 10,000개 이벤트를 무작위 순서로 발송
    List<Event> events = generateRandomOrderEvents(10000);

    events.parallelStream().forEach(event ->
        kafkaTemplate.send("test-topic", event)
    );

    // 최종 상태 검증
    await().atMost(30, SECONDS).until(() ->
        repository.count() == 10000
    );

    // 순서 보장 검증
    List<Reservation> results = repository.findAll();
    assertThat(results).allMatch(r ->
        r.getStatus() == calculateExpectedStatus(r.getId())
    );
}
```

## 6. 실제 운영 사례

### 6.1 Uber의 접근 방식
- **Vector Clock** 사용으로 인과관계 추적
- **CRDT** (Conflict-free Replicated Data Types) 활용
- 최종 일관성 보장

### 6.2 Booking.com의 접근 방식
- **Event Sourcing**으로 모든 이벤트 저장
- **Snapshot** 기반 상태 복원
- 매일 정합성 검증 배치 실행

## 7. 체크리스트

### 개발 단계
- [ ] 상태 기계 정의 및 구현
- [ ] 멱등성 키 관리 구현
- [ ] 이벤트 버전 관리 구현
- [ ] 보상 트랜잭션 구현
- [ ] 순서 역전 감지 로직
- [ ] 에러 핸들링 구현

### 테스트 단계
- [ ] 순서 역전 시나리오 테스트
- [ ] 동시성 테스트
- [ ] 보상 트랜잭션 테스트
- [ ] 부하 테스트

### 운영 단계
- [ ] 모니터링 대시보드 구성
- [ ] 알림 설정
- [ ] 로그 분석 파이프라인
- [ ] 정합성 검증 배치