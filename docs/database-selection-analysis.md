# 데이터베이스 선택 분석서: 운영시간 관리 및 예약 서비스

## 1. 시스템 요구사항 분석

### 1.1 핵심 요구사항
- **복잡한 운영시간 정책**: 요일별, 격주별 휴무일 관리
- **유연한 타임슬롯 관리**: 동적 시간 단위 (15분, 30분, 60분)
- **계층적 데이터 구조**: 공간 > 방 구조
- **이벤트 기반 아키텍처**: MSA 환경에서 상태 변화 이벤트 전파
- **시계열 데이터 처리**: 날짜별 운영 캘린더 관리
- **JSON 데이터 지원**: 복잡한 정책 정보 저장

### 1.2 예상 데이터 규모
- 공간: 1,000개
- 방: 10,000개 (공간당 평균 10개)
- 운영 스케줄: 70,000개 (엔티티당 평균 7개 요일)
- 예약 가능 슬롯: 일별 100,000개
- 운영 캘린더: 연간 3,650,000 레코드 (10,000 엔티티 × 365일)

## 2. 데이터베이스 옵션 분석

### 2.1 MariaDB 10.11 LTS

#### 장점
- **완전 무료**: 상용 서비스에 라이선스 비용 없음
- **MySQL 호환성**: MySQL과 99% 호환, 쉬운 마이그레이션
- **우수한 JSON 지원**: JSON 데이터 타입과 함수 완벽 지원
- **더 나은 성능**: MySQL 대비 일부 쿼리에서 우수한 성능
- **추가 스토리지 엔진**: Aria, ColumnStore 등 다양한 엔진
- **Spring Boot 완벽 지원**: MySQL 드라이버로 그대로 사용 가능
- **활발한 오픈소스**: 빠른 버그 수정과 기능 개선
- **Galera Cluster**: 내장된 동기식 멀티마스터 클러스터

#### 단점
- **엔터프라이즈 지원**: Oracle MySQL 대비 제한적
- **일부 MySQL 8.0 기능 누락**: Window functions 일부 제한
- **도구 생태계**: MySQL Workbench 등 일부 도구 호환성

#### 예시 테이블 구조
```sql
-- MariaDB 10.11 최적화 테이블
CREATE TABLE operating_calendar (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type VARCHAR(20) NOT NULL,
    entity_id BIGINT NOT NULL,
    calendar_date DATE NOT NULL,

    -- JSON 컬럼 활용
    operating_hours JSON COMMENT '[{"start": "09:00", "end": "18:00"}]',
    available_slots JSON COMMENT '["09:00", "10:00", "11:00"]',
    holiday_info JSON COMMENT '{"isHoliday": true, "reason": "격주 월요일"}',

    -- 생성 가상 컬럼 (JSON 인덱싱)
    is_holiday BOOLEAN GENERATED ALWAYS AS (holiday_info->>'$.isHoliday') STORED,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_entity_date (entity_type, entity_id, calendar_date),
    INDEX idx_date_holiday (calendar_date, is_holiday),
    INDEX idx_entity_range (entity_type, entity_id, calendar_date)
) ENGINE=InnoDB
PARTITION BY RANGE (YEAR(calendar_date)) (
    PARTITION p2024 VALUES LESS THAN (2025),
    PARTITION p2025 VALUES LESS THAN (2026),
    PARTITION p2026 VALUES LESS THAN (2027)
);
```

### 2.2 PostgreSQL 14+

#### 장점
- **JSONB 성능**: 바이너리 JSON, 우수한 인덱싱
- **확장성**: 다양한 확장 (TimescaleDB, PostGIS)
- **고급 기능**: CTE, Window Functions, UPSERT
- **파티셔닝**: 선언적 파티셔닝 지원
- **동시성**: MVCC 기반 우수한 동시성

#### 단점
- **운영 복잡도**: MySQL 대비 높은 학습 곡선
- **Spring Boot 호환성**: 일부 Hibernate 기능 제한
- **리소스 사용**: 메모리 사용량 높음
- **한국 레퍼런스**: MySQL 대비 적음

