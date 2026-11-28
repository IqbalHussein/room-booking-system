import { DAYS, blocksOf, upgradeSchedule } from './schedule.js';
import { resolveRooms } from '../public/rooms.js';

/** What a signed-in person sees of their own profile. The password never leaves the server. */
export function toClient(p) {
  return {
    id: p.id,
    name: p.name,
    username: cleanUsername(p.credentials?.username),
    hasPassword: Boolean(p.credentials?.password),
    paused: Boolean(p.paused),
    autoBackups: p.autoBackups !== false,
    schedule: upgradeSchedule(p.schedule),
    lastResults: p.lastResults || [],
  };
}

/** What the nightly job needs: only active days of profiles that aren't paused. */
export function toExport(p) {
  const schedule = {};
  for (const day of DAYS) {
    const d = p.schedule?.[day];
    const blocks = d?.enabled ? blocksOf(d).map((b) => withRealRooms(b, p.autoBackups !== false)).filter((b) => b.rooms.length) : [];
    if (blocks.length) schedule[day] = { blocks };
  }
  return {
    id: p.id,
    name: p.name,
    username: cleanUsername(p.credentials?.username),
    password: p.credentials?.password || '',
    schedule,
    // Dates an earlier run already booked, so a rerun never books a second room.
    bookedDates: [...new Set((p.lastResults || []).filter((r) => r.status === 'SUCCESS' && r.targetDate).map((r) => r.targetDate))],
  };
}

/**
 * Carleton's sign-in page wants the bare username ("jsmith"), not the email
 * address ("jsmith@cmail.carleton.ca"); with the email it rejects the login.
 */
export function cleanUsername(username) {
  return String(username ?? '').trim().replace(/@(cmail\.)?carleton\.ca$/i, '');
}

/**
 * The rooms the booker actually tries for a block: saved rooms that aren't
 * bookable swapped for the closest real one, then (unless turned off) the
 * closest rooms to the first choice as automatic backups. See resolveRooms.
 */
export function withRealRooms(block, autoBackups = true) {
  return { ...block, rooms: resolveRooms(block.rooms || [], autoBackups).rooms };
}

export function cleanName(name) {
  const n = String(name ?? '').trim().replace(/\s+/g, ' ');
  return n.length >= 1 && n.length <= 24 ? n : null;
}
