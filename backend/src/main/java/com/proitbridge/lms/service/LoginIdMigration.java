package com.proitbridge.lms.service;

import com.proitbridge.lms.repo.AppSettingRepository;
import com.proitbridge.lms.domain.AppSetting;
import com.proitbridge.lms.domain.User;
import com.proitbridge.lms.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Everybody who existed before login IDs did gets one.
 *
 * Their password is untouched. A login ID is a second way to reach the same account, not
 * a reset, so somebody who has already chosen their own password keeps it and simply
 * gains a shorter thing to type. Anyone still on a generated password is left on it too:
 * reissuing in bulk would mail the entire user base at once, which is an admin decision
 * rather than a migration's.
 */
@Service
public class LoginIdMigration {

    private static final String FLAG = "auth.loginIds.backfilled";
    private static final Logger log = LoggerFactory.getLogger(LoginIdMigration.class);

    private final UserRepository users;
    private final AppSettingRepository settings;
    private final CredentialService credentials;

    public LoginIdMigration(UserRepository users, AppSettingRepository settings,
                            CredentialService credentials) {
        this.users = users;
        this.settings = settings;
        this.credentials = credentials;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (settings.findById(FLAG).isPresent()) return;

        List<User> all = users.findAll();
        int given = 0;
        for (User u : all) {
            if (u.getLoginId() != null && !u.getLoginId().isBlank()) continue;
            /* one at a time rather than in a batch: each has to see the ones already
               handed out to know whether it is the second Priya Sharma */
            u.setLoginId(credentials.loginIdFor(u.getFullName(), u.getEmail(), u.getId()));
            users.save(u);
            given++;
        }

        AppSetting done = new AppSetting();
        done.setKey(FLAG);
        done.setValue("yes");
        settings.save(done);
        log.info("Backfilled {} login IDs across {} accounts. No password was changed.",
                given, all.size());
    }
}
