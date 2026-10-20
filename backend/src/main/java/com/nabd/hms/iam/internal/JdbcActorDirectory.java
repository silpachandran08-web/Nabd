package com.nabd.hms.iam.internal;

import com.nabd.hms.iam.api.ActorDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
class JdbcActorDirectory implements ActorDirectory {

    private static final String SELECT = "SELECT s.id, s.name, r.name AS role_name, s.is_clinician FROM staff s JOIN roles r ON r.id = s.role_id ";
    private static final RowMapper<Actor> MAPPER = (rs, i) ->
            new Actor(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("role_name"), rs.getBoolean("is_clinician"));

    private final JdbcTemplate jdbc;

    JdbcActorDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Actor> findActor(UUID tenantId, UUID staffId) {
        if (staffId == null) return Optional.empty();
        return jdbc.query(SELECT + "WHERE s.tenant_id = ? AND s.id = ?", MAPPER, tenantId, staffId).stream().findFirst();
    }

    @Override
    public Map<UUID, Actor> findActors(UUID tenantId, Collection<UUID> staffIds) {
        Map<UUID, Actor> out = new LinkedHashMap<>();
        if (staffIds == null || staffIds.isEmpty()) return out;
        UUID[] ids = staffIds.stream().filter(java.util.Objects::nonNull).distinct().toArray(UUID[]::new);
        if (ids.length == 0) return out;
        jdbc.query(con -> {
            var ps = con.prepareStatement(SELECT + "WHERE s.tenant_id = ? AND s.id = ANY (?)");
            ps.setObject(1, tenantId);
            ps.setArray(2, con.createArrayOf("uuid", ids));
            return ps;
        }, MAPPER).forEach(a -> out.put(a.id(), a));
        return out;
    }

    @Override
    public List<Actor> listClinicians(UUID tenantId) {
        return jdbc.query(SELECT + "WHERE s.tenant_id = ? AND s.is_clinician AND s.status = 'active' ORDER BY s.name", MAPPER, tenantId);
    }
}