#### 예시 테이블 구조
```sql
-- PostgreSQL JSONB 활용
CREATE TABLE operating_calendar (
    id BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(20) NOT NULL,
    entity_id BIGINT NOT NULL,
    calendar_date DATE NOT NULL,

    -- JSONB 컬럼
    operating_data JSONB NOT NULL DEFAULT '{}',

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_entity_date UNIQUE (entity_type, entity_id, calendar_date)
) PARTITION BY RANGE (calendar_date);

-- GIN 인덱스 (JSONB 검색 최적화)
CREATE INDEX idx_operating_data ON operating_calendar USING GIN (operating_data);

-- 함수 기반 인덱스
CREATE INDEX idx_holiday ON operating_calendar ((operating_data->>'isHoliday'));

-- 파티션 생성
CREATE TABLE operating_calendar_2024 PARTITION OF operating_calendar
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
```

### 2.3 MongoDB

#### 장점
- **스키마 유연성**: 동적 스키마, 중첩 문서
- **수평 확장**: 샤딩 내장 지원
- **집계 파이프라인**: 복잡한 쿼리 처리
- **지리 공간 쿼리**: 위치 기반 서비스

#### 단점
- **트랜잭션 제한**: 분산 트랜잭션 성능 이슈
- **JPA 미지원**: Spring Data MongoDB 사용
- **일관성**: 기본 Eventual Consistency
- **조인 성능**: 관계형 데이터 처리 비효율

#### 예시 컬렉션 구조
```javascript
// MongoDB 문서 구조
{
  "_id": ObjectId("..."),
  "entityType": "ROOM",
  "entityId": 12345,
  "date": ISODate("2024-03-15"),

  "operatingHours": [
    { "start": "09:00", "end": "12:00" },
    { "start": "14:00", "end": "18:00" }
  ],

  "timeSlots": {
    "duration": "HALF_HOUR",
    "available": ["09:00", "09:30", "10:00", "10:30"]
  },

  "holiday": {
    "isHoliday": false,
    "policies": [
      { "type": "BIWEEKLY_ODD", "dayOfWeek": 1 }
    ]
  },

  "metadata": {
    "weekNumber": 11,
    "isOddWeek": true,
    "createdAt": ISODate("2024-01-01T00:00:00Z")
  }
}

// 인덱스
db.operating_calendar.createIndex({
  "entityType": 1,
  "entityId": 1,
  "date": 1
}, { unique: true });

db.operating_calendar.createIndex({
  "date": 1,
  "holiday.isHoliday": 1
});
```

### 2.4 Redis + MariaDB (하이브리드)

#### 장점
- **캐싱 성능**: 빈번한 조회 데이터 메모리 캐싱
- **실시간 슬롯 관리**: Redis Set/Sorted Set 활용
- **분산 락**: Redisson 통한 동시성 제어
- **이벤트 스트림**: Redis Streams로 이벤트 처리

#### 단점
- **복잡도 증가**: 두 시스템 운영
- **데이터 동기화**: 일관성 관리 필요
- **비용**: 메모리 비용 추가

#### 예시 구조
```sql
-- MariaDB: 영속성 데이터
CREATE TABLE operating_policies (
    id BIGINT PRIMARY KEY,
    entity_type VARCHAR(20),
    entity_id BIGINT,
    policy_data JSON,
    version INT DEFAULT 0,
    updated_at TIMESTAMP
);
```

```redis
# Redis: 캐싱 및 실시간 데이터
# 운영시간 캐시 (Hash)
HSET room:12345:schedule monday "09:00-18:00"

# 가용 슬롯 (Sorted Set)
ZADD room:12345:slots:20240315 0900 "09:00" 0930 "09:30"

# 휴무일 캐시 (Set)
SADD holidays:2024 "2024-01-01" "2024-02-09"

# 분산 락
SET lock:room:12345 "unique_id" NX EX 10
```

## 3. 성능 비교 분석

### 3.1 쿼리 성능 예상

| 작업 | MariaDB | PostgreSQL | MongoDB | Redis+MariaDB |
|------|---------|------------|---------|---------------|
| 단일 날짜 조회 | 4ms | 4ms | 3ms | 1ms (캐시) |
| 월별 캘린더 조회 | 45ms | 45ms | 40ms | 200ms (join) |
| 복잡한 필터링 | 90ms | 80ms | 150ms | 45ms |
| JSON 검색 | 25ms | 20ms | 15ms | N/A |
| 대량 INSERT | 480ms | 450ms | 300ms | 580ms |

