package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.FieldRequest;
import com.thinklab.application.dto.request.InitiateCatalogItemRequest;
import com.thinklab.application.dto.request.UpdateCatalogItemRequest;
import com.thinklab.domain.exception.CatalogItemNotFoundException;
import com.thinklab.domain.exception.DuplicateCatalogItemException;
import com.thinklab.domain.exception.InvalidCatalogItemStatusException;
import com.thinklab.domain.exception.ServiceRequestAccessDeniedException;
import com.thinklab.domain.model.CatalogItem;
import com.thinklab.domain.model.CatalogItem.CatalogItemStatus;
import com.thinklab.domain.model.CatalogItem.Field;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.CatalogItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogUseCaseTest {

    private static final String REQUESTER = "REQUESTER";

    @Mock private CatalogItemRepository repository;
    @Mock private HashServicePort hashService;

    private final UUID org = UUID.randomUUID();
    private CatalogWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new CatalogWorkflow(repository);
    }

    private CatalogItem item(boolean publish) {
        CatalogItem item = CatalogItem.createNew(UUID.randomUUID(), org, "VPN", "VPN access", null, "ACCESS", List.of(new Field("reason", "Why?", true)), 24, null, "op-1");
        if (publish) {
            item.publish("op-1");
        }
        return item;
    }

    private InitiateCatalogItemRequest initiateRequest() {
        return new InitiateCatalogItemRequest(" vpn ", "VPN access", null, "ACCESS", List.of(new FieldRequest("reason", "Why?", true)), 24, null);
    }

    @Test
    @DisplayName("initiate drafts a new item under a sovereign id, looking the code up case-insensitively first")
    void initiate() {
        UUID id = UUID.randomUUID();
        when(repository.findByCode("VPN", org)).thenReturn(Mono.empty());
        when(hashService.generateSovereignId("catalog-item-creation")).thenReturn(Mono.just(id));
        when(repository.create(any(CatalogItem.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(new InitiateCatalogItemUseCase(hashService, repository).execute(org, initiateRequest(), "op-1", null))
                .assertNext(created -> {
                    assertEquals(id, created.id());
                    assertEquals("DRAFT", created.status());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("initiate refuses a code the organisation already has, without spending a sovereign id")
    void initiateDuplicate() {
        when(repository.findByCode("VPN", org)).thenReturn(Mono.just(item(false)));

        StepVerifier.create(new InitiateCatalogItemUseCase(hashService, repository).execute(org, initiateRequest(), "op-1", "ADMIN"))
                .expectError(DuplicateCatalogItemException.class).verify();
        verifyNoInteractions(hashService);
        verify(repository, never()).create(any());
    }

    @Test
    @DisplayName("a REQUESTER cannot manage the catalog")
    void initiateDenied() {
        StepVerifier.create(new InitiateCatalogItemUseCase(hashService, repository).execute(org, initiateRequest(), "op-1", REQUESTER))
                .expectError(ServiceRequestAccessDeniedException.class).verify();
        verifyNoInteractions(repository, hashService);
    }

    @Test
    @DisplayName("retrieve: staff see any status, a REQUESTER sees only PUBLISHED items, an unknown id is a 404")
    void retrieve() {
        CatalogItem draft = item(false);
        CatalogItem published = item(true);
        when(repository.findById(draft.getId(), org)).thenReturn(Mono.just(draft));
        when(repository.findById(published.getId(), org)).thenReturn(Mono.just(published));
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown, org)).thenReturn(Mono.empty());
        RetrieveCatalogItemUseCase useCase = new RetrieveCatalogItemUseCase(repository);

        StepVerifier.create(useCase.execute(draft.getId(), org, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.execute(published.getId(), org, REQUESTER)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.execute(draft.getId(), org, REQUESTER)).expectError(CatalogItemNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(unknown, org, null)).expectError(CatalogItemNotFoundException.class).verify();
    }

    @Test
    @DisplayName("retrieve all: a REQUESTER is forced to PUBLISHED whatever they ask for; staff filter freely")
    void retrieveAll() {
        when(repository.findAll(org, CatalogItemStatus.PUBLISHED)).thenReturn(Flux.just(item(true)));
        when(repository.findAll(org, null)).thenReturn(Flux.just(item(false), item(true)));
        RetrieveCatalogItemsUseCase useCase = new RetrieveCatalogItemsUseCase(repository);

        StepVerifier.create(useCase.execute(org, CatalogItemStatus.DRAFT, REQUESTER)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.execute(org, null, null)).expectNextCount(2).verifyComplete();
    }

    @Test
    @DisplayName("update edits a DRAFT item through a guarded save; a published one is refused; a REQUESTER and an unknown id are refused too")
    void update() {
        CatalogItem draft = item(false);
        when(repository.findById(draft.getId(), org)).thenReturn(Mono.just(draft));
        when(repository.save(any(), any(), any())).thenReturn(Mono.empty());
        var request = new UpdateCatalogItemRequest("Renamed", null, null, List.of(), 48, null);
        UpdateCatalogItemUseCase useCase = new UpdateCatalogItemUseCase(workflow);

        StepVerifier.create(useCase.execute(draft.getId(), org, request, "op-2", null)).verifyComplete();
        verify(repository).save(eq(draft), eq(CatalogItemStatus.DRAFT), any());
        assertEquals("Renamed", draft.getName());

        CatalogItem published = item(true);
        when(repository.findById(published.getId(), org)).thenReturn(Mono.just(published));
        StepVerifier.create(useCase.execute(published.getId(), org, request, "op-2", null)).expectError(InvalidCatalogItemStatusException.class).verify();

        StepVerifier.create(useCase.execute(draft.getId(), org, request, "op-2", REQUESTER)).expectError(ServiceRequestAccessDeniedException.class).verify();
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown, org)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(unknown, org, request, "op-2", null)).expectError(CatalogItemNotFoundException.class).verify();
    }

    @Test
    @DisplayName("publish and retire walk the lifecycle through guarded saves")
    void control() {
        CatalogItem item = item(false);
        when(repository.findById(item.getId(), org)).thenReturn(Mono.just(item));
        when(repository.save(any(), any(), any())).thenReturn(Mono.empty());
        ControlCatalogItemUseCase useCase = new ControlCatalogItemUseCase(workflow);

        StepVerifier.create(useCase.execute(item.getId(), org, ControlCatalogItemUseCase.Action.PUBLISH, "op-1", null)).verifyComplete();
        verify(repository).save(eq(item), eq(CatalogItemStatus.DRAFT), any());
        StepVerifier.create(useCase.execute(item.getId(), org, ControlCatalogItemUseCase.Action.RETIRE, "op-1", null)).verifyComplete();
        verify(repository).save(eq(item), eq(CatalogItemStatus.PUBLISHED), any());
        assertEquals(CatalogItemStatus.RETIRED, item.getStatus());
        StepVerifier.create(useCase.execute(item.getId(), org, ControlCatalogItemUseCase.Action.PUBLISH, "op-1", REQUESTER))
                .expectError(ServiceRequestAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("the audit log of an item is for staff; an unknown item is a 404")
    void auditLog() {
        CatalogItem item = item(true);
        when(repository.findById(item.getId(), org)).thenReturn(Mono.just(item));
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown, org)).thenReturn(Mono.empty());
        RetrieveCatalogAuditLogUseCase useCase = new RetrieveCatalogAuditLogUseCase(repository);

        StepVerifier.create(useCase.execute(item.getId(), org, null)).assertNext(log -> assertEquals(2, log.size())).verifyComplete();
        StepVerifier.create(useCase.execute(item.getId(), org, REQUESTER)).expectError(ServiceRequestAccessDeniedException.class).verify();
        StepVerifier.create(useCase.execute(unknown, org, "ADMIN")).expectError(CatalogItemNotFoundException.class).verify();
    }
}
