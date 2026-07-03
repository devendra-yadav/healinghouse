# Healing House Clinic Management System
## Requirements Document & Phased Implementation Guide

**Version:** 1.1 (Updated with user clarifications)  
**Date:** June 27, 2026  
**Clinic:** Healing House Clinic  
**Prepared by:** Grok (Senior Java Developer & Architect)  
**Purpose:** Provide a complete, actionable blueprint to build a fresh Spring Boot application for managing your clinic operations, appointments, patients, therapists, services, products, and therapist performance/perks.

---

## Executive Summary

This document defines a **modern, clean, maintainable Spring Boot + Thymeleaf + MySQL** web application tailored specifically for **Healing House Clinic**.

The system will allow you (as Admin) to:
- Manage patients, therapists, service catalog, and herbal/natural product inventory.
- Schedule and record appointments with multiple services and products sold in one visit.
- Automatically track revenue, attribute sales to therapists, and calculate **fair therapist payouts** (fixed salary + commission on services/products + performance bonuses).
- Get powerful daily, weekly, and monthly views/reports showing exactly how many patients came, what they bought/did, who treated them, and how much each therapist earned in perks that day/month.

**Important Scope Decision (as requested):**
- **No login, no security, no roles in initial phases.** The entire application behaves as if you are the Admin with full access. We will add therapist logins, role-based access, and Spring Security **only after** the core functionality is complete and stable.
- Focus 100% on **core operational features** first.

The document is structured so you (or an AI coding assistant like Claude/Cursor/Windsurf) can implement it **step-by-step** with clear deliverables after each phase. You can literally say:  
> "Follow the Healing House Clinic Requirements v1.0 and implement **Phase 2 Step 2.3** now."

---

## 1. Business Context & Key Requirements

### What Healing House Clinic Offers
- **TCM-related therapies** (Traditional Chinese Medicine)
- Massages (various kinds)
- Acupuncture
- Ion therapy
- Detoxification services/programs
- Compression therapy ("Compressor")
- Other natural/holistic therapies
- Sale of **herbal products**, supplements, oils, teas, detox kits, etc.

### Core Business Rules You Described
- Every appointment involves **one primary therapist** who performed the work.
- The therapist gets:
  - **Fixed monthly salary** (base pay)
  - **Commission** (example: 10%) on the value of services performed **and** products sold during/through their appointments
  - **Performance bonus** (example: ₹5,000) if they cross a threshold (e.g., >100 massages/services in a month)
- You need visibility into:
  - Daily/weekly/monthly patient footfall + services taken + products bought
  - Revenue (services vs products)
  - Per-therapist earnings breakdown (so you know exactly what to pay as "perks" at month end)
- Simple admin workflow: Add/Edit/Delete everything, schedule/record appointments quickly, see insightful reports.

---

## 2. Assumptions (Clearly Stated)

1. **TCM** = Traditional Chinese Medicine. Services will have a flexible `category` field so you can tag "TCM", "Massage", "Hijama", "IonDetox", "Acupuncture", etc. All services are fully configurable via admin UI.
2. **One therapist per appointment** (the person who gets credit for commission/bonus). Multi-therapist appointments can be added later.
3. Product sales always happen **inside an appointment** (after consultation). No separate counter sales in core.
4. All money in **Indian Rupees (₹)**. Use `BigDecimal` everywhere for precision.
5. Status flow (simple for core): `SCHEDULED` → `COMPLETED` (or `CANCELLED` / `NO_SHOW`).
6. Stock is decremented **only when an appointment is marked COMPLETED** and products are included.
7. Bonus threshold is based on **number of services performed** (line items), not number of appointments. This matches your "100 massages" example.
8. Fixed salary is shown as reference in reports. Actual "payout" calculation focuses on **variable pay** (commission + bonus) earned in the selected period. You can adjust the business rule later.
9. No recurring appointments, treatment packages, or patient loyalty points in **core phase**.
10. UI must be usable on a tablet at reception desk (responsive Bootstrap).

If any assumption is wrong, reply with corrections and we will update this document + adjust the phases.

---

## 3. Clarifications Incorporated (from User Feedback - v1.1)

