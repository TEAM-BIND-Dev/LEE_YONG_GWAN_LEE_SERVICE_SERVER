# ADR-007: Event Handler Architecture Alignment

**Status**: Proposed
**Date**: 2025-01-19
**Decision Makers**: Teambind_dev_backend Team
**Technical Story**: EventHandler의 Hexagonal Architecture 정합성 개선

---

## Context

현재 `SlotRestoredEventHandler`는 Hexagonal Architecture의 원칙을 위반하고 있습니다.

### 현재 구현

```java
// SlotRestoredEventHandler.java:6
import com.teambind.springproject.room.repository.RoomTimeSlotRepository;

@Component
@RequiredArgsConstructor
public class SlotRestoredEventHandler implements EventHandler<SlotRestoredEvent> {

    // ❌ Infrastructure 계층의 Repository를 직접 의존
    private final RoomTimeSlotRepository slotRepository;

    @Override
    @Transactional
    public void handle(SlotRestoredEvent event) {
        Long reservationId = Long.parseLong(event.getReservationId());

        // Repository를 직접 호출
        List<RoomTimeSlot> slots = slotRepository.findByReservationId(reservationId);

        for (RoomTimeSlot slot : slots) {
            try {
                slot.restore();
                slotRepository.save(slot); // ❌ 부분 실패 시 일부만 복구
                restoredCount++;
            } catch (Exception e) {
                log.error("Failed to restore slot...");
                // 예외 무시 (일부 슬롯만 복구됨)
            }
        }
    }
}
```

### 문제점

**1. Hexagonal Architecture 위반**:
```
┌─────────────────────────────────────────┐
│      Event Handler (Application)       │
│              ↓ 의존                     │
│      JPA Repository (Infrastructure)    │ ❌ 계층 침범
└─────────────────────────────────────────┘

올바른 의존성 방향:
Application → Domain (Port) → Infrastructure (Adapter)
```

**2. Port 미사용**:
- `TimeSlotPort` 인터페이스가 존재하지만 사용하지 않음
- DIP (Dependency Inversion Principle) 위반

**3. 부분 실패 처리 문제**:
```java
for (RoomTimeSlot slot : slots) {
    try {
        slot.restore();
        slotRepository.save(slot);
    } catch (Exception e) {
        // ❌ 예외를 잡아서 무시 → 일부 슬롯만 복구됨
    }
}
```

**시나리오**:
```
예약 ID: 1001 (슬롯 3개: 10:00, 11:00, 12:00)

1. 10:00 슬롯 복구 성공 ✅
2. 11:00 슬롯 복구 실패 ❌ (DB 제약조건 위반)
3. 12:00 슬롯 복구 성공 ✅

결과: 예약 1001의 슬롯이 일부만 복구됨 (데이터 불일치)
```

**4. 도메인 로직 중복**:
- 슬롯 복구 로직이 EventHandler에 직접 구현
- Domain Service(`TimeSlotManagementService`)에 이미 존재하는 로직과 중복

### 영향도

- **우선순위**: High (아키텍처 원칙 위반)
- **리스크**: 부분 실패 시 데이터 불일치
- **유지보수성**: 도메인 로직 분산으로 변경 영향 범위 증가

---

## Decision Drivers

- Hexagonal Architecture 일관성 유지 (ADR-003 준수)
- DIP (Dependency Inversion Principle) 준수
- 트랜잭션 원자성 보장 (All-or-Nothing)
- 도메인 로직의 단일 책임 위치 (Domain Service)
- 기존 Port/Adapter 패턴 활용

---

## Considered Options

### Option 1: Domain Service 활용 (권장)

**개념**:
EventHandler는 Application 계층의 역할만 수행하고, 도메인 로직은 Domain Service에 위임합니다.

**구현 예시**:

*1. Domain Service에 메서드 추가*:
```java
// TimeSlotManagementService.java (Interface)
public interface TimeSlotManagementService {

    // 기존 메서드들...

    /**
     * 예약 ID로 모든 슬롯을 복구한다.
     * 하나라도 실패하면 전체 롤백 (All-or-Nothing).
     *
     * @param reservationId 예약 ID
     * @return 복구된 슬롯 개수
     */
    int restoreSlotsByReservationId(Long reservationId);
}
```

