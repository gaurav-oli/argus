# Terminal Noir — design language

Branch: `design/terminal-noir`. It replaces Private Bank Editorial. The concept was picked from the
October 2026 redesign exploration (concept 2 of 5), which lives on the "Argus Redesign Concepts"
canvas.

## The idea

Argus as an amber-phosphor research terminal: a Bloomberg desk crossed with a 1980s CRT. Everything
is monospace and corners are square. Panes are framed in amber with box-drawn headers (`┌─ MORNING
BRIEFING ─`). Motion is mechanical: text types itself out, panes "boot" with a stepped wipe, a ticker
tape scrolls, and a caret blinks where input lives.

## Tokens (`.terminal-theme` in `frontend/src/app/globals.css`)

| Role | Value |
|---|---|
| Background / surface / elevated | `#0a0a08` / `#0f0e0a` / `#15140e` |
| Accent (phosphor amber) | `#ffb000` |
| Text / secondary | `#e9e4d4` / `#a39d86` (about 7:1 on the background) |
| Gains / losses / warning | `#8ce99a` / `#ff6b57` / `#ffd166` |
| Body font | JetBrains Mono (everything; ligatures off) |
| Display font | VT323, with `font-size-adjust` and a soft amber glow |

The theme works the same way the editorial skin did: it swaps the CSS variable set and re-declares
the Tailwind `--color-*` and `--font-*` aliases. Every existing component re-skins without being
edited. `.font-serif-editorial` and `.font-display` both map to VT323.

## Logo and app icon

Argus is named after **Argus Panoptes**, the hundred-eyed watchman of Greek myth who never closed
every eye at once.

- **Logo (L1 · Panoptes ring):** a central eye inside a ring of sixteen smaller eyes. In the app the
  ring's eyes blink one after another, so some are always open. Component: `ArgusMark` /
  `ArgusLockup` in `components/brand/ArgusMark.tsx`. It draws in `currentColor`. Used in the
  sidebar, the mobile top bar, sign-in, first-run setup and the "connecting" screen.
- **App icon (L3 · A-eye monogram):** an **A** whose crossbar is an open eye, dark on a solid amber
  tile. It's static and legible down to 16px. The SVG sources are in `frontend/brand/`, and
  `node brand/build-icons.mjs` regenerates every icon file with `sharp` (already installed by
  Next.js):
  - `icon-192/512.png`: PWA icons, also maskable
  - `apple-icon.png` (180): iPhone home screen
  - `icon.svg` plus `favicon.ico` (16/32/48): browser tab
  - `badge-96.png`: a white silhouette for Android notification badges

The design exploration (five directions, plus mockups of the logo in use) is on the "Argus Redesign
Concepts" canvas, on its Logo and Logo in use pages.

## Signature pieces (built)

| Where | What |
|---|---|
| Every page | CRT backdrop: scanlines with a slow flicker, an amber centre bloom and a vignette (`AmbientBackground`) |
| Every page | **Ticker tape** of *your own* holdings (price + day %) under the top bar. Hidden in Demo Mode and when there are no holdings (`components/terminal/TickerTape.tsx`) |
| Sidebar | `ARGUS://` wordmark, `[1] HOME` numbered menu with the active row inverted to amber, **Alt+1…5** shortcuts, and a blinking `argus@mini:~$` prompt |
| Mobile nav | Monospace tabs; the active tab is an inverted amber block |
| Top bar | Value in glowing VT323; "Ask AI" is a shell prompt, `argus> ask▌` |
| Status line (desktop footer) | `● NORMAL MODE · ▲ agents 15/15 · haiku $6.12 / $20.00 · 08:42:17` with a live clock |
| Page headers | The eyebrow becomes the working directory (`~/argus/operations $`) and the title types itself out (`TypedText`) |
| Panes | Stepped CRT "boot" wipe on entrance (`MotionCard`); hover lights the frame instead of lifting the card |
| Home | The greeting types itself out with a caret. The briefing headline prints as you arrive. |
| Sign-in | A boot log (`> probing 15 agents [ OK ]` …) then a `login:` prompt with Sign in with Google |
| Intelligence → ticker detail | **Forecast spread**: the call's model odds as a big VT323 %, a bull/bear block bar, and an ASCII return histogram (`▁▃▆█▆▃▁`) for the call's own horizon. The side the call bets on is lit; stop `S`, entry `│` and target `T` sit on a rail underneath. Columns grow upward on open (`ForecastSpread`, math in `lib/forecastSpread.ts`) |
| Agents | A live **`tail -f` agent log**: each agent's last run as a log line, with new lines animating in (`AgentLogTail`) |
| Agents → pipeline | Same wires, particles and core as before, but the motion now carries information. **Recency**: wire density and speed come from each agent's real cadence and last run, and a stalled agent's wire breaks. **Streams**: Sources / Market / Analysis curve into the core, which feeds "Your calls". **Reactive core**: particles are coloured by stream, the core ripples, and it highlights each live call in turn. Cadence and stale thresholds come from the backend's `AgentCadence`, which is shared with the freshness alert (`AgentActivity`, `lib/pipelineFlow.ts`) |

