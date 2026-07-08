# Phase 3 Implementation Guide
## Dashboard + Reports + Analytics + Therapist Earnings Calculation

**Document Date:** July 8, 2026  
**Based on:** Healing House Clinic Requirements v1.2

---

## Executive Summary

Phase 3 transforms raw appointment data into actionable business intelligence. With **per-line commission attribution**, you'll have accurate visibility into therapist earnings, service/product performance, and patient trends across multiple report types.

**Key Deliverables:**
- Dashboard with KPI cards, charts, trends
- 5 comprehensive report types (Daily, Period, Comparison, Patient Acquisition, Performance)
- CSV and PDF export
- Full responsive design (mobile to desktop)
- Accurate per-line commission calculations

---

## Critical Implementation Decision: Per-Line Therapist Attribution

### What Changed from Original Requirements

**Original:** All lines in an appointment attributed to the main therapist.

**New (v1.2):** Each service/product line has its own therapist assignment:
- Service line → has a `therapist` field
- Product line → has a `therapist` field
- Default: appointment's main therapist
- Can be different: Dr. A did massage (service), Dr. B sold product

### Why This Matters

1. **Accurate Commission**: Each therapist gets credit only for their actual work
2. **Performance Tracking**: See which therapist drives which service/product
3. **Incentives**: Therapists are paid fairly for what they actually did

### Commission Calculation Logic (Per-Line)

```
For Therapist T in period P:
  
  servicesRevenue = SUM(line.lineTotal for all ServiceLines where line.therapist = T)
  productsRevenue = SUM(line.lineTotal for all ProductLines where line.therapist = T)
  
  serviceCommission = servicesRevenue × T.commissionRate
  productCommission = productsRevenue × T.commissionRate
  totalCommission = serviceCommission + productCommission
  
  servicesCount = COUNT(ServiceLines where line.therapist = T)
  
  bonus = T.bonusAmount if servicesCount ≥ T.bonusThreshold else 0
  
  totalVariablePay = totalCommission + bonus
```

---

## Phase 3 Architecture Overview

### Core Services to Build

```
CommissionCalculator
├── calculatePeriodiCommission(therapist, dateFrom, dateTo)
├── calculateDailyCommission(therapist, date)
├── calculateBonusEarned(therapist, servicesCount)
└── getTotalVariablePay(commission, bonus)

ReportService
├── getDailyReport(date) → DailyReportDTO
├── getPeriodReport(dateFrom, dateTo) → PeriodReportDTO
├── getTherapistComparison(therapistIds, dateFrom, dateTo) → ComparisonReportDTO
├── getPatientAcquisitionReport(dateFrom, dateTo) → PatientReportDTO
└── getProductPerformanceReport(dateFrom, dateTo) → PerformanceReportDTO

DashboardService
├── getTodayKPIs() → KPICardDTO[]
├── getTodayAppointments() → List<AppointmentSummaryDTO>
├── getLowStockAlerts() → List<ProductAlertDTO>
├── getRevenueTrend(days) → TrendChartDTO
└── getTagRevenueBreakdown(dateFrom, dateTo) → PieChartDTO

AnalyticsService
├── getTherapistComparison(therapistIds, period) → ComparisonAnalyticsDTO
├── getServicePerformance(dateFrom, dateTo) → List<ServicePerformanceDTO>
├── getProductPerformance(dateFrom, dateTo) → List<ProductPerformanceDTO>
└── getPatientTrends(dateFrom, dateTo) → PatientTrendsDTO
```

### Data Transfer Objects (DTOs) Needed

