# Healing House Clinic — Tags for Services & Products
## Requirements Document (Addendum to Requirements v1)

**Version:** 1.0
**Date:** July 7, 2026
**Status:** Approved — ready for implementation, all open questions resolved
**Relation to core doc:** Extends `Healing_House_Clinic_Requirements_v1.md`. Replaces the hardcoded `category` field on `ClinicService` (section 5, entity 3) and `Product` (section 5, entity 4). Builds on Phase 1 (Master Data, done). Can be implemented as **Phase 1.5**, independently of Phase 3 (Dashboard/Reports), though Phase 3's "Revenue by category" chart should be re-pointed to tags when built.

---

## 1. Problem Statement

Today, `ClinicService.category` and `Product.category` are free-text `String` columns, constrained only by a hardcoded `List<String>` of allowed values living in each controller (`TreatmentController.CATEGORIES`, `ProductController.CATEGORIES`). Consequences:

- Adding a new category requires a code change and redeploy.
- Each service/product can only belong to **one** category, even though real items often span multiple concepts (e.g. a "Foot Ion Detox" is both `IonTherapy` and `Detox`; a "Ginger Tea" is both `Tea` and `Detox Kit`).
- Product and Service categories are separate hardcoded lists even though some concepts (e.g. "Detox") apply to both.

This document replaces `category` with a **Tag** entity: a free-text-created, autocompleted, many-to-many label that a Service or Product can have one or more of.

---

## 2. Goals

