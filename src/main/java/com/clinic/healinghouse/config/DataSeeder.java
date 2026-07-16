package com.clinic.healinghouse.config;

import com.clinic.healinghouse.entity.*;
import com.clinic.healinghouse.repository.*;
import com.clinic.healinghouse.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
@Profile("!prod & !preprod")
public class DataSeeder implements CommandLineRunner {

    private final PatientRepository        patientRepository;
    private final TherapistRepository      therapistRepository;
    private final ClinicServiceRepository  clinicServiceRepository;
    private final ProductRepository        productRepository;
    private final TagService               tagService;
    private final HealingHouseProperties   properties;

    /** Resolves tag names via find-or-create so seeded catalog items share the same Tag pool as user-created ones. */
    private Set<Tag> tags(String... names) {
        return Arrays.stream(names).map(tagService::findOrCreate).collect(Collectors.toCollection(HashSet::new));
    }

    @Override
    @Transactional
    public void run(String... args) {
        seedTherapists();
        seedPatients();
        seedServices();
        seedProducts();
    }

    // ─────────────────────────────────────────────────────────────────
    // Therapists
    // ─────────────────────────────────────────────────────────────────
    private void seedTherapists() {
        if (therapistRepository.count() > 0) return;

        List<Therapist> therapists = List.of(

            // Owner — no salary or commission calculation
            Therapist.builder()
                .fullName(properties.getOwner().getFullName())
                .specialization("Holistic Wellness & TCM Practitioner")
                .phone("9800000001")
                .email("marcia@healinghouse.in")
                .fixedMonthlySalary(BigDecimal.ZERO)
                .commissionRate(BigDecimal.ZERO)
                .performanceBonusThreshold(null)
                .performanceBonusAmount(BigDecimal.ZERO)
                .notes("Owner. No payout calculation applies.")
                .active(true)
                .owner(true)
                .build(),

            Therapist.builder()
                .fullName("Priya Sharma")
                .specialization("Massage Therapist")
                .phone("9800000002")
                .email("priya@healinghouse.in")
                .fixedMonthlySalary(new BigDecimal("15000.00"))
                .commissionRate(new BigDecimal("0.1000"))   // 10 %
                .performanceBonusThreshold(80)
                .performanceBonusAmount(new BigDecimal("4000.00"))
                .notes("Specialises in Swedish, deep tissue, and hot stone massage.")
                .active(true)
                .build(),

            Therapist.builder()
                .fullName("Arjun Mehta")
                .specialization("Acupuncturist & TCM Specialist")
                .phone("9800000003")
                .email("arjun@healinghouse.in")
                .fixedMonthlySalary(new BigDecimal("18000.00"))
                .commissionRate(new BigDecimal("0.1200"))   // 12 %
                .performanceBonusThreshold(70)
                .performanceBonusAmount(new BigDecimal("5000.00"))
                .notes("Certified in acupuncture and traditional Chinese medicine.")
                .active(true)
                .build(),

            Therapist.builder()
                .fullName("Sunita Patel")
                .specialization("Detox & Wellness Specialist")
                .phone("9800000004")
                .email("sunita@healinghouse.in")
                .fixedMonthlySalary(new BigDecimal("14000.00"))
                .commissionRate(new BigDecimal("0.1000"))   // 10 %
                .performanceBonusThreshold(75)
                .performanceBonusAmount(new BigDecimal("3500.00"))
                .notes("Specialises in detox programs, ion therapy, and compression therapy.")
                .active(true)
                .build()
        );

        therapistRepository.saveAll(therapists);
        log.info("Seeded {} therapists.", therapists.size());
    }

