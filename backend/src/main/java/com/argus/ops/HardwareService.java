package com.argus.ops;

import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Runtime hardware telemetry for the Operations dashboard (Epic 9, Story 9.5): RAM, JVM heap,
 * data-volume SSD usage, and CPU load — read from the JVM's {@link OperatingSystemMXBean} and the
 * filesystem. In production the backend runs inside a Docker container, so these describe the
 * <em>container runtime</em> (the Docker Desktop Linux VM), not the whole Mac Mini.
 *
 * <p>RAM "used" is computed from Linux {@code /proc/meminfo}'s {@code MemAvailable} (which accounts for
 * reclaimable page cache), so it reflects memory that's genuinely in use rather than counting disk
 * cache as consumed — the MXBean's {@code getFreeMemorySize()} reports only {@code MemFree}, which makes
 * a healthy cache-filled Linux box look almost full. Falls back to the MXBean off Linux.
 *
 * <p>Honesty notes: {@code neuralEngineLoadPct} is null (Apple's ANE isn't exposed to the JVM),
 * {@code ssdDaysToFull} is null until growth is tracked over time, and the native Ollama/Gemma process
 * runs on the Mac host (not in this container), so its large footprint is not reflected here.
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
		// Prefer MemAvailable (reclaimable-cache-aware); the MXBean's free is MemFree, which counts
		// page cache as "used" and makes a healthy Linux box look nearly full.
		Long available = readMemAvailableBytes();
		long ramFree = available != null ? Math.min(available, ramTotal) : os.getFreeMemorySize();
		long ramUsed = Math.max(0, ramTotal - ramFree);

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

	/**
	 * Bytes of genuinely-available RAM from Linux {@code /proc/meminfo} ({@code MemAvailable}, in kB),
	 * or null off Linux / if unreadable — the caller then falls back to the MXBean's free.
	 */
	private static Long readMemAvailableBytes() {
		Path meminfo = Path.of("/proc/meminfo");
		if (!Files.isReadable(meminfo)) {
			return null;
		}
		try {
			for (String line : Files.readAllLines(meminfo)) {
				if (line.startsWith("MemAvailable:")) {
					String[] parts = line.trim().split("\\s+");
					return Long.parseLong(parts[1]) * 1024L; // value is in kB
				}
			}
		}
		catch (IOException | RuntimeException ex) {
			return null;
		}
		return null;
	}
}
