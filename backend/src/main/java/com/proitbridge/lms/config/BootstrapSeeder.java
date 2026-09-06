package com.proitbridge.lms.config;

import com.proitbridge.lms.domain.Feature;
import com.proitbridge.lms.domain.FormSection;
import com.proitbridge.lms.domain.User;
import com.proitbridge.lms.repo.FeatureRepository;
import com.proitbridge.lms.repo.FormSectionRepository;
import com.proitbridge.lms.repo.UserRepository;
import com.proitbridge.lms.service.CredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * One super admin, and nothing else.
 *
 * A fresh installation should be empty. Everything else is set up from inside the
 * product, which is the only way the catalogue ends up being the one you actually
 * teach rather than a sample somebody has to find and delete.
 *
 * The account is created once and only when the database has no users at all. After
 * that this does nothing, so restarting can never resurrect an account an admin
 * deliberately removed, and changing the environment variable cannot reset a password.
 */
@Configuration
public class BootstrapSeeder {

    private static final Logger log = LoggerFactory.getLogger(BootstrapSeeder.class);

    /**
     * Which features exist, and what each track gets by default.
     *
     * This is not sample content. The code branches on these keys, so a database
     * without them resolves every optional feature to off and a learner quietly loses
     * mocks, projects and job posts with nothing on any screen to explain why. The
     * defaults are a starting point; every one is editable under Track features.
     */
    private static final Object[][] FEATURES = {
        {"biweekly_call", "Biweekly progress call", true, false},
        {"live_sessions", "Live sessions", true, true},
        {"industry_sessions", "Industry expert sessions", true, true},
        {"doubt_clearing", "Doubt clearing", true, true},
        {"one_to_one_booking", "One to one slot booking", true, false},
        {"group_doubt", "Group doubt clearing", false, true},
        {"project_sessions", "Project sessions", true, true},
        {"jobs_referrals", "Job openings and referrals", true, true},
        {"case_studies", "Case studies", true, true},
        {"ai_teach_back", "AI teach back test", true, true},
        {"cohort_pace", "Cohort pace visibility", false, true},
        {"batch_leaderboard", "Batch leaderboard", false, true},
        {"induction_gate", "Fixed Tuesday induction gate", false, true},
        {"top10_guidance", "Top 10 learner guidance", true, true}
    };

    /**
     * Runs on every start, not only the first, and only ever adds what is missing.
     * A key added in a later release has to reach a database that already exists,
     * and an admin who has switched something off must keep it switched off.
     */
    @Bean
    ApplicationRunner ensureFeatures(FeatureRepository features) {
        return args -> {
            for (Object[] d : FEATURES) {
                String key = (String) d[0];
                if (features.findByKey(key).isPresent()) continue;
                Feature f = new Feature();
                f.setKey(key);
                f.setLabel((String) d[1]);
                f.setForPremium((Boolean) d[2]);
                f.setForBatch((Boolean) d[3]);
                features.save(f);
                log.info("Added the feature toggle {}", key);
            }
        };
    }

    /**
     * A starting form, every part of it editable.
     *
     * These are a first draft of what most organisations ask, not a rule. Rename them,
     * reorder them, make one optional or turn it off; the gate only counts the ones
     * still marked required.
     */
    private static final Object[][] FORM = {
        {"basic", "About you", "Name, contact and where you are based."},
        {"education", "Education", "What you studied and where."},
        {"professional", "Work", "Current or most recent role, and how long."},
        {"technical", "What you already know", "Tools and languages, honestly rated."},
        {"projects", "Anything you have built", "Even small things count."},
        {"resume", "Resume", "Upload the latest one."},
        {"intent", "What you want from this", "The role you are aiming at."},
        {"links", "Links", "Portfolio, repository, professional profile."}
    };

    @Bean
    ApplicationRunner ensureFormSections(FormSectionRepository sections) {
        return args -> {
            /* only on a genuinely fresh database: a section somebody deleted on
               purpose must not come back on the next restart */
            if (sections.count() > 0) return;
            int i = 0;
            for (Object[] d : FORM) {
                FormSection f = new FormSection();
                f.setKey((String) d[0]);
                f.setLabel((String) d[1]);
                f.setNote((String) d[2]);
                f.setPosition(i++);
                sections.save(f);
            }
            log.info("Created {} starting form sections. All of them are editable.", FORM.length);
        };
    }

    @Bean
    ApplicationRunner bootstrap(UserRepository users, PasswordEncoder encoder,
                                @Value("${lms.bootstrap.email}") String email,
                                @Value("${lms.bootstrap.password}") String password,
                                @Value("${lms.bootstrap.name}") String name) {
        return args -> {
            if (users.count() > 0) return;

            if (email == null || email.isBlank()) {
                log.warn("No users and no BOOTSTRAP_EMAIL set. Nobody can sign in. "
                        + "Set BOOTSTRAP_EMAIL and BOOTSTRAP_PASSWORD and restart.");
                return;
            }

            User u = new User();
            u.setEmail(email.trim().toLowerCase());
            u.setFullName(name == null || name.isBlank() ? "Super admin" : name);
            u.setLoginId(CredentialService.slug(u.getFullName()));
            u.setRole(User.Role.SUPER_ADMIN);
            u.setPasswordHash(encoder.encode(password));
            u.setActive(true);
            /* whatever was in the environment variable is now in a shell history and
               probably a deployment file, so it is a way in once and no more */
            u.setMustChangePassword(true);
            users.save(u);

            log.info("First run: created the super admin {} (login id {}). "
                    + "You will be asked to set a new password when you sign in.",
                    u.getEmail(), u.getLoginId());
        };
    }
}
