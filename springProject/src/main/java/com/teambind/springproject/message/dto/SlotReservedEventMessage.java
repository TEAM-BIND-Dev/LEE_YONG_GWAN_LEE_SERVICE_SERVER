package com.teambind.springproject.message.dto;

import com.teambind.springproject.room.event.event.SlotReservedEvent;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 슬롯 예약 대기 이벤트 메시지 DTO.
 * <p>
 * Kafka 메시지로 전송될 때 사용되며, 모든 ID 필드는 String으로 직렬화된다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SlotReservedEventMessage {

	private String topic;
	private String eventType;
	private String roomId;
	private LocalDate slotDate;
	private List<LocalTime> startTimes;
	private String reservationId;
	private LocalDateTime occurredAt;

	/**
	 * SlotReservedEvent로부터 메시지 DTO를 생성한다.
	 * Long ID → String ID 변환
	 */
	public static SlotReservedEventMessage from(SlotReservedEvent event) {
		return new SlotReservedEventMessage(
				event.getTopic(),
				event.getEventType(),
				event.getRoomId() != null ? event.getRoomId().toString() : null,
				event.getSlotDate(),
				event.getStartTimes(),
				event.getReservationId() != null ? event.getReservationId().toString() : null,
				event.getOccurredAt()
		);
	}

	/**
	 * 메시지 DTO를 SlotReservedEvent로 변환한다.
	 * String ID → Long ID 변환
	 */
	public SlotReservedEvent toEvent() {
		return SlotReservedEvent.of(
				roomId != null ? Long.parseLong(roomId) : null,
				slotDate,
				startTimes,
				reservationId != null ? Long.parseLong(reservationId) : null
		);
	}
}