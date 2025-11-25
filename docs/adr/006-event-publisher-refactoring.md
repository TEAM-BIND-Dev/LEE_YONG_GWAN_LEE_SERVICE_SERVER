# ADR-006: Event Publisher Refactoring Strategy

**Status**: Proposed
**Date**: 2025-01-19
**Decision Makers**: Teambind_dev_backend Team
**Technical Story**: EventPublisher의 OCP 준수 및 확장성 개선

---

## Context

현재 `EventPublisher`는 `instanceof` 체인을 사용하여 Event 타입을 판별하고 Message DTO로 변환합니다.

### 현재 구현

```java
// EventPublisher.java:30-46
private Object convertToMessage(Event event) {
    if (event instanceof SlotReservedEvent) {
        return SlotReservedEventMessage.from((SlotReservedEvent) event);
    } else if (event instanceof SlotConfirmedEvent) {
        return SlotConfirmedEventMessage.from((SlotConfirmedEvent) event);
    } else if (event instanceof SlotCancelledEvent) {
        return SlotCancelledEventMessage.from((SlotCancelledEvent) event);
    } else if (event instanceof SlotRestoredEvent) {
        return SlotRestoredEventMessage.from((SlotRestoredEvent) event);
    } else if (event instanceof SlotGenerationRequestedEvent) {
        return SlotGenerationRequestedEventMessage.from((SlotGenerationRequestedEvent) event);
    } else if (event instanceof ClosedDateUpdateRequestedEvent) {
        return ClosedDateUpdateRequestedEventMessage.from((ClosedDateUpdateRequestedEvent) event);
    }

    throw new IllegalArgumentException("Unknown event type: " + event.getClass().getName());
}
```

### 문제점

**1. OCP (Open-Closed Principle) 위반**:
- 새로운 Event 타입 추가 시마다 `convertToMessage` 메서드 수정 필요
- "확장에는 열려있고 수정에는 닫혀있어야 한다" 원칙 위반

**2. 휴먼 에러 가능성**:
```java
// 시나리오: 새 이벤트 추가
// 1. SlotExpiredEvent.java 생성 ✅
// 2. SlotExpiredEventMessage.java 생성 ✅
// 3. EventPublisher.convertToMessage() 수정 누락 ❌
// 4. 런타임 시 IllegalArgumentException 발생
// 5. 프로덕션 장애 발생
```

**3. 코드 응집도 저하**:
- Event ↔ MessageDTO 변환 로직이 EventPublisher에 집중
- 각 Event의 변환 방법을 EventPublisher가 모두 알아야 함
- 변환 로직 변경 시 EventPublisher 수정 필요

**4. 테스트 어려움**:
- 모든 Event 타입에 대한 테스트가 EventPublisher에 집중
- 새 Event 추가 시 EventPublisher 테스트 수정 필요

### 영향도

- **우선순위**: Medium (현재 동작에는 문제 없으나 유지보수성 저하)
- **리스크**: 신규 Event 추가 시 누락 가능성
- **빈도**: Event 타입은 자주 추가되지 않음 (월 1-2회)

---

## Decision Drivers

- SOLID 원칙 준수 (특히 OCP, SRP)
- Event 타입 확장 시 안전성 확보
- 코드 응집도 향상
- 팀의 디자인 패턴 경험 활용
- 과도한 복잡도 방지 (실용주의)

---

## Considered Options

### Option 1: Strategy Pattern + Spring Bean Registry

**개념**:
각 Event 타입별로 Converter를 전략 객체로 분리하고, Spring이 자동으로 등록하도록 구성합니다.

**구현 예시**:

*1. Converter 인터페이스 정의*:
```java
// EventMessageConverter.java
public interface EventMessageConverter<T extends Event> {

    /**
     * Event를 Message DTO로 변환한다.
     */
    Object convert(T event);

    /**
     * 이 Converter가 처리할 수 있는 Event 타입을 반환한다.
     */
    Class<T> getSupportedEventType();
}
```

