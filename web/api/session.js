import { BlobPreconditionFailedError } from '@vercel/blob';
import { handler, method, send, body } from '../lib/http.js';
import { loadProfileVersioned, saveProfile } from '../lib/store.js';
import {
  checkPasscode, isPasscode, issueSession, lockRemaining, registerFailure, registerSuccess,
} from '../lib/auth.js';
import { toClient } from '../lib/profiles.js';

const lockedResponse = (res, ms) =>
  send(res, 423, { error: 'Too many wrong tries.', lockedForSeconds: Math.ceil(ms / 1000) });

export default handler(async (req, res) => {
  if (!method(req, res, ['POST'])) return;
  const b = body(req);
  const loaded = await loadProfileVersioned(b.profileId);
  if (!loaded) return send(res, 404, { error: 'That profile no longer exists.' });
  const { profile, etag } = loaded;

  const locked = lockRemaining(profile);
  if (locked > 0) return lockedResponse(res, locked);

  // Record the attempt as a failure *before* checking the passcode, with a
  // conditional write. Parallel guesses then can't all read the same counter:
  // only one wins the write, the rest are turned away unchecked.
  const left = registerFailure(profile);
  try {
    await saveProfile(profile, { ifMatch: etag });
  } catch (err) {
    if (err instanceof BlobPreconditionFailedError) {
      return send(res, 429, { error: 'Too many attempts at once. Wait a second and try again.' });
    }
    throw err;
  }

  if (!isPasscode(b.passcode) || !checkPasscode(profile, b.passcode)) {
    const nowLocked = lockRemaining(profile);
    if (nowLocked > 0) return lockedResponse(res, nowLocked);
    return send(res, 401, { error: 'Wrong passcode.', triesLeft: left });
  }

  registerSuccess(profile);
  await saveProfile(profile);
  return send(res, 200, { token: issueSession(profile), profile: toClient(profile) });
});
