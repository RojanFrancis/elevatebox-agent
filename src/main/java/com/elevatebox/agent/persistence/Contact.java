package com.elevatebox.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Someone who messaged the business on WhatsApp, which is what allows the agent to call them.
 *
 * <p>The agent only calls people who asked for it: {@code consentAt} is when they sent a message
 * asking for a call. Their message also opens WhatsApp's 24-hour window, so the mid-call and
 * post-call WhatsApps can be delivered as free-form text. {@code lastCallId} links a Vapi call
 * back to the contact, so those messages go to the person on the call.
 *
 * <p>Only what the flow needs is stored: the number, the WhatsApp profile name and timestamps,
 * never the message text.
 */
@Entity
@Table(name = "contacts", indexes = @Index(name = "idx_contacts_last_call_id", columnList = "last_call_id"))
@Getter
@Setter
@NoArgsConstructor
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** E.164, for example {@code +919876543210}. */
    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    @Column(length = 100)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "consent_at")
    private Instant consentAt;

    @Column(name = "last_call_at")
    private Instant lastCallAt;

    @Column(name = "last_call_id", length = 100)
    private String lastCallId;

    @Column(name = "call_count", nullable = false)
    private int callCount;

    @PrePersist
    void stampCreationTime() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
