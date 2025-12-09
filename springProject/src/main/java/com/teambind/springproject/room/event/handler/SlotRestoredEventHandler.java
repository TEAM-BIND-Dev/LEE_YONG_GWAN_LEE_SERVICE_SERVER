package com.teambind.springproject.room.event.handler;

import com.teambind.springproject.message.handler.EventHandler;
import com.teambind.springproject.room.command.domain.service.TimeSlotManagementService;
import com.teambind.springproject.room.event.event.SlotRestoredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 슬롯 복구 이벤트 핸들러.
 * <p>
 * Hexagonal Architecture 적용:
 * - Infrastructure Layer의 Repository를 직접 참조하지 않음
 * - Domain Service를 통한 간접 참조로 계층 격리 유지
 * <p>
 * 해당 예약의 모든 슬롯을 CANCELLED/PENDING/RESERVED → AVAILABLE 상태로 전환한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlotRestoredEventHandler implements EventHandler<SlotRestoredEvent> {
	
	private final TimeSlotManagementService timeSlotManagementService;
	
	@Override
	@Transactional
	public void handle(SlotRestoredEvent event) {
		log.info("Processing SlotRestoredEvent: reservationId={}, reason={}",
				event.getReservationId(), event.getRestoreReason());
		
		try {
			// String → Long 변환
			Long reservationId = Long.parseLong(event.getReservationId());
			
			// Domain Service를 통한 슬롯 복구 (트랜잭션 원자성 보장)
			timeSlotManagementService.cancelSlotsByReservationId(reservationId);
			
			log.info("SlotRestoredEvent processed successfully: reservationId={}, reason={}",
					reservationId, event.getRestoreReason());
			
		} catch (NumberFormatException e) {
			log.error("Invalid reservationId format: reservationId={}", event.getReservationId(), e);
			throw new IllegalArgumentException("Invalid reservationId format: " + event.getReservationId(), e);
		} catch (Exception e) {
			log.error("Failed to process SlotRestoredEvent: reservationId={}, reason={}",
					event.getReservationId(), e.getMessage(), e);
			throw e; // Re-throw for transaction rollback
		}
	}
	
	@Override
	public String getSupportedEventType() {
		return "SlotRestored";
	}
}
