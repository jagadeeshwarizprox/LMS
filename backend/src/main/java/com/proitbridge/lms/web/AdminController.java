package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService admin;
    private final ImportService importer;
    private final ProvisioningService provisioning;
    private final OnboardingBoardService onboarding;
    private final RecordingService recordings;
    private final MentorCoverService cover;
    private final SuperAdminService staff;
    private final CurrentUser current;

    public AdminController(AdminService admin, ImportService importer,
                           ProvisioningService provisioning,
                           OnboardingBoardService onboarding, RecordingService recordings,
                           MentorCoverService cover, SuperAdminService staff,
                           CurrentUser current) {
        this.admin = admin; this.importer = importer;
        this.provisioning = provisioning; this.onboarding = onboarding;
        this.recordings = recordings; this.cover = cover; this.staff = staff;
        this.current = current;
    }

    private String actor() { return current.get().email(); }

    @GetMapping("/overview")
    public Map<String, Object> overview() { return admin.overview(); }

    /** The mentor list the register and the cover screen assign from. */
    @GetMapping("/mentors")
    public List<Map<String, Object>> mentors() { return admin.mentors(); }

    /* -------------------------------------------------------------- the staff */

    /*
     * Adding and editing a mentor was super admin only, which made the one person who
     * configures the product also the one person who can replace a mentor who left.
     * Admin runs the machine, so it belongs here too. What stays upstairs is the part
     * that is not operational: pay, granting the admin or super admin role, and
     * deletion. Role is forced to MENTOR below rather than read from the body, so this
     * route cannot be used to make another admin.
     */

    @GetMapping("/people")
    public List<Map<String, Object>> people() { return staff.hierarchy(); }

    @PostMapping("/people")
    public Map<String, Object> saveMentor(@RequestBody Map<String, Object> body) {
        User u = new User();
        u.setId((String) body.get("id"));
        u.setEmail((String) body.get("email"));
        u.setFullName((String) body.get("fullName"));
        u.setPhone(Validate.phone((String) body.get("phone"), "Phone number"));
        u.setWhatsapp(Validate.phone((String) body.get("whatsapp"), "WhatsApp number"));
        u.setRole(User.Role.MENTOR);
        u.setReportsToId((String) body.get("reportsToId"));
        if (u.getId() != null && !staff.isMentor(u.getId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "That account is not a mentor. Editing it is a super admin job.");
        }
        u.setActive(!Boolean.FALSE.equals(body.get("active")));
        User saved = staff.saveStaff(u, null, actor());

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", saved.getId());
        out.put("email", saved.getEmail());
        out.put("fullName", saved.getFullName());
        out.put("loginId", saved.getLoginId());
        out.put("password", staff.consumeLastPassword());
        return out;
    }

    @PostMapping("/people/{id}/active")
    public Map<String, Object> setMentorActive(@PathVariable String id,
                                               @RequestBody Map<String, Boolean> body) {
        return staff.setStaffActive(id, Boolean.TRUE.equals(body.get("active")), actor());
    }

    @PostMapping("/people/{id}/reassign-learners")
    public Map<String, Object> reassign(@PathVariable String id,
                                        @RequestBody Map<String, String> body) {
        return staff.reassignLearners(id, body.get("toId"), body.get("reason"), actor());
    }

    @PostMapping("/people/{id}/reset-password")
    public Map<String, Object> resetMentorPassword(@PathVariable String id) {
        return staff.resetToDefault(id, actor());
    }

    @GetMapping("/register")
    public List<Map<String, Object>> register(@RequestParam(required = false) String trackType,
                                              @RequestParam(required = false) String batchId,
                                              @RequestParam(required = false) String mentorId,
                                              @RequestParam(required = false) String q) {
        return admin.register(trackType, batchId, mentorId, q);
    }

    /* ------------------------------------------------------------- provisioning */

    @PostMapping("/import")
    public ImportService.ImportSummary importSheet(@RequestParam("file") MultipartFile file,
                                                   @RequestParam(defaultValue = "true") boolean dispatchMail)
            throws Exception {
        return importer.importWorkbook(file, dispatchMail, actor());
    }

    /** The same read with nothing written, so an admin sees which rows are new first. */
    @PostMapping("/import/preview")
    public ImportService.ImportSummary previewSheet(@RequestParam("file") MultipartFile file)
            throws Exception {
        return importer.previewWorkbook(file);
    }

    /**
     * Manual single enrolment. Same path as the sheet, so the rules cannot drift apart.
     *
     * The course and the batch arrive as ids chosen from the real catalogue rather than
     * as typed names. A typed course name that matches nothing is how a learner ends up
     * enrolled on an empty roadmap and nobody finds out until they sign in.
     */
    @PostMapping("/enrol")
    public Map<String, Object> enrol(@RequestBody Map<String, String> body) {
        return admin.enrolOne(body, actor());
    }

    /** What the login ID and first password would be, shown before anything is created. */
    @GetMapping("/credential-preview")
    public Map<String, Object> credentialPreview(@RequestParam String fullName,
                                                 @RequestParam(required = false) String email) {
        return admin.credentialPreview(fullName, email);
    }

    /** Put an account back to the password made from its name. */
    @PostMapping("/learners/{id}/reset-password")
    public Map<String, Object> resetLearnerPassword(@PathVariable String id) {
        return admin.resetLearnerPassword(id, actor());
    }

    @PostMapping("/learners/{id}/mentor")
    public Learner assignMentor(@PathVariable String id, @RequestBody Map<String, String> body) {
        return admin.assignMentor(id, body.get("mentorId"), body.get("reason"), actor());
    }

    @GetMapping("/learners/{id}/mentor-history")
    public List<Map<String, Object>> mentorHistory(@PathVariable String id) {
        return admin.mentorHistory(id);
    }

    @PostMapping("/assign-unassigned")
    public Map<String, Object> assignAll() {
        return Map.of("assigned", admin.assignAllUnassigned(actor()));
    }

    @PostMapping("/learners/{id}/resend-credentials")
    public Map<String, Object> resend(@PathVariable String id) {
        return Map.of("sentTo", admin.resendCredentials(id, actor()));
    }

    @PostMapping("/learners/{id}/features/{key}")
    public LearnerFeatureOverride override(@PathVariable String id, @PathVariable String key,
                                           @RequestBody Map<String, Boolean> body) {
        return admin.setOverride(id, key, Boolean.TRUE.equals(body.get("enabled")), actor());
    }

    /* ------------------------------------------------------------- batches */

    @GetMapping("/batches")
    public List<Map<String, Object>> batches() { return admin.batchList(); }

    @PostMapping("/batches")
    public Batch createBatch(@RequestBody Batch body) { return admin.createBatch(body, actor()); }

    @PostMapping("/batches/{id}")
    public Batch updateBatch(@PathVariable String id, @RequestBody Map<String, String> body) {
        return admin.updateBatch(id, body, actor());
    }

    /** Closing is about intake only: the cohort inside carries on exactly as it was. */
    @PostMapping("/batches/{id}/open")
    public Batch setBatchOpen(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return admin.setBatchOpen(id, Boolean.TRUE.equals(body.get("open")), actor());
    }

    @PostMapping("/batches/{id}/induction-done")
    public Batch inductionDone(@PathVariable String id, @RequestBody Map<String, String> body) {
        return admin.markInductionDone(id, body.get("recordingUrl"), actor());
    }

    @PostMapping("/announcements")
    public Announcement announce(@RequestBody Map<String, String> body) {
        var me = current.get();
        return admin.announce(body.get("batchId"), me.id(), me.name(), body.get("body"));
    }

    /** Stages one and two, made visible: who is stuck where, and for how long. */
    @GetMapping("/onboarding-board")
    public Map<String, Object> onboardingBoard() { return onboarding.board(); }

    /** Sessions held against sessions recorded, so a missing week is visible. */

    /* ----------------------------------------------------------- mentor cover */

    @GetMapping("/cover")
    public List<Map<String, Object>> cover() { return cover.all(); }

    /**
     * A mentor going away hands nothing over: their learners stay theirs, and the
     * covering mentor sees the same queues until the dates run out.
     */
    @PostMapping("/cover")
    public MentorCover arrangeCover(@RequestBody Map<String, String> body) {
        return cover.arrange(body.get("mentorId"), body.get("coveringMentorId"),
                day(body.get("from"), "From"), day(body.get("until"), "Until"),
                body.get("reason"), actor());
    }

    /**
     * A bare LocalDate.parse on a request body turns an empty box into a stack trace and a
     * five hundred, which tells whoever is arranging cover nothing at all about which of
     * the two dates they missed. This says which one and asks for it.
     */
    private static LocalDate day(String value, String which) {
        if (value == null || value.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    which + " needs a date.");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    which + " is not a date we can read. Use the date picker.");
        }
    }

    @PostMapping("/cover/{id}/end")
    public Map<String, Object> endCover(@PathVariable String id) {
        cover.end(id, actor());
        return Map.of("ended", true);
    }

    @GetMapping("/mail-log")
    public List<MailLog> mailLog() { return admin.mailLog(); }
}
