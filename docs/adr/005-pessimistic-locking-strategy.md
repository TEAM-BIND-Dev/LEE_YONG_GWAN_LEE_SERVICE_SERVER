# ADR-005: Pessimistic Locking Strategy for Slot Reservation

**Status**: Proposed
**Date**: 2025-01-19
**Decision Makers**: Teambind_dev_backend Team
**Technical Story**: 단일 슬롯 예약 시 동시성 제어 강화

---

## Context

현재 시스템은 두 가지 예약 방식을 제공합니다:
1. **단일 슬롯 예약** (`markSlotAsPending`): Pessimistic Lock **미적용**
2. **다중 슬롯 예약** (`markMultipleSlotsAsPending`): Pessimistic Lock **적용**

### 현재 구현 분석

**단일 슬롯 예약 (문제 있음)**:
```java
// TimeSlotManagementServiceImpl.java:42-56
@Override
public void markSlotAsPending(Long roomId, LocalDate slotDate, LocalTime slotTime, Long reservationId) {
    // ❌ Lock 없이 조회
    RoomTimeSlot slot = findSlot(roomId, slotDate, slotTime);

    // 상태 전이
    slot.markAsPending(reservationId);
    timeSlotPort.save(slot);
}

private RoomTimeSlot findSlot(...) {
    return timeSlotPort.findByRoomIdAndSlotDateAndSlotTime(...); // SELECT (no lock)
}
```

**다중 슬롯 예약 (올바름)**:
```java
// TimeSlotManagementServiceImpl.java:120-181
@Override
public int markMultipleSlotsAsPending(...) {
    // ✅ SELECT ... FOR UPDATE
    List<RoomTimeSlot> slots = timeSlotPort.findByRoomIdAndSlotDateAndSlotTimeInWithLock(
        roomId, slotDate, slotTimes
    );

    // 모든 슬롯 AVAILABLE 검증 후 PENDING으로 변경
    for (RoomTimeSlot slot : slots) {
        slot.markAsPending(reservationId);
    }

    timeSlotPort.saveAll(slots);
}
```

### 문제점: Lost Update (갱신 손실)

**Race Condition 시나리오**:
```
시간    Thread A (사용자 1)                      Thread B (사용자 2)
────────────────────────────────────────────────────────────────────────
T1     SELECT * FROM room_time_slots
       WHERE room_id=101 AND slot_date='2025-01-20' AND slot_time='10:00'
       → status=AVAILABLE, reservationId=NULL

T2                                              SELECT * FROM room_time_slots
                                                WHERE room_id=101 AND slot_date='2025-01-20' AND slot_time='10:00'
                                                → status=AVAILABLE, reservationId=NULL

T3     // Entity 메모리 상에서 변경
       slot.status = PENDING
       slot.reservationId = 1001

T4                                              // Entity 메모리 상에서 변경
                                                slot.status = PENDING
                                                slot.reservationId = 1002

T5     UPDATE room_time_slots
       SET status='PENDING', reservationId=1001
       WHERE slot_id=999

T6                                              UPDATE room_time_slots
                                                SET status='PENDING', reservationId=1002
                                                WHERE slot_id=999
                                                ✅ 마지막 승자 (사용자 1의 예약이 사라짐)

T7     Kafka 발행: SlotReservedEvent(reservationId=1001)

T8                                              Kafka 발행: SlotReservedEvent(reservationId=1002)

결과: DB에는 reservationId=1002만 남음, 사용자 1의 예약은 유실
```

**영향도**:
- 비즈니스 크리티컬 (예약 시스템 핵심 데이터)
- 법적 리스크 (예약 손실 → 서비스 신뢰도 저하)
- 충돌 확률: 동일 슬롯에 동시 예약 시도 시 100% 발생 가능
- 현실성: 인기 있는 시간대(금요일 저녁 등)에 충돌 빈번

### 현재 완화 장치 (불충분)

**Unique Constraint**:
```java
// RoomTimeSlot.java:35-39
@UniqueConstraint(
    name = "uk_room_date_time",
    columnNames = {"room_id", "slot_date", "slot_time"}
)
```

**한계점**:
- Unique Constraint는 **INSERT** 시에만 동작
- 같은 row를 **UPDATE** 하는 경우 제약조건 체크 안 함
- Lost Update는 여전히 발생 가능

