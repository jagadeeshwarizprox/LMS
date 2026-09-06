package org.springframework.mail.javamail;
import org.springframework.mail.SimpleMailMessage;
public interface JavaMailSender { void send(SimpleMailMessage message); void send(SimpleMailMessage... messages); }
