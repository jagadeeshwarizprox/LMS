package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.MailLog;
import com.proitbridge.lms.repo.MailLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.List;

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

    public MailService(MailLogRepository repo, JavaMailSender sender,
                       @Value("${lms.mail.enabled}") boolean enabled,
                       @Value("${lms.mail.from}") String from) {
        this.repo = repo;
        this.sender = sender;
        this.enabled = enabled;
        this.from = from;
    }

    public MailLog send(String to, String subject, String body, String kind) {
        MailLog log = new MailLog();
        log.setToEmail(to);
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

    public List<MailLog> recent() {
        return repo.findTop100ByOrderBySentAtDesc();
    }

    public List<MailLog> forLearner(String email) {
        return repo.findByToEmailOrderBySentAtDesc(email);
    }
}