*2. 각 Event별 Converter 구현*:
```java
// SlotReservedEventConverter.java
@Component
public class SlotReservedEventConverter implements EventMessageConverter<SlotReservedEvent> {

    @Override
    public Object convert(SlotReservedEvent event) {
        return SlotReservedEventMessage.from(event);
    }

    @Override
    public Class<SlotReservedEvent> getSupportedEventType() {
        return SlotReservedEvent.class;
    }
}

// SlotConfirmedEventConverter.java
@Component
public class SlotConfirmedEventConverter implements EventMessageConverter<SlotConfirmedEvent> {

    @Override
    public Object convert(SlotConfirmedEvent event) {
        return SlotConfirmedEventMessage.from(event);
    }

    @Override
    public Class<SlotConfirmedEvent> getSupportedEventType() {
        return SlotConfirmedEvent.class;
    }
}

// ... 나머지 Event별 Converter 동일하게 구현
```

*3. EventPublisher 리팩터링*:
```java
@Service
@RequiredArgsConstructor
public class EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JsonUtil jsonUtil;
    private final Map<Class<? extends Event>, EventMessageConverter<?>> converters;

    // Spring이 모든 EventMessageConverter 빈을 주입하여 Map 생성
    public EventPublisher(
        KafkaTemplate<String, Object> kafkaTemplate,
        JsonUtil jsonUtil,
        List<EventMessageConverter<?>> converterList
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonUtil = jsonUtil;

        // Converter를 Event 타입별로 Map에 등록
        this.converters = converterList.stream()
            .collect(Collectors.toMap(
                EventMessageConverter::getSupportedEventType,
                Function.identity()
            ));
    }

    public void publish(Event event) {
        // 1. Converter 조회
        EventMessageConverter converter = converters.get(event.getClass());

        if (converter == null) {
            throw new IllegalArgumentException(
                "No converter found for event type: " + event.getClass().getName()
            );
        }

        // 2. 변환 및 발행
        Object messageDto = converter.convert(event);
        String json = jsonUtil.toJson(messageDto);
        kafkaTemplate.send(event.getTopic(), json);
    }
}
```

**장점**:
- **OCP 완전 준수**: 새 Event 추가 시 Converter만 구현하면 됨 (EventPublisher 수정 불필요)
- **SRP 준수**: 각 Converter가 하나의 변환 책임만 가짐
- **타입 안전성**: 제네릭으로 컴파일 타임 타입 체크
- **테스트 용이**: 각 Converter를 독립적으로 테스트 가능
- **자동 등록**: Spring이 @Component 스캔하여 자동 등록

**단점**:
- **클래스 수 증가**: Event 1개당 Converter 클래스 1개 추가 (6개 Event → 6개 Converter)
- **초기 설정 복잡도**: Map 생성 로직 추가
- **간접 참조**: EventPublisher → Map → Converter (호출 경로 증가)

**Trade-offs**:
- 클래스 수 vs 확장성: 클래스 증가를 허용하여 OCP 준수

---

### Option 2: Template Method Pattern (Event에 메서드 추가)

**개념**:
Event 자체에 MessageDTO 변환 메서드를 추가합니다.

**구현 예시**:

*1. Event 추상 클래스 수정*:
```java
// Event.java
public abstract class Event {

    private String topic;
    private String eventType;

    public abstract String getEventTypeName();

    /**
     * Event를 Message DTO로 변환한다.
     * 하위 클래스에서 구현.
     */
    public abstract Object toMessageDto();
}
```

*2. 각 Event 구현*:
```java
// SlotReservedEvent.java
public class SlotReservedEvent extends Event {

    private String roomId;
    private LocalDate slotDate;
    private List<LocalTime> startTimes;
    private String reservationId;
    private LocalDateTime occurredAt;

    @Override
    public Object toMessageDto() {
        return SlotReservedEventMessage.from(this);
    }

    @Override
    public String getEventTypeName() {
        return "SlotReserved";
    }
}
```

