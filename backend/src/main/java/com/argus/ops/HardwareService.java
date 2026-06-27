package com.argus.ops;

import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Host hardware telemetry for the Operations dashboard (Epic 9, Story 9.5): system RAM, JVM heap,
 * data-volume SSD usage, and CPU load — read from the JVM's {@link OperatingSystemMXBean} and the
 * filesystem, so the values are real wherever the process runs (the Mac Mini in production).
 *
 * <p>Honesty notes: {@code neuralEngineLoadPct} is null (Apple's ANE isn't exposed to the JVM —
 * a Mini-native concern), {@code ssdDaysToFull} is null until growth is tracked over time, and the
 * per-component RAM breakdown is limited to what the JVM can see (system total + this JVM's heap);
 * Postgres/Redis/model resident sizes need OS-level introspection on the Mini.
 */
@Service
public class HardwareService {

	private static final long MB = 1024L * 1024L;
	private static final long GB = 1024L * 1024L * 1024L;

	/** Filesystem whose free/total space represents the data SSD (the Mini's 256GB volume in prod). */
	private final String dataDir;

	public HardwareService(@Value("${argus.ops.data-dir:/}") String dataDir) {
		this.dataDir = dataDir;
	}

	public HardwareMetrics snapshot() {
		OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
		MemoryMXBean mem = ManagementFactory.getMemoryMXBean();

		long ramTotal = os.getTotalMemorySize();
		long ramFree = os.getFreeMemorySize();
		long ramUsed = ramTotal - ramFree;

		long heapUsed = mem.getHeapMemoryUsage().getUsed();
		long heapMax = mem.getHeapMemoryUsage().getMax();

		File volume = new File(dataDir);
		long ssdTotal = volume.getTotalSpace();
		long ssdFree = volume.getUsableSpace();
		long ssdUsed = ssdTotal - ssdFree;

		return new HardwareMetrics(
				ramTotal / MB, ramUsed / MB, ramFree / MB,
				heapUsed / MB, heapMax / MB,
				ssdTotal / GB, ssdUsed / GB, ssdFree / GB,
				null, // ssdDaysToFull — needs growth history (Story 9.7 follow-up)
				pct(os.getCpuLoad()),
				pct(os.getProcessCpuLoad()),
				null, // neuralEngineLoadPct — not exposed to the JVM (Mini-native)
				Instant.now());
	}

	/** Convert a 0–1 load (or -1 "unavailable") to a 0–100 percentage, or null when unavailable. */
	private static Double pct(double load) {
		return load < 0 ? null : Math.round(load * 1000.0) / 10.0;
	}
}
