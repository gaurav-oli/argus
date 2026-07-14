package com.argus.notification;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPrefsRepository extends JpaRepository<NotificationPrefs, Short> {

	default Optional<NotificationPrefs> findSingleton() {
		return findById(NotificationPrefs.SINGLETON_ID);
	}
}
