# Responsive UI Testing Guide — Healing House Clinic

## Summary of Changes

This document guides you through verifying the responsive UI implementation for Healing House Clinic. The following changes have been made to make the UI mobile-friendly:

### ✅ Completed Changes

1. **Appointments Form Line-Item Rows** (CRITICAL FIX)
   - Service and Product line-item rows now use responsive column classes (`col-12 col-md-4`, etc.)
   - On mobile: fields wrap naturally (Service full-width, Therapist full-width, Qty + Rate side-by-side, Subtotal + Remove stacked)
   - On desktop: maintains current 6-column/7-column layout
   - Column headers (`#serviceHeader`, `#productHeader`) hidden on mobile (`d-none d-md-flex`)

2. **Form Action-Button Rows** (STANDARDIZED)
   - Changed all form submit/cancel button rows to: `d-grid gap-2 d-md-flex justify-content-md-end`
   - On mobile: buttons stack full-width, vertically
   - On desktop (768px+): buttons sit inline, right-aligned
   - Affects: `services/form.html`, `products/form.html`, `patients/form.html`, `therapists/form.html`, `appointments/form.html`

3. **Visual Audit**
   - Dashboard: KPI cards, quick actions, quick-nav grid already responsive ✓
   - Patient/Therapist/Services/Products forms: already use responsive grid layout ✓
   - All list pages: tables wrapped in `.table-responsive` (horizontal scroll on overflow) ✓
   - Navigation: hamburger menu + collapsible nav already in place ✓

---

## How to Test

### Step 1: Run the Application

```bash
cd /path/to/healinghouse
mvn clean spring-boot:run -Dspring-boot.run.profiles=test
```

Wait for the app to start (~30-60 seconds). You should see:
```
Tomcat started on port(s): 8080 (http)
```

Open your browser to: **`http://localhost:8080`**

### Step 2: Test in Browser DevTools (Recommended)

1. Open **Chrome DevTools** (`F12` or right-click → Inspect)
2. Click the **device toggle** icon (top-left of DevTools) to enable responsive design mode
3. Test at three breakpoints:
   - **Mobile (375px)** — simulates iPhone 12
   - **Tablet (768px)** — simulates iPad
   - **Desktop (1920px)** — full HD desktop

---

## Test Checklist

### Dashboard (`http://localhost:8080/`)

**Mobile (375px):**
- [ ] KPI cards display in 2x2 grid (not 4 across)
- [ ] No horizontal page scroll
- [ ] Quick Actions buttons wrap naturally
- [ ] Today's Appointments table readable (may scroll horizontally for table columns)
- [ ] Manage section cards display in 2-column grid

**Tablet (768px):**
- [ ] KPI cards in 2x2 grid (not 4 across until desktop)
- [ ] All sections visible, no overflow

**Desktop (1920px):**
- [ ] KPI cards in 1x4 grid
- [ ] Two-column layout for Today's Appointments + Revenue chart
- [ ] Manage section in 6-column grid

---

### Forms (Services, Products, Patients, Therapists)

**Mobile (375px):**
- [ ] Form is in a centered card, readable
- [ ] All input fields full-width
- [ ] Labels positioned above inputs (not inline)
- [ ] Submit/Cancel buttons **STACK vertically** (d-grid), each full-width
- [ ] Minimum button height ≥ 44px for touch
- [ ] No horizontal page scroll
- [ ] Multi-column rows (e.g., Name + Phone) collapse to single column

**Tablet (768px):**
- [ ] Multi-column form rows can display side-by-side (col-md-*)
- [ ] Submit/Cancel buttons still side-by-side ✓

**Desktop (1920px):**
- [ ] All form elements properly spaced and aligned

---

### Appointments Form (NEW/EDIT) — HIGHEST PRIORITY

**Mobile (375px):**
- [ ] Patient/Therapist/DateTime section: three fields stack vertically
- [ ] Services section: Add Service row shows fields responsively:
  - Row 1: Service dropdown (full width)
  - Row 2: Therapist dropdown (full width)
  - Row 3: Qty (half width) + Rate (half width) side-by-side
  - Row 4: Subtotal (full width)
  - Row 5: Remove button (full width)