**Entity 내 검증**:
```java
// RoomTimeSlot.java:115-126
public void markAsPending(Long reservationId) {
    if (status != SlotStatus.AVAILABLE) {
        throw new InvalidSlotStateTransitionException(...);
    }
    // ...
}
```

**한계점**:
- 검증은 애플리케이션 레벨 (메모리 상태 기반)
- DB의 실제 상태와 다를 수 있음 (Race Condition)
- Thread A와 B 모두 AVAILABLE을 읽었기 때문에 검증 통과

### 요구사항

1. **정확성 우선**: 예약 손실이 절대 발생해서는 안 됨
2. **일관성**: 단일/다중 슬롯 예약의 동시성 제어 전략 통일
3. **성능**: 처리량 감소를 최소화
4. **유지보수성**: 코드 복잡도 증가 최소화

---

## Decision Drivers

- 예약 시스템의 정확성이 성능보다 중요 (ACID의 Consistency 우선)
- 다중 슬롯 예약에서 이미 Pessimistic Lock 사용 중 (일관성)
- 팀의 JPA Lock 사용 경험 있음
- MySQL/MariaDB의 InnoDB는 Row-Level Lock 지원 (성능 양호)
- 예약 트랜잭션은 짧고 빠름 (Lock 경합 시간 짧음)

---

## Considered Options

### Option 1: Pessimistic Lock (권장)

**개념**:
```sql
-- JPA가 생성하는 쿼리
SELECT * FROM room_time_slots
WHERE room_id = ? AND slot_date = ? AND slot_time = ?
FOR UPDATE;  -- ← 다른 트랜잭션이 이 row를 읽거나 쓸 수 없음
```

**구현 예시**:

*1. Port 인터페이스에 메서드 추가*:
```java
// TimeSlotPort.java
public interface TimeSlotPort {

    // 기존 메서드
    Optional<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTime(
        Long roomId, LocalDate slotDate, LocalTime slotTime
    );

    // ✅ 신규 추가: Lock을 걸고 조회
    Optional<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTimeWithLock(
        Long roomId, LocalDate slotDate, LocalTime slotTime
    );
}
```

*2. Adapter 구현*:
```java
// TimeSlotJpaAdapter.java
@Repository
public class TimeSlotJpaAdapter implements TimeSlotPort {

    private final RoomTimeSlotRepository repository;

    @Override
    public Optional<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTimeWithLock(
        Long roomId, LocalDate slotDate, LocalTime slotTime
    ) {
        return repository.findByRoomIdAndSlotDateAndSlotTimeWithLock(
            roomId, slotDate, slotTime
        );
    }
}

// RoomTimeSlotRepository.java
public interface RoomTimeSlotRepository extends JpaRepository<RoomTimeSlot, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM RoomTimeSlot s " +
           "WHERE s.roomId = :roomId " +
           "AND s.slotDate = :slotDate " +
           "AND s.slotTime = :slotTime")
    Optional<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTimeWithLock(
        @Param("roomId") Long roomId,
        @Param("slotDate") LocalDate slotDate,
        @Param("slotTime") LocalTime slotTime
    );
}
```

*3. Domain Service 수정*:
```java
// TimeSlotManagementServiceImpl.java
@Override
public void markSlotAsPending(Long roomId, LocalDate slotDate, LocalTime slotTime, Long reservationId) {
    // ✅ Lock을 걸고 조회
    RoomTimeSlot slot = timeSlotPort
        .findByRoomIdAndSlotDateAndSlotTimeWithLock(roomId, slotDate, slotTime)
        .orElseThrow(() -> new SlotNotFoundException(roomId, slotDate.toString(), slotTime.toString()));

    // 도메인 로직: 상태 전이
    slot.markAsPending(reservationId);
    timeSlotPort.save(slot);

    log.info("Slot marked as pending with lock: slotId={}, roomId={}, reservationId={}",
        slot.getSlotId(), roomId, reservationId);
}
```

**동작 플로우**:
```
시간    Thread A (사용자 1)                      Thread B (사용자 2)
────────────────────────────────────────────────────────────────────────
T1     SELECT ... FOR UPDATE
       WHERE slot_id=999
       → Lock 획득 ✅
       → status=AVAILABLE

T2                                              SELECT ... FOR UPDATE
                                                WHERE slot_id=999
                                                → Lock 대기 ⏳ (블로킹)

T3     // 상태 전이
       slot.status = PENDING
       slot.reservationId = 1001

T4     UPDATE room_time_slots
       SET status='PENDING', reservationId=1001
       WHERE slot_id=999

T5     COMMIT
       → Lock 해제

T6                                              → Lock 획득 ✅
                                                → status=PENDING (이미 변경됨)

T7                                              // 검증 실패
                                                slot.markAsPending(1002)
                                                → InvalidSlotStateTransitionException
                                                → ROLLBACK

결과: 사용자 1만 예약 성공, 사용자 2는 실패 (정확한 동작 ✅)
```

