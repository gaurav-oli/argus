import { ThemeToggle } from "@/components/theme/ThemeToggle";
import { MotionCard } from "@/components/ui/MotionCard";
import { PageHeader } from "@/components/ui/PageHeader";
import { LogoutButton } from "@/features/auth/LogoutButton";
import { PasskeyManager } from "@/features/auth/PasskeyManager";
import { SessionManager } from "@/features/auth/SessionManager";
import { SessionTimeoutSetting } from "@/features/auth/SessionTimeoutSetting";
import { NotificationsSetting } from "@/features/notifications/NotificationsSetting";
import { InvestorProfileSetting } from "@/features/profile/InvestorProfileSetting";
import { PanicSettings } from "@/features/panic/PanicSettings";

export default function ProfilePage() {
  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader eyebrow="Account" title="Profile" subtitle="Appearance, security, sessions, and safety controls." />

      <div className="flex flex-col gap-4">
        <SettingsCard
          index={0}
          title="Appearance"
          desc="Switch between the dark and light themes."
          icon={
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="4" />
              <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
            </svg>
          }
        >
          <div className="flex items-center justify-between">
            <span className="text-sm text-text-secondary">Theme</span>
            <ThemeToggle />
          </div>
        </SettingsCard>

        <SettingsCard
          index={1}
          title="Security"
          desc="Session timeout and biometric unlock."
          icon={
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            </svg>
          }
        >
          <div className="flex flex-col gap-6">
            <SessionTimeoutSetting />
            <div>
              <h3 className="mb-3 text-sm font-medium text-text-primary">Biometric unlock</h3>
              <PasskeyManager />
            </div>
          </div>
        </SettingsCard>

        <SettingsCard
          index={2}
          title="Notifications"
          desc="Push alerts and your morning briefing."
          icon={
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
              <path d="M13.7 21a2 2 0 0 1-3.4 0" />
            </svg>
          }
        >
          <NotificationsSetting />
        </SettingsCard>

        <SettingsCard
          index={3}
          title="Investor profile"
          desc="Risk tolerance, goals, and preferences that ground Ask-AI and the personas."
          icon={
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
              <circle cx="12" cy="7" r="4" />
            </svg>
          }
        >
          <InvestorProfileSetting />
        </SettingsCard>

        <SettingsCard
          index={4}
          title="Safety"
          desc="Panic mode and emergency controls."
          icon={
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
              <path d="M12 9v4M12 17h.01" />
            </svg>
          }
        >
          <PanicSettings />
        </SettingsCard>

        <SettingsCard
          index={5}
          title="Active sessions"
          desc="Devices currently signed in to Argus."
          icon={
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <rect x="2" y="3" width="20" height="14" rx="2" />
              <path d="M8 21h8M12 17v4" />
            </svg>
          }
        >
          <SessionManager />
        </SettingsCard>

        <div className="mt-2 flex justify-end">
          <LogoutButton />
        </div>
      </div>
    </div>
  );
}

function SettingsCard({
  index,
  title,
  desc,
  icon,
  children,
}: {
  index: number;
  title: string;
  desc?: string;
  icon: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <MotionCard index={index} interactive={false} className="p-6">
      <div className="mb-5 flex items-start gap-3">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-accent/30 bg-accent/[0.08] text-accent [&>svg]:h-5 [&>svg]:w-5">
          {icon}
        </span>
        <div>
          <h2 className="font-display text-base font-semibold text-text-primary">{title}</h2>
          {desc && <p className="mt-0.5 text-xs text-text-secondary">{desc}</p>}
        </div>
      </div>
      {children}
    </MotionCard>
  );
}
