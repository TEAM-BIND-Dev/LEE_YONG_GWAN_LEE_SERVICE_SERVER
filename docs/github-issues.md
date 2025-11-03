# GitHub Issues

## Phase 1: 기반 구축 (2주)

### Issue #1: 프로젝트 초기 설정
**Title:** [Setup] Spring Boot 프로젝트 초기 구성
**Labels:** setup, priority:high
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
Spring Boot 3.x 기반 프로젝트 초기 설정

## 체크리스트
- [ ] Spring Boot 3.x 프로젝트 생성
- [ ] build.gradle 의존성 설정
- [ ] 패키지 구조 설계 (DDD 기반)
- [ ] application.yml 기본 설정
- [ ] 프로파일 분리 (local, dev, prod)

## 완료 조건
- 프로젝트 정상 빌드 및 실행
- 기본 헬스체크 엔드포인트 동작
```

---

### Issue #2: MariaDB 데이터베이스 설정
**Title:** [DB] MariaDB 연결 및 초기 설정
**Labels:** database, priority:high
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
MariaDB 10.11 LTS 연결 설정 및 데이터소스 구성

## 체크리스트
- [ ] MariaDB Docker 컨테이너 구성
- [ ] 데이터소스 설정
- [ ] HikariCP 커넥션 풀 설정
- [ ] JPA/Hibernate 설정
- [ ] 트랜잭션 설정

## 완료 조건
- 데이터베이스 연결 성공
- 테스트 쿼리 실행 확인
```

---

### Issue #3: 공간(Space) 도메인 엔티티 구현
**Title:** [Domain] Space 엔티티 및 Repository 구현
**Labels:** domain, backend
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
공간(Space) 도메인 엔티티 구현

## 체크리스트
- [ ] Space 엔티티 클래스 작성
- [ ] SpaceRepository 인터페이스 구현
- [ ] Space 생성 팩토리 메서드 구현
- [ ] 단위 테스트 작성

## 완료 조건
- Space CRUD 동작 확인
- 테스트 커버리지 80% 이상
```

---

### Issue #4: 방(Room) 도메인 엔티티 구현
**Title:** [Domain] Room 엔티티 및 TimeSlotType 구현
**Labels:** domain, backend
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
방(Room) 도메인 엔티티 및 타임슬롯 타입 구현

## 체크리스트
- [ ] Room 엔티티 클래스 작성
- [ ] TimeSlotType Enum (HOUR, HALF_HOUR, QUARTER_HOUR)
- [ ] Room-Space 연관관계 매핑
- [ ] RoomRepository 구현
- [ ] 단위 테스트 작성

## 완료 조건
- Room CRUD 동작 확인
- Space와의 연관관계 정상 동작
```

---

### Issue #5: 운영시간 스키마 설계
**Title:** [DB] 운영시간 관련 테이블 스키마 생성
**Labels:** database, schema
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
운영시간 관리를 위한 데이터베이스 스키마 생성

## 체크리스트
- [ ] operating_schedules 테이블 생성
- [ ] holiday_policies 테이블 생성
- [ ] holiday_notices 테이블 생성
- [ ] 인덱스 설정
- [ ] Flyway 마이그레이션 스크립트 작성

## 완료 조건
- 모든 테이블 정상 생성
- 인덱스 적용 확인
```

---

### Issue #6: 운영시간 엔티티 구현
**Title:** [Domain] OperatingSchedule 엔티티 구현
**Labels:** domain, backend
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
운영시간 관리 엔티티 구현

## 체크리스트
- [ ] OperatingSchedule 엔티티 클래스
- [ ] TimeRange Value Object 구현
- [ ] DayOfWeek Enum 구현
- [ ] Repository 인터페이스 작성
- [ ] 단위 테스트

## 완료 조건
- 운영시간 CRUD 동작
- 시간 범위 유효성 검증
```

---

### Issue #7: 휴무일 정책 엔티티 구현
**Title:** [Domain] HolidayPolicy 엔티티 구현
**Labels:** domain, backend
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
휴무일 정책 관리 엔티티 구현

## 체크리스트
- [ ] HolidayPolicy 엔티티 클래스
- [ ] HolidayPolicyType Enum (WEEKLY, BIWEEKLY_ODD, BIWEEKLY_EVEN, SPECIFIC_DATE)
- [ ] 휴무일 계산 로직 구현
- [ ] 격주 계산 알고리즘
- [ ] Repository 구현