**장점**:
- **정확성 보장**: Lost Update 완전 방지
- **단순성**: 애플리케이션 로직 변경 최소 (Repository 메서드 추가만)
- **일관성**: 다중 슬롯 예약과 동일한 전략
- **디버깅 용이**: 예외 발생으로 문제 즉시 인지
- **ACID 준수**: 트랜잭션의 Isolation 보장

**단점**:
- **처리량 감소**: Lock 대기 시간만큼 응답 시간 증가
  - **완화**: 예약 트랜잭션은 짧음 (평균 50ms 이내)
  - **완화**: InnoDB Row-Level Lock으로 영향 최소화

- **데드락 가능성**: 여러 슬롯을 순서 없이 Lock 획득 시
  - **완화**: 단일 슬롯 예약은 데드락 불가능
  - **완화**: 다중 슬롯은 이미 정렬된 순서로 Lock (TimeSlotPort 구현에서 정렬)

**Trade-offs**:
- 성능 vs 정확성: 약간의 처리량 감소를 허용하여 데이터 정확성 확보
- 동시성 vs 일관성: Pessimistic 접근으로 강한 일관성 보장

---

### Option 2: Optimistic Lock

**개념**:
```java
@Entity
public class RoomTimeSlot {

    @Version
    private Long version; // ← JPA가 자동으로 버전 관리
}
```

**동작 플로우**:
```
시간    Thread A                               Thread B
─────────────────────────────────────────────────────────────────
T1     SELECT * WHERE slot_id=999
       → version=1, status=AVAILABLE

T2                                              SELECT * WHERE slot_id=999
                                                → version=1, status=AVAILABLE

T3     UPDATE room_time_slots
       SET status='PENDING', reservationId=1001, version=2
       WHERE slot_id=999 AND version=1
       → 1 row affected ✅

T4     COMMIT

T5                                              UPDATE room_time_slots
                                                SET status='PENDING', reservationId=1002, version=2
                                                WHERE slot_id=999 AND version=1
                                                → 0 row affected (version mismatch)
                                                → OptimisticLockException

T6                                              // 재시도 로직 필요
                                                SELECT * WHERE slot_id=999
                                                → version=2, status=PENDING
                                                → 검증 실패
```

**구현 예시**:
```java
@Service
public class ReservationApplicationService {

    @Retryable(
        value = {OptimisticLockException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 100)
    )
    @Transactional
    public void createReservation(SlotReservationRequest request) {
        timeSlotManagementService.markSlotAsPending(...);
        eventPublisher.publish(event);
    }
}
```

**장점**:
- **성능 우수**: Lock 없이 동작 (읽기 처리량 높음)
- **데드락 없음**: Lock을 걸지 않음
- **확장성**: 분산 환경에서도 동작

**단점**:
- **재시도 로직 필수**: 충돌 시 애플리케이션에서 재처리
- **사용자 경험 저하**: 충돌 시 응답 시간 증가 (재시도 대기)
- **복잡도 증가**: 재시도 전략, 최대 시도 횟수, 백오프 설정 필요
- **이벤트 중복 발행 위험**: 재시도 시 Kafka 발행도 재실행 (Idempotency 필요)

**Trade-offs**:
- 성능 vs 복잡도: 성능은 좋지만 애플리케이션 로직 복잡
- 낙관적 vs 비관적: 충돌이 드문 경우에만 유리

---

### Option 3: Unique Index with Status Condition (Partial Index)

**개념**:
```sql
-- MySQL 8.0+ Partial Index
CREATE UNIQUE INDEX uk_room_date_time_pending
ON room_time_slots(room_id, slot_date, slot_time, status)
WHERE status = 'PENDING';

-- 또는 Functional Index
CREATE UNIQUE INDEX uk_room_date_time_reserved
ON room_time_slots(room_id, slot_date, slot_time, CASE WHEN status IN ('PENDING', 'RESERVED') THEN status END);
```

**동작**:
- 같은 (roomId, slotDate, slotTime)에 대해 PENDING 상태가 2개 생성되려 하면 DB 레벨에서 차단