- [ ] No horizontal scroll on service/product line items
- [ ] All quantities and prices are visible and updateable
- [ ] Totals update correctly when values change
- [ ] Products section follows same pattern
- [ ] Notes + Payment fields stack properly
- [ ] Summary card (on desktop col-md-5) becomes full-width on mobile
- [ ] Submit/Cancel buttons stack and are tappable (≥44px)

**Tablet (768px):**
- [ ] Services/Products header row visible (`#serviceHeader`, `#productHeader` shown)
- [ ] Line items align in column format
- [ ] Layout is clean and readable

**Desktop (1920px):**
- [ ] Column headers visible and aligned with data
- [ ] 6-column service layout works: Service | Therapist | Qty | Rate | Subtotal | Remove
- [ ] 7-column product layout works: Product | Therapist | Qty | Stock | Rate | Subtotal | Remove
- [ ] Notes (col-md-7) + Summary (col-md-5) sit side-by-side

---

### List Pages (Patients, Therapists, Services, Products, Tags, Appointments)

**Mobile (375px):**
- [ ] Header row (title + "New" button) displays properly
- [ ] Search/filter inputs are usable (not crushed)
- [ ] Table displays with horizontal scroll if needed (`.table-responsive`)
  - Scroll left/right to see all columns
  - Key columns visible (Name, key data)
- [ ] Action buttons (Edit/Delete) are tappable
- [ ] No horizontal page scroll (only table scroll if needed)

**Tablet (768px):**
- [ ] Table readable without horizontal scroll or minimal scroll
- [ ] Layout clean

**Desktop (1920px):**
- [ ] Full table visible, all columns readable
- [ ] No unnecessary horizontal scroll

---

### Modals (Delete, Rename, Merge Confirmations)

**Mobile (375px):**
- [ ] Modal fits within viewport (not taller than screen)
- [ ] Modal content readable (title, body text, buttons)
- [ ] Buttons in footer are tappable (≥44px)
- [ ] Modal doesn't overflow horizontally

**All sizes:**
- [ ] Modal dismissible (X button, Cancel button, backdrop click all work)

---

### Navigation Menu

**Mobile (375px):**
- [ ] Hamburger menu icon visible (≡)
- [ ] Clicking hamburger opens collapsible menu
- [ ] All navigation items listed vertically
- [ ] Menu items have adequate spacing for tapping
- [ ] Menu closes when you click a link or backdrop
- [ ] Healing House logo/brand name visible

**Tablet (768px) and Desktop:**
- [ ] Navigation bar shows all menu items horizontally
- [ ] Hamburger menu hidden
- [ ] Logo + full nav menu visible

---

## Common Issues to Watch For

❌ **Horizontal page scroll** — entire page scrolling left/right (tables may scroll horizontally, but the page itself should not)

❌ **Text/buttons overflowing viewport edge** — cut off or invisible

❌ **Unreadable text** — too small, wrong color contrast, overlapping

❌ **Buttons < 44px** — too hard to tap on mobile

❌ **Form fields compressed** — select dropdowns or inputs crushed/unreadable

❌ **Modals off-screen** — modal taller than viewport or positioned incorrectly

---

## Troubleshooting

### App Won't Start
- Ensure Maven is installed: `mvn --version`
- Check you're in the project root (has `pom.xml`)
- Check DB is configured per `application-test.yml`

### Browser Shows Old Version
- Hard refresh: `Ctrl+Shift+R` (Chrome/Firefox) or `Cmd+Shift+R` (Mac)
- Clear browser cache
- Close and reopen DevTools

### Responsive Mode Looks Different from Real Device
- Browser DevTools responsive mode is a close approximation but not 100% pixel-perfect
- If possible, test on an actual phone/tablet to verify touch targets and real fonts

---

## After Testing

✅ All tests pass → Responsive UI implementation complete!

❌ Issues found → Document them and let me know:
- Page name
- Breakpoint (375px / 768px / 1920px)
- Issue description
- Screenshot (if possible)

Then we can iterate on fixes.

---

## Files Modified

- `templates/appointments/form.html` — responsive line-item rows, button stacking
- `templates/services/form.html` — button stacking
- `templates/products/form.html` — button stacking
- `templates/patients/form.html` — button stacking
- `templates/therapists/form.html` — button stacking

No CSS changes needed — Bootstrap utilities handle all responsive behavior.

---

*Guide created July 8, 2026 — Healing House Clinic Responsive UI Implementation*
