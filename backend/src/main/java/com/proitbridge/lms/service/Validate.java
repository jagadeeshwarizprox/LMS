package com.proitbridge.lms.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Locale;

/**
 * The rules about what a phone number and a link are, in one place.
 *
 * Both fields took anything at all. A mentor could be saved with a name typed into the
 * phone box, and a batch could be saved with a WhatsApp link that was three letters, which
 * the browser then resolved against our own origin and dropped the learner back on the LMS
 * home page wondering where the group had gone.
 *
 * The frontend checks the same things, but a check that only exists in the browser is a
 * hint rather than a rule: the import, the API and anything written later all arrive here
 * instead. Neither field is made mandatory by this class. It only says that a value which
 * is present has to be the kind of thing it claims to be.
 */
public final class Validate {

    private Validate() {}

    /**
     * Indian mobile numbers, with or without the country code, and tolerant of the
     * spaces, dashes and brackets people paste in from a contact card. Ten digits
     * starting six through nine is the whole rule; anything shorter is a landline
     * fragment and anything longer is two numbers in one box.
     */
    public static String phone(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("91") && digits.length() == 12) digits = digits.substring(2);
        if (digits.startsWith("0") && digits.length() == 11) digits = digits.substring(1);
        if (digits.length() != 10 || digits.charAt(0) < '6') {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " should be a ten digit mobile number, with or without +91.");
        }
        return digits;
    }

    /**
     * A link has to be absolute and has to be http or https.
     *
     * A relative string is the case that caused the trouble: the browser happily resolves
     * it against wherever it is, so a typo becomes a link to us rather than a link that
     * visibly fails. Anything with a scheme we did not ask for is refused outright rather
     * than stored and clicked later.
     */
    public static String link(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        field + " should start with https://");
            }
            if (uri.getHost() == null || uri.getHost().isBlank() || !uri.getHost().contains(".")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        field + " does not look like a web address.");
            }
            return value;
        } catch (java.net.URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " is not a link. Paste the full address, starting with https://");
        }
    }
}