All points from the original questions have been addressed and integrated:
- PCM → **TCM (Traditional Chinese Medicine)**
- Services: Fully configurable by Admin. Examples include Acupuncture, Hijama, Deep Tissue Massage, Foot Ion Detox, etc. Appointments are general; exact treatments decided post-consultation and recorded via line items.
- Commission, bonus, and salary rules: **Per-therapist configurable** (different values supported).
- No pure counter sales — all product sales tied to appointments after consultation.
- Payment methods: CASH, UPI, BANK_TRANSFER, CARD, OTHER (as implemented in `PaymentMethod` enum).
- Patient: DOB (estimated from age on entry) with auto age calculation; other fields (allergies, medical history, ID proof, etc.) optional.
- Reports: Support **daily, weekly, monthly, and custom date ranges**.
- **Marcia Gomes Yadav** (owner/main therapist): Special handling — no salary or commission calculations.
- Development: Broken into very small, incremental stages to minimize bugs.
- No existing data import needed.

---

## 4. Technology Stack & Project Setup

## 4. Technology Stack & Project Setup

| Layer          | Technology                          | Version / Notes                     |
|----------------|-------------------------------------|-------------------------------------|
| Backend        | Spring Boot                         | Latest stable (3.3+ / 3.4+ as of 2026) |
| Web            | Spring MVC + Thymeleaf              | Thymeleaf 3.x                       |
| Database       | MySQL 8+                            | Local first                         |
| ORM            | Spring Data JPA + Hibernate         | ddl-auto=update initially           |
| Utilities      | Lombok, Jakarta Validation          | -                                   |
| Frontend       | Bootstrap 5.3 (CDN) + Vanilla JS    | Clean clinic theme (calming greens) |
| Charts         | Chart.js (CDN)                      | For dashboard & reports             |
| Build          | Maven                               | -                                   |
| Java           | 21                                   | As implemented                      |
| Migrations     | Hibernate `ddl-auto=update`         | Flyway not adopted; schema auto-managed by Hibernate (see `application.yml`). No migration files exist yet. |

> **As-built note:** the actual base package is `com.clinic.healinghouse` (not `com.healinghouse.clinic` as originally drafted below), and Spring Boot 4.1.0 is in use. Package layout otherwise matches the structure below.

**Recommended package structure:**
```
com.clinic.healinghouse
├── HealinghouseApplication.java
├── config/
├── controller/          (web controllers returning Thymeleaf views)
├── dto/                 (form DTOs, report DTOs)
├── entity/              (JPA entities + enums)
├── exception/           (@ControllerAdvice + custom exceptions)
├── repository/          (Spring Data JPA repos)
├── service/             (business logic + commission calculator)
├── util/                (formatters, date helpers)
└── resources/
    ├── templates/
    │   ├── fragments/   (layout, header, sidebar, alerts)
    │   ├── dashboard.html
    │   ├── patients/
    │   ├── therapists/
    │   ├── services/
    │   ├── products/
    │   ├── appointments/
    │   └── reports/
    ├── static/
    │   ├── css/
    │   ├── js/
    │   └── images/      (logo placeholder)
    └── db/migration/    (Flyway .sql files)
```

**Database name (local):** `healing_house_clinic`

---

## 5. Domain Model (Core Entities)

### Entity Relationship Overview (Mermaid - copy to any MD viewer)

```mermaid
erDiagram
    PATIENT ||--o{ APPOINTMENT : "books"
    THERAPIST ||--o{ APPOINTMENT : "handles"
    APPOINTMENT ||--o{ APPOINTMENT_SERVICE_LINE : "includes"
    APPOINTMENT ||--o{ APPOINTMENT_PRODUCT_LINE : "sells"
    CLINIC_SERVICE ||--o{ APPOINTMENT_SERVICE_LINE : "performed"
    PRODUCT ||--o{ APPOINTMENT_PRODUCT_LINE : "sold"
```

> **As-built note:** entity class names differ slightly from the names below to avoid collisions with Spring's own `@Service` stereotype: `Service` → **`ClinicService`**, `AppointmentService` → **`AppointmentServiceLine`**, `AppointmentProduct` → **`AppointmentProductLine`**.

### Core Entities (Detailed)

**1. Patient**
- `id` (Long, PK, auto)
- `fullName` (String, required)
- `phone` (String, unique/index)
- `email` (String, optional)
- `gender` (Enum or String)
- `dateOfBirth` (LocalDate)
- `address` (String)
- `medicalHistory`, `allergies`, `notes` (Text)
- `active` (boolean, default true)
- `createdAt`, `updatedAt`

**2. Therapist**
- `id`
- `fullName` (e.g. "Marcia Gomes Yadav" is the owner/main therapist — special handling: **no salary or commission calculation** for her)
- `specialization` (e.g. "Massage Therapist", "Acupuncturist & Detox Specialist")
- `phone`, `email`
- `fixedMonthlySalary` (BigDecimal, null/0 for owner)
- `commissionRate` (BigDecimal, configurable, null/0 for owner; can differ per therapist)
- `performanceBonusThreshold` (Integer, configurable)
- `performanceBonusAmount` (BigDecimal, configurable)
- `notes`
- `active`
- `createdAt`, `updatedAt`