## 완료 조건
- 휴무일 정책 CRUD
- 격주 계산 정확성 검증
```

---

## Phase 2: 핵심 기능 (2주)

### Issue #8: Pre-computed 타임슬롯 테이블 구현
**Title:** [DB] time_slots 테이블 생성 및 파티셔닝
**Labels:** database, performance, priority:high
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
사전 계산된 타임슬롯 테이블 구현

## 체크리스트
- [ ] time_slots 테이블 생성
- [ ] 월별 파티셔닝 설정
- [ ] 복합 인덱스 생성
- [ ] slot_generation_log 테이블 생성
- [ ] 마이그레이션 스크립트

## 완료 조건
- 파티셔닝 정상 동작
- 인덱스 성능 확인
```

---

### Issue #9: 타임슬롯 생성 배치 구현
**Title:** [Batch] 일일 타임슬롯 생성 스케줄러
**Labels:** batch, backend, priority:high
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
매일 60일 후 타임슬롯을 생성하는 배치 구현

## 체크리스트
- [ ] TimeSlotGenerator 클래스 구현
- [ ] 운영시간 기반 슬롯 생성 로직
- [ ] 휴무일 체크 로직
- [ ] 벌크 INSERT 최적화
- [ ] 오래된 슬롯 정리 로직
- [ ] 스케줄러 설정 (매일 자정)

## 완료 조건
- 60일 후 슬롯 자동 생성
- 1000개 슬롯 1초 이내 생성
```

---

### Issue #10: 가용성 조회 API 구현
**Title:** [API] 가용성 체크 REST API
**Labels:** api, backend, priority:high
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
시간 슬롯 가용성 조회 API 구현

## 체크리스트
- [ ] AvailabilityController 구현
- [ ] AvailabilityService 비즈니스 로직
- [ ] 단일 쿼리 최적화
- [ ] DTO 및 응답 포맷 정의
- [ ] API 문서화 (OpenAPI)
- [ ] 통합 테스트

## 완료 조건
- 응답시간 5ms 이내
- 정확한 가용성 판단
```

---

### Issue #11: Redis 연동 설정
**Title:** [Cache] Redis 연결 및 설정
**Labels:** cache, infrastructure
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
Redis 캐시 레이어 구성

## 체크리스트
- [ ] Redis Docker 컨테이너 설정
- [ ] Spring Data Redis 설정
- [ ] RedisTemplate 구성
- [ ] Lettuce 클라이언트 설정
- [ ] 연결 테스트

## 완료 조건
- Redis 연결 성공
- 기본 CRUD 동작 확인
```

---

### Issue #12: 임시 락 메커니즘 구현
**Title:** [Feature] Redis 기반 임시 락 구현
**Labels:** feature, backend, priority:high
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
예약 진행 중 타임슬롯 임시 락 구현

## 체크리스트
- [ ] LockService 구현
- [ ] Redis 락 생성/해제 로직
- [ ] TTL 15분 설정
- [ ] DB 슬롯 상태 동기화
- [ ] 락 만료 처리 스케줄러
- [ ] Lua Script 원자성 보장

## 완료 조건
- 락 획득/해제 정상 동작
- TTL 자동 만료
- 데드락 방지
```

---

### Issue #13: 가격 정책 테이블 구현
**Title:** [DB] 시간대별 가격 정책 스키마
**Labels:** database, backend
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
시간대별 동적 가격 관리 테이블 구현

## 체크리스트
- [ ] time_pricing_rules 테이블 생성
- [ ] special_pricing 테이블 생성
- [ ] 가격 계산 로직 구현
- [ ] 우선순위 처리 로직

## 완료 조건
- 가격 정책 CRUD
- 정확한 가격 계산
```

---

### Issue #14: 가격 계산 서비스 구현
**Title:** [Service] 동적 가격 계산 서비스
**Labels:** service, backend
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
시간대별 가격 계산 비즈니스 로직

## 체크리스트
- [ ] PricingService 인터페이스 정의
- [ ] 기본 가격 계산 구현
- [ ] 서지 가격 적용
- [ ] 휴일 가격 적용
- [ ] 특별 가격 우선순위 처리

## 완료 조건
- 정확한 가격 산출
- 다양한 정책 조합 지원
```

---

## Phase 3: 이벤트 통합 (2주)

