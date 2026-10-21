package com.elevatebox.agent.persistence;

import com.elevatebox.agent.service.GeminiService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One analysed call. Deliberately stores the outcome (label, reason, summary, callback request,
 * which steps failed) and not the transcript: transcripts are real people's phone conversations,
 * so they are not kept by default.
 *
 * <p>{@code callId} is Vapi's call ID and is unique, which makes duplicate end-of-call reports
 * harmless even after a restart. It is null for records created by the manual {@code /process}
 * endpoint (databases allow several nulls in a unique column).
 */
@Entity
@Table(name = "call_records")
@Getter
@Setter
@NoArgsConstructor
public class CallRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_id", unique = true, length = 100)
    private String callId;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GeminiService.LeadClassification classification;

    @Column(length = 1000)
    private String reason;

    @Column(length = 4000)
    private String summary;

    @Column(name = "callback_requested", nullable = false)
    private boolean callbackRequested;

    @Column(name = "callback_when", length = 255)
    private String callbackWhen;

    @Column(name = "failed_steps", length = 255)
    private String failedSteps;

    @PrePersist
    void stampCreationTime() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