*3. EventPublisher 간소화*:
```java
@Service
@RequiredArgsConstructor
public class EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JsonUtil jsonUtil;

    public void publish(Event event) {
        // Event 자체가 변환 담당
        Object messageDto = event.toMessageDto();

        String json = jsonUtil.toJson(messageDto);
        kafkaTemplate.send(event.getTopic(), json);
    }
}
```

**장점**:
- **간결함**: Converter 클래스 불필요
- **응집도**: Event와 변환 로직이 함께 위치
- **직관성**: Event.toMessageDto() 호출로 명확함

**단점**:
- **계층 결합도 증가**: Event(Domain)가 MessageDTO(Infrastructure) 알게 됨
  - Hexagonal Architecture 관점에서 Domain이 외부 계층 의존
- **Event 책임 증가**: Event의 책임이 "도메인 이벤트 표현" + "Kafka 메시지 변환"으로 분산
- **SRP 위반**: Event가 2개의 변경 사유를 가짐
  - 도메인 로직 변경
  - Kafka 메시지 포맷 변경

**Trade-offs**:
- 단순성 vs 계층 분리: 코드는 간단하지만 아키텍처 원칙 위배

---

### Option 3: 현재 방식 유지 + 테스트 강화

**개념**:
`instanceof` 체인을 유지하되, 테스트로 누락을 방지합니다.

**구현 예시**:

*1. EventPublisher 유지*:
```java
// 현재 코드 그대로 유지
private Object convertToMessage(Event event) {
    if (event instanceof SlotReservedEvent) {
        return SlotReservedEventMessage.from((SlotReservedEvent) event);
    }
    // ... 기존 코드
}
```

*2. 포괄적 테스트 작성*:
```java
@SpringBootTest
class EventPublisherTest {

    @Autowired
    private EventPublisher eventPublisher;

    @Test
    void shouldConvertAllEventTypes() {
        // Given: 모든 Event 타입 나열
        List<Event> allEvents = List.of(
            SlotReservedEvent.of("101", LocalDate.now(), List.of(LocalTime.of(10, 0)), "1001"),
            SlotConfirmedEvent.of("101", LocalDate.now(), LocalTime.of(10, 0)),
            SlotCancelledEvent.of("101", LocalDate.now(), LocalTime.of(10, 0)),
            SlotRestoredEvent.of("1001", "REFUND_COMPLETED"),
            SlotGenerationRequestedEvent.of("101", 30),
            ClosedDateUpdateRequestedEvent.of("101", List.of(...))
        );

        // When & Then: 모든 Event 변환 성공
        allEvents.forEach(event -> {
            assertDoesNotThrow(() -> eventPublisher.publish(event));
        });
    }

    @Test
    void shouldFailForUnknownEventType() {
        // Given: 미지원 Event
        Event unknownEvent = new UnknownEvent();

        // When & Then: 예외 발생
        assertThrows(IllegalArgumentException.class, () -> {
            eventPublisher.publish(unknownEvent);
        });
    }
}
```

*3. 컴파일 타임 체크 추가 (ArchUnit)*:
```java
@Test
void allEventsShouldHaveConverter() {
    // Given: Event를 상속한 모든 클래스 조회
    Set<Class<?>> eventClasses = new Reflections("com.teambind.springproject.room.event.event")
        .getSubTypesOf(Event.class);

    // When: EventPublisher 소스코드 파싱
    String publisherSource = Files.readString(Path.of("EventPublisher.java"));

    // Then: 모든 Event가 instanceof에 포함되어야 함
    eventClasses.forEach(eventClass -> {
        assertThat(publisherSource).contains(eventClass.getSimpleName());
    });
}
```