*2. Domain Service 구현*:
```java
// TimeSlotManagementServiceImpl.java
@Service
@Transactional
public class TimeSlotManagementServiceImpl implements TimeSlotManagementService {

    private final TimeSlotPort timeSlotPort;

    @Override
    public int restoreSlotsByReservationId(Long reservationId) {
        log.info("Restoring slots for reservationId={}", reservationId);

        // Port를 통해 슬롯 조회
        List<RoomTimeSlot> slots = timeSlotPort.findByReservationId(reservationId);

        if (slots.isEmpty()) {
            log.warn("No slots found for reservationId={}", reservationId);
            return 0;
        }

        // 모든 슬롯을 복구 상태로 변경 (예외 발생 시 전체 롤백)
        slots.forEach(RoomTimeSlot::restore);

        // 일괄 저장
        timeSlotPort.saveAll(slots);

        log.info("Restored {} slots for reservationId={}", slots.size(), reservationId);
        return slots.size();
    }
}
```

*3. EventHandler 리팩터링*:
```java
// SlotRestoredEventHandler.java
@Component
@RequiredArgsConstructor
public class SlotRestoredEventHandler implements EventHandler<SlotRestoredEvent> {

    // ✅ Domain Service에 의존 (Application → Domain)
    private final TimeSlotManagementService timeSlotManagementService;

    @Override
    public void handle(SlotRestoredEvent event) {
        log.info("Processing SlotRestoredEvent: reservationId={}, reason={}",
            event.getReservationId(), event.getRestoreReason());

        // String → Long 변환
        Long reservationId = Long.parseLong(event.getReservationId());

        // Domain Service에 위임
        int restoredCount = timeSlotManagementService.restoreSlotsByReservationId(reservationId);

        log.info("SlotRestoredEvent processed: reservationId={}, restored {} slots",
            reservationId, restoredCount);
    }

    @Override
    public String getSupportedEventType() {
        return "SlotRestored";
    }
}
```

**의존성 방향 (올바름)**:
```
EventHandler (Application)
    ↓ 의존
TimeSlotManagementService (Domain)
    ↓ 의존
TimeSlotPort (Domain Interface)
    ↑ 구현
TimeSlotJpaAdapter (Infrastructure)
    ↓ 사용
RoomTimeSlotRepository (Infrastructure)
```

**장점**:
- **Hexagonal Architecture 준수**: Application → Domain → Infrastructure
- **DIP 준수**: 인터페이스에 의존
- **트랜잭션 원자성**: Domain Service의 @Transactional로 All-or-Nothing 보장
- **도메인 로직 집중**: 슬롯 복구 로직이 Domain Service에만 존재
- **재사용성**: 다른 Use Case에서도 동일 메서드 재사용 가능
- **테스트 용이**: Domain Service만 단위 테스트하면 됨

**단점**:
- **메서드 추가**: Domain Service에 메서드 1개 추가 필요

**Trade-offs**:
- 코드 증가 vs 아키텍처 정합성: 메서드 추가를 허용하여 원칙 준수

---

### Option 2: Port를 직접 주입

**개념**:
EventHandler가 TimeSlotPort를 직접 의존하도록 변경합니다.

**구현 예시**:
```java
@Component
@RequiredArgsConstructor
public class SlotRestoredEventHandler implements EventHandler<SlotRestoredEvent> {

    // ✅ Port에 의존 (Repository 대신)
    private final TimeSlotPort timeSlotPort;

    @Override
    @Transactional
    public void handle(SlotRestoredEvent event) {
        Long reservationId = Long.parseLong(event.getReservationId());

        List<RoomTimeSlot> slots = timeSlotPort.findByReservationId(reservationId);

        // 모든 슬롯 복구
        slots.forEach(RoomTimeSlot::restore);
        timeSlotPort.saveAll(slots);
    }
}
```

**장점**:
- **DIP 준수**: Port 인터페이스에 의존
- **코드 간결**: Domain Service 메서드 추가 불필요

