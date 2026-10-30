package com.elevatebox.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    Optional<Contact> findByPhone(String phone);

    Optional<Contact> findFirstByLastCallId(String lastCallId);

    long countByLastCallAtAfter(Instant since);
}