**장점**:
- **변경 최소화**: 기존 코드 유지
- **간단함**: 추가 설계 불필요
- **즉시 적용 가능**: 리팩터링 시간 없음

**단점**:
- **여전히 OCP 위반**: 새 Event 추가 시 EventPublisher 수정 필요
- **런타임 에러 가능성**: 테스트 누락 시 프로덕션 장애
- **휴먼 에러 의존**: 개발자가 테스트 코드에 새 Event 추가해야 함

**Trade-offs**:
- 안정성 vs 원칙: 테스트로 리스크를 완화하지만 설계 원칙은 위배

---

### Option 4: Reflection 기반 자동 변환

**개념**:
`SlotReservedEvent` → `SlotReservedEventMessage`의 네이밍 규칙을 이용하여 리플렉션으로 자동 변환합니다.

**구현 예시**:
```java
private Object convertToMessage(Event event) {
    String eventClassName = event.getClass().getSimpleName();
    String messageClassName = eventClassName + "Message";

    try {
        // 리플렉션으로 MessageDTO 클래스 조회
        Class<?> messageClass = Class.forName(
            "com.teambind.springproject.message.dto." + messageClassName
        );

        // static from(Event) 메서드 호출
        Method fromMethod = messageClass.getMethod("from", event.getClass());
        return fromMethod.invoke(null, event);

    } catch (Exception e) {
        throw new IllegalArgumentException("Failed to convert event: " + eventClassName, e);
    }
}
```

**장점**:
- **OCP 준수**: 새 Event 추가 시 코드 수정 불필요 (네이밍 규칙만 따르면 됨)
- **클래스 수 증가 없음**: Converter 클래스 불필요

**단점**:
- **타입 안전성 상실**: 컴파일 타임 체크 불가능
- **런타임 에러 증가**: 클래스 미존재, 메서드 미존재 시 런타임 예외
- **디버깅 어려움**: 리플렉션 스택트레이스 복잡
- **성능 저하**: 리플렉션 오버헤드
- **네이밍 의존성**: 규칙 위반 시 장애 (휴먼 에러)

**Trade-offs**:
- 자동화 vs 타입 안전성: 편리함을 얻지만 안정성 희생

---

## Decision Outcome

**선택: Option 3 - 현재 방식 유지 + 테스트 강화**

### 선택 이유

1. **실용주의**
   - Event 타입 추가 빈도가 낮음 (월 1-2회)
   - Option 1(Strategy)의 복잡도 증가 대비 이점이 크지 않음

2. **비용 대비 효과**
   - Option 1: 6개 Converter 클래스 추가 (약 300줄 코드)
   - Option 3: 테스트 코드 50줄 추가
   - 투입 시간 대비 안정성 확보 효율성 높음

3. **팀 컨텍스트**
   - 신규 Event 추가 시 PR 리뷰 프로세스 존재
   - 리뷰어가 EventPublisher 수정 누락을 쉽게 발견 가능
   - 테스트 커버리지 80% 이상 유지 정책

4. **변경 비용 최소화**
   - 현재 코드 변경 불필요
   - 기존 동작 영향 없음
   - 테스트만 추가하면 즉시 적용 가능

5. **향후 전환 가능성**
   - Event 타입이 크게 증가하면 (20개 이상) Option 1로 전환 가능
   - 현재 시점에는 과도한 엔지니어링

### 조건부 전환 기준

**Option 1(Strategy)로 전환해야 하는 경우**:
- Event 타입이 15개 이상 증가
- Event 추가 빈도가 주 1회 이상으로 증가
- EventPublisher 수정 누락으로 인한 장애 발생

---

## Implementation Details

### 1. 포괄적 테스트 작성

