package com.argus.security;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserActivityDayRepository extends JpaRepository<UserActivityDay, UserActivityDay.Key> {

	Optional<UserActivityDay> findById_UserIdAndId_ActivityDate(Long userId, LocalDate date);

	List<UserActivityDay> findById_UserIdOrderById_ActivityDateDesc(Long userId);
}
