"use client";

// `motion/react` is the current package for Framer Motion (same team, same API — `framer-motion`
// is now a thin alias re-exporting from this package), already used elsewhere in this app
// (see MotionCard.tsx), so this reuses it rather than adding a second animation library.
import { motion, useReducedMotion } from "motion/react";
import { Particles, ParticlesProvider } from "@tsparticles/react";
import { loadSlim } from "@tsparticles/slim";
import { loadOpacityUpdater } from "@tsparticles/updater-opacity";
import { loadSizeUpdater } from "@tsparticles/updater-size";
import { loadOutModesUpdater } from "@tsparticles/updater-out-modes";
import type { Container, Engine, ISourceOptions } from "@tsparticles/engine";
import { useEffect, useId, useRef, useState } from "react";
import { cn } from "@/lib/utils";

// Module-level, not recreated per render — ParticlesProvider requires `init` to be referentially
// stable across the component's lifetime (it throws if handed a fresh function every render,
// which an inline arrow in JSX would be).
async function initEngine(engine: Engine) {
	await loadSlim(engine);
	// Idempotent — safe even if `loadSlim` already registered any of these internally.
	await Promise.all([loadOpacityUpdater(engine), loadSizeUpdater(engine), loadOutModesUpdater(engine)]);
}

type Variant = "emerald-cyan" | "amber";

const VARIANTS: Record<Variant, [string, string]> = {
	"emerald-cyan": ["#34d399", "#22d3ee"],
	amber: ["#f59e0b", "#fcd34d"], // amber-500 → amber-300 ("gold-300" isn't a standard Tailwind token)
};

type Props = {
	/** e.g. "Retirement Fund" */
	label: string;
	/** 0–100 */
	percent: number;
	variant?: Variant;
	className?: string;
};

/**
 * Goals progress meter with a tsParticles "stardust" trail at the bar's leading edge. The bar's
 * width is driven by Motion (Framer Motion); the particle engine is tsParticles' slim preset plus
 * the opacity/size/out-of-bounds updater plugins loaded explicitly and idempotently — safe even
 * where `slim`'s own `@tsparticles/basic` dependency already registers them transitively. (Base
 * movement itself comes from `basic`'s `@tsparticles/plugin-move`; the older, separately
 * installable `@tsparticles/move-base` package turned out to pin an incompatible engine version
 * and was dropped rather than fought.) tsParticles owns all particle physics/decay/GC internally (`life.duration` +
 * opacity/size animations with `destroy: "min"`); this component's only job each frame is reading
 * the bar's *real* rendered edge (getBoundingClientRect, not Motion's internal animated value) and
 * calling `container.particles.addParticle()` there while the edge is actually moving. That means
 * it stays correct regardless of what drives the width — the initial tween, a later `percent`
 * change — with no need to restart anything. tsParticles doesn't ship a canvas shadowBlur/glow
 * updater in this version, so the ambient glow is a CSS `drop-shadow` filter on the particle layer
 * instead — same visual result, different mechanism.
 */
export function GoalsProgressBar({ label, percent, variant = "emerald-cyan", className }: Props) {
	const reduce = useReducedMotion();
	if (reduce) return <StaticBar label={label} percent={percent} variant={variant} className={className} />;
	return (
		<ParticlesProvider init={initEngine}>
			<AnimatedBar label={label} percent={percent} variant={variant} className={className} />
		</ParticlesProvider>
	);
}

function StaticBar({ label, percent, variant = "emerald-cyan", className }: Props) {
	const [stop1, stop2] = VARIANTS[variant];
	return (
		<div className={cn("flex w-full flex-col gap-2", className)}>
			<Header label={label} percent={percent} />
			<div className="relative h-2.5 w-full overflow-visible rounded-full bg-white/5">
				<div
					className="absolute inset-y-0 left-0 rounded-full shadow-[0_0_15px_rgba(245,158,11,0.4)]"
					style={{ width: `${percent}%`, background: `linear-gradient(90deg, ${stop1}, ${stop2})` }}
				/>
			</div>
		</div>
	);
}

function Header({ label, percent }: { label: string; percent: number }) {
	return (
		<div className="flex items-baseline justify-between">
			<span className="text-sm font-semibold tracking-tight text-text-primary">{label}</span>
			<span className="text-xs font-medium tabular-nums text-text-secondary">{Math.round(percent)}% Funded</span>
		</div>
	);
}