**장점**:
- **DB 레벨 보장**: 애플리케이션 버그가 있어도 DB가 보호
- **성능**: Lock 없이 동작

**단점**:
- **MySQL 버전 의존**: 8.0 이상 필요 (Partial Index)
  - MariaDB 10.x는 Partial Index 지원 안 함 (프로젝트 현황 확인 필요)
- **마이그레이션 리스크**: 기존 데이터 검증 필요
- **제한적 보호**: UPDATE는 여전히 Lost Update 가능
  - AVAILABLE → PENDING 동시 변경 시 하나만 성공 (좋음)
  - 하지만 reservationId가 다르면 마지막 UPDATE가 승리 (나쁨)

**Trade-offs**:
- 안정성 vs 기술 부채: DB 버전 종속성 증가

---

### Option 4: Application-Level Distributed Lock (Redis)

**개념**:
```java
@Service
public class TimeSlotManagementServiceImpl {

    private final RedissonClient redisson;

    public void markSlotAsPending(...) {
        String lockKey = String.format("slot:%d:%s:%s", roomId, slotDate, slotTime);
        RLock lock = redisson.getLock(lockKey);

        try {
            // Lock 획득 (최대 5초 대기, 10초 후 자동 해제)
            if (lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                RoomTimeSlot slot = findSlot(...);
                slot.markAsPending(reservationId);
                timeSlotPort.save(slot);
            } else {
                throw new SlotLockTimeoutException();
            }
        } finally {
            lock.unlock();
        }
    }
}
```

**장점**:
- **분산 환경 지원**: 여러 애플리케이션 인스턴스 간 동기화
- **유연성**: Lock 타임아웃, TTL 등 세밀한 제어

**단점**:
- **인프라 복잡도**: Redis 의존성 추가
- **네트워크 오버헤드**: Redis 왕복 시간 발생
- **장애 포인트 증가**: Redis 다운 시 예약 불가
- **오버엔지니어링**: 단일 DB 환경에서는 불필요

**Trade-offs**:
- 확장성 vs 복잡도: 분산 시스템에는 적합하지만 현재는 과도함

---

## Decision Outcome

**선택: Option 1 - Pessimistic Lock**

### 선택 이유

1. **정확성 최우선**
   - 예약 시스템에서 데이터 손실은 절대 허용 불가
   - Pessimistic Lock이 가장 확실한 보장 제공

2. **일관성 유지**
   - 다중 슬롯 예약에서 이미 Pessimistic Lock 사용
   - 단일/다중 예약의 동시성 제어 전략 통일

3. **단순성**
   - Option 2(Optimistic)처럼 재시도 로직 불필요
   - Option 4(Redis)처럼 추가 인프라 불필요
   - Repository 메서드 하나만 추가

4. **성능 허용 범위**
   - 예약 트랜잭션은 짧음 (평균 50ms)
   - Lock 대기 시간이 사용자 경험에 큰 영향 없음
   - InnoDB Row-Level Lock으로 영향 최소화

5. **검증된 패턴**
   - JPA Pessimistic Lock은 표준 기능
   - 많은 프로덕션 환경에서 검증됨

### 거부 사유

- **Option 2**: 재시도 로직 복잡, 이벤트 중복 발행 위험
- **Option 3**: MariaDB 버전 지원 불확실, 제한적 보호
- **Option 4**: 오버엔지니어링, 인프라 복잡도 과다

---

## Implementation Details

### 1. Repository 계층

```java
// RoomTimeSlotRepository.java
public interface RoomTimeSlotRepository extends JpaRepository<RoomTimeSlot, Long> {

    // 기존 메서드 (Lock 없음)
    Optional<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTime(
        Long roomId, LocalDate slotDate, LocalTime slotTime
    );

    // 신규 메서드 (Lock 있음)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM RoomTimeSlot s " +
           "WHERE s.roomId = :roomId " +
           "AND s.slotDate = :slotDate " +
           "AND s.slotTime = :slotTime")
    Optional<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTimeWithLock(
        @Param("roomId") Long roomId,
        @Param("slotDate") LocalDate slotDate,
        @Param("slotTime") LocalTime slotTime
    );

    // 다중 슬롯 Lock (기존 유지)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM RoomTimeSlot s " +
           "WHERE s.roomId = :roomId " +
           "AND s.slotDate = :slotDate " +
           "AND s.slotTime IN :slotTimes " +
           "ORDER BY s.slotTime ASC") // ← 데드락 방지
    List<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTimeInWithLock(
        @Param("roomId") Long roomId,
        @Param("slotDate") LocalDate slotDate,
        @Param("slotTimes") List<LocalTime> slotTimes
    );
}
```

