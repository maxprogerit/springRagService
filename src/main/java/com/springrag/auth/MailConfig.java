package com.springrag.auth;

import com.springrag.common.RagProperties;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    MailService mailService(RagProperties props) {
        RagProperties.Mail mail = props.mail();
        if (!mail.enabled()) {
            return new MailService() {
                @Override
                public boolean enabled() {
                    return false;
                }

                @Override
                public void send(String to, String subject, String text) {
                    throw new IllegalStateException("E-mail is not configured (MAIL_HOST is empty)");
                }
            };
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mail.host());
        sender.setPort(mail.port());
        if (mail.username() != null && !mail.username().isBlank()) {
            sender.setUsername(mail.username());
            sender.setPassword(mail.password());
        }
        Properties javaMail = sender.getJavaMailProperties();
        javaMail.put("mail.smtp.auth", String.valueOf(mail.username() != null && !mail.username().isBlank()));
        javaMail.put("mail.smtp.starttls.enable", String.valueOf(mail.startTls()));
        javaMail.put("mail.smtp.connectiontimeout", "5000");
        javaMail.put("mail.smtp.timeout", "10000");
        javaMail.put("mail.smtp.writetimeout", "10000");
        return new MailService() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void send(String to, String subject, String text) {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(mail.from());
                message.setTo(to);
                message.setSubject(subject);
                message.setText(text);
                sender.send(message);
                log.info("E-mail '{}' sent", subject);
            }
        };
    }
}
