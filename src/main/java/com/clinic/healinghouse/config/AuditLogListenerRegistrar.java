package com.clinic.healinghouse.config;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.springframework.stereotype.Component;

/**
 * Wires {@link AuditLogEventListener} into Hibernate's own event pipeline — there's no Spring Boot
 * auto-configuration hook for this, so it has to reach into the built {@link SessionFactoryImplementor}
 * directly once the JPA {@code EntityManagerFactory} bean exists, appending (not replacing) the
 * default listeners for each event type.
 */
@Component
@RequiredArgsConstructor
public class AuditLogListenerRegistrar {

    private final EntityManagerFactory entityManagerFactory;
    private final AuditLogEventListener auditLogEventListener;

    @PostConstruct
    public void registerListener() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        EventListenerRegistry registry = sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, auditLogEventListener);
        registry.appendListeners(EventType.POST_UPDATE, auditLogEventListener);
        registry.appendListeners(EventType.POST_DELETE, auditLogEventListener);
    }
}
