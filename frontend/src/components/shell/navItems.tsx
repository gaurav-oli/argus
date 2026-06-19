import type { SVGProps } from "react";

/**
 * Single source of truth for the 5 primary destinations.
 * Consumed by the desktop Sidebar and the mobile BottomNav so the two
 * stay in lockstep. Icons are inline SVG (stroke-based, currentColor) —
 * no icon-library dependency, fully offline.
 */

type IconProps = SVGProps<SVGSVGElement>;

function baseIcon(props: IconProps) {
  return {
    width: 22,
    height: 22,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.75,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    ...props,
  };
}

function HomeIcon(props: IconProps) {
  return (
    <svg {...baseIcon(props)}>
      <path d="M3 10.5 12 3l9 7.5" />
      <path d="M5 9.5V21h14V9.5" />
      <path d="M9.5 21v-6h5v6" />
    </svg>
  );
}

function PortfolioIcon(props: IconProps) {
  return (
    <svg {...baseIcon(props)}>
      <path d="M3 3v18h18" />
      <path d="M7 14l3-4 3 3 5-7" />
    </svg>
  );
}

function IntelligenceIcon(props: IconProps) {
  return (
    <svg {...baseIcon(props)}>
      <circle cx="11" cy="11" r="7" />
      <path d="m21 21-4.5-4.5" />
    </svg>
  );
}

function AgentsIcon(props: IconProps) {
  return (
    <svg {...baseIcon(props)}>
      <rect x="4" y="8" width="16" height="11" rx="2" />
      <path d="M12 8V4" />
      <circle cx="12" cy="3" r="1" />
      <path d="M9 13v2M15 13v2" />
    </svg>
  );
}

function ProfileIcon(props: IconProps) {
  return (
    <svg {...baseIcon(props)}>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 20c0-4 3.5-6 8-6s8 2 8 6" />
    </svg>
  );
}

export type NavItem = {
  label: string;
  href: string;
  Icon: (props: IconProps) => React.JSX.Element;
};

export const navItems: NavItem[] = [
  { label: "Home", href: "/", Icon: HomeIcon },
  { label: "Portfolio", href: "/portfolio", Icon: PortfolioIcon },
  { label: "Intelligence", href: "/intelligence", Icon: IntelligenceIcon },
  { label: "Agents", href: "/agents", Icon: AgentsIcon },
  { label: "Profile", href: "/profile", Icon: ProfileIcon },
];

/** Active when the path matches exactly, or is a sub-route (non-root). */
export function isActive(pathname: string, href: string): boolean {
  if (href === "/") return pathname === "/";
  return pathname === href || pathname.startsWith(`${href}/`);
}
