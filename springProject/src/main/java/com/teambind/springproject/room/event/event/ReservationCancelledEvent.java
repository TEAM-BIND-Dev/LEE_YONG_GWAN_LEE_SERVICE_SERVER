package com.teambind.springproject.room.event.event;

import com.teambind.springproject.message.event.Event;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 예약 취소 이벤트.
 * <p>
 * 다른 서비스(예약 서비스)에서 예약이 취소될 때 발행된다.
 * 이 이벤트를 수신하면 해당 예약의 슬롯 락을 해제하여 AVAILABLE 상태로 전환한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationCancelledEvent extends Event {

	private static final String TOPIC = "reservation-cancelled";
	private static final String EVENT_TYPE = "ReservationCancelled";

	private Long reservationId;
	private String cancelReason;
	private LocalDateTime occurredAt;

	private ReservationCancelledEvent(
			Long reservationId,
			String cancelReason,
			LocalDateTime occurredAt
	) {
		super(TOPIC, EVENT_TYPE);
		this.reservationId = reservationId;
		this.cancelReason = cancelReason;
		this.occurredAt = occurredAt;
	}

	public static ReservationCancelledEvent of(
			Long reservationId,
			String cancelReason
	) {
		return new ReservationCancelledEvent(
				reservationId,
				cancelReason,
				LocalDateTime.now()
		);
	}

	@Override
	public String getEventTypeName() {
		return EVENT_TYPE;
	}
}