### 2. Port 인터페이스

```java
// TimeSlotPort.java
public interface TimeSlotPort {

    // 조회용 (Lock 없음)
    Optional<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTime(
        Long roomId, LocalDate slotDate, LocalTime slotTime
    );

    // 예약용 (Lock 있음)
    Optional<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTimeWithLock(
        Long roomId, LocalDate slotDate, LocalTime slotTime
    );

    // 다중 슬롯 예약용 (Lock 있음)
    List<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTimeInWithLock(
        Long roomId, LocalDate slotDate, List<LocalTime> slotTimes
    );
}
```

### 3. Adapter 구현

```java
// TimeSlotJpaAdapter.java
@Repository
public class TimeSlotJpaAdapter implements TimeSlotPort {

    private final RoomTimeSlotRepository repository;

    @Override
    public Optional<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTime(
        Long roomId, LocalDate slotDate, LocalTime slotTime
    ) {
        return repository.findByRoomIdAndSlotDateAndSlotTime(roomId, slotDate, slotTime);
    }

    @Override
    public Optional<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTimeWithLock(
        Long roomId, LocalDate slotDate, LocalTime slotTime
    ) {
        return repository.findByRoomIdAndSlotDateAndSlotTimeWithLock(roomId, slotDate, slotTime);
    }

    @Override
    public List<RoomTimeSlot> findByRoomIdAndSlotDateAndSlotTimeInWithLock(
        Long roomId, LocalDate slotDate, List<LocalTime> slotTimes
    ) {
        return repository.findByRoomIdAndSlotDateAndSlotTimeInWithLock(roomId, slotDate, slotTimes);
    }
}
```

### 4. Domain Service 수정

```java
// TimeSlotManagementServiceImpl.java
@Service
@Transactional
public class TimeSlotManagementServiceImpl implements TimeSlotManagementService {

    private final TimeSlotPort timeSlotPort;

    @Override
    public void markSlotAsPending(Long roomId, LocalDate slotDate, LocalTime slotTime, Long reservationId) {
        // ✅ Lock을 걸고 조회
        RoomTimeSlot slot = timeSlotPort
            .findByRoomIdAndSlotDateAndSlotTimeWithLock(roomId, slotDate, slotTime)
            .orElseThrow(() -> new SlotNotFoundException(roomId, slotDate.toString(), slotTime.toString()));

        // 도메인 로직: 상태 전이
        slot.markAsPending(reservationId);
        timeSlotPort.save(slot);

        log.info("Slot marked as pending with pessimistic lock: slotId={}, roomId={}, reservationId={}",
            slot.getSlotId(), roomId, reservationId);
    }

    // confirmSlot, cancelSlot도 동일하게 Lock 적용
    @Override
    public void confirmSlot(Long roomId, LocalDate slotDate, LocalTime slotTime) {
        RoomTimeSlot slot = timeSlotPort
            .findByRoomIdAndSlotDateAndSlotTimeWithLock(roomId, slotDate, slotTime)
            .orElseThrow(() -> new SlotNotFoundException(roomId, slotDate.toString(), slotTime.toString()));

        slot.confirm();
        timeSlotPort.save(slot);
    }

    @Override
    public void cancelSlot(Long roomId, LocalDate slotDate, LocalTime slotTime) {
        RoomTimeSlot slot = timeSlotPort
            .findByRoomIdAndSlotDateAndSlotTimeWithLock(roomId, slotDate, slotTime)
            .orElseThrow(() -> new SlotNotFoundException(roomId, slotDate.toString(), slotTime.toString()));

        slot.cancel();
        timeSlotPort.save(slot);
    }
}
```

### 5. Lock Timeout 설정

```yaml
# application.yaml
spring:
  jpa:
    properties:
      javax.persistence.lock.timeout: 5000  # 5초 후 LockTimeoutException
      hibernate.query.fail_on_pagination_over_collection_fetch: true
```

---

## Consequences

### Positive

- **정확성 보장**: Lost Update 완전 방지
- **코드 일관성**: 단일/다중 슬롯 예약의 동시성 제어 통일
- **단순성**: 추가 인프라나 복잡한 재시도 로직 불필요
- **디버깅 용이**: Lock 타임아웃 예외로 문제 즉시 인지
- **SOLID 준수**: Port/Adapter 패턴 유지