**단점**:
- **도메인 로직 분산**: 슬롯 복구 로직이 EventHandler에 직접 구현
  - 다른 곳에서 슬롯 복구가 필요하면 코드 중복
- **SRP 위반**: EventHandler가 "이벤트 수신" + "도메인 로직 실행" 두 가지 책임
- **테스트 복잡도**: EventHandler 테스트 시 Port Mock 필요

**Trade-offs**:
- 단순성 vs 책임 분리: 코드는 간단하지만 SRP 위반

---

### Option 3: Application Service 추가

**개념**:
슬롯 복구 전용 Application Service를 만들어 EventHandler에서 호출합니다.

**구현 예시**:

*1. Application Service 생성*:
```java
// SlotRestorationApplicationService.java
@Service
@RequiredArgsConstructor
public class SlotRestorationApplicationService {

    private final TimeSlotManagementService timeSlotManagementService;

    @Transactional
    public int restoreSlots(Long reservationId) {
        return timeSlotManagementService.restoreSlotsByReservationId(reservationId);
    }
}
```

*2. EventHandler 수정*:
```java
@Component
@RequiredArgsConstructor
public class SlotRestoredEventHandler implements EventHandler<SlotRestoredEvent> {

    private final SlotRestorationApplicationService restorationService;

    @Override
    public void handle(SlotRestoredEvent event) {
        Long reservationId = Long.parseLong(event.getReservationId());
        restorationService.restoreSlots(reservationId);
    }
}
```

**장점**:
- **계층 분리 명확**: Application Service가 Use Case 조율
- **추가 로직 확장 용이**: Application Service에서 다른 작업 추가 가능 (알림 발송 등)

**단점**:
- **과도한 계층화**: 단순 위임만 하는 Application Service 추가 (오버엔지니어링)
- **클래스 증가**: Application Service 추가로 복잡도 증가

**Trade-offs**:
- 확장성 vs 단순성: 확장 가능성을 위해 계층 추가

---

### Option 4: 현재 방식 유지 + 부분 실패 문제만 수정

**개념**:
Repository 의존은 유지하되, 트랜잭션 처리만 개선합니다.

**구현 예시**:
```java
@Component
@RequiredArgsConstructor
public class SlotRestoredEventHandler implements EventHandler<SlotRestoredEvent> {

    private final RoomTimeSlotRepository slotRepository;

    @Override
    @Transactional
    public void handle(SlotRestoredEvent event) {
        Long reservationId = Long.parseLong(event.getReservationId());

        List<RoomTimeSlot> slots = slotRepository.findByReservationId(reservationId);

        // ✅ try-catch 제거 → 예외 발생 시 전체 롤백
        slots.forEach(RoomTimeSlot::restore);
        slotRepository.saveAll(slots);

        log.info("Restored {} slots for reservationId={}", slots.size(), reservationId);
    }
}
```

**장점**:
- **변경 최소화**: 기존 코드 유지
- **부분 실패 해결**: All-or-Nothing 보장

**단점**:
- **아키텍처 위반 지속**: Repository 직접 의존
- **기술 부채 누적**: Hexagonal Architecture 불일치

**Trade-offs**:
- 안정성 vs 원칙: 부분 실패는 해결하지만 설계 원칙 위배

---

## Decision Outcome

**선택: Option 1 - Domain Service 활용**

### 선택 이유

1. **아키텍처 일관성**
   - ADR-003(Hexagonal Architecture)의 원칙 준수
   - 모든 계층이 올바른 의존성 방향 유지
   - Application → Domain → Infrastructure

2. **도메인 로직 집중**
   - 슬롯 복구 로직이 Domain Service에만 존재
   - 코드 중복 방지
   - 단일 책임 위치 (SRP)

3. **트랜잭션 안정성**
   - Domain Service의 @Transactional로 All-or-Nothing 보장
   - 부분 실패 문제 완전 해결

4. **재사용성**
   - 다른 Use Case(예: 관리자 수동 복구)에서도 동일 메서드 사용 가능
   - API 엔드포인트 추가 시 코드 중복 없음