- A `Tag` is a simple named label (e.g. "Massage", "Detox", "TCM"), created by admins on the fly — no upfront hardcoded list, no dedicated "add tag" step required before it can be used.
- A **Service** or **Product** can have **one or more** tags.
- Tags are a **single shared pool** across both Services and Products — the same "Detox" tag can be attached to a service and a product. One tag table, one autocomplete source.
- On the Service/Product create/edit form: a free-text tag input where the admin types a tag name and adds it (chip-style), can add another, and can remove any already-added tag before saving. While typing, if an existing tag matches the characters typed so far, it is suggested in a dropdown so the admin can pick it instead of creating a near-duplicate (e.g. "Detox" vs "detox " vs "Detoxs").
- A dedicated **Tags management page** (`/tags`) to view all tags, see usage counts, rename a tag (renames everywhere it's used), merge two tags into one, and delete a tag that is unused (or after confirming reassignment).
- Existing hardcoded category values are migrated into tags automatically (see section 6), and the `category` column is dropped from both entities after migration.
- Service and Product list pages replace the current single-select "category filter chips" with **tag filter chips**; since an item can have multiple tags, selecting a tag shows all items that have that tag (not exclusively that tag).

### Non-goals (explicitly out of scope for this iteration)
- Tag hierarchies / parent-child categories (e.g. "Massage" containing "Deep Tissue", "Swedish"). Flat tags only.
- Per-tag colors or icons in the UI beyond a consistent badge style.
- Tag-based commission rules (commission stays purely per-therapist, unaffected by tags).
- Rebuilding the Phase 3 dashboard "Revenue by category" chart — that is Phase 3 work and not yet built. When it is built, it should group by tag instead of category (an item with N tags contributes to each of its N tags' totals), per this doc's design, but the chart implementation itself belongs to Phase 3.
- Restricting which tags apply to Services vs Products (any tag can be used on either).

---

## 3. Data Model

### 3.1 New Entity: `Tag`

| Field       | Type      | Notes                                              |
|-------------|-----------|-----------------------------------------------------|
| `id`        | Long, PK  | auto-generated                                     |
| `name`      | String    | required, **unique** (case-insensitive), e.g. "Detox" |
| `createdAt` | Timestamp | `@CreationTimestamp`                               |

- Uniqueness enforced case-insensitively at the service layer (and a unique index on a normalized/lowercase column, or a `unique` constraint plus a pre-save lookup) so "Detox" and "detox" cannot both be created.
- No `active` flag needed — unused tags can simply be deleted from the Tags management page.

### 3.2 Updated Entities: `ClinicService` and `Product`

- **Remove** the `category` field (`String`) and its DB index (`idx_service_category`, `idx_product_category`) from both entities.
- **Add** a `@ManyToMany` relationship to `Tag` via join tables:
  - `service_tag` (`service_id`, `tag_id`)
  - `product_tag` (`product_id`, `tag_id`)
- Field name: `tags` (`Set<Tag>`), fetched `LAZY`, exposed via a helper for display (e.g. sorted by name).
- `@EqualsAndHashCode`/`@ToString` on both entities should exclude `tags` (same pattern already used to exclude `serviceLines`/`productLines` on `Appointment`) to avoid Lombok circular-reference issues.

### 3.3 Repository

- New `TagRepository extends JpaRepository<Tag, Long>` with:
  - `findByNameIgnoreCase(String name)` — used to look up an existing tag before creating a new one.
  - `findByNameContainingIgnoreCaseOrderByNameAsc(String partial)` — powers the autocomplete endpoint.
  - A count/usage query (or computed in the service layer) for the Tags management page's usage column.

---

## 4. UI/UX

### 4.1 Tag Input on Service/Product Create/Edit Forms

Replaces the current `<select>` dropdown for category in `services/form.html` and `products/form.html` with a **tag chip input**, same component reused on both forms:

- A text input labeled "Tags" with placeholder like "Type a tag and press Enter…".
- As the admin types, an autocomplete dropdown below the input shows existing tags whose name contains the typed text (case-insensitive), fetched via a small JSON endpoint (debounced, e.g. 200ms).
- Admin can either:
  - Click a suggestion to add that existing tag, or
  - Press **Enter** (or a "+Add" button) to add the typed text as a tag — if a tag with that exact name (case-insensitive) already exists, it reuses it; otherwise a new `Tag` is created on save.
- Added tags render as removable **chips** (badge + small "×") below the input, in the order added.
- Duplicate prevention: typing/selecting a tag already added as a chip is a no-op (or shows a brief inline note), not a duplicate chip.
- At least **one tag is recommended but not strictly required** — mirrors current behavior where `category` is optional (nullable) on both entities today.
- Implementation approach: plain vanilla JS (consistent with the rest of the app's "Bootstrap 5.3 + Vanilla JS, no npm build step" stack) — a hidden form field (e.g. comma-separated tag names or repeated hidden inputs) is populated as chips are added/removed, and read by the controller on submit.

### 4.2 Autocomplete Endpoint

- `GET /tags/search?q={text}` → JSON array of matching tag names (or `{id, name}` pairs), max ~10 results, ordered alphabetically. Used by both the Service and Product forms.

### 4.3 List Pages — Tag Filter Chips

`services/list.html` and `products/list.html`:
- Replace the current single-select category chips (`th:each="cat : ${categories}"`, one active category at a time) with tag chips sourced from **all tags currently in use** by that entity type.
- Clicking a tag chip filters the list to items that **have that tag** (an item with multiple tags can appear under any of them). Keep it single-tag-select-at-a-time for this iteration (matches today's simple UX) — clicking a different chip switches the filter, clicking the active one or "All" clears it.
- The list table's "Category" column becomes a "Tags" column, rendering all of an item's tags as small badges.

### 4.4 New Page: Tag Management (`/tags`)

- `GET /tags` — table listing all tags: name, usage count (number of services + products using it, or split into two columns), and actions.
- **Rename**: inline edit or small modal — updates the tag's `name` in place; since services/products reference the tag by ID, this instantly re-labels it everywhere.
- **Merge**: select a source tag and a target tag — all services/products tagged with the source get the target tag added (if not already present) and the source tag is deleted. Used to clean up near-duplicates (e.g. merge "Detoxification" into "Detox").
- **Delete**: allowed for any tag; if in use, show a confirmation listing how many items will lose this tag (removes the association, does not delete the services/products themselves).
- Reuses the standard `fragments/layout.html` shell and Bootstrap modal-confirmation pattern already used for service/product delete.

---

## 5. Backend Changes Summary

| Layer | Change |
|---|---|
| `entity/Tag.java` | New entity as described in 3.1 |
| `entity/ClinicService.java` | Remove `category`; add `@ManyToMany Set<Tag> tags` |
| `entity/Product.java` | Remove `category`; add `@ManyToMany Set<Tag> tags` |
| `repository/TagRepository.java` | New, per 3.3 |
| `repository/ClinicServiceRepository.java` | Remove `findByCategoryAndActiveTrueOrderByNameAsc`; add `findByTagsNameAndActiveTrueOrderByNameAsc` (or similar) |
| `repository/ProductRepository.java` | Same pattern as above |
| `service/TagService.java` | New — `findOrCreate(name)`, `search(partial)`, `rename(id, newName)`, `merge(sourceId, targetId)`, `delete(id)`, usage-count queries |
| `service/TreatmentService.java` | Replace `findByCategory` with `findByTag`; wire tag resolution (find-or-create per typed name) into `save(...)` |
| `service/ProductService.java` | Same pattern as above |
| `controller/TreatmentController.java` | Remove hardcoded `CATEGORIES` list; pass tag suggestions/selected tag to model; accept tag names from form submission |
| `controller/ProductController.java` | Same pattern as above |
| `controller/TagController.java` | New — `/tags` CRUD-ish management page + `/tags/search` JSON autocomplete endpoint |
| `dto/` | Small form-binding addition: services/products forms need a `List<String> tagNames` (or similar) alongside existing fields, since tags aren't a simple scalar field |
| `config/DataSeeder.java` | Update to create `Tag` rows and associate them instead of setting `.category(...)` (see section 6) |

---

## 6. Migration Plan (Existing Data → Tags)

Since `ddl-auto: update` is in use (no Flyway migration files yet), this is a **one-time data migration** performed via a startup component (e.g. a `CommandLineRunner` that runs once, similar in spirit to `DataSeeder` but for migration rather than seeding — or folded into `DataSeeder` with a guard), executed **before** the `category` column is dropped from the entities:

1. On startup, if the `category` column still exists / still has non-null values and no migration has run yet:
   - For each distinct `category` string currently in `service` and `product` tables, create (or reuse, case-insensitively) a `Tag` with that name.
   - For each `ClinicService`/`Product` row with a non-null `category`, associate it with the corresponding `Tag` via the new join table.
2. Once confirmed working locally, remove the `category` column and this one-time migration code, since `ddl-auto: update` does not drop columns automatically — a manual `ALTER TABLE ... DROP COLUMN category` (or a fresh local DB drop/recreate, acceptable per current "no migration files yet" setup) is needed to fully remove it.
3. Update `DataSeeder` so freshly-seeded sample data (Massage, Acupuncture, TCM, Detox, IonTherapy, Compression, Hijama, Tea, Oil, Detox Kit, Capsule, Herbal Supplement, Other, etc.) is created directly as `Tag` associations rather than `category` strings — some seeded items should demonstrate **multiple tags** (e.g. "Foot Ion Detox" tagged both `IonTherapy` and `Detox`) to prove the many-to-many behavior works end to end.

**Note:** Because this app has no production data yet (internal tool, pre-launch), the safest and simplest path — if acceptable at implementation time — is to just let the migration component run once locally, verify tag associations look right, then drop the `category` column directly. No need to over-engineer a reversible migration.

---

## 7. Open Items Resolved During Requirements Gathering

| Question | Decision |
|---|---|
| Shared tag pool vs separate per entity? | **Shared** — one `Tag` table for both Services and Products. |
| Keep `category` column alongside tags, or replace it? | **Replace** — existing category values are converted to tags, then the `category` column is dropped. |
| Inline-only tag creation, or a management page? | **Both** — inline create-on-the-fly via autocomplete on the forms, plus a dedicated `/tags` page for rename/merge/delete. |
| How do multi-tag items interact with list filtering and future revenue-by-category charts? | List pages filter by "has this tag" (not exclusive); future tag-based revenue reporting attributes an item's revenue to **each** of its tags (Phase 3 scope, not this doc). |

---

## 8. Suggested Implementation Steps (Phase 1.5)

**Step 1.5.1** — Create `Tag` entity, repository, and `TagService` (find-or-create, search, rename, merge, delete, usage counts).

**Step 1.5.2** — Add `@ManyToMany Set<Tag> tags` to `ClinicService` and `Product`; keep `category` temporarily for the migration step.

**Step 1.5.3** — Write and run the one-time migration (section 6) against local dev data; verify via `/services` and `/products` that tags appear correctly; then drop the `category` column and field from both entities and their repositories.

**Step 1.5.4** — Build the tag chip input component (vanilla JS) and `/tags/search` autocomplete endpoint; wire into `services/form.html` and `products/form.html`, replacing the category `<select>`.

**Step 1.5.5** — Update `services/list.html` and `products/list.html`: tag filter chips + "Tags" column with badges, replacing category chips/column. Update `TreatmentController`/`ProductController` accordingly.

**Step 1.5.6** — Build `/tags` management page (`TagController` + `tags/list.html`) with rename, merge, and delete actions.

**Step 1.5.7** — Update `DataSeeder` to seed tags (including at least one multi-tag example per entity type) instead of category strings.

**Deliverable:** Services and Products each support multiple free-text, autocompleted tags from a shared pool; a `/tags` page allows cleanup/management; the old hardcoded category lists and column are fully removed.

---

*Document Version 1.0 – Healing House Clinic – July 2026*
