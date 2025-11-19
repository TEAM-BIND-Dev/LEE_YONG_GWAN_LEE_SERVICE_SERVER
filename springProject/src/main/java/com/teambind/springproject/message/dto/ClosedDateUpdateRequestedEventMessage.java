package com.teambind.springproject.message.dto;

import com.teambind.springproject.room.event.event.ClosedDateUpdateRequestedEvent;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 휴무일 업데이트 요청 이벤트 메시지 DTO.
 * <p>
 * Kafka 메시지로 전송될 때 사용되며, 모든 ID 필드는 String으로 직렬화된다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ClosedDateUpdateRequestedEventMessage {

	private String topic;
	private String eventType;
	private String requestId;
	private String roomId;
	private LocalDateTime requestedAt;

	/**
	 * ClosedDateUpdateRequestedEvent로부터 메시지 DTO를 생성한다.
	 * Long ID → String ID 변환
	 */
	public static ClosedDateUpdateRequestedEventMessage from(ClosedDateUpdateRequestedEvent event) {
		return new ClosedDateUpdateRequestedEventMessage(
				event.getTopic(),
				event.getEventType(),
				event.getRequestId(),
				event.getRoomId() != null ? event.getRoomId().toString() : null,
				event.getRequestedAt()
		);
	}

	/**
	 * 메시지 DTO를 ClosedDateUpdateRequestedEvent로 변환한다.
	 * String ID → Long ID 변환
	 */
	public ClosedDateUpdateRequestedEvent toEvent() {
		return ClosedDateUpdateRequestedEvent.of(
				requestId,
				roomId != null ? Long.parseLong(roomId) : null
		);
	}
}