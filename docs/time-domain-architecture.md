# 시간 도메인 아키텍처 설계

## 1. 도메인 책임 분리

### 1.1 현재 서버 (시간 관리 도메인)
**핵심 책임:**
- 운영시간 관리
- 예약 가능 시간 슬롯 관리
- 시간대별 가격 정책
- 시간 가용성 체크
- 예약 시간 락(Lock) 관리

**제외 사항:**
- 예약 승인/거절 (예약 도메인)
- 블랙리스트 관리 (사용자 도메인)
- 추가 상품 (상품 도메인)
- 결제 처리 (결제 도메인)

### 1.2 도메인 경계 정의
```
┌─────────────────────────────────────────────────────────┐
│                     시간 관리 도메인                      │
├─────────────────────────────────────────────────────────┤
│ • Operating Hours (운영시간)                             │
│ • Time Slots (시간 슬롯)                                │
│ • Pricing Rules (가격 규칙)                             │
│ • Availability (가용성)                                 │
│ • Temporal Locks (시간 락)                              │
└─────────────────────────────────────────────────────────┘
                              ↕
                    Domain Events (이벤트)
                              ↕
┌─────────────────────────────────────────────────────────┐
│                      예약 도메인                         │
│ • Reservation Status                                    │
│ • Approval Process                                      │
│ • Additional Products                                   │
└─────────────────────────────────────────────────────────┘
```

## 2. 데이터베이스 설계

### 2.1 핵심 테이블 구조

```sql
-- 시간별 가격 정책
CREATE TABLE time_pricing_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    day_of_week TINYINT NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    base_price DECIMAL(10,2) NOT NULL,

    -- 동적 가격 조정
    surge_multiplier DECIMAL(3,2) DEFAULT 1.00,
    holiday_multiplier DECIMAL(3,2) DEFAULT 1.00,

    -- 우선순위 (충돌 시 높은 값 적용)
    priority INT DEFAULT 0,

    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_room_day (room_id, day_of_week),
    INDEX idx_time_range (start_time, end_time)
) ENGINE=InnoDB;

-- 특별 가격 정책 (특정 날짜)
CREATE TABLE special_pricing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    specific_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    special_price DECIMAL(10,2) NOT NULL,
    reason VARCHAR(255),

    UNIQUE KEY uk_room_date_time (room_id, specific_date, start_time),
    INDEX idx_date_lookup (specific_date, room_id)
) ENGINE=InnoDB;

-- 예약된 시간 슬롯 (확정된 예약만)
CREATE TABLE reserved_time_slots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    reservation_id BIGINT NOT NULL, -- 예약 도메인 참조
    reservation_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,

    -- 예약 상태 (이벤트로 동기화)
    status VARCHAR(50) NOT NULL, -- CONFIRMED, CANCELLED, COMPLETED

    -- 가격 스냅샷 (예약 시점 가격)
    applied_price DECIMAL(10,2) NOT NULL,
    price_rule_id BIGINT,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_reservation (reservation_id),
    INDEX idx_room_date (room_id, reservation_date),
    INDEX idx_date_time (reservation_date, start_time, end_time),
    INDEX idx_status (status, reservation_date)
) ENGINE=InnoDB;

-- 시간 가용성 캐시 (읽기 최적화)
CREATE TABLE time_availability_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    availability_date DATE NOT NULL,

    -- JSON 형태로 시간별 상태 저장
    time_slots JSON COMMENT '[
        {
            "time": "09:00",
            "status": "available|reserved|locked|maintenance",
            "price": 50000,
            "lockId": "uuid",
            "lockExpiry": "2024-01-01 10:00:00"
        }
    ]',

    -- 빠른 필터링을 위한 집계 필드
    total_slots INT NOT NULL,
    available_count INT NOT NULL,
    min_price DECIMAL(10,2),
    max_price DECIMAL(10,2),

    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_room_date (room_id, availability_date),
    INDEX idx_availability (availability_date, available_count),
    INDEX idx_price_range (min_price, max_price)
) ENGINE=InnoDB;
```

### 2.2 Redis 구조 (임시 락 관리)

