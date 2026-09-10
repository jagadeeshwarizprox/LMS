/*
 * The same rules the server keeps, said early enough to be useful.
 *
 * Phone and link boxes took anything at all. A mentor could be saved with a name typed
 * into the phone field, and a batch could be saved with a WhatsApp link that was three
 * letters, which the browser then resolved against our own origin and dropped the learner
 * back on the LMS home page wondering where the group had gone.
 *
 * These are a courtesy, not the rule. Validate.java refuses the same values on the way in,
 * because a check that only exists in a browser is a check anything else can walk past.
 * The point of having it here as well is that somebody typing gets told before they press
 * a button, rather than after a round trip.
 */

/** Ten digits starting six through nine, with or without +91, however it was pasted. */
export function phoneError(raw) {
  if (!raw || !String(raw).trim()) return null
  let digits = String(raw).replace(/[^0-9]/g, '')
  if (digits.length === 12 && digits.startsWith('91')) digits = digits.slice(2)
  if (digits.length === 11 && digits.startsWith('0')) digits = digits.slice(1)
  if (digits.length !== 10 || digits[0] < '6') {
    return 'Ten digit mobile number, with or without +91.'
  }
  return null
}

/**
 * Absolute, http or https, and a host with a dot in it.
 *
 * The relative string is the case worth catching: the browser resolves it happily, so a
 * typo becomes a link back to us instead of a link that visibly fails.
 */
export function linkError(raw) {
  if (!raw || !String(raw).trim()) return null
  const value = String(raw).trim()
  let url
  try {
    url = new URL(value)
  } catch {
    return 'Paste the full address, starting with https://'
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return 'Links have to start with https://'
  }
  if (!url.hostname || !url.hostname.includes('.')) {
    return 'That does not look like a web address.'
  }
  return null
}

/** For an input: the message, or empty string, so it can go straight into the DOM. */
export const phoneHint = (v) => phoneError(v) || ''
export const linkHint = (v) => linkError(v) || ''