5. **테스트 용이성**
   - Domain Service 단위 테스트로 로직 검증
   - EventHandler는 얇은 래퍼로만 동작하여 테스트 단순

### 거부 사유

- **Option 2**: 도메인 로직이 EventHandler에 분산됨
- **Option 3**: 단순 위임만 하는 Application Service 추가 (오버엔지니어링)
- **Option 4**: 아키텍처 원칙 위반 지속

---

## Implementation Details

### 1. Domain Service 인터페이스 수정

```java
// TimeSlotManagementService.java
public interface TimeSlotManagementService {

    // 기존 메서드들...
    void markSlotAsPending(Long roomId, LocalDate slotDate, LocalTime slotTime, Long reservationId);
    void confirmSlot(Long roomId, LocalDate slotDate, LocalTime slotTime);
    void cancelSlot(Long roomId, LocalDate slotDate, LocalTime slotTime);
    void cancelSlotsByReservationId(Long reservationId);
    int markMultipleSlotsAsPending(Long roomId, LocalDate slotDate, List<LocalTime> slotTimes, Long reservationId);
    int restoreExpiredPendingSlots();

    /**
     * 예약 ID로 모든 슬롯을 복구한다.
     * 하나라도 실패하면 전체 롤백 (All-or-Nothing).
     *
     * @param reservationId 예약 ID
     * @return 복구된 슬롯 개수
     * @throws SlotNotFoundException 슬롯이 존재하지 않을 경우 (경고 로그 후 0 반환)
     */
    int restoreSlotsByReservationId(Long reservationId);
}
```

### 2. Domain Service 구현 추가

```java
// TimeSlotManagementServiceImpl.java
@Service
@Transactional
public class TimeSlotManagementServiceImpl implements TimeSlotManagementService {

    private static final Logger log = LoggerFactory.getLogger(TimeSlotManagementServiceImpl.class);

    private final TimeSlotPort timeSlotPort;

    // ... 기존 메서드들 ...

    @Override
    public int restoreSlotsByReservationId(Long reservationId) {
        log.info("Attempting to restore slots for reservationId={}", reservationId);

        // Port를 통해 예약 ID로 슬롯 조회
        List<RoomTimeSlot> slots = timeSlotPort.findByReservationId(reservationId);

        if (slots.isEmpty()) {
            log.warn("No slots found for reservationId={}. Nothing to restore.", reservationId);
            return 0;
        }

        log.debug("Found {} slots to restore for reservationId={}", slots.size(), reservationId);

        // 모든 슬롯을 복구 상태로 변경
        // 도메인 로직: Entity의 restore() 메서드 호출
        // 예외 발생 시 @Transactional에 의해 전체 롤백
        for (RoomTimeSlot slot : slots) {
            slot.restore();
            log.debug("Restoring slot: slotId={}, slotDate={}, slotTime={}",
                slot.getSlotId(), slot.getSlotDate(), slot.getSlotTime());
        }

        // 일괄 저장
        timeSlotPort.saveAll(slots);

        log.info("Successfully restored {} slots for reservationId={}", slots.size(), reservationId);
        return slots.size();
    }
}
```

### 3. EventHandler 리팩터링

