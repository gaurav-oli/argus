package com.argus.ops;

import java.time.Instant;

/**
 * Host hardware snapshot for the Ops dashboard (Story 9.5). Memory in MB, disk in GB. Nullable fields
 * are values the JVM can't measure on the current host (see {@link HardwareService}).
 *
 * @param ramTotalMb          total physical RAM (≈ 28 GB on the Mini)
 * @param ramUsedMb           system RAM in use
 * @param ramFreeMb           system RAM free
 * @param jvmHeapUsedMb       this backend JVM's heap in use
 * @param jvmHeapMaxMb        this backend JVM's heap ceiling
 * @param ssdTotalGb          data-volume capacity (≈ 256 GB on the Mini)
 * @param ssdUsedGb           data-volume used
 * @param ssdFreeGb           data-volume free
 * @param ssdDaysToFull       projected days until full, or null until growth is tracked
 * @param cpuLoadPct          system-wide CPU load 0–100, or null if unavailable
 * @param processCpuLoadPct   this JVM's CPU load 0–100, or null if unavailable
 * @param neuralEngineLoadPct Apple Neural Engine load, or null (not exposed to the JVM)
 * @param asOf                capture time
 */
public record HardwareMetrics(
		long ramTotalMb,
		long ramUsedMb,
		long ramFreeMb,
		long jvmHeapUsedMb,
		long jvmHeapMaxMb,
		long ssdTotalGb,
		long ssdUsedGb,
		long ssdFreeGb,
		Integer ssdDaysToFull,
		Double cpuLoadPct,
		Double processCpuLoadPct,
		Double neuralEngineLoadPct,
		Instant asOf) {
}