### 3.2 확장성 비교

| 항목 | MariaDB | PostgreSQL | MongoDB | Redis+MariaDB |
|------|---------|------------|---------|---------------|
| 수직 확장 | 우수 | 우수 | 보통 | 우수 |
| 수평 확장 | 보통 | 제한적 | 우수 | 우수 |
| 샤딩 | Spider Engine | 수동 | 자동 | 부분적 |
| 읽기 분산 | MaxScale/ProxySQL | Streaming Replication | Replica Set | 캐시 활용 |
| 클러스터링 | Galera Cluster | Patroni | Replica Set | 혼합 |

## 4. 최종 추천

### 추천: MariaDB 10.11 LTS + Redis (선택적)

#### 선택 이유

1. **완전한 오픈소스 & 무료**
   - 상용 서비스에 라이선스 비용 없음
   - Oracle MySQL의 상업적 제약 없음
   - 장기 지원(LTS) 버전으로 안정성 보장

2. **MySQL과 동일한 개발 경험**
   - Spring Boot + JPA 완벽 호환
   - MySQL 드라이버 그대로 사용 가능
   - 기존 MySQL 코드/쿼리 99% 호환

3. **우수한 JSON 지원**
   - MariaDB 10.3+ 부터 완전한 JSON 타입 지원
   - JSON_TABLE, JSON_ARRAYAGG 등 고급 함수
   - Generated Column으로 JSON 인덱싱

4. **더 나은 성능과 기능**
   - Thread Pool로 대량 연결 처리 우수
   - Galera Cluster 내장 (동기식 복제)
   - Spider Storage Engine (샤딩)
   - MaxScale (프록시/로드밸런서)

5. **점진적 확장 전략**
   - 초기: MariaDB 단독 운영
   - 성장기: Redis 캐싱 추가
   - 확장기: Galera Cluster, MaxScale 도입

#### 구현 전략

```yaml
# Phase 1: MariaDB Only
- 기본 테이블 구조 구현
- JSON 컬럼 활용
- 파티셔닝 적용
- InnoDB 엔진 사용

# Phase 2: Redis 캐싱 (필요시)
- 운영시간 캐싱
- 자주 조회되는 슬롯 정보
- Redisson 분산 락

# Phase 3: 고가용성
- MariaDB Galera Cluster (3 nodes)
- MaxScale 로드밸런싱
- 읽기/쓰기 분리
```

### 대안: PostgreSQL (특정 상황)

다음 경우 PostgreSQL 고려:
- 복잡한 분석 쿼리가 많은 경우
- TimescaleDB 같은 확장이 필요한 경우
- JSONB의 고급 기능이 필수인 경우

### 비추천: MongoDB

- JPA 생태계와 불일치
- 트랜잭션 요구사항과 맞지 않음
- MSA 환경에서 이벤트 일관성 보장 어려움

## 5. MariaDB vs MySQL 상세 비교

### 라이선스 차이
| 항목 | MariaDB | MySQL |
|------|---------|-------|
| 라이선스 | GPL v2 (완전 무료) | GPL + 상용 라이선스 |
| 상용 사용 | 제약 없음 | Oracle 라이선스 구매 필요할 수 있음 |
| 플러그인 | 모두 오픈소스 | 일부 상용 전용 |

### 기능 비교
| 기능 | MariaDB 10.11 | MySQL 8.0 |
|------|---------------|-----------|
| JSON 지원 | 완벽 지원 | 완벽 지원 |
| 파티셔닝 | 지원 | 지원 |
| Thread Pool | 내장 (무료) | Enterprise만 |
| Galera Cluster | 내장 | 별도 설치 |
| Spider Engine | 지원 (샤딩) | 미지원 |
| ColumnStore | 지원 (분석용) | 미지원 |
| 가상 컬럼 | 지원 | 지원 |
| CTE | 지원 | 지원 |

### Spring Boot 설정 예시
```yaml
# application.yml - MariaDB 설정
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb  # MySQL 드라이버 사용
    driver-class-name: org.mariadb.jdbc.Driver  # 또는 MariaDB 전용 드라이버
    username: root
    password: password

  jpa:
    database-platform: org.hibernate.dialect.MariaDB103Dialect
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MariaDB103Dialect
```