Every animation is disabled under `prefers-reduced-motion`. `TypedText` gives screen readers the
full string immediately.

## Ideas for the next pass, page by page

These are not built yet. They are ordered by how much each would add.

### Home
- **`argus> brief` command bar** under the briefing, with chips: `brief`, `why <top call>`, `risk`,
  `events`. Each one retypes its answer in the briefing pane, using data already on the page
  (recommendations, health score, calendar). This is the interaction from the concept artboard.
- **Upcoming events as a cron table**: `28 OCT  FOMC  ·  30 OCT  SHOP.TO earnings [HELD]`.

### Portfolio
- **Holdings as `ls -l`**: monospace columns with a block-bar weight column (`AsciiBar`) and a 7-day
  sparkline in `▁▂▃▅▇` characters.
- **Net worth "odometer"**: digits roll into place in VT323 on load and on each live tick.
- **Import as a progress log**: statement import shows `> parsing page 3/7 … > reconciling totals
  [ OK ]`, which surfaces the self-healing parser's real steps.

### Intelligence
- ~~Probability Weather inside the terminal~~: **built** as the forecast spread (see above). It
  deliberately has **no 7/30/90 toggle**. The model scores each call at one horizon only, and
  showing odds for horizons it never scored would mean inventing probabilities. A toggle can come
  back if the engine starts scoring every horizon.
- **The command palette as the primary nav for tickers**: `/` focuses an `argus>` prompt with fuzzy
  ticker search.
- **Personas as a chat transcript**: `[buffett] AGREE — durable moat…` lines that print in order.

### Agents
- **`htop` view of the fleet**: one row per agent with a CPU-style activity meter (captures in the
  last 24h), uptime, next run and status in `[ RUN ]` / `[IDLE]` / `[PLAN]` brackets.
- **Budget as a fuel gauge**: `haiku [████████░░░░░░░░] 31% · 22 days left`.

### Profile
- **Settings as a config file**: `~/.argus/profile.yml` rendered with line numbers. Each setting is
  a `key: value` line you edit in place.
- **Admin "People on Argus" as `who`**: `gaurav  tty1  last 08:42  42 logins`, with invite status as
  `sent → opened → joined` arrows.

### System-wide
- **Boot-once splash**: the first load of a session plays a 1-second POST before the shell appears.
  Later loads skip it.
- **Alt+K command palette** available from every page.
- **A "phosphor" setting**: amber (default), green or white, which swaps only `--c-accent`.

## Verification status

Built on the MacBook. Typecheck, lint (no new warnings) and the production build all pass. The
visual and live-data checks need the running stack on the Mini: see
`docs/mac-mini-validation.md` §15.

## How the forecast spread stays honest

Argus's probabilities come from the scoring engine and are never generated in the UI, so the spread
adds **no new number**. It takes:

1. the model's calibrated bull odds `p` for the call's own `holdDays`, and
2. the stock's realized volatility: the sample standard deviation of daily log returns over the last
   60 closes from Agent 10's candles.

It then draws the one normal curve with that spread (`σ = σ_daily·√trading days`) that puts exactly
`p` of its area above zero (`μ = σ·Φ⁻¹(p)`). The on-screen caption says the shape illustrates the
odds and is not a second forecast. With fewer than 21 usable closes, no curve is drawn. The math is
covered by `npm test` (16 tests, Node's built-in runner, no new dependencies).

> Note: an earlier pass put the ASCII odds bar in `features/recommendations/RecommendationCards.tsx`.
> That component is no longer mounted anywhere (it predates the Intelligence rebuild), so the bar
> now lives in the ticker detail instead. `RecommendationCards` is dead code and could be deleted.