### Issue #15: Kafka 연동 설정
**Title:** [Event] Apache Kafka 연결 설정
**Labels:** event, infrastructure, priority:high
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
Apache Kafka 이벤트 스트리밍 설정

## 체크리스트
- [ ] Kafka Docker 컨테이너 구성
- [ ] Spring Kafka 의존성 추가
- [ ] Producer/Consumer 설정
- [ ] 토픽 생성 (reservation-events)
- [ ] 파티션 전략 설정

## 완료 조건
- Kafka 연결 성공
- 메시지 발행/구독 테스트
```

---

### Issue #16: 이벤트 스토어 구현
**Title:** [Event] Event Store 테이블 및 저장 로직
**Labels:** event, database
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
이벤트 소싱을 위한 이벤트 스토어 구현

## 체크리스트
- [ ] event_store 테이블 생성
- [ ] processed_events 테이블 (멱등성)
- [ ] EventEntity 클래스
- [ ] EventRepository 구현
- [ ] 이벤트 직렬화/역직렬화

## 완료 조건
- 이벤트 저장/조회 성공
- JSON 직렬화 정상 동작
```

---

### Issue #17: 예약 이벤트 핸들러 구현
**Title:** [Event] ReservationEventHandler 구현
**Labels:** event, backend, priority:high
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
예약 관련 이벤트 처리 핸들러

## 체크리스트
- [ ] ReservationInitiated 처리
- [ ] PaymentCompleted 처리
- [ ] ReservationCancelled 처리
- [ ] 멱등성 보장 로직
- [ ] 에러 핸들링

## 완료 조건
- 모든 이벤트 타입 처리
- 중복 처리 방지
```

---

### Issue #18: 상태 기계 구현
**Title:** [Domain] 예약 상태 전이 State Machine
**Labels:** domain, backend
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
예약 상태 전이 검증을 위한 상태 기계

## 체크리스트
- [ ] ReservationStateMachine 클래스
- [ ] 유효한 상태 전이 정의
- [ ] 상태 우선순위 정의
- [ ] 전이 검증 로직
- [ ] 단위 테스트

## 완료 조건
- 모든 상태 전이 검증
- 비정상 전이 방지
```

---

### Issue #19: 이벤트 순서 보장 처리
**Title:** [Event] Kafka 이벤트 순서 역전 처리
**Labels:** event, backend, priority:high
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
이벤트 순서 역전 감지 및 처리

## 체크리스트
- [ ] 타임스탬프 기반 순서 검증
- [ ] EventVersion 관리
- [ ] Out-of-order 감지 로직
- [ ] 순서 역전 시 처리 전략
- [ ] 로깅 및 모니터링

## 완료 조건
- 순서 역전 정확한 감지
- 데이터 일관성 보장
```

---

### Issue #20: 보상 트랜잭션 구현
**Title:** [Event] Saga 패턴 보상 트랜잭션
**Labels:** event, backend
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
실패 시 보상 처리를 위한 Saga 구현

## 체크리스트
- [ ] CompensationService 구현
- [ ] 보상 이벤트 정의
- [ ] 보상 시나리오별 처리
- [ ] 보상 이력 저장
- [ ] 알림 발송

## 완료 조건
- 주요 시나리오 보상 처리
- 보상 이력 추적 가능
```

---

### Issue #21: Outbox 패턴 구현
**Title:** [Event] 트랜잭션 보장을 위한 Outbox Pattern
**Labels:** event, pattern
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
트랜잭션 일관성을 위한 Outbox 패턴

## 체크리스트
- [ ] domain_events 테이블 생성
- [ ] Outbox 발행 로직
- [ ] Polling Publisher 구현
- [ ] 발행 실패 재시도
- [ ] 이벤트 상태 관리

## 완료 조건
- 트랜잭션 보장
- 이벤트 무손실
```

---

## Phase 4: 최적화 (1주)

### Issue #22: 데이터베이스 인덱스 최적화
**Title:** [Optimize] DB 인덱스 튜닝
**Labels:** performance, database
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
쿼리 성능 향상을 위한 인덱스 최적화

## 체크리스트
- [ ] 슬로우 쿼리 분석
- [ ] 실행 계획 검토
- [ ] 복합 인덱스 추가
- [ ] 불필요한 인덱스 제거
- [ ] 성능 측정

## 완료 조건
- 주요 쿼리 5ms 이내
- 인덱스 적중률 95% 이상
```

