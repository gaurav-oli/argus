"use client";

import { getAuthStatus } from "@/lib/apiClient";
import { useEffect, useState } from "react";
import { HomeHeader } from "./HomeHeader";

/** "Good morning"/"afternoon"/"evening" by the viewer's own local clock — not fixed to any one
 * person's timezone or time of day. */
function timeOfDayGreeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) return "Good morning";
  if (hour < 18) return "Good afternoon";
  return "Good evening";
}

/**
 * The Home page's greeting, personalized to whoever is actually signed in (Phase 2, multi-user) —
 * this used to be a literal hardcoded "Good morning, Gaurav" from back when Argus was single-user,
 * which meant every friend who signed in saw the admin's own name instead of their own. Client-side
 * (the page itself is a server component) since the signed-in person is only known via the session
 * cookie. Starts with no name rather than flashing the wrong one while it loads.
 */
export function HomeGreeting() {
  const [firstName, setFirstName] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getAuthStatus()
      .then((status) => {
        if (!active || !status.user?.name) return;
        setFirstName(status.user.name.split(" ")[0]);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  const greeting = timeOfDayGreeting();
  return (
    <HomeHeader
      eyebrow="Overview"
      title={firstName ? `${greeting}, ${firstName}` : greeting}
      subtitle="Here's how your book is doing today."
    />
  );
}