**Note:** Commission rules (rate, threshold, bonus amount) are fully configurable **per therapist**. Marcia Gomes Yadav (owner) has no salary/commission calculations. Future enhancement: per-therapy/product commission rates if needed.

**3. ClinicService** (Treatment Catalog — named `ClinicService` to avoid clashing with Spring's `@Service`)
- `id`
- `name` (e.g. "Swedish Massage 60 min")
- `description`
- `category` (String: "Massage", "Acupuncture", "PCM", "Detox", "IonTherapy", "Compression", "Other")
- `durationMinutes` (Integer)
- `price` (BigDecimal)
- `active`

**4. Product** (Herbal & Natural Items)
- `id`
- `name`
- `description`
- `category` (String: "Herbal Supplement", "Oil", "Tea", "Detox Kit", etc.)
- `price` (BigDecimal)
- `stockQuantity` (Integer)
- `reorderLevel` (Integer, default 5)
- `active`

**5. Appointment**
- `id`
- `patient` (ManyToOne)
- `therapist` (ManyToOne)
- `appointmentDateTime` (LocalDateTime)
- `status` (Enum: SCHEDULED, COMPLETED, CANCELLED, NO_SHOW)
- `notes` (Text)
- `cancelReason` (String, set when status becomes CANCELLED)
- `totalServiceAmount`, `totalProductAmount`, `grandTotal`, `amountPaid` (BigDecimal)
- `paymentMethod` (Enum: CASH, UPI, BANK_TRANSFER, CARD, OTHER)
- `createdAt`, `completedAt`, `updatedAt`

**6. AppointmentServiceLine** (Line Item)
- `id`
- `appointment` (ManyToOne)
- `service` (ManyToOne → `ClinicService`)
- `priceAtTime` (BigDecimal)   // snapshot
- `quantity` (Integer, default 1)

**7. AppointmentProductLine** (Line Item)
- `id`
- `appointment` (ManyToOne)
- `product` (ManyToOne)
- `quantity` (Integer)
- `priceAtTime` (BigDecimal)
- `lineTotal` (BigDecimal, calculated)

> **Design Note:** We use separate line-item entities so history is preserved even if catalog prices change later. Totals are calculated in service layer (not stored redundantly except for quick reporting).

---

## 6. Core Features (What Must Work)

### 6.1 Master Data (Full CRUD + Search)
- Patients (list + search by name/phone + view history link)
- Therapists (list + salary/commission/bonus config visible)
- Services (by category)
- Products (stock visible, low-stock highlighted)

### 6.2 Appointment Flow (Most Important)
- Create appointment form with:
  - Patient selector (dropdown + search)
  - Therapist selector
  - Date + Time (`datetime-local`)
  - Dynamic "Services performed" section (add multiple rows via JS, live price + total)
  - Dynamic "Products sold" section (add multiple, show current stock, warn if insufficient)
  - Notes
  - Payment section (method + amount paid)
- On save → persist lines, snapshot prices, calculate totals, **decrement stock**, link everything to the chosen therapist.
- List appointments (filter by date range, therapist, patient, status)
- Detail view + "Mark as Completed" action (if not already)
- Cancel with reason (soft)

### 6.3 Reports & Therapist Earnings (Your Key Ask)
**Daily View** (select any date):
- Total appointments, patients served, revenue split (services vs products)
- Per-therapist mini table: appts handled, services count, revenue generated, commission earned that day

**Daily / Weekly / Monthly / Custom Date Range View**:
- KPI cards + breakdown table per therapist:
  - Services Revenue | Products Revenue | Commission Earned (services + products × rate)
  - Services Performed Count | Bonus Earned? (Yes/No + amount)
  - **Total Variable Pay** for the period
- Reference: Fixed Monthly Salary shown for context
- Export to CSV button (for accountant)

**Commission Calculation Logic (to be implemented in service):**
```java
BigDecimal serviceCommission = servicesRevenue.multiply(therapist.getCommissionRate());
BigDecimal productCommission = productsRevenue.multiply(therapist.getCommissionRate());
BigDecimal totalCommission = serviceCommission.add(productCommission);

BigDecimal bonus = BigDecimal.ZERO;
if (totalServicesPerformedCount >= therapist.getPerformanceBonusThreshold()) {
    bonus = therapist.getPerformanceBonusAmount();
}
BigDecimal totalVariablePay = totalCommission.add(bonus);
```

### 6.4 Dashboard (Home Page)
- Today's appointments (quick list)
- KPI cards: Today's appts, This month's revenue, Low stock items, Active therapists
- Mini charts: Last 7/30 days revenue trend + Revenue by category pie
- Quick action buttons: New Appointment, New Patient, View Reports

---

## 7. Phased Implementation Roadmap (Ready for AI Coding)

Each phase ends with a **working, testable increment**. Use the exact phase/step numbers when prompting your coding assistant.

### Phase 0: Project Bootstrap (Run this first) — Small incremental stage

**Step 0.1** — Generate project at [start.spring.io](https://start.spring.io)
- Maven + Java 21 + Spring Boot latest stable
- Dependencies: **Spring Web, Thymeleaf, Spring Data JPA, MySQL Driver, Lombok, Validation, DevTools**

**Step 0.2** — Create local MySQL database:
```sql
CREATE DATABASE healing_house_clinic 
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'clinic_user'@'localhost' IDENTIFIED BY 'StrongPass123!';
GRANT ALL ON healing_house_clinic.* TO 'clinic_user'@'localhost';
FLUSH PRIVILEGES;
```

**Step 0.3** — Configure `application.yml` (or `.properties`) with datasource, `ddl-auto: update`, `show-sql: true`, Thymeleaf cache false for dev.

**Step 0.4** — Create package structure + a basic `HomeController` returning a placeholder dashboard with navigation links to all future sections (Patients, Therapists, Services, Products, Appointments, Reports).

**Step 0.5** — Add a simple `fragments/layout.html` (header with clinic name "Healing House Clinic", nav, footer). Make home page use the layout.

**Deliverable:** Application starts cleanly on `http://localhost:8080`, shows nice header/nav, connects to your local MySQL without errors.

---

### Phase 1: Master Data – Patients, Therapists, Services, Products

**Step 1.1** — Create all **Entity** classes + Enums (`AppointmentStatus`, `PaymentMethod`) with proper JPA, Lombok (`@Data`, `@Builder` where safe), `@CreationTimestamp`/`@UpdateTimestamp`, `active` flags, and sensible indexes.

**Step 1.2** — Create **Repository** interfaces (extend `JpaRepository`). Add useful query methods (`findByActiveTrue()`, `findByPhoneContainingIgnoreCase`, `findByFullNameContainingIgnoreCase`, etc.).

**Step 1.3** — Create **Service** classes (e.g. `PatientService`, `TherapistService`) with CRUD methods + search. Use `@Transactional` where appropriate.

**Step 1.4** — Create **Controllers + Thymeleaf views** for:
- Patients (list table + search form + create/edit form with validation)
- Therapists (show salary, commission rate nicely formatted, bonus config)
- Services (category dropdown or chips)
- Products (stock column + low stock warning in red)

Use Bootstrap 5 tables, forms, modals for delete confirmation. Add flash messages (`RedirectAttributes`).

**Step 1.5** — Create `DataSeeder` (implements `CommandLineRunner`) that populates realistic sample data **only if tables are empty** on first run:
- Several Patients (with DOB estimates)
- 3–4 Therapists (include Marcia Gomes Yadav as owner with salary=0, commission=0; others with configurable values)
- 8–10 Services (TCM, Acupuncture, Hijama, Deep Tissue Massage, Foot Ion Detox, etc.)
- 6–8 Products with varying stock levels

**Deliverable Phase 1:** You can fully manage (add/edit/deactivate/search) all master data through a clean UI. Sample data is there for testing. No broken pages.

---

### Phase 2: Appointment Management & Line Items (Core Workflow)

**Step 2.1** — Implement `Appointment`, `AppointmentServiceLine`, `AppointmentProductLine` entities + repositories. Add proper relationships (ManyToOne with `FetchType.LAZY`).

**Step 2.2** — Create `AppointmentService.java` (business logic) with methods:
- `createAppointment(...)` — handles validation, price snapshots, total calculation, stock decrement, persistence.
- `findByFilters(...)`
- `markAsCompleted(Long id)`
- `cancelAppointment(id, reason)` — restores product stock on cancel
- `markAsNoShow(Long id)` — restores product stock (as-built addition beyond original spec)
- `updateAppointment(id, form)` — full edit for SCHEDULED appointments, incl. re-snapshotting lines and stock adjustment (as-built addition beyond original spec)

**Step 2.3** — Build the **Appointment creation form** (most complex UI):
- Patient & Therapist selects
- `datetime-local` input
- **Dynamic Services section** (JS-powered "Add Service" button that appends a row with `<select>`, auto-fills price, live subtotal, remove button). Client-side total calculation.
- **Dynamic Products section** (same pattern + current stock display + warning if qty > stock)
- Notes + Payment method + Amount paid
- Big live "Grand Total" display
- On submit: send data to controller (use JS to serialize lines into hidden JSON field or indexed form params — common Thymeleaf pattern)

**Step 2.4** — Appointment list page with server-side filters (date range, therapist, status, patient name). Table shows key info + action buttons (View, Cancel, Complete).

**Step 2.5** — Appointment detail page (nice cards showing patient info, therapist, all line items in tables, totals, payment info, status actions).

**Deliverable Phase 2:** Complete end-to-end appointment flow works. Multiple services + products per appointment, stock updates correctly, everything attributed to the chosen therapist. List + detail views are usable.

---

### Phase 3: Dashboard + Reports + Therapist Earnings Calculation

**Step 3.1** — Create `ReportService` / `DashboardService` + `CommissionCalculator` (or one service with clear methods).

Implement the exact calculation logic from section 6.3.

**Step 3.2** — Build **Dashboard** (`/`):
- KPI cards (Today's appointments, Monthly revenue, etc.)
- Today's appointments mini list
- Low stock alert list
- Two Chart.js charts (revenue trend + category breakdown) — data prepared in controller/model

**Step 3.3** — Build **Reports** section:
- `/reports/daily` — date picker → shows daily breakdown + per-therapist earnings table
- `/reports/period` — date range form → comprehensive therapist earnings report with commission + bonus columns + CSV export button

**Step 3.4** — Add CSV export utility (simple Java CSV writer, no extra heavy dependency if possible).

**Deliverable Phase 3:** You can now see exactly what you asked for — per day/week/month views of patients, services, products, payments, and **precise therapist perk calculations** (commission + bonus). Numbers match the business rules you described.

---

### Phase 4: Polish, UX, Testing & Documentation

**Step 4.1** — Consistent layout using Thymeleaf fragments (header, sidebar nav, footer, alert messages, confirmation modals).

**Step 4.2** — UX improvements:
- Responsive design (test on tablet size)
- Client-side form enhancements (better dynamic rows, total auto-update)
- Date formatting (nice readable dates everywhere)
- Success/error toasts or Bootstrap alerts
- Quick search on list pages

**Step 4.3** — Add indexes on high-query columns (`appointmentDateTime`, foreign keys) via `@Index` or Flyway migration.

**Step 4.4** — Update `README.md` with setup instructions, screenshots placeholders, how to run, and future roadmap.

**Step 4.5** (optional but recommended) — Basic test coverage for `CommissionCalculator` service (unit test with mocked data).

**Deliverable Phase 4:** Polished, professional internal tool ready for daily use at the clinic. All core functionality complete and delightful to use.

---

## 8. Future Roadmap (After Core is Done)

1. **Phase 5** — Spring Security + User management
   - Admin full access
   - Therapist login → own schedule + own earnings dashboard only
2. **Phase 6** — PDF invoice / receipt generation + email/SMS reminders (optional)
3. **Phase 7** — Advanced features (treatment packages, recurring appts, detailed patient visit notes history, GST invoices, multi-location)
4. Docker + production deployment guide

---

## 9. How to Use This Document with AI Coding Assistants

1. Keep this file in your project root or share the relevant section.
2. Start a new chat/session with your AI (Claude, Cursor, etc.).
3. Paste the **Executive Summary + the exact Phase + Step** you want.
4. Example prompt:
   ```
   You are a senior Spring Boot developer. Follow the "Healing House Clinic Requirements Document v1.0" exactly.

   Implement Phase 1 Step 1.4 and Step 1.5:
   - Create clean Thymeleaf + Bootstrap UI for Patients and Therapists CRUD.
   - Add realistic DataSeeder for first run.
   - Use proper validation and flash messages.
   - Make sure everything follows the entity design in section 5.

   After finishing, give me a summary of what was created and any files I should review.
   ```
5. Review the code, test in browser, then say "Good, now do Phase 2 Step 2.3" or give specific feedback for fixes.

This phased approach minimizes context loss and keeps the project clean and on track.

---

## 10. Getting Started Right Now

1. Reply with answers to the **Open Questions** (section 3) if you have any changes.
2. Create the folder `healing-house-clinic` and run **Phase 0 Step 0.1 – 0.5**.
3. Once Phase 0 is done and the app runs, come back and say:  
   **"Phase 0 complete. Let's start Phase 1."**

I will then guide you (or generate the exact code) for the next steps.

---

**This system will give you full control and transparency over your clinic operations and therapist compensation — exactly as you described.**

Ready when you are. Let's build it step by step.

---

*Document Version 1.0 – Healing House Clinic – June 2026*