    // ─────────────────────────────────────────────────────────────────
    // Patients
    // ─────────────────────────────────────────────────────────────────
    private void seedPatients() {
        if (patientRepository.count() > 0) return;

        List<Patient> patients = List.of(

            Patient.builder()
                .fullName("Anita Desai")
                .phone("9811100001")
                .email("anita.desai@email.com")
                .gender(Gender.FEMALE)
                .dateOfBirth(LocalDate.of(1985, 3, 15))
                .address("12, Rose Garden, Mumbai 400001")
                .allergies("Penicillin")
                .active(true)
                .build(),

            Patient.builder()
                .fullName("Rajesh Kumar")
                .phone("9811100002")
                .email("rajesh.kumar@email.com")
                .gender(Gender.MALE)
                .dateOfBirth(LocalDate.of(1972, 7, 22))
                .address("45, Sector 14, Delhi 110001")
                .medicalHistory("Mild hypertension. On medication.")
                .active(true)
                .build(),

            Patient.builder()
                .fullName("Meera Iyer")
                .phone("9811100003")
                .gender(Gender.FEMALE)
                .dateOfBirth(LocalDate.of(1990, 11, 8))
                .address("8, Koramangala, Bengaluru 560034")
                .notes("Prefers female therapists.")
                .active(true)
                .build(),

            Patient.builder()
                .fullName("Suresh Nair")
                .phone("9811100004")
                .email("suresh.nair@email.com")
                .gender(Gender.MALE)
                .dateOfBirth(LocalDate.of(1965, 4, 30))
                .address("22, MG Road, Kochi 682001")
                .medicalHistory("Type 2 diabetes. Controlled.")
                .allergies("Shellfish")
                .active(true)
                .build(),

            Patient.builder()
                .fullName("Fatima Sheikh")
                .phone("9811100005")
                .gender(Gender.FEMALE)
                .dateOfBirth(LocalDate.of(1988, 9, 12))
                .address("5, Bandra West, Mumbai 400050")
                .active(true)
                .build(),

            Patient.builder()
                .fullName("Vikram Singh")
                .phone("9811100006")
                .email("vikram.singh@email.com")
                .gender(Gender.MALE)
                .dateOfBirth(LocalDate.of(1978, 1, 25))
                .address("33, Civil Lines, Jaipur 302006")
                .medicalHistory("Chronic lower back pain.")
                .active(true)
                .build(),

            Patient.builder()
                .fullName("Lakshmi Rao")
                .phone("9811100007")
                .email("lakshmi.rao@email.com")
                .gender(Gender.FEMALE)
                .dateOfBirth(LocalDate.of(1995, 6, 18))
                .address("17, Jubilee Hills, Hyderabad 500033")
                .active(true)
                .build(),

            Patient.builder()
                .fullName("Arun Pillai")
                .phone("9811100008")
                .gender(Gender.MALE)
                .dateOfBirth(LocalDate.of(1958, 12, 5))
                .address("9, Anna Nagar, Chennai 600040")
                .medicalHistory("Arthritis. Knee replacement in 2019.")
                .allergies("Ibuprofen")
                .notes("Walks with slight limp — seated treatments preferred.")
                .active(true)
                .build()
        );

        patientRepository.saveAll(patients);
        log.info("Seeded {} patients.", patients.size());
    }