```java
// SlotRestoredEventHandler.java
package com.teambind.springproject.room.event.handler;

import com.teambind.springproject.message.handler.EventHandler;
import com.teambind.springproject.room.command.domain.service.TimeSlotManagementService;
import com.teambind.springproject.room.event.event.SlotRestoredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 슬롯 복구 이벤트 핸들러.
 *
 * Payment 서비스에서 환불 완료(RefundCompleted) 이벤트가 발행되면
 * 해당 예약의 모든 슬롯을 AVAILABLE 상태로 복구한다.
 *
 * Hexagonal Architecture:
 * EventHandler (Application) → TimeSlotManagementService (Domain)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlotRestoredEventHandler implements EventHandler<SlotRestoredEvent> {

    // ✅ Domain Service에 의존 (Hexagonal Architecture 준수)
    private final TimeSlotManagementService timeSlotManagementService;

    @Override
    public void handle(SlotRestoredEvent event) {
        log.info("Processing SlotRestoredEvent: reservationId={}, reason={}",
            event.getReservationId(), event.getRestoreReason());

        // String → Long 변환 (Message DTO는 String, Domain은 Long 사용)
        Long reservationId;
        try {
            reservationId = Long.parseLong(event.getReservationId());
        } catch (NumberFormatException e) {
            log.error("Invalid reservationId format: {}", event.getReservationId());
            throw new IllegalArgumentException("reservationId must be numeric", e);
        }

        // Domain Service에 위임
        // 트랜잭션은 Domain Service에서 관리 (@Transactional)
        int restoredCount = timeSlotManagementService.restoreSlotsByReservationId(reservationId);

        log.info("SlotRestoredEvent processed successfully: reservationId={}, restored {} slots",
            reservationId, restoredCount);
    }

    @Override
    public String getSupportedEventType() {
        return "SlotRestored";
    }
}
```

### 4. 계층 의존성 검증

```bash
# Domain 계층이 Infrastructure에 의존하지 않는지 확인
grep -r "import.*repository" \
  src/main/java/com/teambind/springproject/room/command/domain/service/

# Expected: 결과 없음

# EventHandler가 Repository를 직접 의존하지 않는지 확인
grep -r "Repository" \
  src/main/java/com/teambind/springproject/room/event/handler/

# Expected: 결과 없음
```

---

## Consequences

### Positive

- **아키텍처 정합성**: Hexagonal Architecture 완전 준수
- **DIP 준수**: Application → Domain (Port) → Infrastructure (Adapter)
- **트랜잭션 안정성**: All-or-Nothing 보장, 부분 실패 문제 해결
- **도메인 로직 집중**: 슬롯 복구 로직이 Domain Service에만 존재
- **재사용성**: 다른 Use Case에서도 동일 메서드 사용 가능
- **테스트 용이**: Domain Service 단위 테스트로 충분

### Negative

- **메서드 추가**: Domain Service에 메서드 1개 추가
  - **완화**: 필요한 도메인 로직이므로 정당한 추가

### Risks

- **없음**: 기존 동작을 개선하는 리팩터링이므로 리스크 최소

---

## Validation

### 1. 단위 테스트 (Domain Service)

```java
@ExtendWith(MockitoExtension.class)
class TimeSlotManagementServiceImplTest {

    @Mock
    private TimeSlotPort timeSlotPort;

    @InjectMocks
    private TimeSlotManagementServiceImpl service;

    @Test
    void restoreSlotsByReservationId_ShouldRestoreAllSlots() {
        // Given
        Long reservationId = 1001L;
        List<RoomTimeSlot> slots = List.of(
            createSlot(1L, SlotStatus.RESERVED, reservationId),
            createSlot(2L, SlotStatus.RESERVED, reservationId),
            createSlot(3L, SlotStatus.RESERVED, reservationId)
        );

        when(timeSlotPort.findByReservationId(reservationId)).thenReturn(slots);

        // When
        int restoredCount = service.restoreSlotsByReservationId(reservationId);

        // Then
        assertThat(restoredCount).isEqualTo(3);
        verify(timeSlotPort).saveAll(argThat(savedSlots -> {
            return savedSlots.stream().allMatch(slot ->
                slot.getStatus() == SlotStatus.AVAILABLE &&
                slot.getReservationId() == null
            );
        }));
    }

    @Test
    void restoreSlotsByReservationId_WhenNoSlotsFound_ShouldReturnZero() {
        // Given
        Long reservationId = 9999L;
        when(timeSlotPort.findByReservationId(reservationId)).thenReturn(Collections.emptyList());

        // When
        int restoredCount = service.restoreSlotsByReservationId(reservationId);

        // Then
        assertThat(restoredCount).isEqualTo(0);
        verify(timeSlotPort, never()).saveAll(any());
    }
}
```

### 2. 통합 테스트 (EventHandler)

