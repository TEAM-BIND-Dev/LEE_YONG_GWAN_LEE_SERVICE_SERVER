package com.teambind.springproject.message.dto;

import com.teambind.springproject.room.event.event.SlotCancelledEvent;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 슬롯 취소 이벤트 메시지 DTO.
 * <p>
 * Kafka 메시지로 전송될 때 사용되며, 모든 ID 필드는 String으로 직렬화된다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SlotCancelledEventMessage {

	private String topic;
	private String eventType;
	private String reservationId;
	private String cancelReason;
	private LocalDateTime occurredAt;

	/**
	 * SlotCancelledEvent로부터 메시지 DTO를 생성한다.
	 * Long ID → String ID 변환
	 */
	public static SlotCancelledEventMessage from(SlotCancelledEvent event) {
		return new SlotCancelledEventMessage(
				event.getTopic(),
				event.getEventType(),
				event.getReservationId() != null ? event.getReservationId().toString() : null,
				event.getCancelReason(),
				event.getOccurredAt()
		);
	}

	/**
	 * 메시지 DTO를 SlotCancelledEvent로 변환한다.
	 * String ID → Long ID 변환
	 */
	public SlotCancelledEvent toEvent() {
		return SlotCancelledEvent.of(
				reservationId != null ? Long.parseLong(reservationId) : null,
				cancelReason
		);
	}
}