```redis
# 예약 진행 중 임시 락 (TTL 설정)
# Key: lock:room:{roomId}:date:{date}:slot:{startTime}
# Value: {
#   "reservationId": "temp-uuid",
#   "userId": 12345,
#   "lockedAt": "2024-01-01T10:00:00",
#   "expiresAt": "2024-01-01T10:15:00"
# }
# TTL: 15분 (결제 제한 시간)

# Lua Script for Atomic Lock
EVAL "
  local key = KEYS[1]
  local value = ARGV[1]
  local ttl = ARGV[2]

  if redis.call('EXISTS', key) == 0 then
    redis.call('SET', key, value, 'EX', ttl)
    return 1
  else
    return 0
  end
" 1 lock:room:123:date:2024-01-01:slot:09:00 {...} 900

# 가용 시간 조회 캐시
# Key: available:room:{roomId}:date:{date}
# Type: Sorted Set
# Score: 시간 (0900, 0930, ...)
# Member: JSON (가격, 상태)
ZADD available:room:123:date:2024-01-01 \
  0900 '{"price":50000,"status":"available"}' \
  0930 '{"price":50000,"status":"available"}' \
  1000 '{"price":60000,"status":"available"}'
```

## 3. 이벤트 기반 아키텍처

### 3.1 이벤트 흐름

```yaml
# 1. 예약 시작 (시간 락)
ReservationInitiated:
  source: reservation-service
  data:
    reservationId: temp-uuid
    roomId: 123
    date: 2024-01-01
    timeSlots: ["09:00", "09:30"]
    userId: 456
  action:
    - Redis에 임시 락 생성 (TTL 15분)
    - 가용성 캐시 업데이트 (locked 상태)

# 2. 결제 대기 상태
PaymentPending:
  source: reservation-service
  data:
    reservationId: actual-uuid
    tempReservationId: temp-uuid
  action:
    - 임시 락 ID를 실제 예약 ID로 교체
    - TTL 연장 (필요시)

# 3. 결제 완료
PaymentCompleted:
  source: payment-service
  data:
    reservationId: actual-uuid
    status: SUCCESS
  action:
    - Redis 락 제거
    - DB에 확정된 예약 저장
    - 가용성 캐시 업데이트 (reserved 상태)

# 4. 결제 실패/취소
PaymentFailed:
  source: payment-service
  data:
    reservationId: actual-uuid
    status: FAILED
  action:
    - Redis 락 즉시 제거
    - 가용성 캐시 업데이트 (available 상태)

# 5. 락 만료 (자동)
LockExpired:
  source: time-service (scheduled)
  trigger: Redis TTL or Scheduler
  action:
    - 만료된 락 정리
    - 가용성 캐시 업데이트
    - ReservationExpired 이벤트 발행
```

### 3.2 이벤트 처리 구현

```java
@Component
@RequiredArgsConstructor
public class ReservationEventHandler {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ReservedTimeSlotRepository timeSlotRepository;
    private final TimeAvailabilityService availabilityService;

    @EventHandler
    @Transactional
    public void handle(ReservationInitiated event) {
        // 1. Redis 임시 락
        String lockKey = buildLockKey(event);
        TimeLock lock = TimeLock.builder()
            .reservationId(event.getReservationId())
            .userId(event.getUserId())
            .lockedAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusMinutes(15))
            .build();

        // Lua Script로 원자적 락 획득
        Boolean acquired = redisTemplate.execute(
            acquireLockScript,
            Collections.singletonList(lockKey),
            lock.toJson(),
            900 // 15분 TTL
        );

        if (!acquired) {
            throw new TimeSlotAlreadyLockedException();
        }

        // 2. 가용성 캐시 업데이트
        availabilityService.updateStatus(
            event.getRoomId(),
            event.getDate(),
            event.getTimeSlots(),
            SlotStatus.LOCKED
        );
    }

    @EventHandler
    @Transactional
    public void handle(PaymentCompleted event) {
        // 1. 예약 확정 DB 저장
        ReservedTimeSlot confirmed = ReservedTimeSlot.builder()
            .roomId(event.getRoomId())
            .reservationId(event.getReservationId())
            .reservationDate(event.getDate())
            .startTime(event.getStartTime())
            .endTime(event.getEndTime())
            .status(ReservationStatus.CONFIRMED)
            .appliedPrice(event.getPrice())
            .build();

        timeSlotRepository.save(confirmed);

        // 2. Redis 락 제거
        String lockKey = buildLockKey(event);
        redisTemplate.delete(lockKey);

        // 3. 가용성 캐시 업데이트
        availabilityService.updateStatus(
            event.getRoomId(),
            event.getDate(),
            event.getTimeSlots(),
            SlotStatus.RESERVED
        );
    }
}
```