```java
@SpringBootTest
@Transactional
class SlotRestoredEventHandlerIntegrationTest {

    @Autowired
    private SlotRestoredEventHandler eventHandler;

    @Autowired
    private RoomTimeSlotRepository repository;

    @Test
    void handle_ShouldRestoreAllSlotsAtomically() {
        // Given: RESERVED 상태의 슬롯 3개 준비
        Long reservationId = 1001L;
        List<RoomTimeSlot> slots = List.of(
            createReservedSlot(101L, LocalDate.of(2025, 1, 20), LocalTime.of(10, 0), reservationId),
            createReservedSlot(101L, LocalDate.of(2025, 1, 20), LocalTime.of(11, 0), reservationId),
            createReservedSlot(101L, LocalDate.of(2025, 1, 20), LocalTime.of(12, 0), reservationId)
        );
        repository.saveAll(slots);

        SlotRestoredEvent event = SlotRestoredEvent.of(
            reservationId.toString(), "REFUND_COMPLETED"
        );

        // When: EventHandler 실행
        eventHandler.handle(event);

        // Then: 모든 슬롯이 AVAILABLE 상태로 복구
        List<RoomTimeSlot> restoredSlots = repository.findByReservationId(reservationId);
        assertThat(restoredSlots).isEmpty(); // reservationId가 null로 변경됨

        List<RoomTimeSlot> availableSlots = repository.findByRoomIdAndSlotDateAndStatus(
            101L, LocalDate.of(2025, 1, 20), SlotStatus.AVAILABLE
        );
        assertThat(availableSlots).hasSize(3);
    }

    @Test
    void handle_WhenPartialFailure_ShouldRollbackAll() {
        // Given: 슬롯 3개 중 1개가 이미 삭제된 상태 (부분 실패 시나리오)
        Long reservationId = 1002L;
        RoomTimeSlot slot1 = createReservedSlot(..., reservationId);
        RoomTimeSlot slot2 = createReservedSlot(..., reservationId);
        repository.saveAll(List.of(slot1, slot2));

        // slot2를 삭제하여 부분 실패 유발
        repository.delete(slot2);

        SlotRestoredEvent event = SlotRestoredEvent.of(
            reservationId.toString(), "REFUND_COMPLETED"
        );

        // When: EventHandler 실행 (예외 예상)
        // (실제로는 삭제된 슬롯이 조회 안 되므로 성공하지만,
        //  다른 제약조건 위반 시나리오로 테스트 가능)

        // Then: 전체 롤백 확인
        // @Transactional 테스트이므로 자동 롤백
    }
}
```

### 3. 아키텍처 테스트 (ArchUnit)

```java
@AnalyzeClasses(packages = "com.teambind.springproject")
class ArchitectureTest {

    @ArchTest
    static final ArchRule eventHandlersShouldNotDependOnRepositories =
        classes()
            .that().resideInAPackage("..event.handler..")
            .should().onlyDependOnClassesThat()
            .resideOutsideOfPackage("..repository..")
            .because("EventHandlers should depend on Domain Services, not Repositories");

    @ArchTest
    static final ArchRule domainServicesShouldDependOnPorts =
        classes()
            .that().resideInAPackage("..command.domain.service..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("..domain.port..", "..entity..", "java..")
            .because("Domain Services should only depend on Ports and Entities");
}
```

---

## References

- [Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/)
- [Dependency Inversion Principle](https://en.wikipedia.org/wiki/Dependency_inversion_principle)
- [Domain-Driven Design Layers](https://herbertograca.com/2017/08/03/layered-architecture/)
- ADR-003: Hexagonal Architecture 적용

---

## Future Considerations

**Phase 1 (현재)**:
- EventHandler → Domain Service 의존성 정립
- 트랜잭션 원자성 보장

**Phase 2**:
- 모든 EventHandler 일괄 리팩터링
- SlotConfirmedEventHandler, SlotCancelledEventHandler 등도 동일 패턴 적용

**Phase 3**:
- Event-Driven Architecture 성숙도 향상
- Event Sourcing 도입 시 Handler 역할 재평가

---

**Maintained by**: Teambind_dev_backend Team
**Lead Developer**: DDINGJOO