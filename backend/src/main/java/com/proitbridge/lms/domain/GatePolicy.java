package com.proitbridge.lms.domain;

/**
 * Whether onboarding blocks the course, held where a document can read it.
 *
 * `Learner.modulesUnlocked()` is checked in sixteen places, including the video grant,
 * and a document cannot be given a service. Rather than thread a settings lookup through
 * every one of those call sites, the answer is cached here and refreshed whenever the
 * setting is written.
 *
 * Static mutable state is worth being uneasy about. It earns its place because there is
 * exactly one value, it is read far more often than written, and the alternative is
 * sixteen call sites that each have to remember to ask. It is deliberately not a general
 * settings cache: one flag, one meaning.
 */
public final class GatePolicy {

    /*
     * False until the settings are read, matching the setting's own default.
     *
     * This field initialised to true while `onboarding.required` defaults to false, so
     * between class load and ApplicationReadyEvent the two disagreed: any learner request
     * served in that window was gated by a rule the settings screen said was off. It is a
     * narrow window, but it is the kind that produces one unreproducible complaint after
     * a restart. The startup listener still sets the real value.
     */
    private static volatile boolean onboardingRequired = false;

    private GatePolicy() { }

    public static boolean onboardingRequired() { return onboardingRequired; }

    public static void setOnboardingRequired(boolean required) { onboardingRequired = required; }
}