```java
// EventPublisherTest.java
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventPublisherTest {

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private EmbeddedKafkaBroker embeddedKafka;

    @BeforeAll
    void setup() {
        embeddedKafka = embeddedKafkaBroker();
    }

    /**
     * 모든 Event 타입에 대한 변환 테스트.
     * 새 Event 추가 시 여기에 추가해야 함.
     */
    @Test
    @DisplayName("모든 Event 타입을 MessageDTO로 변환할 수 있어야 한다")
    void shouldConvertAllEventTypes() {
        // Given: 시스템에 존재하는 모든 Event 인스턴스
        List<Event> allEvents = createAllEventInstances();

        // When & Then: 모든 Event가 변환 및 발행 성공
        allEvents.forEach(event -> {
            assertDoesNotThrow(() -> {
                eventPublisher.publish(event);
            }, "Event 타입 변환 실패: " + event.getClass().getSimpleName());
        });
    }

    /**
     * 미지원 Event 타입에 대한 예외 처리 테스트.
     */
    @Test
    @DisplayName("미지원 Event 타입은 IllegalArgumentException을 던져야 한다")
    void shouldThrowExceptionForUnsupportedEventType() {
        // Given: 미지원 Event
        Event unsupportedEvent = new UnsupportedEvent("test-topic", "Unsupported");

        // When & Then
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> eventPublisher.publish(unsupportedEvent)
        );

        assertThat(exception.getMessage()).contains("Unknown event type");
    }

    /**
     * 모든 Event 인스턴스를 생성하는 헬퍼 메서드.
     * 새 Event 추가 시 여기에 반드시 추가해야 함.
     */
    private List<Event> createAllEventInstances() {
        return List.of(
            // 슬롯 예약 관련
            SlotReservedEvent.of("101", LocalDate.of(2025, 1, 20), List.of(LocalTime.of(10, 0)), "1001"),
            SlotConfirmedEvent.of("101", LocalDate.of(2025, 1, 20), LocalTime.of(10, 0)),
            SlotCancelledEvent.of("101", LocalDate.of(2025, 1, 20), LocalTime.of(10, 0)),
            SlotRestoredEvent.of("1001", "REFUND_COMPLETED"),

            // 슬롯 생성 관련
            SlotGenerationRequestedEvent.of("101", 30),

            // 휴무일 관련
            ClosedDateUpdateRequestedEvent.of("101", List.of(
                ClosedDateRange.ofFullDay(LocalDate.of(2025, 1, 1))
            ))
        );
    }

    // 테스트용 미지원 Event
    private static class UnsupportedEvent extends Event {
        public UnsupportedEvent(String topic, String eventType) {
            super(topic, eventType);
        }

        @Override
        public String getEventTypeName() {
            return "Unsupported";
        }
    }
}
```

### 2. PR 체크리스트 추가

```markdown
# Pull Request Checklist

## Event 추가 시 필수 확인 사항

새로운 Event를 추가한 경우, 다음을 반드시 확인하세요:

- [ ] Event 클래스 작성 (`*Event.java`)
- [ ] MessageDTO 클래스 작성 (`*EventMessage.java`)
- [ ] `EventPublisher.convertToMessage()`에 instanceof 추가
- [ ] `EventPublisherTest.createAllEventInstances()`에 테스트 케이스 추가
- [ ] 통합 테스트 실행 확인

**리뷰어:** EventPublisher 수정 누락 여부를 반드시 확인해주세요.
```

### 3. README 문서화

```markdown
# Event 추가 가이드

새로운 도메인 이벤트를 추가할 때 다음 절차를 따르세요:

## 1. Event 클래스 작성

`com.teambind.springproject.room.event.event` 패키지에 Event 클래스를 작성합니다.

## 2. MessageDTO 클래스 작성

`com.teambind.springproject.message.dto` 패키지에 MessageDTO 클래스를 작성합니다.

## 3. EventPublisher 수정

`EventPublisher.convertToMessage()` 메서드에 instanceof 분기를 추가합니다.

## 4. 테스트 추가

`EventPublisherTest.createAllEventInstances()` 메서드에 테스트 케이스를 추가합니다.

## 5. 테스트 실행

`./gradlew test --tests EventPublisherTest`

모든 테스트가 통과하는지 확인합니다.
```

