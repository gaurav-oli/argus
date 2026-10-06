"use client";

import { MotionCard } from "@/components/ui/MotionCard";
import {
  deleteUser,
  getAdminInvites,
  getAdminUserStats,
  removeInvite,
  restoreUser,
  revokeUser,
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

  /** After a revoke/restore/delete/remove: both lists change (status badges, a row disappearing). */
  const refetchAll = useCallback(() => {
    getAdminUserStats()
      .then(setRows)
      .catch(() => {});
    refetchInvites();
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
  const adminEmails = new Set(rows.filter((r) => r.admin).map((r) => r.email.toLowerCase()));

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
                  <span className={`font-medium ${r.revokedAt ? "text-text-secondary line-through" : "text-text-primary"}`}>{r.name}</span>
                  {r.admin && (
                    <span className="rounded bg-accent/15 px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide text-accent">
                      admin
                    </span>
                  )}
                  {r.revokedAt && (
                    <span className="rounded bg-losses/15 px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide text-losses">
                      revoked
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
        <p className="mt-1 text-[11px] text-text-secondary">
          Sends a real email with their own sign-in link. Use Revoke to lock someone out (their data is kept and you can
          restore them), or Delete to remove them and everything of theirs for good.
        </p>
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
                    <span className="flex shrink-0 items-center gap-3">
                      <button
                        type="button"
                        onClick={() => void send(i.email)}
                        disabled={sendingFor === i.email}
                        className="text-[11px] font-medium text-accent transition hover:opacity-80 disabled:opacity-50"
                      >
                        {sendingFor === i.email ? "Sending…" : i.emailSentAt ? "Resend" : "Send"}
                      </button>
                    </span>
                  )}
                </div>
                {!adminEmails.has(i.email.toLowerCase()) && <AccessActions invite={i} onChanged={refetchAll} />}
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
  if (invite.revoked) {
    return <span className="rounded bg-losses/15 px-1.5 py-0.5 text-[10px] font-semibold text-losses">Revoked</span>;
  }
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

type Pending = "idle" | "revoke" | "delete" | "remove";

/**
 * Per-person access controls in the invite list. Someone who hasn't joined can have their invite
 * withdrawn; someone who has can be revoked (locked out, data kept, restorable) or deleted (account
 * and every row of their private data, for good). Each destructive action asks first; delete makes
 * the admin type the person's email, since there is no undo.
 */
function AccessActions({ invite, onChanged }: { invite: AdminInvite; onChanged: () => void }) {
  const [pending, setPending] = useState<Pending>("idle");
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError("");
    try {
      await action();
      setPending("idle");
      setTyped("");
      onChanged();
    } catch {
      setError("That didn't work — try again.");
    } finally {
      setBusy(false);
    }
  };

  const linkCls = "text-[11px] font-medium transition hover:opacity-80 disabled:opacity-50";
  const cancel = (
    <button type="button" onClick={() => { setPending("idle"); setTyped(""); setError(""); }} disabled={busy} className={`${linkCls} text-text-secondary`}>
      Cancel
    </button>
  );

  if (pending === "remove") {
    return (
      <div className="flex flex-wrap items-center gap-3 text-[11px]">
        <span className="text-text-secondary">Withdraw this invite? Their link will stop working.</span>
        <button type="button" onClick={() => void run(() => removeInvite(invite.email))} disabled={busy} className={`${linkCls} text-losses`}>
          {busy ? "Removing…" : "Remove invite"}
        </button>
        {cancel}
        {error && <span className="text-losses">{error}</span>}
      </div>
    );
  }

  if (pending === "revoke") {
    return (
      <div className="flex flex-wrap items-center gap-3 text-[11px]">
        <span className="text-text-secondary">Sign them out everywhere and block sign-in? Their data is kept.</span>
        <button type="button" onClick={() => void run(() => revokeUser(invite.email))} disabled={busy} className={`${linkCls} text-warning`}>
          {busy ? "Revoking…" : "Revoke access"}
        </button>
        {cancel}
        {error && <span className="text-losses">{error}</span>}
      </div>
    );
  }

  if (pending === "delete") {
    const matches = typed.trim().toLowerCase() === invite.email.toLowerCase();
    return (
      <div className="flex flex-col gap-1.5 rounded-md border border-losses/40 bg-losses/[0.06] p-2 text-[11px]">
        <span className="text-text-primary">
          Permanently delete {invite.email}? This erases their account, portfolio, statements, briefings and history.
          It can&apos;t be undone.
        </span>
        <label className="flex flex-wrap items-center gap-2">
          <span className="text-text-secondary">Type their email to confirm:</span>
          <input
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            placeholder={invite.email}
            autoComplete="off"
            spellCheck={false}
            className="min-w-0 flex-1 rounded border border-border/60 bg-background px-2 py-1 text-[11px] text-text-primary outline-none focus:border-losses/60"
          />
        </label>
        <div className="flex items-center gap-3">
          <button type="button" onClick={() => void run(() => deleteUser(invite.email))} disabled={busy || !matches} className={`${linkCls} text-losses`}>
            {busy ? "Deleting…" : "Delete permanently"}
          </button>
          {cancel}
          {error && <span className="text-losses">{error}</span>}
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-wrap items-center gap-3">
      {!invite.joined ? (
        <button type="button" onClick={() => setPending("remove")} className={`${linkCls} text-text-secondary hover:text-losses`}>
          Remove invite
        </button>
      ) : (
        <>
          {invite.revoked ? (
            <button type="button" onClick={() => void run(() => restoreUser(invite.email))} disabled={busy} className={`${linkCls} text-accent`}>
              {busy ? "Restoring…" : "Restore access"}
            </button>
          ) : (
            <button type="button" onClick={() => setPending("revoke")} className={`${linkCls} text-warning`}>
              Revoke access
            </button>
          )}
          <button type="button" onClick={() => setPending("delete")} className={`${linkCls} text-losses`}>
            Delete user
          </button>
        </>
      )}
      {error && <span className="text-[11px] text-losses">{error}</span>}
    </div>
  );
}
