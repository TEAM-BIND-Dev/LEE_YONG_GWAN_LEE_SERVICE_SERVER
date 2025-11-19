package com.teambind.springproject.message.dto;

import com.teambind.springproject.room.event.event.SlotConfirmedEvent;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 슬롯 예약 확정 이벤트 메시지 DTO.
 * <p>
 * Kafka 메시지로 전송될 때 사용되며, 모든 ID 필드는 String으로 직렬화된다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SlotConfirmedEventMessage {

	private String topic;
	private String eventType;
	private String reservationId;
	private LocalDateTime occurredAt;

	/**
	 * SlotConfirmedEvent로부터 메시지 DTO를 생성한다.
	 * Long ID → String ID 변환
	 */
	public static SlotConfirmedEventMessage from(SlotConfirmedEvent event) {
		return new SlotConfirmedEventMessage(
				event.getTopic(),
				event.getEventType(),
				event.getReservationId() != null ? event.getReservationId().toString() : null,
				event.getOccurredAt()
		);
	}

	/**
	 * 메시지 DTO를 SlotConfirmedEvent로 변환한다.
	 * String ID → Long ID 변환
	 */
	public SlotConfirmedEvent toEvent() {
		return SlotConfirmedEvent.of(
				reservationId != null ? Long.parseLong(reservationId) : null
		);
	}
}