```
ReportDTOs:
- DailyReportDTO (summary + therapist table)
- PeriodReportDTO (summary + detailed earnings)
- ComparisonReportDTO (side-by-side therapist data)
- PatientReportDTO (acquisition + retention metrics)
- PerformanceReportDTO (tag/service/product breakdown)

ChartDTOs:
- LineChartDTO (revenue trend)
- PieChartDTO (tag breakdown)
- BarChartDTO (therapist comparison)
- AreaChartDTO (patient acquisition trend)

SummaryDTOs:
- TherapistEarningsDTO (for tables)
- ServicePerformanceDTO (count, revenue, top therapist)
- ProductPerformanceDTO (units sold, revenue, stock)
- PatientMetricsDTO (new/repeat, retention rate)
```

---

## Step-by-Step Implementation Roadmap

### Step 3.1: Core Calculation Services

**What to build:**
- `CommissionCalculator` — per-line commission logic
- `ReportAggregator` — queries and groups appointments by period/therapist/tag

**Key methods:**
```java
// CommissionCalculator
public BigDecimal calculateTherapistCommission(
    Therapist therapist, 
    LocalDate dateFrom, 
    LocalDate dateTo
) {
    // Query all lines where therapist = this one
    // Sum lineTotal for services and products
    // Multiply by commission rate
    // Return total
}

public BigDecimal calculateTherapistBonus(
    Therapist therapist,
    LocalDate dateFrom,
    LocalDate dateTo
) {
    // Count services performed by this therapist
    // Check if count >= bonusThreshold
    // Return bonusAmount or 0
}
```

**Database queries needed:**
```sql
-- Services performed by therapist in period
SELECT COUNT(*) FROM appointment_service_line asl
WHERE asl.therapist_id = ? 
  AND asl.created_at BETWEEN ? AND ?

-- Revenue attributed to therapist (services)
SELECT SUM(asl.line_total) FROM appointment_service_line asl
WHERE asl.therapist_id = ? 
  AND asl.created_at BETWEEN ? AND ?

-- Revenue attributed to therapist (products)
SELECT SUM(apl.line_total) FROM appointment_product_line apl
WHERE apl.therapist_id = ? 
  AND apl.created_at BETWEEN ? AND ?
```

### Step 3.2: Dashboard Implementation

**What to build:**
- `DashboardController` with method returning dashboard model
- `dashboard.html` template with responsive layout

**Key components:**
```
Dashboard (/):
  ├── KPI Cards Row (responsive grid)
  │   ├── Today's Appointments
  │   ├── Today's Revenue (₹)
  │   ├── Low Stock Items
  │   └── Active Therapists
  ├── Today's Appointments List (table-responsive)
  ├── Low Stock Alerts (collapsible card)
  ├── Revenue Trend Chart (Chart.js line)
  ├── Tag Revenue Breakdown (Chart.js pie)
  └── Quick Action Buttons
```

**Responsive breakpoints:**
- Mobile (375px): KPI cards 1 per row, charts full-width, navigation hamburger
- Tablet (768px): KPI cards 2×2 grid, charts 1 per row
- Desktop (1920px): KPI cards 4 in row, charts side-by-side

### Step 3.3: Reports Implementation

**Five report views to build:**

#### 3.3.1 Daily Report (`/reports/daily`)

**Input:** Date picker  
**Output:** Summary + per-therapist table

**Data needed:**
- Total appointments for date
- Split: new vs repeat patients
- Total revenue (services vs products breakdown)
- Per-therapist: appointment count, services count, revenue, commission, bonus, total pay

**Template:** `reports/daily.html`

#### 3.3.2 Period Report (`/reports/period`)

**Input:** Date range form  
**Output:** Detailed breakdown by therapist and tag

**Data needed:**
- All KPIs from daily, but aggregated for period
- Per-therapist detailed earnings
- Tag-based revenue table
- Product performance table

**Template:** `reports/period.html`

#### 3.3.3 Therapist Comparison (`/reports/comparison`)

**Input:** Multi-select therapists + date range  
**Output:** Side-by-side comparison

**Data needed:**
- All metrics for each selected therapist
- Charts: revenue, appointments, commission side-by-side
- Performance differential calculations

**Template:** `reports/comparison.html`

#### 3.3.4 Patient Acquisition (`/reports/patients`)

