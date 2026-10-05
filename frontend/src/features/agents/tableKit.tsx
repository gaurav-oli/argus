"use client";

import { useState } from "react";
import { compareBy, type SortDir } from "@/lib/compareBy";
import { cn } from "@/lib/utils";

export { compareBy, type SortDir };

/**
 * Small terminal-styled building blocks for the Agents page's long tables (the Investor's book and
 * the Trade Journal): filter chips, sortable column headers and a pager. They keep every row
 * reachable while showing a page at a time, so long histories stop pushing the page down.
 */

export type Sort<K extends string> = { key: K; dir: SortDir };

/** Toggles direction when the same column is clicked again; a new column starts descending. */
export function useSort<K extends string>(initial: Sort<K>) {
  const [sort, setSort] = useState<Sort<K>>(initial);
  const toggle = (key: K) =>
    setSort((s) => (s.key === key ? { key, dir: s.dir === "asc" ? "desc" : "asc" } : { key, dir: "desc" }));
  return [sort, toggle] as const;
}

export function SortHeader<K extends string>({
  label,
  k,
  sort,
  onSort,
  className,
}: {
  label: string;
  k: K;
  sort: Sort<K>;
  onSort: (k: K) => void;
  className?: string;
}) {
  const active = sort.key === k;
  return (
    <th scope="col" aria-sort={active ? (sort.dir === "asc" ? "ascending" : "descending") : "none"} className={cn("py-1.5 font-normal", className)}>
      <button
        type="button"
        onClick={() => onSort(k)}
        className={cn("uppercase tracking-wider hover:text-accent", active ? "text-accent" : "text-text-secondary")}
      >
        {label}
        <span aria-hidden className="ml-0.5 inline-block w-2">
          {active ? (sort.dir === "asc" ? "▲" : "▼") : ""}
        </span>
      </button>
    </th>
  );
}

export function FilterChips<V extends string>({
  label,
  options,
  value,
  onChange,
}: {
  label: string;
  options: { value: V; label: string; count?: number }[];
  value: V;
  onChange: (v: V) => void;
}) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap items-center gap-1">
      {options.map((o) => {
        const on = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            aria-pressed={on}
            onClick={() => onChange(o.value)}
            className={cn(
              "border px-2 py-1 font-mono text-[10px] uppercase tracking-wider transition-colors",
              on ? "border-accent bg-accent text-background" : "border-[var(--glass-border)] text-text-secondary hover:border-accent hover:text-accent",
            )}
          >
            {o.label}
            {o.count != null && <span className={cn("ml-1", on ? "opacity-70" : "opacity-60")}>{o.count}</span>}
          </button>
        );
      })}
    </div>
  );
}

/** Page through `rows`; resets to the first page whenever the filtered row count changes. */
export function usePaged<T>(rows: T[], pageSize: number) {
  const [state, setState] = useState({ page: 0, count: rows.length });
  const page = state.count === rows.length ? state.page : 0;
  const pages = Math.max(1, Math.ceil(rows.length / pageSize));
  const clamped = Math.min(page, pages - 1);
  const go = (p: number) => setState({ page: Math.max(0, Math.min(pages - 1, p)), count: rows.length });
  return {
    rows: rows.slice(clamped * pageSize, clamped * pageSize + pageSize),
    page: clamped,
    pages,
    from: rows.length === 0 ? 0 : clamped * pageSize + 1,
    to: Math.min(rows.length, clamped * pageSize + pageSize),
    total: rows.length,
    go,
  };
}

export function Pager({ p }: { p: ReturnType<typeof usePaged<unknown>> }) {
  if (p.pages <= 1) {
    return p.total > 0 ? <p className="mt-2 font-mono text-[10px] text-text-secondary">{p.total} rows</p> : null;
  }
  return (
    <div className="mt-2 flex items-center justify-between font-mono text-[10px] text-text-secondary">
      <span>
        rows {p.from}–{p.to} of {p.total}
      </span>
      <span className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => p.go(p.page - 1)}
          disabled={p.page === 0}
          aria-label="Previous page"
          className="border border-[var(--glass-border)] px-2 py-0.5 hover:border-accent hover:text-accent disabled:opacity-30"
        >
          ◀
        </button>
        <span className="px-1">
          {p.page + 1}/{p.pages}
        </span>
        <button
          type="button"
          onClick={() => p.go(p.page + 1)}
          disabled={p.page >= p.pages - 1}
          aria-label="Next page"
          className="border border-[var(--glass-border)] px-2 py-0.5 hover:border-accent hover:text-accent disabled:opacity-30"
        >
          ▶
        </button>
      </span>
    </div>
  );
}