function AnimatedBar({ label, percent, variant = "emerald-cyan", className }: Props) {
	const id = useId();
	const trackRef = useRef<HTMLDivElement>(null);
	const barRef = useRef<HTMLDivElement>(null);
	const containerRef = useRef<Container | undefined>(undefined);
	const [ready, setReady] = useState(false);
	const [stop1, stop2] = VARIANTS[variant];

	const options: ISourceOptions = {
		fullScreen: { enable: false },
		background: { color: { value: "transparent" } },
		fpsLimit: 60,
		detectRetina: true,
		// `Container.play()`'s first call is a guarded no-op unless `autoPlay` is set — confirmed by
		// reading the engine source, since `number.value: 0` means there's nothing for it to decide
		// to animate on its own otherwise. Without this the draw loop never starts, so hundreds of
		// manually-added particles were sitting in the data array, never drawn or aged out.
		autoPlay: true,
		// A decorative trail on a progress bar shouldn't stop just because the tab loses focus or
		// scrolls out of view — default tsParticles behavior otherwise silently no-ops `.play()`.
		pauseOnBlur: false,
		pauseOnOutsideViewport: false,
		particles: {
			number: { value: 0 }, // manually spawned only — no ambient auto-generated particles
			shape: { type: "circle" },
			paint: { color: { value: [stop1, stop2] } },
			opacity: {
				value: { min: 0.4, max: 0.9 },
				animation: { enable: true, speed: 1.4, sync: false, startValue: "max", destroy: "min" },
			},
			size: {
				value: { min: 0.5, max: 2.5 },
				animation: { enable: true, speed: 2.6, sync: false, startValue: "max", destroy: "min" },
			},
			life: {
				count: 1,
				duration: { value: { min: 1, max: 2 }, sync: false }, // 1-2s lifespan
				delay: { value: 0, sync: true },
			},
			move: {
				enable: true,
				direction: "left", // backward thrust — opposite the bar's forward growth
				straight: false, // organic wobble instead of a rigid line = gentle Y drift
				random: true,
				speed: { min: 0.3, max: 1.2 },
				outModes: { default: "destroy" },
				gravity: { enable: false },
			},
		},
	};

	useEffect(() => {
		if (!ready) return;
		const track = trackRef.current;
		const bar = barRef.current;
		if (!track || !bar) return;

		let raf = 0;
		let prevHeadX: number | null = null;
		let playedContainer: Container | undefined; // which container we've already called .play() on

		function frame() {
			// Read fresh every frame rather than capturing once: React Strict Mode's dev-only
			// mount→unmount→remount cycle destroys the first container `particlesLoaded` hands us
			// and creates a second one, updating the ref — a captured reference would keep silently
			// feeding a dead, `destroyed: true` container forever (confirmed via direct inspection:
			// `addParticle` succeeded and `particles.count` climbed into the hundreds with nothing
			// ever drawn, because `Container.animationStatus` gates on `!destroyed`).
			const container = containerRef.current;
			if (container && !container.destroyed) {
				// `Container.play()`'s first call on a given container is a guarded no-op unless
				// `autoPlay` is set (see the `autoPlay: true` option below) — calling it once more
				// per live container here is a defensive belt-and-suspenders, not a workaround.
				if (playedContainer !== container) {
					container.play();
					playedContainer = container;
				}

				const trackRect = track!.getBoundingClientRect();
				const barRect = bar!.getBoundingClientRect();
				const headX = barRect.right - trackRect.left;
				const headY = trackRect.height / 2;
				const moving = prevHeadX !== null && Math.abs(headX - prevHeadX) > 0.05;

				if (moving) {
					const count = 3 + Math.floor(Math.random() * 3); // 3-5 per frame
					for (let i = 0; i < count; i++) {
						container.particles.addParticle({
							x: headX,
							y: headY + (Math.random() - 0.5) * trackRect.height * 0.6,
						});
					}
				}
				prevHeadX = headX;
			}
			raf = requestAnimationFrame(frame);
		}
		raf = requestAnimationFrame(frame);

		return () => cancelAnimationFrame(raf);
	}, [ready]);

	return (
		<div className={cn("flex w-full flex-col gap-2", className)}>
			<Header label={label} percent={percent} />
			<div ref={trackRef} className="relative h-2.5 w-full overflow-visible rounded-full bg-white/5">
				<motion.div
					ref={barRef}
					className="absolute inset-y-0 left-0 z-0 rounded-full shadow-[0_0_15px_rgba(245,158,11,0.4)]"
					style={{ background: `linear-gradient(90deg, ${stop1}, ${stop2})` }}
					initial={{ width: "0%" }}
					animate={{ width: `${percent}%` }}
					transition={{ duration: 1.8, ease: "easeInOut" }}
				/>
				{/* z-10: particles drift backward into the space the bar just filled (comet-trail
				    physics), so this layer must paint above the solid fill or the trail is invisible.
				    Sized via an explicit style, not a wrapping div — tsParticles' own root element has
				    no intrinsic height, so a plain wrapper's `height: 100%` resolves circularly against
				    it (confirmed: the canvas rendered 191px tall against a 10px track before this fix). */}
				<Particles
					id={`gpb-${id}`}
					options={options}
					className="pointer-events-none absolute inset-0 z-10"
					style={{ width: "100%", height: "100%", filter: `drop-shadow(0 0 3px ${stop1}99)` }}
					particlesLoaded={async (container) => {
						containerRef.current = container;
						setReady(true);
					}}
				/>
			</div>
		</div>
	);
}
