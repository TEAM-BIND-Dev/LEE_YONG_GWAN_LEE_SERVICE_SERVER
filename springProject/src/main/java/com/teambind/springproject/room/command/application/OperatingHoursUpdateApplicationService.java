package com.teambind.springproject.room.command.application;

import com.teambind.springproject.room.command.domain.service.OperatingHoursUpdateService;
import com.teambind.springproject.room.command.dto.OperatingHoursUpdateRequest;
import com.teambind.springproject.room.command.dto.WeeklySlotDto;
import com.teambind.springproject.room.entity.vo.WeeklySlotSchedule;
import com.teambind.springproject.room.entity.vo.WeeklySlotTime;
import com.teambind.springproject.room.query.dto.OperatingHoursUpdateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 운영 시간 업데이트 Application Service.
 * <p>
 * 룸의 운영 시간 변경을 담당한다.
 * 정책 업데이트 후 비동기로 슬롯을 재생성한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatingHoursUpdateApplicationService {

	private final OperatingHoursUpdateService operatingHoursUpdateService;

	/**
	 * 운영 시간을 업데이트한다.
	 * <p>
	 * 플로우:
	 * 1. WeeklySlotDto를 WeeklySlotSchedule로 변환
	 * 2. 정책 업데이트 및 슬롯 재생성 요청
	 * 3. 응답 반환 (202 Accepted)
	 *
	 * @param request 운영 시간 업데이트 요청
	 * @return 업데이트 응답 (요청 ID 포함)
	 */
	public OperatingHoursUpdateResponse updateOperatingHours(OperatingHoursUpdateRequest request) {
		log.info("Operating hours update requested: roomId={}", request.getRoomId());

		// 1. WeeklySlotSchedule 생성
		List<WeeklySlotTime> slotTimes = new ArrayList<>();
		for (WeeklySlotDto slotDto : request.getSlots()) {
			for (LocalTime startTime : slotDto.getStartTimes()) {
				slotTimes.add(WeeklySlotTime.of(slotDto.getDayOfWeek(), startTime));
			}
		}
		WeeklySlotSchedule weeklySchedule = WeeklySlotSchedule.of(slotTimes);

		// 2. 업데이트 요청
		String requestId = operatingHoursUpdateService.requestOperatingHoursUpdate(
				request.getRoomId(),
				weeklySchedule,
				request.getSlotUnit()
		);

		log.info("Operating hours update request accepted: requestId={}", requestId);

		// 3. 응답 생성
		return new OperatingHoursUpdateResponse(requestId, request.getRoomId());
	}
}
