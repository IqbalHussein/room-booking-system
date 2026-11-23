import { DAYS, blocksOf, upgradeSchedule } from './schedule.js';

/** What a signed-in person sees of their own profile. The password never leaves the server. */
export function toClient(p) {
  return {
    id: p.id,
    name: p.name,
    username: p.credentials?.username || '',
    hasPassword: Boolean(p.credentials?.password),
    paused: Boolean(p.paused),
    schedule: upgradeSchedule(p.schedule),
    lastResults: p.lastResults || [],
  };
}

/** What the nightly job needs: only active days of profiles that aren't paused. */
export function toExport(p) {
  const schedule = {};
  for (const day of DAYS) {
    const d = p.schedule?.[day];
    const blocks = d?.enabled ? blocksOf(d).filter((b) => b.rooms?.length) : [];
    if (blocks.length) schedule[day] = { blocks };
  }
  return {
    id: p.id,
    name: p.name,
    username: p.credentials?.username || '',
    password: p.credentials?.password || '',
    schedule,
  };
}

export function cleanName(name) {
  const n = String(name ?? '').trim().replace(/\s+/g, ' ');
  return n.length >= 1 && n.length <= 24 ? n : null;
}
