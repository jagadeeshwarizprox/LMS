package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A note that lands on the learner's record as well as in their mail.
 *
 * At risk could name a problem and offer nothing but a WhatsApp deep link, which meant
 * the follow up left no trace. A nudge sent from here is on the record, so the next
 * mentor can see it was already tried.
 */
@Service
public class MessageService {

    private final LearnerMessageRepository messages;
    private final LearnerRepository learners;
    private final UserRepository users;
    private final MailService mail;

    public MessageService(LearnerMessageRepository messages, LearnerRepository learners,
                          UserRepository users, MailService mail) {
        this.messages = messages; this.learners = learners; this.users = users; this.mail = mail;
    }

    /**
     * A note to one learner.
     *
     * The in-app inbox was removed: conversation lives on WhatsApp and a second place to
     * check was a place nobody checked. The row is still written, because it is the
     * record of what was sent and the mentor's own view reads it, but **email is what
     * actually reaches the learner** now, and it always did go out alongside. Nothing
     * that sends here has been silently disconnected.
     */
    public LearnerMessage send(String learnerId, String fromId, String fromName,
                               String subject, String body, String kind) {
        LearnerMessage m = new LearnerMessage();
        m.setLearnerId(learnerId);
        m.setFromId(fromId);
        m.setFromName(fromName);
        m.setSubject(subject);
        m.setBody(body);
        m.setKind(kind == null ? "MESSAGE" : kind);
        messages.save(m);

        /*
         * A note not addressed to a learner is a legitimate thing to write: an org wide
         * announcement, or a record of something arranged between staff. Passing that
         * null straight into findById threw "The given id must not be null" from the
         * driver, and because the row had already been saved by then the caller's own
         * work was committed while the caller saw a five hundred. The mentor cover screen
         * was reporting an argument error over a cover it had successfully arranged.
         */
        if (learnerId == null || learnerId.isBlank()) return m;

        learners.findById(learnerId)
                .flatMap(l -> users.findById(l.getUserId()))
                .ifPresent(u -> mail.send(u.getEmail(), subject,
                        "Hello " + u.getFullName() + ",\n\n" + body
                        + "\n\n" + (fromName == null ? "Team ProITBridge" : fromName), "MESSAGE"));
        return m;
    }

    /** Several learners, one note. Used for a cohort announcement or a batch nudge. */
    public int broadcast(Collection<String> learnerIds, String fromId, String fromName,
                         String subject, String body) {
        int n = 0;
        for (String id : learnerIds) {
            send(id, fromId, fromName, subject, body, "ANNOUNCEMENT");
            n++;
        }
        return n;
    }

    /**
     * A nudge worth sending: it names what is actually stalled rather than asking
     * whether everything is alright, which nobody answers.
     */
    public String draftNudge(String learnerId, List<String> reasons) {
        String name = learners.findById(learnerId)
                .flatMap(l -> users.findById(l.getUserId()))
                .map(u -> u.getFullName().split(" ")[0]).orElse("there");
        String why = reasons == null || reasons.isEmpty()
                ? "it has been quiet for a while"
                : String.join(", ", reasons).toLowerCase();
        return "Hi " + name + ",\n\n"
                + "I noticed " + why + ". Nothing to worry about, it happens.\n\n"
                + "Tell me where you are stuck and I will find you a slot this week. "
                + "Even one chapter is enough to get moving again.\n";
    }

    public List<Map<String, Object>> forLearner(String learnerId) {
        return messages.findByLearnerIdOrderBySentAtDesc(learnerId).stream().map(m -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", m.getId());
            x.put("from", m.getFromName());
            x.put("subject", m.getSubject());
            x.put("body", m.getBody());
            x.put("kind", m.getKind());
            x.put("read", m.isReadByLearner());
            x.put("sentAt", m.getSentAt());
            return x;
        }).collect(Collectors.toList());
    }

    public void markRead(String learnerId) {
        messages.findByLearnerIdAndReadByLearnerFalse(learnerId).forEach(m -> {
            m.setReadByLearner(true);
            messages.save(m);
        });
    }

    public long unread(String learnerId) {
        return messages.findByLearnerIdAndReadByLearnerFalse(learnerId).size();
    }
}