---

## Consequences

### Positive

- **변경 최소화**: 기존 코드 유지, 안정성 확보
- **즉시 적용**: 테스트만 추가하면 되므로 빠른 적용
- **비용 효율**: 클래스 증가 없이 안정성 확보
- **팀 부담 최소화**: 새로운 패턴 학습 불필요

### Negative

- **OCP 위반 지속**: 새 Event 추가 시 EventPublisher 수정 필요
- **휴먼 에러 의존**: 테스트 추가를 누락하면 리스크
- **기술 부채**: 향후 Event 증가 시 리팩터링 필요

### Mitigation

- **PR 체크리스트**: 리뷰 프로세스로 누락 방지
- **포괄적 테스트**: 모든 Event 타입 커버
- **문서화**: README에 명확한 가이드 제공
- **모니터링**: Event 타입 수 추적, 15개 초과 시 리팩터링 트리거

---

## Validation

### 1. 기능 테스트

```java
@Test
void newEventType_ShouldBeConvertedSuccessfully() {
    // Given: 신규 추가된 Event
    SlotExpiredEvent newEvent = SlotExpiredEvent.of("1001", LocalDateTime.now());

    // When
    eventPublisher.publish(newEvent);

    // Then: Kafka 발행 성공
    ConsumerRecord<String, String> record = kafkaConsumer.poll(Duration.ofSeconds(5));
    assertThat(record).isNotNull();
    assertThat(record.value()).contains("SlotExpired");
}
```

### 2. 누락 감지 테스트

```java
@Test
void allEventClassesShouldHaveTestCase() {
    // Given: Event를 상속한 모든 클래스 조회
    Set<Class<?>> eventClasses = new Reflections("com.teambind.springproject.room.event.event")
        .getSubTypesOf(Event.class)
        .stream()
        .filter(clazz -> !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers()))
        .collect(Collectors.toSet());

    // When: 테스트에 포함된 Event 타입 조회
    List<Event> testEvents = createAllEventInstances();
    Set<Class<?>> testedEventClasses = testEvents.stream()
        .map(Event::getClass)
        .collect(Collectors.toSet());

    // Then: 모든 Event가 테스트에 포함되어야 함
    Set<Class<?>> missingEvents = new HashSet<>(eventClasses);
    missingEvents.removeAll(testedEventClasses);

    assertThat(missingEvents)
        .as("다음 Event 타입이 테스트에 누락되었습니다: %s", missingEvents)
        .isEmpty();
}
```

---

## References

- [Open-Closed Principle](https://en.wikipedia.org/wiki/Open%E2%80%93closed_principle)
- [Strategy Pattern](https://refactoring.guru/design-patterns/strategy)
- [Pragmatic Programmer: Good Enough Software](https://pragprog.com/titles/tpp20/the-pragmatic-programmer-20th-anniversary-edition/)
- [YAGNI: You Aren't Gonna Need It](https://martinfowler.com/bliki/Yagni.html)

---

## Future Considerations

**전환 트리거** (다음 조건 중 하나라도 충족 시):
- Event 타입이 15개 이상 증가
- Event 추가 빈도가 주 1회 이상
- EventPublisher 수정 누락으로 인한 장애 발생

**전환 계획**:
1. **Phase 1**: Strategy Pattern 도입 (Option 1 적용)
2. **Phase 2**: Spring Cloud Function 검토 (FaaS 스타일 변환)
3. **Phase 3**: Event-Driven Architecture 성숙도에 따라 Event Sourcing 도입 시 재평가

**모니터링 지표**:
- Event 타입 수 추적 (월별)
- EventPublisher 수정 빈도 (월별)
- Event 변환 실패 횟수 (일별)

---

**Maintained by**: Teambind_dev_backend Team
**Lead Developer**: DDINGJOO