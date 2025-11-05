package com.teambind.springproject.common.exceptions.domain;

import com.teambind.springproject.common.exceptions.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Policy 도메인 예외 테스트")
class PolicyExceptionsTest {
	
	@Nested
	@DisplayName("PolicyNotFoundException")
	class PolicyNotFoundExceptionTest {
		
		@Test
		@DisplayName("[정상] 기본 생성자로 예외 생성")
		void createWithDefaultConstructor() {
			// When
			PolicyNotFoundException exception = new PolicyNotFoundException();
			
			// Then
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.POLICY_NOT_FOUND);
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
			assertThat(exception.getExceptionType()).isEqualTo("DOMAIN");
		}
		
		@Test
		@DisplayName("[정상] Policy ID로 예외 생성")
		void createWithPolicyId() {
			// Given
			Long policyId = 456L;
			
			// When
			PolicyNotFoundException exception = new PolicyNotFoundException(policyId);
			
			// Then
			assertThat(exception.getMessage()).contains("456");
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.POLICY_NOT_FOUND);
		}
		
		@Test
		@DisplayName("[정상] Room ID로 예외 생성")
		void createWithRoomId() {
			// Given
			Long roomId = 101L;
			
			// When
			PolicyNotFoundException exception = new PolicyNotFoundException(roomId, true);
			
			// Then
			assertThat(exception.getMessage()).contains("101");
			assertThat(exception.getMessage()).contains("Room ID");
		}
	}
	
	@Nested
	@DisplayName("PolicyAlreadyExistsException")
	class PolicyAlreadyExistsExceptionTest {
		
		@Test
		@DisplayName("[정상] 기본 생성자로 예외 생성")
		void createWithDefaultConstructor() {
			// When
			PolicyAlreadyExistsException exception = new PolicyAlreadyExistsException();
			
			// Then
			assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.POLICY_ALREADY_EXISTS);
			assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
			assertThat(exception.getExceptionType()).isEqualTo("DOMAIN");
		}
		
		@Test
		@DisplayName("[정상] Room ID로 예외 생성")
		void createWithRoomId() {
			// Given
			Long roomId = 101L;
			
			// When
			PolicyAlreadyExistsException exception = new PolicyAlreadyExistsException(roomId);
			
			// Then
			assertThat(exception.getMessage()).contains("101");
			assertThat(exception.getMessage()).contains("이미 존재");
		}
	}
}
