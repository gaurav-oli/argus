import { Skeleton } from "@/components/ui/Skeleton";
import { LogoutButton } from "@/features/auth/LogoutButton";
import { PasskeyManager } from "@/features/auth/PasskeyManager";
import { SessionTimeoutSetting } from "@/features/auth/SessionTimeoutSetting";

export default function ProfilePage() {
  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <h1 className="text-2xl font-bold tracking-tight lg:text-3xl">Profile</h1>

      <section className="rounded-xl border border-border bg-surface p-6">
        <h2 className="mb-4 text-[11px] font-medium uppercase tracking-wide text-text-secondary">
          Settings
        </h2>
        <div className="space-y-3">
          <Skeleton className="h-4 w-1/2" />
          <Skeleton className="h-4 w-2/3" />
          <Skeleton className="h-4 w-1/3" />
        </div>
      </section>

      <section className="rounded-xl border border-border bg-surface p-6">
        <h2 className="mb-4 text-[11px] font-medium uppercase tracking-wide text-text-secondary">
          Security
        </h2>
        <div className="flex flex-col gap-6">
          <SessionTimeoutSetting />
          <div>
            <h3 className="mb-3 text-sm font-medium text-text-primary">Biometric unlock</h3>
            <PasskeyManager />
          </div>
          <LogoutButton />
        </div>
      </section>
    </div>
  );
}
