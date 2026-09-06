package com.proitbridge.lms.config;

import com.proitbridge.lms.domain.FormField;
import com.proitbridge.lms.repo.FormFieldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The questions the form used to ask in JSX, moved into data.
 *
 * Every one of these is exactly what was hard-coded before, with the same answer key, so
 * an existing learner's saved answers still line up and nothing is lost on the day this
 * ships. From here they are ordinary rows: editable, reorderable, and switchable off.
 *
 * Seeded only into a genuinely empty collection. A question somebody deleted on purpose
 * must not reappear on the next restart, which is the same rule the sections follow.
 */
@Configuration
public class FormFieldSeeder {

    private static final Logger log = LoggerFactory.getLogger(FormFieldSeeder.class);

    /** section, key, label, type, options (pipe separated), showWhen (pipe separated) */
    private static final String[][] FIELDS = {
        {"basic", "fullName", "Full name as it should appear on your certificate", "TEXT", "", ""},
        {"basic", "email", "Email (from your record)", "EMAIL", "", ""},
        {"basic", "phone", "Contact number", "PHONE", "", ""},
        {"basic", "altPhone", "Alternate number", "PHONE", "", ""},
        {"basic", "whatsapp", "WhatsApp number, if different", "PHONE", "", ""},
        {"basic", "city", "City", "TEXT", "", ""},
        {"basic", "state", "State", "TEXT", "", ""},
        {"basic", "language", "Preferred language of instruction", "CHOICE",
         "English|Hindi|Telugu|Tamil|Kannada|Malayalam", ""},

        {"education", "qualification", "Highest qualification", "TEXT", "", ""},
        {"education", "specialisation", "Specialisation", "TEXT", "", ""},
        {"education", "college", "College or university", "TEXT", "", ""},
        {"education", "gradYear", "Year of graduation", "TEXT", "", ""},
        {"education", "score", "Aggregate percentage or CGPA", "TEXT", "", ""},
        {"education", "studyStatus", "Current status", "CHOICE", "Still studying|Passed out", ""},
        {"education", "background", "Academic background", "CHOICE", "IT|Non IT", ""},
        {"education", "otherCerts", "Other degrees or certifications", "LONG_TEXT", "", ""},

        {"professional", "profileType", "Which describes you today", "CHOICE",
         "FRESHER|WORKING|CAREER_GAP", ""},

        {"professional", "passedOutYear", "Passed out year", "TEXT", "", "FRESHER"},
        {"professional", "status", "Current status", "CHOICE",
         "Studying|Passed out and searching|Passed out, not searching yet", "FRESHER"},
        {"professional", "internship", "Internship or industrial training", "LONG_TEXT", "", "FRESHER"},
        {"professional", "partTime", "Part time, freelance or campus work", "LONG_TEXT", "", "FRESHER"},
        {"professional", "priorCourse", "Prior course in data or analytics", "TEXT", "", "FRESHER"},
        {"professional", "jobSearch", "Job search status", "CHOICE",
         "Not started|Actively applying|Attending interviews", "FRESHER"},
        {"professional", "interviews", "Interviews attended so far", "NUMBER", "", "FRESHER"},

        {"professional", "designation", "Current designation", "TEXT", "", "WORKING"},
        {"professional", "employer", "Employer", "TEXT", "", "WORKING"},
        {"professional", "totalExp", "Total years of experience", "TEXT", "", "WORKING"},
        {"professional", "roleExp", "Years in the current role", "TEXT", "", "WORKING"},
        {"professional", "domain", "Domain or industry", "TEXT", "", "WORKING"},
        {"professional", "touchesData", "Does your role touch data, analytics or reporting", "CHOICE",
         "Yes, daily|Sometimes|No", "WORKING"},
        {"professional", "tools", "Tools used at work", "LONG_TEXT", "", "WORKING"},
        {"professional", "shift", "Shift pattern", "TEXT", "", "WORKING"},
        {"professional", "notice", "Notice period", "TEXT", "", "WORKING"},
        {"professional", "intent", "What you want from this", "CHOICE",
         "Switch role|Switch company|Upskill in present role", "WORKING"},
        {"professional", "days", "Preferred days", "TEXT", "", "WORKING"},

        {"professional", "lastRole", "Last designation before the break", "TEXT", "", "CAREER_GAP"},
        {"professional", "lastEmployer", "Last employer", "TEXT", "", "CAREER_GAP"},
        {"professional", "expBefore", "Years of experience before the break", "TEXT", "", "CAREER_GAP"},
        {"professional", "gapStart", "Gap start date", "MONTH", "", "CAREER_GAP"},
        {"professional", "gapDuration", "Gap duration", "TEXT", "", "CAREER_GAP"},
        {"professional", "gapReason", "Reason for the gap (optional)", "LONG_TEXT", "", "CAREER_GAP"},
        {"professional", "gapActivity", "What you did during the gap", "CHOICE",
         "Study|Certification|Family responsibility|Health|Relocation|Other", "CAREER_GAP"},
        {"professional", "skillsKept", "Skills kept current, and how", "LONG_TEXT", "", "CAREER_GAP"},
        {"professional", "readiness", "Ready to rejoin", "CHOICE",
         "Immediately|Within three months|Later", "CAREER_GAP"},

        {"professional", "hours", "Hours per week available", "NUMBER", "", ""},

        {"technical", "programming", "Prior programming experience, and languages", "LONG_TEXT", "", ""},
        {"technical", "toolsUsed", "Tools and software already used", "LONG_TEXT", "", ""},
        {"technical", "priorCourse", "Prior data course taken", "LONG_TEXT", "", ""},
        {"technical", "mathComfort", "How comfortable are you with the subject", "CHOICE",
         "Beginner|Intermediate|Advanced", ""},
        {"technical", "certs", "Relevant certifications", "LONG_TEXT", "", ""},

        {"projects", "title", "Project title", "TEXT", "", ""},
        {"projects", "context", "Context", "CHOICE",
         "Academic|Internship|Professional|Self initiated|Not applicable", ""},
        {"projects", "summary", "Problem addressed and outcome", "LONG_TEXT", "", ""},
        {"projects", "tools", "Tools and techniques", "TEXT", "", ""},
        {"projects", "role", "Individual or team, and your role", "TEXT", "", ""},
        {"projects", "repoUrl", "Repository or demo link", "LINK", "", ""},

        {"resume", "resumeUrl", "Resume link (Drive, Dropbox or similar)", "LINK", "", ""},
        {"resume", "filename", "File name", "TEXT", "", ""},

        {"intent", "targetRole", "Target role", "TEXT", "", ""},
        {"intent", "timeline", "Target timeline", "CHOICE",
         "Within 3 months|3 to 6 months|6 to 12 months", ""},
        {"intent", "targetCompanies", "Target companies or industry", "TEXT", "", ""},
        {"intent", "salary", "Expected salary range (optional)", "TEXT", "", ""},
        {"intent", "location", "Location preference", "TEXT", "", ""},
        {"intent", "relocation", "Open to relocation or remote", "CHOICE",
         "Open to relocation|Remote only|Current city only", ""},
        {"intent", "doubtWindow", "Preferred doubt clearing window", "CHOICE",
         "Morning|Afternoon|Evening|Late evening", ""},
        {"intent", "wants", "What you most want out of this programme", "LONG_TEXT", "", ""},

        {"links", "linkedin", "LinkedIn", "LINK", "", ""},
        {"links", "github", "GitHub", "LINK", "", ""},
        {"links", "portfolio", "Portfolio or blog", "LINK", "", ""},
        {"links", "placementConsent", "Contact me about placement and referral opportunities.",
         "CHECKBOX", "", ""},
        {"links", "terms", "I accept the terms and the privacy policy.", "CHECKBOX", "", ""}
    };

    @Bean
    ApplicationRunner ensureFormFields(FormFieldRepository fields) {
        return args -> {
            if (fields.count() > 0) return;
            int i = 0;
            for (String[] d : FIELDS) {
                FormField f = new FormField();
                f.setSectionKey(d[0]);
                f.setKey(d[1]);
                f.setLabel(d[2]);
                f.setType(d[3]);
                if (!d[4].isBlank()) f.setOptions(List.of(d[4].split("\\|")));
                if (!d[5].isBlank()) f.setShowWhen(List.of(d[5].split("\\|")));
                /* the email is the record's, shown filled in and not editable here */
                f.setReadOnly("email".equals(d[1]));
                f.setPosition(i++);
                fields.save(f);
            }
            log.info("Created {} starting form questions. All of them are editable.", FIELDS.length);
        };
    }
}
