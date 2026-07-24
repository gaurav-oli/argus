import { AmbientBackground } from "@/components/shell/AmbientBackground";
import { BottomNav } from "@/components/shell/BottomNav";
import { BottomStrip } from "@/components/shell/BottomStrip";
import { RightPanel } from "@/components/shell/RightPanel";
import { Sidebar } from "@/components/shell/Sidebar";
import { TopBar } from "@/components/shell/TopBar";
import { AuthGate } from "@/features/auth/AuthGate";
import { PanicProvider } from "@/features/panic/PanicProvider";
import { PrivacyProvider } from "@/features/privacy/PrivacyProvider";

/**
 * Dashboard shell composition (PRD §12 Layout), gated by {@link AuthGate} (Story 2.1) — the shell
 * renders only once a valid session exists; otherwise the PIN setup/lock screen shows.
 * - Desktop (lg+): Sidebar | [TopBar / main / BottomStrip]; RightPanel at xl+.
 * - Mobile (<lg): TopBar / main / BottomNav; the desktop chrome collapses away.
 * Each shell piece owns its own responsive visibility; the layout orders them.
 */
export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <AuthGate>
      <PanicProvider>
      <PrivacyProvider>
        {/* Private Bank Editorial (approved 2026-07-22) — the whole shell, not just Home, re-skins
            via the same CSS-variable mechanism that powers light/dark mode. See globals.css. */}
        <div className="editorial-theme">
          <AmbientBackground />
          <div className="flex h-dvh overflow-hidden">
            <Sidebar />

            <div className="flex min-w-0 flex-1 flex-col">
              <TopBar />

              <div className="flex min-h-0 flex-1">
                <main className="flex-1 overflow-y-auto p-4 lg:p-6">{children}</main>
                <RightPanel />
              </div>

              <BottomStrip />
              <BottomNav />
            </div>
          </div>
        </div>
      </PrivacyProvider>
      </PanicProvider>
    </AuthGate>
  );
}