    // ─────────────────────────────────────────────────────────────────
    // Services (Treatment Catalog)
    // Sourced from requirements/Healing_House_Services_Products.xlsx, "SingleItems" sheet only.
    // ─────────────────────────────────────────────────────────────────
    private void seedServices() {
        if (clinicServiceRepository.count() > 0) return;

        List<ClinicService> services = List.of(

            ClinicService.builder()
                .name("Abhyanga Massage")
                .tags(tags("Bonus"))
                .durationMinutes(60)
                .price(new BigDecimal("999.00"))
                .description("Traditional Ayurvedic oil massage to detox, nourish and balance the body.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Acupuncture (TCM Needling Therapy)")
                .durationMinutes(30)
                .price(new BigDecimal("1000.00"))
                .description("Traditional Chinese Medicine needling therapy to restore balance and promote natural healing.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Acupuncture (Weight Loss Support)")
                .durationMinutes(30)
                .price(new BigDecimal("1000.00"))
                .description("Restores flow of energy, relieves pain and stress, supports weight loss journey.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Advanced Face Lifting & Skin Rejuvenation Machine")
                .durationMinutes(30)
                .price(new BigDecimal("899.00"))
                .description("New arrival machine treatment for nasolabial folds, double chin, eye wrinkles, undefined jawline. Complimentary 15 min face massage included.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Anti Inflammatory Mat Therapy")
                .tags(tags("Commission"))
                .durationMinutes(30)
                .price(new BigDecimal("500.00"))
                .description("Latest red light mat technology from China to reduce inflammation and pain, improve circulation, reduce fat cells. Suitable for all ages.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Back Massage")
                .tags(tags("Bonus"))
                .durationMinutes(40)
                .price(new BigDecimal("800.00"))
                .description("Relaxes back muscles, improves posture and relieves daily stress.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Bleeding Cupping Therapy")
                .durationMinutes(30)
                .price(new BigDecimal("2000.00"))
                .description("Cupping therapy to detoxify blood and remove toxins from the body.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Consultation / Diagnosis")
                .durationMinutes(30)
                .price(new BigDecimal("400.00"))
                .description("Personalized treatment based on TCM diagnosis.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Cupping Therapy")
                .durationMinutes(30)
                .price(new BigDecimal("399.00"))
                .description("Cupping therapy")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Deep Tissue Massage")
                .tags(tags("Bonus"))
                .durationMinutes(60)
                .price(new BigDecimal("1500.00"))
                .description("Targets deep muscle layers to relieve chronic pain and muscle tension.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Detox Ion (Weight Loss)")
                .tags(tags("Commission"))
                .durationMinutes(60)
                .price(new BigDecimal("1500.00"))
                .description("Removes toxins, reduces inflammation, boosts body's natural healing process. Part of Weight Loss Program.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Dry Cupping Therapy")
                .durationMinutes(30)
                .price(new BigDecimal("699.00"))
                .description("Traditional dry cupping therapy for pain relief and circulation.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Facelift Acupuncture")
                .durationMinutes(60)
                .price(new BigDecimal("2500.00"))
                .description("Advanced acupuncture techniques to lift, tone and rejuvenate; stimulates collagen and elastin, improves skin elasticity, reduces fine lines.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Facial Korean Skin Care")
                .tags(tags("Korean"))
                .durationMinutes(60)
                .price(new BigDecimal("3499.00"))
                .description("Complete Korean skincare ritual: peeling, deep cleaning, tonification, skin barrier protector moisturizer, PRN (salmon).")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Flower Remedy")
                .tags(tags("Commission"))
                .durationMinutes(30)
                .price(new BigDecimal("900.00"))
                .description("Consultation plus flower remedy treatment.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Foot Crack Remover Therapy")
                .tags(tags("Bonus"))
                .durationMinutes(30)
                .price(new BigDecimal("699.00"))
                .description("Advanced foot care device treatment to soften, repair and heal cracked feet; removes dead skin, reduces cracks. Visible results after 1 session.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Foot Ion Detox")
                .tags(tags("Commission"))
                .durationMinutes(30)
                .price(new BigDecimal("1500.00"))
                .description("Ion foot detox to cleanse and rebalance the body.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Foot Reflexology")
                .tags(tags("Bonus"))
                .durationMinutes(30)
                .price(new BigDecimal("599.00"))
                .description("Stimulates reflex points to improve circulation and support overall well-being.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Foot Reflexology (complimentary add-on)")
                .tags(tags("Bonus"))
                .durationMinutes(30)
                .price(new BigDecimal("699.00"))
                .description("Complimentary add-on foot reflexology with steam.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Full Body Abhyanga Massage (Ayurvedic)")
                .tags(tags("Bonus"))
                .durationMinutes(60)
                .price(new BigDecimal("999.00"))
                .description("Traditional Ayurvedic oil massage for full body.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Full Body Massage (Ayurveda)")
                .tags(tags("Bonus"))
                .durationMinutes(90)
                .price(new BigDecimal("1899.00"))
                .description("Ayurvedic full body massage using pure Kerala herbal oils; for sciatica, lumbar pain, cervical, frozen shoulder, spondylosis, migraines, insomnia. Includes free steam.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Head & Shoulder Massage")
                .tags(tags("Bonus"))
                .durationMinutes(60)
                .price(new BigDecimal("1000.00"))
                .description("Relieves stiffness, stress and tension in the head, neck and shoulders.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Head Massage")
                .tags(tags("Bonus"))
                .durationMinutes(30)
                .price(new BigDecimal("799.00"))
                .description("Relax, refresh, renew. Cooling and heat-reducing head massage; stress relief, deeper sleep, hair fall control. By lady therapist.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Hot & Cold Therapy")
                .durationMinutes(30)
                .price(new BigDecimal("699.00"))
                .description("Hot and cold therapy for pain relief.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Hot & Cold Therapy for Back & Knee Pain")
                .durationMinutes(120)
                .price(new BigDecimal("3000.00"))
                .description("Hot & cold therapy, therapeutic body massage and foot massager for back pain, knee pain (arthritis, sprain, inflammation), disc herniation, frozen shoulder, etc. Administered by lady therapist. Special offer.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Japanese Head Spa")
                .tags(tags("Bonus"))
                .durationMinutes(30)
                .price(new BigDecimal("499.00"))
                .description("Part of Japanese Head Spa & Massage Therapies.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Korean Facial Skin Care")
                .tags(tags("Korean"))
                .durationMinutes(60)
                .price(new BigDecimal("3499.00"))
                .description("Authentic Korean skincare rituals: deep cleansing, exfoliation, intense hydration, brightens dull skin.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Korean Facial Skin Care + Lifting Acupuncture Combo")
                .tags(tags("Korean"))
                .durationMinutes(120)
                .price(new BigDecimal("4999.00"))
                .description("Combo package of Korean Facial Skin Care and Lifting Acupuncture for deep hydration, natural lift and firming.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Lady to Lady Full Body Massage")
                .tags(tags("Bonus"))
                .durationMinutes(120)
                .price(new BigDecimal("3500.00"))
                .description("Feminine energy full body massage, head to toe, with aromatherapy premium oils and soothing steam therapy. Free LED lights therapy. Safe space for women. Special offer this month.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("LED Light Therapy (7 Wavelength)")
                .tags(tags("Korean"))
                .durationMinutes(30)
                .price(new BigDecimal("499.00"))
                .description("7-wavelength LED light therapy for anti-aging, acne, oil control, pore care, sensitive skin - pain-free, safe for all skin types.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Leg & Foot Massage")
                .tags(tags("Bonus"))
                .durationMinutes(40)
                .price(new BigDecimal("800.00"))
                .description("Relieves leg tiredness, improves circulation and reduces swelling.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Lymphatic Drainage")
                .durationMinutes(60)
                .price(new BigDecimal("1500.00"))
                .description("Boosts detox, reduces swelling and improves circulation. Part of Weight Loss Program.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Mask Face Harmonization")
                .tags(tags("Commission"))
                .durationMinutes(60)
                .price(new BigDecimal("899.00"))
                .description("Balances facial proportions.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Massage Carving (Body Shape)")
                .tags(tags("Bonus"))
                .durationMinutes(60)
                .price(new BigDecimal("1800.00"))
                .description("Sculpt, tone and reshape body with advanced techniques for visible transformation. Part of Weight Loss Program.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Post Flower Remedy (Follow-up)")
                .durationMinutes(30)
                .price(new BigDecimal("500.00"))
                .description("Follow-up flower remedy treatment after initial consultation.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Red Light Therapy (Total Wellness)")
                .durationMinutes(60)
                .price(new BigDecimal("1200.00"))
                .description("Red light therapy (660nm) and near-infrared (850nm) for anti-inflammatory relief, improved circulation, pain relief. By Dr. Marcia, licensed acupuncturist.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Red Light Therapy for Cellulite")
                .durationMinutes(60)
                .price(new BigDecimal("1500.00"))
                .description("Stimulates collagen, reduces cellulite and calms inflammation for smoother skin. Part of Weight Loss Program.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("TCM Consultation & Root Cause Diagnosis")
                .durationMinutes(30)
                .price(new BigDecimal("400.00"))
                .description("Pulse analysis, tongue diagnosis, personalized healing plan to discover root cause, not just symptoms.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Ultimate Body Massage with Foot Detox (Combo)")
                .tags(tags("Bonus"))
                .durationMinutes(120)
                .price(new BigDecimal("3599.00"))
                .description("Ultimate body massage with free steam, combined with foot detox therapy (cleanse, rebalance, revitalize; removes toxins, heavy metals, improves circulation, supports immunity, diabetes support).")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Ultimate Signature Body Massage")
                .tags(tags("Bonus"))
                .durationMinutes(120)
                .price(new BigDecimal("3999.00"))
                .description("90-min journey crafted from ancient Vedas using pure herbal oils; deep sleep, complete muscle relaxation, breathing freely, mind-body-soul relief. Bonus: free steam & full body ion detox.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Vacuum Therapy (Slide Cupping Machine)")
                .durationMinutes(60)
                .price(new BigDecimal("1699.00"))
                .description("Modern TCM technique using slide cupping machine to promote circulation, relieve pain, restore balance, reduce cellulite and localized body fat. Recommended once every 4 months.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Weight Loss Massage")
                .tags(tags("Bonus"))
                .durationMinutes(30)
                .price(new BigDecimal("1299.00"))
                .description("Targeted body sculpting massage to reduce fat, improve circulation and boost metabolism. Part of Big Weight Loss Program.")
                .active(true)
                .build()
        );

        clinicServiceRepository.saveAll(services);
        log.info("Seeded {} services.", services.size());
    }

    // ─────────────────────────────────────────────────────────────────
    // Products
    // ─────────────────────────────────────────────────────────────────
    private void seedProducts() {
        if (productRepository.count() > 0) return;

        List<Product> products = List.of(

            Product.builder()
                .name("Detox Herbal Tea (100g)")
                .tags(tags("Tea", "Detox"))
                .price(new BigDecimal("450.00"))
                .stockQuantity(25)
                .reorderLevel(8)
                .description("Blend of dandelion, ginger, and licorice root to support liver and kidney detox.")
                .active(true)
                .build(),

            Product.builder()
                .name("Peppermint Massage Oil (200ml)")
                .tags(tags("Oil"))
                .price(new BigDecimal("650.00"))
                .stockQuantity(18)
                .reorderLevel(6)
                .description("Cold-pressed peppermint oil infused with eucalyptus — ideal for muscle relief.")
                .active(true)
                .build(),

            Product.builder()
                .name("Lavender Essential Oil (30ml)")
                .tags(tags("Oil"))
                .price(new BigDecimal("750.00"))
                .stockQuantity(3)
                .reorderLevel(5)
                .description("Pure therapeutic-grade lavender oil for relaxation and sleep support.")
                .active(true)
                .build(),

            Product.builder()
                .name("Liver Cleanse Kit")
                .tags(tags("Detox Kit", "Detox"))
                .price(new BigDecimal("1800.00"))
                .stockQuantity(8)
                .reorderLevel(4)
                .description("21-day liver cleanse program with herbal capsules, teas, and dietary guide.")
                .active(true)
                .build(),

            Product.builder()
                .name("Ashwagandha Capsules (60 caps)")
                .tags(tags("Capsule", "Herbal Supplement"))
                .price(new BigDecimal("550.00"))
                .stockQuantity(30)
                .reorderLevel(10)
                .description("Certified organic ashwagandha root extract for stress relief and vitality.")
                .active(true)
                .build(),

            Product.builder()
                .name("Turmeric & Ginger Supplement (90 caps)")
                .tags(tags("Herbal Supplement"))
                .price(new BigDecimal("400.00"))
                .stockQuantity(4)
                .reorderLevel(10)
                .description("Anti-inflammatory blend of curcumin and ginger extract with black pepper for absorption.")
                .active(true)
                .build(),

            Product.builder()
                .name("Foot Detox Salt Scrub (500g)")
                .tags(tags("Other", "Detox"))
                .price(new BigDecimal("380.00"))
                .stockQuantity(12)
                .reorderLevel(5)
                .description("Himalayan pink salt and neem leaf scrub to exfoliate and detoxify tired feet.")
                .active(true)
                .build()
        );

        productRepository.saveAll(products);
        log.info("Seeded {} products.", products.size());
    }
}