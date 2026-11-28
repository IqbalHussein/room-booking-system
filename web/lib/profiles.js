import { DAYS, blocksOf, upgradeSchedule } from './schedule.js';
import { findRoom, closestRoom } from '../public/rooms.js';

/** What a signed-in person sees of their own profile. The password never leaves the server. */
export function toClient(p) {
  return {
    id: p.id,
    name: p.name,
    username: cleanUsername(p.credentials?.username),
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
    const blocks = d?.enabled ? blocksOf(d).map(withRealRooms).filter((b) => b.rooms.length) : [];
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
 * A saved room that isn't bookable (e.g. "416") is swapped for the closest real
 * one on the same floor ("420") until the profile is fixed, rather than failing
 * the booking. Valid rooms keep their order; duplicates are dropped.
 */
export function withRealRooms(block) {
  const rooms = [];
  const listed = (block.rooms || []).map((r) => findRoom(r)?.id).filter(Boolean);
  for (const r of block.rooms || []) {
    const id = findRoom(r)?.id || closestRoom(r, [...listed, ...rooms]);
    if (id && !rooms.includes(id)) rooms.push(id);
  }
  return { ...block, rooms };
}

export function cleanName(name) {
  const n = String(name ?? '').trim().replace(/\s+/g, ' ');
  return n.length >= 1 && n.length <= 24 ? n : null;
}
