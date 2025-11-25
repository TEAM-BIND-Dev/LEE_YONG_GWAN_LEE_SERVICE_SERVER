-- =====================================================
-- 중복 슬롯 데이터 정리 및 Unique Constraint 추가
-- =====================================================
--
-- 문제: room_time_slots 테이블에 (room_id, slot_date, slot_time) 조합의 중복 데이터 존재
-- 원인: Unique Constraint가 없어서 동일한 조합이 여러 번 생성됨
-- 해결: 중복 제거 + Unique Constraint 추가
--
-- 실행 전 반드시 백업 필요!
-- =====================================================

-- 1. 현재 중복 데이터 확인
SELECT room_id,
       slot_date,
       slot_time,
       COUNT(*)                               as duplicate_count,
       GROUP_CONCAT(slot_id ORDER BY slot_id) as slot_ids
FROM room_time_slots
GROUP BY room_id, slot_date, slot_time
HAVING COUNT(*) > 1
ORDER BY duplicate_count DESC;

-- 2. 중복된 레코드 중 가장 최근 것(slot_id가 큰 것)만 남기고 나머지 삭제
DELETE t1
FROM room_time_slots t1
         INNER JOIN room_time_slots t2
WHERE t1.room_id = t2.room_id
  AND t1.slot_date = t2.slot_date
  AND t1.slot_time = t2.slot_time
  AND t1.slot_id < t2.slot_id;

-- 3. 삭제 결과 확인 (중복이 없어야 함)
SELECT room_id,
       slot_date,
       slot_time,
       COUNT(*) as count
FROM room_time_slots
GROUP BY room_id, slot_date, slot_time
HAVING COUNT(*) > 1;

-- 4. Unique Constraint 추가
-- 참고: 이미 idx_room_date_time 인덱스가 있으므로 UNIQUE 속성만 추가
ALTER TABLE room_time_slots
    ADD UNIQUE KEY uk_room_date_time (room_id, slot_date, slot_time);

-- 5. Constraint 확인
SHOW CREATE TABLE room_time_slots;

-- =====================================================
-- 완료!
-- =====================================================
-- 이제 동일한 (room_id, slot_date, slot_time) 조합으로
-- 중복 insert 시도 시 에러가 발생합니다.
-- =====================================================
