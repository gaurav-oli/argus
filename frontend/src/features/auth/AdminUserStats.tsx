"use client";

import { MotionCard } from "@/components/ui/MotionCard";
import {
  getAdminInvites,
  getAdminUserStats,
  sendInviteEmail,
  type AdminInvite,
  type AdminUserStats,
} from "@/lib/apiClient";
import { useCallback, useEffect, useState } from "react";

/** Relative "time ago", or "never". */
function relTime(iso: string | null): string {
  if (!iso) return "never";
  const ms = Date.now() - new Date(iso).getTime();
  const m = Math.floor(ms / 60000);
  if (m < 1) return "just now";
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  return `${d}d ago`;
}

function minutes(total: number): string {
  if (total < 60) return `${total}m`;
  const h = Math.floor(total / 60);
  const m = total % 60;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}

/**
 * Engagement stats for everyone on this Argus instance — who's signed up, how often they show up,
 * and roughly how long they stick around. Deliberately no portfolio or dollar figures: that stays
 * private to each person, even from the admin. Renders its own card chrome (title included) and
 * nothing at all for anyone but the admin — self-gated by the backend's 403, so a friend never even
 * sees that this section exists, not just an empty one. Safe to drop into the Profile page
 * unconditionally.
 */
export function AdminUserStats({ index }: { index: number }) {
  const [rows, setRows] = useState<AdminUserStats[] | null | undefined>(undefined);
  const [invites, setInvites] = useState<AdminInvite[]>([]);
  const [newEmail, setNewEmail] = useState("");
  const [sendingFor, setSendingFor] = useState<string | null>(null);
  const [errorFor, setErrorFor] = useState<Record<string, string>>({});

  const refetchInvites = useCallback(() => {
    getAdminInvites()
      .then(setInvites)
      .catch(() => {});
  }, []);

  useEffect(() => {
    let active = true;
    getAdminUserStats()
      .then((v) => active && setRows(v))
      .catch(() => {
        // 403 = not an admin — this section simply doesn't exist for them. Any other failure
        // (network blip) also just hides it rather than showing a scary error in a settings page.
        if (active) setRows(null);
      });
    refetchInvites();
    return () => {
      active = false;
    };
  }, [refetchInvites]);

  const send = async (email: string) => {
    setSendingFor(email);
    setErrorFor((prev) => ({ ...prev, [email]: "" }));
    try {
      await sendInviteEmail(email);
      setNewEmail("");
      refetchInvites();
    } catch {
      setErrorFor((prev) => ({ ...prev, [email]: "Couldn't send — check the email and try again." }));
    } finally {
      setSendingFor(null);
    }
  };

  const onInvite = (e: React.FormEvent) => {
    e.preventDefault();
    const email = newEmail.trim();
    if (email) void send(email);
  };

  if (!rows || rows.length === 0) {
    return null;
  }

  return (
    <MotionCard index={index} interactive={false} className="p-6">
      <div className="mb-5 flex items-start gap-3">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-accent/30 bg-accent/[0.08] text-accent [&>svg]:h-5 [&>svg]:w-5">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
            <circle cx="9" cy="7" r="4" />
            <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" />
          </svg>
        </span>
        <div>
          <h2 className="font-display text-base font-semibold text-text-primary">People on Argus</h2>
          <p className="mt-0.5 text-xs text-text-secondary">Admin only — who&apos;s signed up and how they&apos;re using it. Never their portfolio.</p>
        </div>
      </div>
      <div className="overflow-x-auto">
      <table className="w-full text-left text-xs">
        <thead>
          <tr className="text-text-secondary">
            <th className="py-1.5 pr-3 font-medium">Person</th>
            <th className="py-1.5 pr-3 font-medium">Joined</th>
            <th className="py-1.5 pr-3 font-medium">Logins</th>
            <th className="py-1.5 pr-3 font-medium">Active days</th>
            <th className="py-1.5 pr-3 font-medium">Time on platform</th>
            <th className="py-1.5 pr-3 font-medium">Last seen</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.email} className="border-t border-border/60">
              <td className="py-1.5 pr-3">
                <div className="flex items-center gap-2">
                  {r.pictureUrl ? (
                    // eslint-disable-next-line @next/next/no-img-element -- external Google avatar, not a local asset
                    <img src={r.pictureUrl} alt="" className="h-5 w-5 rounded-full" referrerPolicy="no-referrer" />
                  ) : (
                    <span className="flex h-5 w-5 items-center justify-center rounded-full bg-accent/15 text-[9px] font-semibold text-accent">
                      {r.name.slice(0, 1).toUpperCase()}
                    </span>
                  )}
                  <span className="font-medium text-text-primary">{r.name}</span>
                  {r.admin && (
                    <span className="rounded bg-accent/15 px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide text-accent">
                      admin
                    </span>
                  )}
                </div>
              </td>
              <td className="py-1.5 pr-3 text-text-secondary">{new Date(r.joinedAt).toLocaleDateString()}</td>
              <td className="py-1.5 pr-3 tabular-nums text-text-secondary">{r.loginCount}</td>
              <td className="py-1.5 pr-3 tabular-nums text-text-secondary">{r.activeDays}</td>
              <td className="py-1.5 pr-3 tabular-nums text-text-secondary">{minutes(r.totalActiveMinutes)}</td>
              <td className="py-1.5 pr-3 text-text-secondary">{relTime(r.lastActiveAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="mt-2 text-[11px] text-text-secondary">
        &quot;Time on platform&quot; is the span between each day&apos;s first and last activity — a rough proxy, not a claim of
        continuous attention.
      </p>
      </div>

      <div className="mt-5 border-t border-border/60 pt-4">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Invite a friend</h3>
        <p className="mt-1 text-[11px] text-text-secondary">Sends a real email with their own sign-in link.</p>
        <form onSubmit={onInvite} className="mt-2 flex items-center gap-2">
          <input
            type="email"
            required
            placeholder="friend@gmail.com"
            value={newEmail}
            onChange={(e) => setNewEmail(e.target.value)}
            className="flex-1 rounded-lg border border-border/60 bg-background px-3 py-1.5 text-xs text-text-primary outline-none focus:border-accent/60"
          />
          <button
            type="submit"
            disabled={sendingFor !== null || !newEmail.trim()}
            className="shrink-0 rounded-lg bg-accent px-3 py-1.5 text-xs font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
          >
            {sendingFor === newEmail.trim() ? "Sending…" : "Send invite"}
          </button>
        </form>

        {invites.length > 0 && (
          <ul className="mt-3 space-y-2">
            {invites.map((i) => (
              <li key={i.email} className="flex flex-col gap-1 rounded-lg bg-[var(--hover-wash)] px-3 py-2 text-xs">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-text-primary">{i.email}</span>
                  <InviteStatus invite={i} />
                </div>
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[11px] text-text-secondary">
                    {i.emailSentAt
                      ? `Sent ${relTime(i.emailSentAt)}${i.openedAt ? ` · opened ${relTime(i.openedAt)}` : ""}`
                      : "Not sent yet"}
                  </span>
                  {!i.joined && (
                    <button
                      type="button"
                      onClick={() => void send(i.email)}
                      disabled={sendingFor === i.email}
                      className="shrink-0 text-[11px] font-medium text-accent transition hover:opacity-80 disabled:opacity-50"
                    >
                      {sendingFor === i.email ? "Sending…" : i.emailSentAt ? "Resend" : "Send"}
                    </button>
                  )}
                </div>
                {errorFor[i.email] && <p className="text-[11px] text-losses">{errorFor[i.email]}</p>}
              </li>
            ))}
          </ul>
        )}
      </div>
    </MotionCard>
  );
}

/** joined (signed in) beats opened (clicked the link) beats sent (emailed) beats invited-only. */
function InviteStatus({ invite }: { invite: AdminInvite }) {
  if (invite.joined) {
    return <span className="rounded bg-gains/15 px-1.5 py-0.5 text-[10px] font-semibold text-gains">Joined</span>;
  }
  if (invite.openedAt) {
    return <span className="rounded bg-accent/15 px-1.5 py-0.5 text-[10px] font-semibold text-accent">Opened</span>;
  }
  if (invite.emailSentAt) {
    return (
      <span className="rounded bg-border/60 px-1.5 py-0.5 text-[10px] font-semibold text-text-secondary">
        Sent, not opened
      </span>
    );
  }
  return (
    <span className="rounded bg-border/60 px-1.5 py-0.5 text-[10px] font-semibold text-text-secondary">
      Not sent
    </span>
  );
}