**Input:** Date range  
**Output:** Patient trends and per-therapist patient metrics

**Data needed:**
- New vs repeat patient counts
- Per-therapist: new patients, repeat patients, retention rate
- Trend chart (new vs repeat over time)

**Template:** `reports/patients.html`

#### 3.3.5 Product/Service Performance (`/reports/performance`)

**Input:** Date range  
**Output:** Performance breakdown by service, product, and tag

**Data needed:**
- Service table: name, tags, count, revenue, average price, top therapist
- Product table: name, tags, units sold, revenue, stock level, reorder priority
- Tag cross-tab: tag × therapist × revenue

**Template:** `reports/performance.html`

### Step 3.4: Export Functionality

**CSV Export:**
- Use OpenCSV or simple StringBuilder approach
- Generate from report data (same data as HTML tables)
- One file per report type
- Include headers and proper formatting

**PDF Export:**
- Use iText 7 (Maven dependency: `com.itextpdf:itext7-core`)
- Template: Use Thymeleaf to generate HTML, then convert to PDF (or use iText directly)
- Include: Header (clinic name, report type, date range), tables, charts (as images), footer

**Implementation approach:**
```java
// In ReportController
@GetMapping("/reports/period/export-csv")
public void exportPeriodReportCSV(
    @RequestParam LocalDate from,
    @RequestParam LocalDate to,
    HttpServletResponse response
) {
    PeriodReportDTO report = reportService.getPeriodReport(from, to);
    // Write CSV
    response.setContentType("text/csv");
    response.setHeader("Content-Disposition", "attachment;filename=period-report.csv");
    // Write report data
}

@GetMapping("/reports/period/export-pdf")
public void exportPeriodReportPDF(
    @RequestParam LocalDate from,
    @RequestParam LocalDate to,
    HttpServletResponse response
) {
    PeriodReportDTO report = reportService.getPeriodReport(from, to);
    // Generate PDF
    response.setContentType("application/pdf");
    response.setHeader("Content-Disposition", "attachment;filename=period-report.pdf");
    // Write PDF
}
```

### Step 3.5: Responsive Design

**Mobile-first approach:**

```html
<!-- KPI Cards: Stack on mobile, grid on desktop -->
<div class="row g-3">
  <div class="col-12 col-sm-6 col-lg-3">
    <div class="card card-kpi">...</div>
  </div>
  <!-- Repeat for other cards -->
</div>

<!-- Table: Card view on mobile, table on desktop -->
<div class="d-lg-none">
  <!-- Card-based mobile view -->
</div>
<div class="d-none d-lg-block table-responsive">
  <!-- Desktop table view -->
</div>
```

**Breakpoints:**
- xs (default): 320px-575px — Mobile phones
- sm: 576px-767px — Large phones
- md: 768px-991px — Tablets
- lg: 992px-1199px — Laptops
- xl: 1200px+ — Desktops

**Charts on mobile:**
- Chart.js is responsive by default
- Set `responsive: true` and `maintainAspectRatio: true`
- Use smaller font sizes on mobile

### Step 3.6: Testing Strategy

**Unit tests needed:**
```java
CommissionCalculatorTests:
- Test basic commission calculation (revenue × rate)
- Test bonus calculation (threshold logic)
- Test edge cases (zero commission, zero bonus)
- Test per-line attribution (multiple therapists)

ReportServiceTests:
- Test daily report aggregation
- Test period report date range filtering
- Test therapist comparison calculations
- Test patient retention metrics
```

**Integration tests:**
- Create appointment with multiple lines (different therapists)
- Calculate commission for each therapist
- Verify totals match expected amounts
- Run report queries, verify data accuracy

**Browser testing:**
- Test all reports on mobile (375px), tablet (768px), desktop (1920px)
- Verify tables render correctly (not squeezed)
- Charts visible and interactive
- Export buttons work (CSV, PDF)
- Navigation works on mobile (hamburger menu)