### Negative

- **처리량 감소**: Lock 대기 시간만큼 응답 시간 증가
  - **측정 필요**: 부하 테스트로 실제 영향도 파악
  - **완화 방안**: 트랜잭션을 최대한 짧게 유지

- **Lock 타임아웃 위험**: 장기 트랜잭션 시 대기 중인 요청 실패
  - **완화 방안**: 타임아웃 5초로 설정 (충분히 긴 시간)

### Risks

- **데드락 가능성**: 여러 슬롯을 순서 없이 Lock 획득 시
  - **완화 방안**: 다중 슬롯 조회 시 `ORDER BY slotTime ASC`로 정렬
  - **모니터링**: 데드락 발생 시 로그 알림

---

## Validation

### 1. 동시성 테스트

```java
@SpringBootTest
@Transactional
class PessimisticLockConcurrencyTest {

    @Autowired
    private TimeSlotManagementService timeSlotService;

    @Test
    void concurrentReservation_ShouldOnlyAllowOne() throws InterruptedException {
        // Given: 동일 슬롯 준비
        Long roomId = 101L;
        LocalDate slotDate = LocalDate.of(2025, 1, 20);
        LocalTime slotTime = LocalTime.of(10, 0);

        // When: 2개 스레드가 동시 예약 시도
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Runnable reservationTask = () -> {
            try {
                timeSlotService.markSlotAsPending(roomId, slotDate, slotTime, 1001L);
                successCount.incrementAndGet();
            } catch (InvalidSlotStateTransitionException e) {
                failureCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        };

        executor.submit(reservationTask);
        executor.submit(reservationTask);
        latch.await(10, TimeUnit.SECONDS);

        // Then: 1개만 성공, 1개는 실패
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);
    }
}
```

### 2. Lock 타임아웃 테스트

```java
@Test
void reservationWithLongTransaction_ShouldTimeout() {
    // Given: Thread A가 Lock 획득 후 10초 대기
    CompletableFuture<Void> longTransaction = CompletableFuture.runAsync(() -> {
        timeSlotService.markSlotAsPendingAndWait(roomId, slotDate, slotTime, 1001L, 10000);
    });

    Thread.sleep(100); // Lock 획득 대기

    // When: Thread B가 같은 슬롯 예약 시도
    assertThrows(LockTimeoutException.class, () -> {
        timeSlotService.markSlotAsPending(roomId, slotDate, slotTime, 1002L);
    });
}
```

### 3. 성능 테스트

```java
@Test
void lockPerformance_ShouldBeAcceptable() {
    // Given: 100개 슬롯 준비
    List<RoomTimeSlot> slots = prepareSlots(100);

    // When: 순차적으로 예약 (Lock 오버헤드 측정)
    long start = System.currentTimeMillis();
    slots.forEach(slot -> {
        timeSlotService.markSlotAsPending(
            slot.getRoomId(), slot.getSlotDate(), slot.getSlotTime(), 1001L
        );
    });
    long duration = System.currentTimeMillis() - start;

    // Then: 평균 50ms 이내 (Lock 오버헤드 포함)
    assertThat(duration / 100).isLessThan(50);
}
```

---

## References

- [JPA Pessimistic Locking](https://docs.oracle.com/javaee/7/tutorial/persistence-locking002.htm)
- [MySQL InnoDB Locking](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html)
- [Spring Data JPA - Lock](https://docs.spring.io/spring-data/jpa/docs/current/reference/html/#locking)
- [Preventing Lost Updates in Database](https://vladmihalcea.com/preventing-lost-updates-in-database/)

---

## Future Considerations

**Phase 1 (현재)**:
- Pessimistic Lock 적용
- Lock 타임아웃 5초

**Phase 2**:
- Lock 성능 모니터링 (Prometheus + Grafana)
- Lock 대기 시간 메트릭 수집

**Phase 3 (트래픽 증가 시)**:
- Optimistic Lock으로 전환 검토
- 충돌 빈도가 낮으면 성능 우선 전략 고려

**Phase 4 (대규모 트래픽)**:
- Redis 분산 Lock 도입
- Read Replica 활용 (조회와 쓰기 분리)

---

**Maintained by**: Teambind_dev_backend Team
**Lead Developer**: DDINGJOO