package com.teambind.springproject.room.event.handler;

import com.teambind.springproject.message.handler.EventHandler;
import com.teambind.springproject.room.command.domain.service.TimeSlotManagementService;
import com.teambind.springproject.room.event.event.ClosedDateUpdateRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 휴무일 업데이트 요청 이벤트 핸들러.
 * <p>
 * Hexagonal Architecture 적용:
 * - Infrastructure Layer의 Repository를 직접 참조하지 않음
 * - Domain Service를 통한 간접 참조로 계층 격리 유지
 * <p>
 * 비동기로 기존 슬롯의 상태를 CLOSED로 변경한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClosedDateUpdateRequestedEventHandler implements EventHandler<ClosedDateUpdateRequestedEvent> {
	
	private final TimeSlotManagementService timeSlotManagementService;
	
	@Override
	@Transactional
	public void handle(ClosedDateUpdateRequestedEvent event) {
		log.info("Processing ClosedDateUpdateRequestedEvent: requestId={}, roomId={}",
				event.getRequestId(), event.getRoomId());
		
		// Domain Service를 통한 휴무일 업데이트 (트랜잭션 원자성 보장)
		int affectedSlots = timeSlotManagementService.updateClosedDatesForRoom(
				event.getRoomId(),
				event.getRequestId()
		);
		
		log.info("ClosedDateUpdateRequestedEvent processed: requestId={}, affectedSlots={}",
				event.getRequestId(), affectedSlots);
	}
	
	@Override
	public String getSupportedEventType() {
		return "ClosedDateUpdateRequested";
	}
}
