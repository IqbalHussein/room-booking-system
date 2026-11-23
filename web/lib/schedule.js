// Schedule shape, validation and conflict detection. public/app.js mirrors the
// rules client-side; this file is the authority.
//
// schedule = { MONDAY: { enabled, blocks: [{ start: "HH:MM", end: "HH:MM", rooms: [...] }] }, ... }
// A day can hold several blocks (e.g. two 90-minute sessions), but LibCal caps
// one account at DAILY_MAX_MINUTES per day across all of them.
export const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
export const DAILY_MAX_MINUTES = 180;
export const MAX_BLOCKS = 4;
export const MAX_ROOMS = 5;
const TIME = /^([01]\d|2[0-3]):(00|30)$/;
const ROOM = /^[A-Za-z0-9][A-Za-z0-9 .\-]{0,23}$/;

export const toMin = (t) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3));
const label = (day) => day[0] + day.slice(1).toLowerCase();

export function emptySchedule() {
  return Object.fromEntries(DAYS.map((d) => [d, { enabled: false, blocks: [{ start: '09:00', end: '11:00', rooms: [] }] }]));
}

/** Accepts the current shape or the older single-window one ({start, end, rooms}). */
export function blocksOf(day) {
  if (!day) return [];
  if (Array.isArray(day.blocks)) return day.blocks;
  if (day.start) return [{ start: day.start, end: day.end, rooms: day.rooms || [] }];
  return [];
}

/** Returns { schedule } normalised, or { error }. */
export function validateSchedule(input) {
  if (!input || typeof input !== 'object') return { error: 'Schedule is missing.' };
  const out = {};
  for (const day of DAYS) {
    const d = input[day] || {};
    const enabled = Boolean(d.enabled);
    const raw = blocksOf(d);
    if (raw.length > MAX_BLOCKS) return { error: `${label(day)}: up to ${MAX_BLOCKS} blocks per day.` };
    const blocks = [];
    for (const [i, b] of raw.entries()) {
      const name = raw.length > 1 ? `${label(day)} block ${i + 1}` : label(day);
      const start = String(b?.start || '');
      const end = String(b?.end || '');
      if (!TIME.test(start) || !TIME.test(end)) return { error: `${name}: times must be on the hour or half hour.` };
      if (toMin(end) <= toMin(start)) return { error: `${name}: end time must be after the start time.` };
      const seen = new Set();
      const rooms = [];
      for (const r of Array.isArray(b.rooms) ? b.rooms : []) {
        const room = String(r).trim();
        if (!room) continue;
        if (!ROOM.test(room)) return { error: `${name}: "${room}" doesn't look like a room number.` };
        if (seen.has(room.toUpperCase())) continue;
        seen.add(room.toUpperCase());
        rooms.push(room);
      }
      if (rooms.length > MAX_ROOMS) return { error: `${name}: up to ${MAX_ROOMS} rooms per block.` };
      if (enabled && rooms.length === 0) return { error: `${name}: add at least one room.` };
      blocks.push({ start, end, rooms });
    }
    blocks.sort((a, b) => toMin(a.start) - toMin(b.start));
    if (enabled) {
      if (!blocks.length) return { error: `${label(day)}: add a time block, or turn the day off.` };
      for (let i = 1; i < blocks.length; i++) {
        if (toMin(blocks[i].start) < toMin(blocks[i - 1].end)) return { error: `${label(day)}: two blocks overlap.` };
      }
      const total = blocks.reduce((s, b) => s + toMin(b.end) - toMin(b.start), 0);
      if (total > DAILY_MAX_MINUTES) {
        return { error: `${label(day)}: ${total / 60}h scheduled, but the library allows ${DAILY_MAX_MINUTES / 60}h per day.` };
      }
    }
    out[day] = { enabled, blocks: blocks.length ? blocks : emptySchedule()[day].blocks };
  }
  return { schedule: out };
}

/** Normalises a stored schedule to the current shape (for profiles saved before blocks existed). */
export function upgradeSchedule(schedule) {
  const out = {};
  for (const day of DAYS) {
    const d = schedule?.[day];
    const blocks = blocksOf(d);
    out[day] = { enabled: Boolean(d?.enabled), blocks: blocks.length ? blocks : emptySchedule()[day].blocks };
  }
  return out;
}

/** Blocks other active profiles have claimed, without anything private. */
export function claimsOf(profiles, excludeId) {
  // Profiles are booked in this (creation) order, so earlier ones get first pick.
  const myIndex = profiles.findIndex((p) => p.id === excludeId);
  const claims = [];
  for (const [index, p] of profiles.entries()) {
    if (p.id === excludeId || p.paused) continue;
    for (const day of DAYS) {
      const d = p.schedule?.[day];
      if (!d?.enabled) continue;
      for (const b of blocksOf(d)) {
        if (b.rooms?.length) {
          claims.push({ profileName: p.name, day, start: b.start, end: b.end, rooms: b.rooms, runsFirst: index < myIndex });
        }
      }
    }
  }
  return claims;
}

/** Same-room, overlapping-time clashes between `schedule` and others' `claims`. */
export function findConflicts(schedule, claims) {
  const conflicts = [];
  for (const day of DAYS) {
    const mine = schedule[day];
    if (!mine?.enabled) continue;
    blocksOf(mine).forEach((b, block) => {
      for (const c of claims) {
        if (c.day !== day) continue;
        if (!(toMin(b.start) < toMin(c.end) && toMin(c.start) < toMin(b.end))) continue;
        b.rooms.forEach((room, myRank) => {
          const theirRank = c.rooms.findIndex((r) => r.toUpperCase() === room.toUpperCase());
          if (theirRank >= 0) {
            conflicts.push({ day, block, room, myRank, theirRank, profileName: c.profileName, start: c.start, end: c.end, runsFirst: c.runsFirst });
          }
        });
      }
    });
  }
  return conflicts;
}
