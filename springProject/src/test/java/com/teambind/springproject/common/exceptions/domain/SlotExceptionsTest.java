package com.teambind.springproject.common.exceptions.domain;

import com.teambind.springproject.common.exceptions.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Slot 도메인 예외 테스트")
class SlotExceptionsTest {
	
	@Nested
	@DisplayName("SlotNotFoundException")
	class SlotNotFoundExceptionTest {
		
		@Test
		@DisplayName("[정상] 기본 생성자로 예외 생성")
		void createWithDefaultConstructor() {
			// When
			SlotNotFoundException exception = new SlotNotFoundException();
			
			// Then
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SLOT_NOT_FOUND);
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
			assertThat(exception.getExceptionType()).isEqualTo("DOMAIN");
		}
		
		@Test
		@DisplayName("[정상] Slot ID로 예외 생성")
		void createWithSlotId() {
			// Given
			Long slotId = 123L;
			
			// When
			SlotNotFoundException exception = new SlotNotFoundException(slotId);
			
			// Then
			assertThat(exception.getMessage()).contains("123");
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SLOT_NOT_FOUND);
		}
		
		@Test
		@DisplayName("[정상] Room ID, Date, Time으로 예외 생성")
		void createWithRoomDateAndTime() {
			// Given
			Long roomId = 101L;
			String date = "2025-01-15";
			String time = "14:00";
			
			// When
			SlotNotFoundException exception = new SlotNotFoundException(roomId, date, time);
			
			// Then
			assertThat(exception.getMessage()).contains("101");
			assertThat(exception.getMessage()).contains("2025-01-15");
			assertThat(exception.getMessage()).contains("14:00");
		}
	}
	
	@Nested
	@DisplayName("SlotNotAvailableException")
	class SlotNotAvailableExceptionTest {
		
		@Test
		@DisplayName("[정상] 현재 상태로 예외 생성")
		void createWithCurrentStatus() {
			// Given
			String status = "RESERVED";
			
			// When
			SlotNotAvailableException exception = new SlotNotAvailableException(status);
			
			// Then
			assertThat(exception.getMessage()).contains("RESERVED");
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SLOT_NOT_AVAILABLE);
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
		}
		
		@Test
		@DisplayName("[정상] Slot ID와 현재 상태로 예외 생성")
		void createWithSlotIdAndStatus() {
			// Given
			Long slotId = 123L;
			String status = "PENDING";
			
			// When
			SlotNotAvailableException exception = new SlotNotAvailableException(slotId, status);
			
			// Then
			assertThat(exception.getMessage()).contains("123");
			assertThat(exception.getMessage()).contains("PENDING");
		}
	}
	
	@Nested
	@DisplayName("InvalidSlotStateTransitionException")
	class InvalidSlotStateTransitionExceptionTest {
		
		@Test
		@DisplayName("[정상] 현재 상태와 목표 상태로 예외 생성")
		void createWithStates() {
			// Given
			String currentState = "RESERVED";
			String targetState = "PENDING";
			
			// When
			InvalidSlotStateTransitionException exception =
					new InvalidSlotStateTransitionException(currentState, targetState);
			
			// Then
			assertThat(exception.getMessage()).contains("RESERVED");
			assertThat(exception.getMessage()).contains("PENDING");
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SLOT_INVALID_STATE_TRANSITION);
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
		}
		
		@Test
		@DisplayName("[정상] 커스텀 메시지로 예외 생성")
		void createWithCustomMessage() {
			// Given
			String message = "잘못된 상태 전이입니다";
			
			// When
			InvalidSlotStateTransitionException exception =
					new InvalidSlotStateTransitionException(message);
			
			// Then
			assertThat(exception.getMessage()).isEqualTo(message);
			assertThat(exception.getExceptionType()).isEqualTo("DOMAIN");
		}
	}
}
