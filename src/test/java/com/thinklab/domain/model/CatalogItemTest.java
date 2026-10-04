package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidCatalogItemStatusException;
import com.thinklab.domain.model.CatalogItem.CatalogItemStatus;
import com.thinklab.domain.model.CatalogItem.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogItemTest {

    private final UUID org = UUID.randomUUID();

    private CatalogItem draft(List<Field> fields) {
        return CatalogItem.createNew(UUID.randomUUID(), org, " vpn-access ", "VPN access", "Remote access", "ACCESS", fields, 24, null, "op-1");
    }

    private CatalogItem draft() {
        return draft(List.of(new Field("reason", "Why do you need it?", true)));
    }

    @Test
    @DisplayName("a new item is a DRAFT with an upper-cased code and an INITIATED audit entry")
    void createNew() {
        CatalogItem item = draft();

        assertEquals("VPN-ACCESS", item.getCode());
        assertEquals(CatalogItemStatus.DRAFT, item.getStatus());
        assertEquals(1, item.getAuditTrail().size());
        assertNull(item.getAuditTrail().get(0).fromStatus());
        assertEquals(1, item.getFields().size());
        assertNotNull(item.getCreatedAt());
    }

    @Test
    @DisplayName("creation refuses missing ids, blank code or name, null or too many or duplicated questions, a bad target and a blank executor")
    void createGuards() {
        UUID id = UUID.randomUUID();
        List<Field> none = List.of();
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(null, org, "A", "n", null, null, none, 8, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(id, null, "A", "n", null, null, none, 8, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(id, org, null, "n", null, null, none, 8, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(id, org, " ", "n", null, null, none, 8, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(id, org, "A", null, null, null, none, 8, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(id, org, "A", " ", null, null, none, 8, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(id, org, "A", "n", null, null, null, 8, null, "op"));
        List<Field> tooMany = new ArrayList<>();
        for (int i = 0; i <= CatalogItem.MAX_FIELDS; i++) {
            tooMany.add(new Field("f" + i, "Label", false));
        }
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(id, org, "A", "n", null, null, tooMany, 8, null, "op"));
        List<Field> twice = List.of(new Field("a", "A", false), new Field("a", "Again", false));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(id, org, "A", "n", null, null, twice, 8, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(id, org, "A", "n", null, null, none, 0, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(id, org, "A", "n", null, null, none, CatalogItem.MAX_TARGET_HOURS + 1, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(id, org, "A", "n", null, null, none, 8, null, " "));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.createNew(id, org, "A", "n", null, null, none, 8, null, null));
        assertEquals(CatalogItemStatus.DRAFT, CatalogItem.createNew(id, org, "A", "n", null, null, none, CatalogItem.MAX_TARGET_HOURS, null, "op").getStatus());
    }

    @Test
    @DisplayName("a question key is a letter followed by up to 39 letters, digits or underscores, and needs a label")
    void fieldGuards() {
        assertThrows(IllegalArgumentException.class, () -> new Field(null, "L", false));
        assertThrows(IllegalArgumentException.class, () -> new Field("1abc", "L", false));
        assertThrows(IllegalArgumentException.class, () -> new Field("has space", "L", false));
        assertThrows(IllegalArgumentException.class, () -> new Field("a".repeat(41), "L", false));
        assertThrows(IllegalArgumentException.class, () -> new Field("ok", null, false));
        assertThrows(IllegalArgumentException.class, () -> new Field("ok", " ", false));
        assertEquals("ok_1", new Field("ok_1", "L", true).key());
    }

    @Test
    @DisplayName("DRAFT -> PUBLISHED -> RETIRED, each step audited; only a PUBLISHED item can be requested")
    void lifecycle() {
        CatalogItem item = draft();
        InvalidCatalogItemStatusException draftBlocked = assertThrows(InvalidCatalogItemStatusException.class, item::requireRequestable);
        assertTrue(draftBlocked.getMessage().contains("DRAFT"));
        assertThrows(InvalidCatalogItemStatusException.class, () -> item.retire("op-1"));

        var published = item.publish("op-1");
        assertEquals(CatalogItemStatus.DRAFT, published.fromStatus());
        assertEquals(CatalogItemStatus.PUBLISHED, item.getStatus());
        item.requireRequestable();
        assertThrows(InvalidCatalogItemStatusException.class, () -> item.publish("op-1"));

        var retired = item.retire("op-1");
        assertEquals(CatalogItemStatus.RETIRED, retired.toStatus());
        assertThrows(InvalidCatalogItemStatusException.class, item::requireRequestable);
        assertEquals(3, item.getAuditTrail().size());
    }

    @Test
    @DisplayName("details are editable only while DRAFT and are validated like on creation")
    void update() {
        CatalogItem item = draft();
        UUID policy = UUID.randomUUID();

        var entry = item.updateDetails("New name", "d", "c", List.of(), 48, policy, "op-2");

        assertEquals("UPDATED", entry.action());
        assertEquals(CatalogItemStatus.DRAFT, entry.toStatus());
        assertEquals("New name", item.getName());
        assertEquals(48, item.getFulfilmentTargetHours());
        assertEquals(policy, item.getApprovalPolicyId());
        assertTrue(item.getFields().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> item.updateDetails(" ", null, null, List.of(), 8, null, "op"));
        assertThrows(IllegalArgumentException.class, () -> item.updateDetails("n", null, null, List.of(), 8, null, " "));

        item.publish("op-1");
        assertThrows(InvalidCatalogItemStatusException.class, () -> item.updateDetails("x", null, null, List.of(), 8, null, "op"));
    }

    @Test
    @DisplayName("a transition needs an executor")
    void transitionNeedsExecutor() {
        assertThrows(IllegalArgumentException.class, () -> draft().publish(" "));
    }

    @Test
    @DisplayName("reconstitute needs the identity and defaults the optional state")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.reconstitute(null, org, "C", "n", null, null, null, 8, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.reconstitute(id, null, "C", "n", null, null, null, 8, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.reconstitute(id, org, null, "n", null, null, null, 8, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> CatalogItem.reconstitute(id, org, "C", null, null, null, null, 8, null, null, null, null, null));

        CatalogItem bare = CatalogItem.reconstitute(id, org, "C", "n", null, null, null, 8, null, null, null, null, null);
        assertEquals(CatalogItemStatus.DRAFT, bare.getStatus());
        assertTrue(bare.getFields().isEmpty());
        assertTrue(bare.getAuditTrail().isEmpty());
        assertEquals(bare.getCreatedAt(), bare.getUpdatedAt());

        CatalogItem source = draft();
        source.publish("op-1");
        CatalogItem full = CatalogItem.reconstitute(source.getId(), org, source.getCode(), source.getName(), source.getDescription(), source.getCategory(),
                source.getFields(), 24, null, CatalogItemStatus.PUBLISHED, source.getCreatedAt(), source.getUpdatedAt(), source.getAuditTrail());
        assertEquals(CatalogItemStatus.PUBLISHED, full.getStatus());
        assertEquals(2, full.getAuditTrail().size());
        assertEquals(source.getUpdatedAt(), full.getUpdatedAt());
    }
}
