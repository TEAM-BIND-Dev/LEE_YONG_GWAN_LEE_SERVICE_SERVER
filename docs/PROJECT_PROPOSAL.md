# 운영시간 및 예약 시간 관리 서비스 기획안

## 목차
1. [프로젝트 개요](#1-프로젝트-개요)
2. [시스템 아키텍처](#2-시스템-아키텍처)
3. [도메인 설계](#3-도메인-설계)
4. [데이터베이스 설계](#4-데이터베이스-설계)
5. [핵심 기능 구현](#5-핵심-기능-구현)
6. [이벤트 기반 아키텍처](#6-이벤트-기반-아키텍처)
7. [성능 최적화 전략](#7-성능-최적화-전략)
8. [구현 로드맵](#8-구현-로드맵)
9. [기술 스택](#9-기술-스택)
10. [예상 성과](#10-예상-성과)

---

## 1. 프로젝트 개요

### 1.1 서비스 정의
MSA 기반 예약 시스템의 **시간 도메인 전담 서비스**로, 운영시간 관리와 예약 가능 시간 관리를 담당하는 핵심 마이크로서비스

### 1.2 핵심 책임
- **운영시간 관리**: 공간/방별 운영시간 및 휴무일 정책 관리
- **시간 슬롯 관리**: 예약 가능 시간 단위 관리 (15분/30분/60분)
- **가용성 판단**: 실시간 예약 가능 여부 판단
- **가격 정책**: 시간대별 동적 가격 관리
- **임시 락 관리**: 예약 진행 중 시간 슬롯 임시 점유

### 1.3 제외 범위
- 예약 승인/거절 (예약 도메인)
- 사용자 관리 및 블랙리스트 (사용자 도메인)
- 결제 처리 (결제 도메인)
- 추가 상품 관리 (상품 도메인)

---

## 2. 시스템 아키텍처

### 2.1 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                        API Gateway                           │
└─────────────────────────────────────────────────────────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
┌───────┴──────┐      ┌────────┴─────┐      ┌────────┴────────┐
│ Reservation  │      │ Time Service │      │ Payment Service │
│   Service    │      │   (본 서비스) │      │                 │
└───────┬──────┘      └────────┬─────┘      └────────┬────────┘
        │                      │                      │
        └──────────────────────┴──────────────────────┘
                               │
                    ┌──────────┴──────────┐
                    │   Apache Kafka      │
                    │  (Event Streaming)  │
                    └──────────┬──────────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
┌───────┴──────┐      ┌────────┴─────┐      ┌────────┴────────┐
│  MariaDB     │      │    Redis     │      │ Analytics DB    │
│ (Primary DB) │      │   (Cache)    │      │  (Read Model)   │
└──────────────┘      └──────────────┘      └─────────────────┘
```

### 2.2 서비스 간 통신

```yaml
동기 통신 (REST API):
  - 가용성 조회: GET /api/v1/availability
  - 시간 락: POST /api/v1/locks
  - 가격 조회: GET /api/v1/pricing

비동기 통신 (Kafka Events):
  - ReservationInitiated → 시간 락 생성
  - PaymentCompleted → 예약 확정
  - ReservationCancelled → 시간 해제
```

---

## 3. 도메인 설계

### 3.1 핵심 도메인 모델

```
Space (공간)
├── Room (방)
│   ├── TimeSlotType (15분/30분/60분)
│   ├── OperatingSchedule (운영시간)
│   ├── HolidayPolicy (휴무일 정책)
│   └── TimeSlot (사전 생성된 시간 슬롯)
│       ├── Status (AVAILABLE/LOCKED/RESERVED/BLOCKED)
│       ├── Price (가격 정보)
│       └── ReservationInfo (예약 정보)
```

### 3.2 도메인 이벤트

| 이벤트명 | 발생 시점 | 처리 |
|---------|----------|------|
| TimeSlotLocked | 예약 시작 | Redis 락 + DB 상태 변경 |
| TimeSlotConfirmed | 결제 완료 | 락 해제 + RESERVED 변경 |
| TimeSlotReleased | 예약 취소/만료 | AVAILABLE로 복구 |
| OperatingHoursChanged | 운영시간 변경 | 슬롯 재생성 |

---

## 4. 데이터베이스 설계

### 4.1 기술 선택: MariaDB 10.11 LTS

**선택 이유:**
- 완전 무료 오픈소스 (상용 서비스 제약 없음)
- MySQL과 99% 호환
- JSON 지원 우수
- Galera Cluster 내장

### 4.2 핵심 테이블 구조

#### 4.2.1 Pre-computed Time Slots (핵심)

```sql
-- 2개월치 타임슬롯을 미리 생성하여 저장
CREATE TABLE time_slots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    slot_date DATE NOT NULL,
    slot_time TIME NOT NULL,

    -- 상태 관리
    status ENUM('AVAILABLE', 'LOCKED', 'RESERVED', 'BLOCKED') DEFAULT 'AVAILABLE',
    reservation_id BIGINT,
    locked_by VARCHAR(100),
    locked_until TIMESTAMP,

    -- 가격 정보
    base_price DECIMAL(10,2) NOT NULL,
    final_price DECIMAL(10,2) NOT NULL,

    -- 메타 정보
    is_peak_time BOOLEAN DEFAULT FALSE,
    is_holiday BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_room_datetime (room_id, slot_date, slot_time),
    INDEX idx_availability (room_id, slot_date, status)
) ENGINE=InnoDB
PARTITION BY RANGE (TO_DAYS(slot_date));
```

#### 4.2.2 운영시간 관리

```sql
CREATE TABLE operating_schedules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type ENUM('SPACE', 'ROOM') NOT NULL,
    entity_id BIGINT NOT NULL,
    day_of_week TINYINT NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,

    INDEX idx_entity (entity_type, entity_id, day_of_week)
);

CREATE TABLE holiday_policies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type ENUM('SPACE', 'ROOM') NOT NULL,
    entity_id BIGINT NOT NULL,
    policy_type ENUM('WEEKLY', 'BIWEEKLY_ODD', 'BIWEEKLY_EVEN', 'SPECIFIC_DATE'),
    day_of_week TINYINT,
    specific_date DATE,
    reference_week_start DATE
);
```

#### 4.2.3 이벤트 스토어 (CQRS)

```sql
CREATE TABLE event_store (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_data JSON NOT NULL,
    event_metadata JSON,
    occurred_at TIMESTAMP(6) NOT NULL,

    INDEX idx_aggregate (aggregate_id, occurred_at)
);
```

---

## 5. 핵심 기능 구현

### 5.1 가용성 체크 (최적화)

```java
@Service
public class AvailabilityService {

    /**
     * 단일 쿼리로 가용성 판단
     * 응답시간: 3-5ms
     */
    public AvailabilityResult checkAvailability(
            Long roomId,
            LocalDate date,
            List<LocalTime> slots) {

        // 1. Redis 락 체크 (1-2ms)
        Set<String> locked = checkRedisLocks(roomId, date, slots);
        if (!locked.isEmpty()) {
            return TEMPORARILY_LOCKED;
        }

        // 2. DB 조회 - 단일 쿼리 (2-3ms)
        List<TimeSlot> timeSlots = slotRepository.findSlots(
            roomId, date, slots
        );

        // 3. 결과 분석
        if (timeSlots.stream().allMatch(s -> s.status == AVAILABLE)) {
            return AVAILABLE;
        }

        return UNAVAILABLE;
    }
}
```

### 5.2 슬롯 생성 배치

```java
@Component
public class SlotGenerationBatch {

    @Scheduled(cron = "0 0 0 * * *")  // 매일 자정
    public void generateDailySlots() {
        LocalDate targetDate = LocalDate.now().plusDays(60);

        for (Room room : activeRooms) {
            List<TimeSlot> slots = createSlotsForDay(room, targetDate);
            slotRepository.bulkInsert(slots);
        }

        // 오래된 슬롯 정리 (3개월 이전)
        cleanupOldSlots();
    }
}
```

### 5.3 임시 락 메커니즘

```java
@Service
public class LockService {

    /**
     * 2단계 락 전략
     * Redis (임시) → MariaDB (확정)
     */
    public String acquireLock(Long roomId, LocalDate date, List<LocalTime> slots) {
        String lockId = UUID.randomUUID().toString();

        // 1. DB 슬롯 상태 변경 (낙관적 락)
        int updated = slotRepository.lockAvailableSlots(
            roomId, date, slots, lockId,
            LocalDateTime.now().plusMinutes(15)
        );

        if (updated != slots.size()) {
            slotRepository.releaseLock(lockId);
            throw new SlotNotAvailableException();
        }

        // 2. Redis 기록 (빠른 조회용)
        slots.forEach(slot -> {
            String key = buildRedisKey(roomId, date, slot);
            redisTemplate.opsForValue().set(key, lockId,
                Duration.ofMinutes(15));
        });

        return lockId;
    }
}
```

---

## 6. 이벤트 기반 아키텍처

### 6.1 Kafka 이벤트 처리

```java
@Component
public class ReservationEventHandler {

    @KafkaListener(topics = "reservation-events")
    @Transactional
    public void handleEvent(ReservationEvent event) {

        // 멱등성 보장
        if (isAlreadyProcessed(event.getEventId())) {
            return;
        }

        switch (event.getType()) {
            case RESERVATION_INITIATED:
                lockTimeSlots(event);
                break;

            case PAYMENT_COMPLETED:
                confirmReservation(event);
                break;

            case RESERVATION_CANCELLED:
                releaseTimeSlots(event);
                break;
        }

        markAsProcessed(event.getEventId());
    }
}
```

### 6.2 순서 보장 및 보상 트랜잭션

```java
@Component
public class EventOrderingHandler {

    /**
     * 상태 기계 기반 순서 검증
     */
    public void validateStateTransition(Status current, Status incoming) {
        if (!stateMachine.canTransition(current, incoming)) {
            if (requiresCompensation(current, incoming)) {
                // 보상 트랜잭션 실행
                compensationService.compensate(current, incoming);
            }
        }
    }
}
```

---

## 7. 성능 최적화 전략

### 7.1 캐싱 전략

```yaml
Redis 캐싱:
  - 운영시간: 24시간 캐싱
  - Hot 슬롯: 7일치 데이터 캐싱
  - 임시 락: TTL 15분

DB 최적화:
  - 파티셔닝: 월 단위
  - 인덱스: 복합 인덱스 최적화
  - 벌크 연산: 배치 INSERT/UPDATE
```

### 7.2 성능 목표 및 달성 전략

| 작업 | 목표 | 전략 |
|------|------|------|
| 가용성 조회 | < 5ms | Pre-computed slots + 단일 쿼리 |
| 락 획득 | < 10ms | Redis + 낙관적 락 |
| 슬롯 생성 | < 1초/1000건 | 벌크 INSERT |
| 이벤트 처리 | < 100ms | 비동기 처리 + 배치 |

---

## 8. 구현 로드맵

### Phase 1: 기반 구축 (2주)
- [x] 프로젝트 셋업 및 기본 구조
- [ ] MariaDB 스키마 설계 및 구현
- [ ] 도메인 엔티티 구현
- [ ] 기본 CRUD API 개발

### Phase 2: 핵심 기능 (2주)
- [ ] Pre-computed 슬롯 생성 배치
- [ ] 가용성 체크 API
- [ ] Redis 락 메커니즘
- [ ] 가격 정책 관리

### Phase 3: 이벤트 통합 (2주)
- [ ] Kafka 연동
- [ ] 이벤트 핸들러 구현
- [ ] 상태 기계 구현
- [ ] 보상 트랜잭션

### Phase 4: 최적화 (1주)
- [ ] 성능 튜닝
- [ ] 캐싱 레이어 구현
- [ ] 모니터링 설정

### Phase 5: 운영 준비 (1주)
- [ ] 통합 테스트
- [ ] 부하 테스트
- [ ] 문서화
- [ ] 배포 준비

---

## 9. 기술 스택

### 9.1 Core
- **Language**: Java 17
- **Framework**: Spring Boot 3.x
- **Build**: Gradle

### 9.2 Data
- **Primary DB**: MariaDB 10.11 LTS
- **Cache**: Redis 7.x
- **Event Store**: Apache Kafka

### 9.3 Infrastructure
- **Container**: Docker
- **Orchestration**: Kubernetes
- **Monitoring**: Prometheus + Grafana
- **Logging**: ELK Stack

### 9.4 Development
- **Code Style**: Google Java Style Guide
- **Architecture**: DDD + Hexagonal Architecture
- **Testing**: JUnit 5 + Mockito
- **API Doc**: OpenAPI 3.0

---

## 10. 예상 성과

### 10.1 성능 개선

| 지표 | 현재 | 목표 | 개선율 |
|------|------|------|--------|
| 가용성 조회 | 20ms | 5ms | 75% ↓ |
| 동시 처리량 | 2,000 TPS | 10,000 TPS | 400% ↑ |
| 에러율 | 0.1% | 0.01% | 90% ↓ |

### 10.2 비즈니스 가치

1. **운영 효율성**
   - 자동화된 슬롯 관리로 운영 부담 감소
   - 실시간 가용성 제공으로 예약 전환율 향상

2. **데이터 분석**
   - 완벽한 이벤트 소싱으로 상세 분석 가능
   - 패턴 분석을 통한 가격 최적화

3. **확장성**
   - MSA 구조로 독립적 스케일링
   - 월 1억 건 이상 처리 가능

### 10.3 위험 요소 및 대응

| 위험 | 영향도 | 대응 방안 |
|------|--------|----------|
| Redis 장애 | 높음 | DB 폴백 메커니즘 |
| 이벤트 순서 역전 | 중간 | 상태 기계 + 보상 |
| 슬롯 생성 실패 | 높음 | 재시도 + 알림 |

---

## 부록

### A. API 명세
```yaml
GET /api/v1/availability:
  parameters:
    - roomId: Long
    - date: LocalDate
    - slots: List<LocalTime>
  response:
    - status: AVAILABLE | UNAVAILABLE | PARTIALLY_AVAILABLE
    - availableSlots: List<TimeSlot>
    - totalPrice: BigDecimal

POST /api/v1/locks:
  request:
    - roomId: Long
    - date: LocalDate
    - slots: List<LocalTime>
    - userId: Long
  response:
    - lockId: String
    - expiresAt: LocalDateTime
```

### B. 데이터베이스 용량 계산
```
일일 데이터:
- 10,000 방 × 20 슬롯 = 200,000 레코드
- 레코드당 100 bytes = 20MB/일

2개월 데이터:
- 60일 × 20MB = 1.2GB
- 인덱스 포함: ~2GB

연간 이벤트:
- 10,000 예약/일 × 5 이벤트 × 365일 = 18,250,000 이벤트
- 이벤트당 500 bytes = ~9GB/년
```

### C. 모니터링 대시보드
- 실시간 TPS
- 가용 슬롯 비율
- 락 성공률
- 이벤트 처리 지연
- 에러율 추이

---

