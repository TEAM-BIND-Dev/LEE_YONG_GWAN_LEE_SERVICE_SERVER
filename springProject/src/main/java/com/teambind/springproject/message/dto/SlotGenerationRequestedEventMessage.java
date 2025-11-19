package com.teambind.springproject.message.dto;

import com.teambind.springproject.room.event.event.SlotGenerationRequestedEvent;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 슬롯 생성 요청 이벤트 메시지 DTO.
 * <p>
 * Kafka 메시지로 전송될 때 사용되며, 모든 ID 필드는 String으로 직렬화된다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SlotGenerationRequestedEventMessage {

	private String topic;
	private String eventType;
	private String requestId;
	private String roomId;
	private LocalDate startDate;
	private LocalDate endDate;
	private LocalDateTime requestedAt;

	/**
	 * SlotGenerationRequestedEvent로부터 메시지 DTO를 생성한다.
	 * Long ID → String ID 변환
	 */
	public static SlotGenerationRequestedEventMessage from(SlotGenerationRequestedEvent event) {
		return new SlotGenerationRequestedEventMessage(
				event.getTopic(),
				event.getEventType(),
				event.getRequestId(),
				event.getRoomId() != null ? event.getRoomId().toString() : null,
				event.getStartDate(),
				event.getEndDate(),
				event.getRequestedAt()
		);
	}

	/**
	 * 메시지 DTO를 SlotGenerationRequestedEvent로 변환한다.
	 * String ID → Long ID 변환
	 */
	public SlotGenerationRequestedEvent toEvent() {
		return SlotGenerationRequestedEvent.of(
				requestId,
				roomId != null ? Long.parseLong(roomId) : null,
				startDate,
				endDate
		);
	}
}