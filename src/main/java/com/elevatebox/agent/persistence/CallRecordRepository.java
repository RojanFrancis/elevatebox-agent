package com.elevatebox.agent.persistence;

import com.elevatebox.agent.service.GeminiService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CallRecordRepository extends JpaRepository<CallRecord, Long> {

    boolean existsByCallId(String callId);

    List<CallRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByClassification(GeminiService.LeadClassification classification);
}
