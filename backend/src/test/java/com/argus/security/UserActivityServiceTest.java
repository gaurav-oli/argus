package com.argus.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserActivityServiceTest {

	private final UserActivityDayRepository days = mock(UserActivityDayRepository.class);
	private final UserActivityService service = new UserActivityService(days);

	@Test
	void theFirstTouchOfTheDayCreatesARow() {
		when(days.findById_UserIdAndId_ActivityDate(eq(1L), any())).thenReturn(Optional.empty());

		service.touch(1L);

		verify(days).save(any(UserActivityDay.class));
	}

	@Test
	void aSecondTouchWithinTheDebounceWindowIsSkipped() {
		when(days.findById_UserIdAndId_ActivityDate(eq(1L), any())).thenReturn(Optional.empty());

		service.touch(1L);
		service.touch(1L); // immediately again — a page polling every few seconds shouldn't write every time

		verify(days, times(1)).save(any());
	}

	@Test
	void aDifferentUsersTouchIsNeverDebouncedByAnotherUsers() {
		when(days.findById_UserIdAndId_ActivityDate(any(), any())).thenReturn(Optional.empty());

		service.touch(1L);
		service.touch(2L);

		verify(days, times(2)).save(any());
	}

	@Test
	void recordDayUpdatesAnExistingRowsLastSeenInsteadOfInsertingASecondOne() {
		UserActivityDay existing = new UserActivityDay(1L, LocalDate.now(), Instant.now().minusSeconds(120));
		when(days.findById_UserIdAndId_ActivityDate(eq(1L), any())).thenReturn(Optional.of(existing));

		service.recordDay(1L, Instant.now());

		verify(days).save(existing);
	}

	@Test
	void recordDayInsertsANewRowWhenNoneExistsForToday() {
		when(days.findById_UserIdAndId_ActivityDate(eq(1L), any())).thenReturn(Optional.empty());

		service.recordDay(1L, Instant.now());

		verify(days, times(1)).save(any(UserActivityDay.class));
	}

	@Test
	void aFailingRepositoryNeverPropagatesOutOfTouch() {
		when(days.findById_UserIdAndId_ActivityDate(any(), any())).thenThrow(new RuntimeException("db down"));

		service.touch(1L); // must not throw — activity tracking is best-effort

		verify(days, never()).save(any());
	}
}
