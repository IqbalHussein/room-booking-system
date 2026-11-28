// Bookable study rooms, as LibCal lists them (floors 2-5). Taken from the room
// list the booker reads on carletonu.libcal.com; update this if LibCal adds or
// removes rooms (a booking run that can't find a room logs the full list it saw).
export const ROOMS = [
  ['234A', 5], ['234B', 5], ['234C', 4], ['234D', 4],
  ['324A', 6], ['324B', 6], ['324C', 6], ['324D', 6],
  ['420', 6], ['421', 6], ['422', 6], ['423', 6], ['424', 6],
  ['462', 6], ['463', 6], ['464', 8], ['465', 6], ['466', 6], ['467', 6, 'VR'], ['468', 8], ['469', 6, 'VR'],
  ['470', 6], ['471', 6], ['472', 4], ['473', 5],
  ['504', 6], ['505', 6], ['506', 6], ['507', 6], ['508', 6],
  ['561', 4], ['562', 4], ['563', 4], ['564', 4], ['565', 4], ['566', 4], ['567', 4], ['568', 4], ['569', 4],
  ['570', 6], ['571', 6], ['572', 6], ['573', 6], ['574', 6],
].map(([id, capacity, note]) => ({ id, capacity, note: note || '' }));

const BY_ID = new Map(ROOMS.map((r) => [r.id, r]));

/** The canonical id for what someone typed ("324d " -> "324D"), or null if it isn't a bookable room. */
export function findRoom(input) {
  return BY_ID.get(String(input ?? '').trim().toUpperCase()) || null;
}

/**
 * The real room closest to one that doesn't exist, used as a stand-in until the
 * profile is fixed: same floor first, then nearest room number (ties go to the
 * lower number), skipping rooms in `exclude`. "416" -> "420", "324E" -> "324D".
 */
export function closestRoom(input, exclude = []) {
  const typed = String(input ?? '').trim().toUpperCase();
  if (!typed || findRoom(typed)) return findRoom(typed)?.id || null;
  const skip = new Set(exclude.map((r) => String(r).trim().toUpperCase()));
  const num = (id) => parseInt(id, 10);
  const suffix = (id) => (id.match(/[A-Z]$/)?.[0] || '@').charCodeAt(0);
  const target = num(typed);
  if (Number.isNaN(target)) return null;
  const floor = typed[0];
  const pool = ROOMS.filter((r) => !skip.has(r.id));
  const sameFloor = pool.filter((r) => r.id[0] === floor);
  const candidates = sameFloor.length ? sameFloor : pool;
  return candidates
    .map((r) => ({ id: r.id, d: Math.abs(num(r.id) - target) * 100 + Math.abs(suffix(r.id) - suffix(typed)), n: num(r.id) }))
    .sort((a, b) => a.d - b.d || a.n - b.n)[0]?.id || null;
}
