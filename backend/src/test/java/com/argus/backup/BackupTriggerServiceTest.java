package com.argus.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.argus.backup.BackupTriggerService.TriggerState;
import com.argus.backup.BackupTriggerService.TriggerStatus;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test (no Spring context, no Docker) for the trigger's own guard logic — the actual
 * pg_dump execution isn't installed on the dev machine (only in the deployed image, per the
 * Dockerfile), so the success/failure-from-a-real-dump paths are verified live post-deploy instead,
 * matching how other infra-dependent behavior in this codebase is verified.
 */
class BackupTriggerServiceTest {

	private static BackupProperties disabled() {
		return new BackupProperties("", 25200, 2700, "postgres");
	}

	@Test
	void triggerFailsFastWhenBackupsAreNotConfigured() {
		BackupTriggerService service = new BackupTriggerService(disabled(), "user", "pass", "db");

		TriggerStatus status = service.trigger();

		assertEquals(TriggerState.FAILED, status.state());
		assertNotNull(status.message());
	}

	@Test
	void statusStartsIdleBeforeAnyTrigger() {
		BackupTriggerService service = new BackupTriggerService(disabled(), "user", "pass", "db");

		assertEquals(TriggerState.IDLE, service.status().state());
	}
}
