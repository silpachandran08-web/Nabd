package com.nabd.hms.platform.trial;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class TrialRepository {

    record Signup(UUID id, String region, String status, String otpHash, int otpAttempts, Instant createdAt, UUID jobId) {
    }

    private final JdbcTemplate jdbc;

    TrialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Signup times from this IP since {@code since}, oldest first. */
    List<Instant> ipAttempts(String ipHash, Instant since) {
        return jdbc.query("SELECT created_at FROM master.trial_signups WHERE ip_hash = ? AND created_at >= ? ORDER BY created_at",
                (rs, i) -> rs.getTimestamp(1).toInstant(), ipHash, Timestamp.from(since));
    }

    boolean verifiedSince(String mobileHash, Instant since) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM master.trial_signups WHERE mobile_hash = ? AND created_at >= ? " +
                "AND status IN ('verified','provisioning','ready'))", Boolean.class, mobileHash, Timestamp.from(since)));
    }

    boolean otpSentSince(String mobileHash, Instant since) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM master.trial_signups WHERE mobile_hash = ? AND created_at >= ?)",
                Boolean.class, mobileHash, Timestamp.from(since)));
    }

    void insert(UUID id, String mobileHash, String ipHash, String market, String region, String clinicName, String specialty, String otpHash, Instant now) {
        jdbc.update("INSERT INTO master.trial_signups (id, mobile_hash, ip_hash, market, region, clinic_name, specialty, otp_hash, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, mobileHash, ipHash, market, region, clinicName, specialty, otpHash, Timestamp.from(now));
    }

    Optional<Signup> find(UUID id) {
        return jdbc.query("SELECT id, region, status, otp_hash, otp_attempts, created_at, job_id FROM master.trial_signups WHERE id = ? FOR UPDATE",
                (rs, i) -> new Signup(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getTimestamp(6).toInstant(), rs.getString(7) == null ? null : UUID.fromString(rs.getString(7))), id).stream().findFirst();
    }

    void failedAttempt(UUID id) {
        jdbc.update("UPDATE master.trial_signups SET otp_attempts = otp_attempts + 1 WHERE id = ?", id);
    }

    void markVerified(UUID id) {
        jdbc.update("UPDATE master.trial_signups SET status = 'verified', otp_hash = NULL WHERE id = ?", id);
    }

    void markExpired(UUID id) {
        jdbc.update("UPDATE master.trial_signups SET status = 'expired', otp_hash = NULL WHERE id = ? AND status = 'otp_sent'", id);
    }
}
