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
                .fullName("Marcia Gomes Yadav")
                .specialization("Holistic Wellness & TCM Practitioner")
                .phone("9800000001")
                .email("marcia@healinghouse.in")
                .fixedMonthlySalary(BigDecimal.ZERO)
                .commissionRate(BigDecimal.ZERO)
                .performanceBonusThreshold(null)
                .performanceBonusAmount(BigDecimal.ZERO)
                .notes("Owner. No payout calculation applies.")
                .active(true)
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
    // ─────────────────────────────────────────────────────────────────
    private void seedServices() {
        if (clinicServiceRepository.count() > 0) return;

        List<ClinicService> services = List.of(

            ClinicService.builder()
                .name("Swedish Massage 60 min")
                .tags(tags("Massage"))
                .durationMinutes(60)
                .price(new BigDecimal("800.00"))
                .description("Relaxing full-body Swedish massage using long, flowing strokes to ease muscle tension.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Deep Tissue Massage 60 min")
                .tags(tags("Massage"))
                .durationMinutes(60)
                .price(new BigDecimal("1000.00"))
                .description("Targets deep muscle layers to relieve chronic pain and stiffness.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Hot Stone Massage 90 min")
                .tags(tags("Massage"))
                .durationMinutes(90)
                .price(new BigDecimal("1500.00"))
                .description("Heated basalt stones combined with massage to promote deep relaxation.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Reflexology 45 min")
                .tags(tags("Massage"))
                .durationMinutes(45)
                .price(new BigDecimal("700.00"))
                .description("Foot reflexology targeting pressure points linked to organs and body systems.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Acupuncture Session")
                .tags(tags("Acupuncture"))
                .durationMinutes(45)
                .price(new BigDecimal("1200.00"))
                .description("Traditional needle acupuncture to restore energy flow and relieve pain.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("TCM Consultation & Treatment")
                .tags(tags("TCM"))
                .durationMinutes(60)
                .price(new BigDecimal("1500.00"))
                .description("Traditional Chinese Medicine consultation with herbal recommendations and treatment.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Hijama (Cupping Therapy)")
                .tags(tags("Hijama"))
                .durationMinutes(60)
                .price(new BigDecimal("1000.00"))
                .description("Wet or dry cupping therapy to improve circulation and remove toxins.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Foot Ion Detox")
                .tags(tags("IonTherapy", "Detox"))
                .durationMinutes(45)
                .price(new BigDecimal("700.00"))
                .description("Ionic foot bath that draws out toxins through the soles of the feet.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Full Body Detox Program")
                .tags(tags("Detox"))
                .durationMinutes(90)
                .price(new BigDecimal("2000.00"))
                .description("Comprehensive detox session combining dry brushing, herbal wraps, and steam therapy.")
                .active(true)
                .build(),

            ClinicService.builder()
                .name("Compression Therapy")
                .tags(tags("Compression"))
                .durationMinutes(30)
                .price(new BigDecimal("600.00"))
                .description("Pneumatic compression to improve lymphatic drainage and reduce swelling in limbs.")
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