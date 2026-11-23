// Validation and conflict detection shared by the API (the UI mirrors it).
export const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const TIME = /^([01]\d|2[0-3]):(00|30)$/;
const ROOM = /^[A-Za-z0-9][A-Za-z0-9 .\-]{0,23}$/;

export function emptySchedule() {
  return Object.fromEntries(DAYS.map((d) => [d, { enabled: false, start: '09:00', end: '11:00', rooms: [] }]));
}

const minutes = (t) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3));

/** Returns { schedule } normalised, or { error }. */
export function validateSchedule(input) {
  if (!input || typeof input !== 'object') return { error: 'Schedule is missing.' };
  const out = {};
  for (const day of DAYS) {
    const d = input[day] || {};
    const enabled = Boolean(d.enabled);
    const start = String(d.start || '09:00');
    const end = String(d.end || '11:00');
    const label = day[0] + day.slice(1).toLowerCase();
    if (!TIME.test(start) || !TIME.test(end)) return { error: `${label}: times must be on the hour or half hour.` };
    if (minutes(end) <= minutes(start)) return { error: `${label}: end time must be after the start time.` };
    const seen = new Set();
    const rooms = [];
    for (const r of Array.isArray(d.rooms) ? d.rooms : []) {
      const room = String(r).trim();
      if (!room) continue;
      if (!ROOM.test(room)) return { error: `${label}: "${room}" doesn't look like a room number.` };
      if (seen.has(room.toUpperCase())) continue;
      seen.add(room.toUpperCase());
      rooms.push(room);
    }
    if (rooms.length > 5) return { error: `${label}: up to 5 rooms per day.` };
    if (enabled && rooms.length === 0) return { error: `${label}: add at least one room, or turn the day off.` };
    out[day] = { enabled, start, end, rooms };
  }
  return { schedule: out };
}

/** Rooms other active profiles have claimed, without anything private. */
export function claimsOf(profiles, excludeId) {
  // Profiles are booked in this (creation) order, so earlier ones get first pick.
  const myIndex = profiles.findIndex((p) => p.id === excludeId);
  const claims = [];
  for (const [index, p] of profiles.entries()) {
    if (p.id === excludeId || p.paused) continue;
    for (const day of DAYS) {
      const d = p.schedule?.[day];
      if (d?.enabled && d.rooms?.length) {
        claims.push({ profileName: p.name, day, start: d.start, end: d.end, rooms: d.rooms, runsFirst: index < myIndex });
      }
    }
  }
  return claims;
}

/** Overlapping same-room claims between `schedule` and others' `claims`. */
export function findConflicts(schedule, claims) {
  const conflicts = [];
  for (const day of DAYS) {
    const mine = schedule[day];
    if (!mine?.enabled) continue;
    for (const c of claims) {
      if (c.day !== day) continue;
      if (!(minutes(mine.start) < minutes(c.end) && minutes(c.start) < minutes(mine.end))) continue;
      mine.rooms.forEach((room, myRank) => {
        const theirRank = c.rooms.findIndex((r) => r.toUpperCase() === room.toUpperCase());
        if (theirRank >= 0) {
          conflicts.push({ day, room, myRank, theirRank, profileName: c.profileName, start: c.start, end: c.end, runsFirst: c.runsFirst });
        }
      });
    }
  }
  return conflicts;
}
