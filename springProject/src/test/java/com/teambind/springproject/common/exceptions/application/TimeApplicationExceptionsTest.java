package com.teambind.springproject.common.exceptions.application;

import com.teambind.springproject.common.exceptions.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Time 애플리케이션 예외 테스트")
class TimeApplicationExceptionsTest {
	
	@Nested
	@DisplayName("InvalidTimeRangeException")
	class InvalidTimeRangeExceptionTest {
		
		@Test
		@DisplayName("[정상] 종료 시간이 시작 시간보다 이전일 때")
		void endBeforeStart() {
			// Given
			String startTime = "14:00";
			String endTime = "13:00";
			
			// When
			InvalidTimeRangeException exception =
					InvalidTimeRangeException.endBeforeStart(startTime, endTime);
			
			// Then
			assertThat(exception.getMessage()).contains("14:00");
			assertThat(exception.getMessage()).contains("13:00");
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TIME_INVALID_RANGE);
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(exception.getExceptionType()).isEqualTo("APPLICATION");
		}
		
		@Test
		@DisplayName("[정상] 종료 날짜가 시작 날짜보다 이전일 때")
		void endDateBeforeStartDate() {
			// Given
			String startDate = "2025-01-20";
			String endDate = "2025-01-15";
			
			// When
			InvalidTimeRangeException exception =
					InvalidTimeRangeException.endDateBeforeStartDate(startDate, endDate);
			
			// Then
			assertThat(exception.getMessage()).contains("2025-01-20");
			assertThat(exception.getMessage()).contains("2025-01-15");
		}
		
		@Test
		@DisplayName("[정상] 잘못된 기간일 때")
		void invalidDuration() {
			// Given
			String duration = "-5 days";
			
			// When
			InvalidTimeRangeException exception =
					InvalidTimeRangeException.invalidDuration(duration);
			
			// Then
			assertThat(exception.getMessage()).contains("-5 days");
		}
	}
	
	@Nested
	@DisplayName("PastDateNotAllowedException")
	class PastDateNotAllowedExceptionTest {
		
		@Test
		@DisplayName("[정상] 과거 날짜 예외 생성")
		void createWithDate() {
			// Given
			String date = "2024-01-01";
			
			// When
			PastDateNotAllowedException exception = new PastDateNotAllowedException(date);
			
			// Then
			assertThat(exception.getMessage()).contains("2024-01-01");
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TIME_PAST_DATE_NOT_ALLOWED);
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(exception.getExceptionType()).isEqualTo("APPLICATION");
		}
		
		@Test
		@DisplayName("[정상] 필드명과 날짜로 예외 생성")
		void createWithFieldNameAndDate() {
			// Given
			String fieldName = "startDate";
			String date = "2024-01-01";
			
			// When
			PastDateNotAllowedException exception =
					new PastDateNotAllowedException(fieldName, date);
			
			// Then
			assertThat(exception.getMessage()).contains("startDate");
			assertThat(exception.getMessage()).contains("2024-01-01");
		}
	}
	
	@Nested
	@DisplayName("InvalidScheduleException")
	class InvalidScheduleExceptionTest {
		
		@Test
		@DisplayName("[정상] 빈 스케줄 예외 생성")
		void emptySchedule() {
			// When
			InvalidScheduleException exception = InvalidScheduleException.emptySchedule();
			
			// Then
			assertThat(exception.getMessage()).contains("비어있습니다");
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.POLICY_INVALID_SCHEDULE);
			assertThat(exception.getExceptionType()).isEqualTo("APPLICATION");
		}
		
		@Test
		@DisplayName("[정상] 잘못된 슬롯 시간 예외 생성")
		void invalidSlotTime() {
			// Given
			String dayOfWeek = "MONDAY";
			String time = "25:00";
			
			// When
			InvalidScheduleException exception =
					InvalidScheduleException.invalidSlotTime(dayOfWeek, time);
			
			// Then
			assertThat(exception.getMessage()).contains("MONDAY");
			assertThat(exception.getMessage()).contains("25:00");
		}
		
		@Test
		@DisplayName("[정상] 중복된 슬롯 시간 예외 생성")
		void duplicateSlotTime() {
			// Given
			String dayOfWeek = "TUESDAY";
			String time = "09:00";
			
			// When
			InvalidScheduleException exception =
					InvalidScheduleException.duplicateSlotTime(dayOfWeek, time);
			
			// Then
			assertThat(exception.getMessage()).contains("중복");
			assertThat(exception.getMessage()).contains("TUESDAY");
			assertThat(exception.getMessage()).contains("09:00");
		}
	}
	
	@Nested
	@DisplayName("SlotGenerationFailedException")
	class SlotGenerationFailedExceptionTest {
		
		@Test
		@DisplayName("[정상] 날짜로 예외 생성")
		void forDate() {
			// Given
			String date = "2025-01-15";
			Throwable cause = new RuntimeException("DB error");
			
			// When
			SlotGenerationFailedException exception =
					SlotGenerationFailedException.forDate(date, cause);
			
			// Then
			assertThat(exception.getMessage()).contains("2025-01-15");
			assertThat(exception.getCause()).isEqualTo(cause);
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TIME_SLOT_GENERATION_FAILED);
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			assertThat(exception.getExceptionType()).isEqualTo("APPLICATION");
		}
		
		@Test
		@DisplayName("[정상] Room ID로 예외 생성")
		void forRoom() {
			// Given
			Long roomId = 101L;
			Throwable cause = new RuntimeException("Generation error");
			
			// When
			SlotGenerationFailedException exception =
					SlotGenerationFailedException.forRoom(roomId, cause);
			
			// Then
			assertThat(exception.getMessage()).contains("101");
			assertThat(exception.getCause()).isEqualTo(cause);
		}
	}
}