## 4. 조회 최적화 전략

### 4.1 필터링 쿼리 예시

```sql
-- 특정 날짜/시간에 예약 가능한 방 조회
SELECT
    tac.room_id,
    tac.availability_date,
    tac.available_count,
    tac.min_price,
    JSON_EXTRACT(tac.time_slots,
        '$[*] ? (@.status == "available" && @.time >= "14:00" && @.time <= "18:00")'
    ) as available_slots
FROM time_availability_cache tac
INNER JOIN rooms r ON r.id = tac.room_id
WHERE
    tac.availability_date = '2024-03-15'
    AND tac.available_count > 0
    AND JSON_CONTAINS(
        tac.time_slots,
        '{"status": "available"}',
        '$[*]'
    )
    AND r.status = 'ACTIVE'
ORDER BY tac.min_price ASC;
```

### 4.2 복합 조건 처리

```java
@Repository
public interface TimeAvailabilityRepository {

    @Query("""
        SELECT new TimeAvailabilityDto(
            tac.roomId,
            tac.availabilityDate,
            tac.timeSlots,
            tac.minPrice,
            tac.maxPrice
        )
        FROM TimeAvailabilityCache tac
        WHERE tac.availabilityDate BETWEEN :startDate AND :endDate
        AND tac.availableCount >= :minSlots
        AND tac.minPrice <= :maxPrice
        AND FUNCTION('JSON_CONTAINS',
            tac.timeSlots,
            :requiredTimeSlots,
            '$[*].time'
        ) = 1
        """)
    List<TimeAvailabilityDto> findAvailableRooms(
        LocalDate startDate,
        LocalDate endDate,
        Integer minSlots,
        BigDecimal maxPrice,
        String requiredTimeSlots
    );
}
```

## 5. 장점과 고려사항

### 5.1 장점

1. **명확한 도메인 경계**
   - 시간 관리만 집중
   - 다른 도메인과 느슨한 결합

2. **높은 성능**
   - Redis 락으로 빠른 응답
   - 캐시 테이블로 복잡한 조회 최적화

3. **확장성**
   - 이벤트 기반으로 새로운 요구사항 쉽게 추가
   - 독립적 스케일링 가능

4. **데이터 일관성**
   - 이벤트 소싱으로 상태 추적
   - 임시 락과 확정 예약 분리

### 5.2 구현 시 고려사항

1. **Redis 장애 대응**
   ```java
   @Component
   public class FallbackLockService {
       // DB 기반 폴백 락 구현
       public boolean acquireLockWithFallback(String key) {
           try {
               return redisLockService.acquire(key);
           } catch (RedisException e) {
               return dbLockService.acquire(key);
           }
       }
   }
   ```

2. **이벤트 순서 보장**
   - Kafka의 경우 파티션 키로 roomId 사용
   - 동일 방의 이벤트는 순서 보장

3. **캐시 일관성**
   - 정기적 동기화 배치
   - 이벤트 기반 즉시 갱신

4. **락 만료 처리**
   ```java
   @Scheduled(fixedDelay = 60000) // 1분마다
   public void cleanExpiredLocks() {
       // Redis SCAN으로 만료 락 정리
       // 가용성 캐시 업데이트
       // 예약 서비스에 만료 이벤트 발행
   }
   ```

## 6. 구현 로드맵

### Phase 1: 기본 구조 (2주)
- [ ] DB 스키마 구현
- [ ] 기본 CRUD API
- [ ] 운영시간/휴무일 관리

### Phase 2: Redis 락 (1주)
- [ ] Redis 락 메커니즘
- [ ] TTL 기반 자동 만료
- [ ] Lua Script 원자성 보장

### Phase 3: 이벤트 통합 (2주)
- [ ] 이벤트 핸들러 구현
- [ ] Outbox Pattern
- [ ] 이벤트 재처리 로직

### Phase 4: 최적화 (1주)
- [ ] 가용성 캐시 테이블
- [ ] 복합 조건 쿼리 최적화
- [ ] 모니터링/알림