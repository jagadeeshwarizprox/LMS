package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.MailLog;
import com.proitbridge.lms.repo.MailLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every mail is written to mail_log first. SMTP delivery is switched on with
 * lms.mail.enabled, so the whole flow can be exercised without a mail server.
 */
@Service
public class MailService {

    private final MailLogRepository repo;
    private final JavaMailSender sender;
    private final boolean enabled;
    private final String from;
    private final MongoTemplate mongo;

    public MailService(MailLogRepository repo, JavaMailSender sender, MongoTemplate mongo,
                       @Value("${lms.mail.enabled}") boolean enabled,
                       @Value("${lms.mail.from}") String from) {
        this.repo = repo;
        this.sender = sender;
        this.mongo = mongo;
        this.enabled = enabled;
        this.from = from;
    }

    public MailLog send(String to, String subject, String body, String kind) {
        MailLog log = new MailLog();
        log.setToEmail(to == null ? null : to.toLowerCase());
        log.setSubject(subject);
        log.setBody(body);
        log.setKind(kind);
        if (!enabled) {
            log.setStatus("LOGGED");
            return repo.save(log);
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            sender.send(msg);
            log.setStatus("SENT");
        } catch (Exception e) {
            log.setStatus("FAILED");
            log.setError(e.getMessage());
        }
        return repo.save(log);
    }

    /**
     * The log a page at a time, newest first.
     *
     * The screen used to show the last hundred and nothing older, which on a busy
     * enrolment day was the last hour: the credentials mail somebody is asking about from
     * yesterday had already fallen off. Paging reaches all of it. The recipient filter is
     * a prefix match on the stored (lower case) address, so it rides the (toEmail, sentAt)
     * index; kind and status narrow within that.
     */
    public Map<String, Object> page(String to, String kind, String status, int page, int size) {
        List<Criteria> parts = new ArrayList<>();
        if (AdminQueries.hasText(to)) {
            parts.add(Criteria.where("toEmail").regex("^" + AdminQueries.literal(to.toLowerCase())));
        }
        if (AdminQueries.hasText(kind)) parts.add(Criteria.where("kind").is(kind.trim()));
        if (AdminQueries.hasText(status)) parts.add(Criteria.where("status").is(status.trim()));
        Criteria where = AdminQueries.all(parts);
        long total = mongo.count(new Query(where), MailLog.class);
        List<MailLog> rows = mongo.find(new Query(where)
                .with(Sort.by(Sort.Direction.DESC, "sentAt"))
                .skip((long) page * size).limit(size), MailLog.class);
        return AdminQueries.pageOf(rows, total, page, size);
    }

    public List<MailLog> forLearner(String email) {
        return repo.findByToEmailOrderBySentAtDesc(email);
    }
}
