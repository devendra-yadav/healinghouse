package com.clinic.healinghouse.config;

import com.clinic.healinghouse.entity.AuditAction;
import com.clinic.healinghouse.entity.AuditLog;
import com.clinic.healinghouse.security.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.hibernate.type.Type;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;

/**
 * Generic "who changed what" CRUD audit trail — a single Hibernate event listener hooked into
 * every entity's insert/update/delete flush (registered by {@link AuditLogListenerRegistrar}), so
 * new entities are covered automatically with no per-service logging code to remember. Deliberately
 * excludes its own {@link AuditLog} type (no self-logging) and the append-only
 * {@code WalletTransaction}/{@code PackageTransaction} ledgers, which are already their own audit
 * trail for money/session movements — logging their creation too would just be duplicate noise.
 *
 * <p>Field-level diffs are computed straight off Hibernate's own {@code oldState}/{@code state}
 * arrays rather than by re-reading the entity (which would risk lazy-initializing collections).
 * {@code @ManyToOne} associations are resolved to just the referenced id (via the loaded/unloaded
 * proxy's identifier, never triggering a lazy load) instead of dumping the whole associated object.
 * {@code version}/{@code updatedAt}/{@code createdAt} are skipped by name since they change on every
 * save regardless of what the user actually edited — an update whose only "changes" are those three
 * (e.g. {@code PackageService}'s {@code OPTIMISTIC_FORCE_INCREMENT} lock on a parent whose real
 * change lives on a child item entity) produces no diff and is correctly not logged at all.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogEventListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private static final Set<String> EXCLUDED_ENTITY_NAMES = Set.of(
            "com.clinic.healinghouse.entity.AuditLog",
            "com.clinic.healinghouse.entity.WalletTransaction",
            "com.clinic.healinghouse.entity.PackageTransaction",
            // Same append-only, self-auditing ledger pattern as the two above — its own javadoc
            // describes it that way, but it was left out of this list when it was introduced, so
            // every RECEIVED/CORRECTED row (including the one-time historical backfill) generated a
            // redundant generic AuditLog CREATE row alongside the ledger row it already is
            // (Bug_Report_v7.md Finding 22 — over-logging, not a security gap, but pure noise).
            "com.clinic.healinghouse.entity.AppointmentPaymentTransaction");

    private static final Set<String> SKIPPED_PROPERTY_NAMES = Set.of("version", "updatedAt", "createdAt");

    private static final int MAX_DETAILS_LENGTH = 3900;
    private static final int MAX_FIELD_VALUE_LENGTH = 150;

    private final PermissionService permissionService;

    @Override
    public void onPostInsert(PostInsertEvent event) {
        EntityPersister persister = event.getPersister();
        if (isExcluded(persister)) return;
        String details = describeState(persister, event.getState());
        persist(event.getSession(), build(AuditAction.CREATE, persister, event.getId(), details));
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        EntityPersister persister = event.getPersister();
        if (isExcluded(persister)) return;
        Object[] oldState = event.getOldState();
        if (oldState == null) return; // no prior snapshot available to diff against
        String details = describeDiff(persister, oldState, event.getState());
        if (details == null) return; // no scalar field actually changed (e.g. a forced version-only bump)
        persist(event.getSession(), build(AuditAction.UPDATE, persister, event.getId(), details));
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        EntityPersister persister = event.getPersister();
        if (isExcluded(persister)) return;
        String details = event.getDeletedState() == null ? null : describeState(persister, event.getDeletedState());
        persist(event.getSession(), build(AuditAction.DELETE, persister, event.getId(), details));
    }

    private boolean isExcluded(EntityPersister persister) {
        return EXCLUDED_ENTITY_NAMES.contains(persister.getEntityName());
    }

    private String describeState(EntityPersister persister, Object[] state) {
        String[] names = persister.getPropertyNames();
        Type[] types = persister.getPropertyTypes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (SKIPPED_PROPERTY_NAMES.contains(names[i]) || types[i].isCollectionType()) continue;
            Object value = resolveValue(state[i], types[i]);
            if (value == null) continue;
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(names[i]).append('=').append(formatValue(value));
        }
        return sb.isEmpty() ? null : truncate(sb.toString());
    }

    private String describeDiff(EntityPersister persister, Object[] oldState, Object[] newState) {
        String[] names = persister.getPropertyNames();
        Type[] types = persister.getPropertyTypes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (SKIPPED_PROPERTY_NAMES.contains(names[i]) || types[i].isCollectionType()) continue;
            Object oldValue = resolveValue(oldState[i], types[i]);
            Object newValue = resolveValue(newState[i], types[i]);
            if (Objects.equals(oldValue, newValue)) continue;
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(names[i]).append(": ").append(formatValue(oldValue)).append(" -> ").append(formatValue(newValue));
        }
        return sb.isEmpty() ? null : truncate(sb.toString());
    }

    private Object resolveValue(Object raw, Type type) {
        if (raw == null) return null;
        if (type.isEntityType()) return resolveEntityId(raw);
        if (type.isComponentType()) return raw.toString();
        return raw;
    }

    /** Resolves a {@code @ManyToOne} association to just its id, reading a lazy proxy's identifier
     *  directly (never triggering initialization) and falling back to reflection on {@code getId()}
     *  for an already-initialized instance — every entity in this codebase exposes that via Lombok. */
    private Object resolveEntityId(Object raw) {
        LazyInitializer lazyInitializer = HibernateProxy.extractLazyInitializer(raw);
        if (lazyInitializer != null) {
            return lazyInitializer.getIdentifier();
        }
        try {
            Method getId = raw.getClass().getMethod("getId");
            return getId.invoke(raw);
        } catch (ReflectiveOperationException e) {
            return raw.toString();
        }
    }

    private String formatValue(Object value) {
        String s = String.valueOf(value);
        return s.length() > MAX_FIELD_VALUE_LENGTH ? s.substring(0, MAX_FIELD_VALUE_LENGTH) + "..." : s;
    }

    private String truncate(String s) {
        return s.length() > MAX_DETAILS_LENGTH ? s.substring(0, MAX_DETAILS_LENGTH) + "..." : s;
    }

    private AuditLog build(AuditAction action, EntityPersister persister, Object id, String details) {
        if (details == null) return null;
        String entityName = persister.getEntityName();
        String simpleName = entityName.substring(entityName.lastIndexOf('.') + 1);
        return AuditLog.builder()
                .userId(permissionService.currentUserId())
                .username(permissionService.currentUsername())
                .action(action)
                .entityType(simpleName)
                .entityId(String.valueOf(id))
                .details(details)
                .ipAddress(currentIpAddress())
                .build();
    }

    private String currentIpAddress() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        return request.getRemoteAddr();
    }

    /**
     * Persists straight through the same Hibernate {@link Session} that just fired the event,
     * rather than queuing for a later flush. This is deliberate, not just simplest: an UPDATE/DELETE
     * is very often only flushed as part of the transaction's own final commit-time auto-flush —
     * i.e. Hibernate defers the actual SQL until the last possible moment — so a Spring
     * {@code TransactionSynchronization.beforeCommit()} hook (the natural-looking alternative) can
     * fire *before* that deferred flush ever happens, silently dropping the audit row for anything
     * but a CREATE (whose IDENTITY id generation forces an immediate insert, which is why that path
     * alone would have appeared to work). Persisting here, inside the POST_* callback itself, avoids
     * the ordering hazard entirely — {@code AuditLog}'s own IDENTITY generation makes this insert
     * execute immediately regardless of where in the flush cycle it's called from.
     */
    private void persist(SharedSessionContractImplementor session, AuditLog auditLog) {
        if (auditLog == null) return;
        ((Session) session).persist(auditLog);
    }
}