---

### Issue #23: Redis 캐싱 레이어 구현
**Title:** [Cache] 운영시간 및 Hot 데이터 캐싱
**Labels:** cache, performance
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
자주 조회되는 데이터 캐싱 구현

## 체크리스트
- [ ] 운영시간 캐싱 (24시간)
- [ ] 7일치 슬롯 데이터 캐싱
- [ ] 캐시 무효화 전략
- [ ] 캐시 워밍업
- [ ] 히트율 모니터링

## 완료 조건
- 캐시 히트율 80% 이상
- 응답시간 50% 감소
```

---

### Issue #24: 배치 쿼리 최적화
**Title:** [Optimize] 벌크 연산 성능 개선
**Labels:** performance, database
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
대량 데이터 처리 성능 최적화

## 체크리스트
- [ ] JDBC 배치 사이즈 조정
- [ ] 벌크 INSERT 최적화
- [ ] 청크 단위 처리
- [ ] 병렬 처리 적용
- [ ] 트랜잭션 범위 최적화

## 완료 조건
- 1000건 INSERT 1초 이내
- 메모리 사용량 최적화
```

---

### Issue #25: 모니터링 설정
**Title:** [Monitor] Prometheus + Grafana 구성
**Labels:** monitoring, infrastructure
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
애플리케이션 모니터링 환경 구축

## 체크리스트
- [ ] Prometheus 설정
- [ ] Micrometer 메트릭 수집
- [ ] Grafana 대시보드 구성
- [ ] 주요 메트릭 정의
- [ ] 알람 규칙 설정

## 완료 조건
- 실시간 메트릭 수집
- 대시보드 시각화
```

---

## Phase 5: 운영 준비 (1주)

### Issue #26: 통합 테스트 작성
**Title:** [Test] E2E 통합 테스트 구현
**Labels:** testing, quality
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
전체 플로우 통합 테스트

## 체크리스트
- [ ] 예약 생성 플로우 테스트
- [ ] 락 획득/해제 테스트
- [ ] 이벤트 처리 테스트
- [ ] 보상 시나리오 테스트
- [ ] TestContainers 설정

## 완료 조건
- 주요 시나리오 커버
- 테스트 자동화
```

---

### Issue #27: 부하 테스트
**Title:** [Test] JMeter 부하 테스트
**Labels:** testing, performance
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
목표 성능 달성 확인을 위한 부하 테스트

## 체크리스트
- [ ] JMeter 테스트 시나리오 작성
- [ ] 10,000 TPS 부하 테스트
- [ ] 응답시간 측정
- [ ] 병목 구간 분석
- [ ] 리포트 작성

## 완료 조건
- 10,000 TPS 처리
- P99 응답시간 10ms 이내
```

---

### Issue #28: API 문서화
**Title:** [Doc] OpenAPI 3.0 문서 작성
**Labels:** documentation
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
REST API 문서화

## 체크리스트
- [ ] OpenAPI 스펙 작성
- [ ] Swagger UI 설정
- [ ] API 예제 추가
- [ ] 에러 코드 정의
- [ ] 인증 방식 문서화

## 완료 조건
- 모든 API 문서화
- Swagger UI 접근 가능
```

---

### Issue #29: Docker 이미지 빌드
**Title:** [Deploy] Docker 컨테이너화
**Labels:** deployment, infrastructure
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
애플리케이션 Docker 이미지 생성

## 체크리스트
- [ ] Dockerfile 작성
- [ ] Multi-stage 빌드 설정
- [ ] 이미지 최적화
- [ ] docker-compose.yml 작성
- [ ] 환경변수 관리

## 완료 조건
- 이미지 빌드 성공
- 컨테이너 정상 실행
```

---

### Issue #30: 배포 파이프라인 구성
**Title:** [CI/CD] GitHub Actions 배포 자동화
**Labels:** deployment, automation
**Assignee:** @ddingjoo
**Description:**
```
## 작업 내용
CI/CD 파이프라인 구축

## 체크리스트
- [ ] GitHub Actions workflow 작성
- [ ] 자동 테스트 실행
- [ ] Docker 이미지 빌드/푸시
- [ ] 배포 스크립트 작성
- [ ] 롤백 전략 수립

## 완료 조건
- Push 시 자동 빌드/테스트
- 배포 자동화
```