---

## Database Queries for Performance

### Indexes to Add

```sql
-- Query performance on date range and therapist filtering
CREATE INDEX idx_asl_therapist_created ON appointment_service_line(therapist_id, created_at);
CREATE INDEX idx_apl_therapist_created ON appointment_product_line(therapist_id, created_at);
CREATE INDEX idx_appointment_created ON appointment(created_at);
CREATE INDEX idx_appointment_therapist ON appointment(therapist_id);

-- Tag relationship queries
CREATE INDEX idx_st_tag ON service_tag(tag_id);
CREATE INDEX idx_st_service ON service_tag(service_id);
CREATE INDEX idx_pt_tag ON product_tag(tag_id);
CREATE INDEX idx_pt_product ON product_tag(product_id);
```

---

## Implementation Order (Recommended)

### Week 1: Core Calculation
- [ ] CommissionCalculator (unit tests)
- [ ] ReportAggregator (queries, aggregation)
- [ ] DTO classes

### Week 2: Dashboard
- [ ] Dashboard service
- [ ] Dashboard controller
- [ ] Dashboard template (responsive)
- [ ] Chart.js integration

### Week 3: Reports
- [ ] Daily report
- [ ] Period report
- [ ] Report templates (all responsive)
- [ ] Integration tests

### Week 4: Advanced Reports
- [ ] Therapist comparison
- [ ] Patient acquisition
- [ ] Product/service performance
- [ ] Performance tests

### Week 5: Export & Polish
- [ ] CSV export utility
- [ ] PDF export (iText)
- [ ] Export on all reports
- [ ] Final responsive testing
- [ ] Browser testing across devices

---

## Known Challenges & Solutions

### Challenge 1: Per-Line Therapist Performance on Queries
**Problem:** Large number of appointments → slow commission queries

**Solution:**
- Add indexes on (therapist_id, created_at)
- Consider materialized view for daily summaries (if needed)
- Use pagination on large reports (show 30 therapists per page)

### Challenge 2: Mobile Chart Responsiveness
**Problem:** Charts look cramped on mobile

**Solution:**
- Chart.js handles responsiveness automatically
- Use smaller canvas sizes on mobile
- Consider hiding least-important charts on mobile (keep KPIs)
- Stack charts vertically on mobile, side-by-side on desktop

### Challenge 3: PDF Generation with Charts
**Problem:** Exporting charts to PDF is complex

**Solution:**
- Option 1: Export without charts (tables only) for MVP
- Option 2: Use Chart.js toBase64Image() to embed chart images in PDF
- Option 3: Use server-side rendering (Selenium) to screenshot charts

---

## Success Criteria

By end of Phase 3, you should be able to:

- [ ] View dashboard with today's KPIs and trends
- [ ] Run daily report for any date and see per-therapist earnings
- [ ] Run period report and see all earnings (commission + bonus) by therapist
- [ ] Compare 2 therapists side-by-side and see performance gap
- [ ] View patient acquisition trends
- [ ] See top-performing services and products
- [ ] Export any report to CSV or PDF
- [ ] Use all features on mobile (375px) without issues
- [ ] Use all features on tablet (768px) comfortably
- [ ] Accurate commission calculations match business rules

---

## Questions for Clarification

1. **Chart.js vs other libraries?** (Recommend Chart.js — lightweight, no npm needed)
2. **PDF format preferences?** (Simple table-based layout vs fancy styling?)
3. **How many months of data** to handle initially? (Plan for at least 1 year)
4. **Should therapists see their own reports?** (Out of scope for Phase 3, Phase 5)
5. **Export scheduling** (auto-email reports daily/weekly)? (Out of scope for now)

---

## Next Steps

1. Review this guide and requirements v1.2
2. Ask any clarification questions above
3. Start with Step 3.1: CommissionCalculator (most critical)
4. Weekly check-ins to verify direction

**Ready to start Phase 3 implementation?** 🚀

