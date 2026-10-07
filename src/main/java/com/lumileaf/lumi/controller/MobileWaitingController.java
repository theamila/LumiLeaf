package com.lumileaf.lumi.controller;

import com.lumileaf.lumi.model.WaitingPoint;
import com.lumileaf.lumi.model.Supplier;
import com.lumileaf.lumi.repository.WaitingPointRepository;
import com.lumileaf.lumi.repository.SupplierRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import com.lumileaf.lumi.model.NotificationEvent;
import com.lumileaf.lumi.repository.NotificationEventRepository;
import com.lumileaf.lumi.model.Admin;
import com.lumileaf.lumi.repository.AdminRepository;
import com.lumileaf.lumi.service.PermissionService;
@Controller
public class MobileWaitingController {


    @Autowired
    private NotificationEventRepository notificationRepo;

    @Autowired
    private WaitingPointRepository waitingRepo;
    @Autowired
    private AdminRepository adminRepo;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private SupplierRepository supplierRepo;
    private static final List<DateTimeFormatter> ORGANIC_CSV_DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    );

    /**
     * Tries each known, unambiguous (year-first) date format in turn.
     * Returns null if none match — callers must treat null as "reject this row",
     * never guess at a day/month-first format.
     */
    private LocalDate parseOrganicCsvDate(String raw) {
        String value = raw == null ? "" : raw.trim();
        for (DateTimeFormatter fmt : ORGANIC_CSV_DATE_FORMATS) {
            try {
                return LocalDate.parse(value, fmt);
            } catch (Exception ignored) {
                // try next format
            }
        }
        return null;
    }

    @GetMapping("/mobile/waiting_dashboard")
    public String showWeighingDashboard(Model model, HttpSession session) {
        if (session.getAttribute("username") == null) {
            return "redirect:/login";
        }

        List<WaitingPoint> allRecords = waitingRepo.findAll();

        // ✅ FIX #1: Show only PENDING records (hide FINALIZED ones)
        List<WaitingPoint> pendingRecords = allRecords.stream()
                .filter(r -> r.getStatus() == null || "PENDING".equals(r.getStatus()))
                .collect(Collectors.toList());

        model.addAttribute("records", pendingRecords);

        // ✅ FIX: Daily total reflects the WHOLE day's intake (pending + already-finalized),
        // not just what's still pending. Previously this was computed from pendingRecords only,
        // so the total dropped to 0.00 kg the moment records were finalized. Keying off the
        // latest date across ALL records also means it naturally resets once a later date's
        // (e.g. tomorrow's) records start coming in.
        Optional<LocalDate> latestDate = allRecords.stream()
                .map(WaitingPoint::getDate)
                .filter(d -> d != null)
                .max(Comparator.naturalOrder());

        double dailyTotal = 0.0;
        LocalDate displayDate = null;

        if (latestDate.isPresent()) {
            LocalDate ld = latestDate.get();

            // Only show the summary while at least one record for this date
            // is still PENDING. Once every record for this date is FINALIZED,
            // the card resets — signalling this date's intake is fully processed.
            boolean hasPendingForLatestDate = allRecords.stream()
                    .anyMatch(r -> ld.equals(r.getDate()) && "PENDING".equals(r.getStatus()));

            if (hasPendingForLatestDate) {
                displayDate = ld;
                dailyTotal = allRecords.stream()
                        .filter(r -> ld.equals(r.getDate()))
                        .mapToDouble(r -> r.getWeight() != null ? r.getWeight() : 0.0)
                        .sum();
            }
        }

        model.addAttribute("dailyTotal", String.format("%.2f", dailyTotal));
        model.addAttribute("displayDate", displayDate);

        return "waiting_point_dashboard";
    }

    @PostMapping("/api/waiting/upload-csv")
    public String handleCSVUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "officerSelect", required = false) String officerSelect,
            @RequestParam(value = "customOfficerName", required = false) String customOfficerName,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String username = (String) session.getAttribute("username");
        Admin admin = (username != null) ? adminRepo.findByUsername(username) : null;
        if (admin == null || !permissionService.hasPermission(admin, "WEIGHING_UPLOAD_CSV")) {
            redirectAttributes.addFlashAttribute("errorMessage", "You don't have permission to upload CSV files.");
            return "redirect:/mobile/waiting_dashboard";
        }

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "The uploaded file is empty.");
            return "redirect:/mobile/waiting_dashboard";
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean isHeader = true;
            int savedCount = 0;
            int duplicateCount = 0;
            int unparseableDateCount = 0;
            List<String> unparseableSamples = new java.util.ArrayList<>();

            List<WaitingPoint> existingRecords = waitingRepo.findAll();

            while ((line = br.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }

                // correct — back to comma
                String[] columns = line.split(",", -1);
                if (columns.length < 6) continue;

                LocalDate recordDate = parseOrganicCsvDate(columns[0]);
                if (recordDate == null) {
                    System.err.println("⚠️ Could not parse date: [" + columns[0].trim() + "] - skipping row");
                    unparseableDateCount++;
                    if (unparseableSamples.size() < 3) {
                        unparseableSamples.add(columns[0].trim());
                    }
                    continue;
                }

                String farmerId = columns[1].trim();
                final LocalDate finalDate = recordDate;


                double grossWeight = Double.parseDouble(columns[3].trim().isEmpty() ? "0.0" : columns[3].trim());
                int bags = Integer.parseInt(columns[4].trim().isEmpty() ? "0" : columns[4].trim());
                double netWeight = Double.parseDouble(columns[5].trim().isEmpty() ? "0.0" : columns[5].trim());

                boolean alreadyExists = existingRecords.stream()
                        .anyMatch(r -> farmerId.equals(r.getSupplierId())
                                && finalDate.equals(r.getDate())
                                && grossWeight == (r.getGrossWeight() != null ? r.getGrossWeight() : -1)
                                && bags == (r.getBags() != null ? r.getBags() : -1)
                                && netWeight == (r.getWeight() != null ? r.getWeight() : -1));
                if (alreadyExists) {
                    duplicateCount++;
                    continue;
                }

                WaitingPoint wp = new WaitingPoint();
                wp.setDate(recordDate);
                wp.setSupplierId(farmerId);
                wp.setSupplierName("");
                wp.setDate(recordDate);
                wp.setSupplierId(farmerId);

                Optional<Supplier> matchedSupplier = supplierRepo.findBySupplierId(farmerId);
                wp.setSupplierName(matchedSupplier.map(Supplier::getName).orElse(""));
                wp.setSection(matchedSupplier.map(Supplier::getSection).orElse("UNASSIGNED"));

                wp.setGrossWeight(grossWeight);
                wp.setBags(bags);
                wp.setWeight(netWeight);

                wp.setBatchId("");
                wp.setLotNumber("");
                wp.setOfficerName("");

                wp.setRoute(matchedSupplier.map(Supplier::getSection).orElse("UNASSIGNED"));

                wp.setStatus("PENDING");

                waitingRepo.save(wp);
                savedCount++;

                existingRecords.add(wp);
            }

            StringBuilder msg = new StringBuilder("CSV imported: " + savedCount + " records added.");
            if (duplicateCount > 0) {
                msg.append(" ").append(duplicateCount).append(" duplicate(s) skipped.");
            }
            if (unparseableDateCount > 0) {
                msg.append(" ").append(unparseableDateCount)
                        .append(" row(s) had an unrecognized date format and were skipped");
                if (!unparseableSamples.isEmpty()) {
                    msg.append(" (e.g. \"").append(String.join("\", \"", unparseableSamples)).append("\")");
                }
                msg.append(".");
            }
            redirectAttributes.addFlashAttribute("successMessage", msg.toString());

        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to process CSV file: " + e.getMessage());
        }
        if (officerSelect != null && !officerSelect.isBlank()) {
            redirectAttributes.addFlashAttribute("lastOfficer", officerSelect);
            if ("Other".equals(officerSelect) && customOfficerName != null && !customOfficerName.isBlank()) {
                redirectAttributes.addFlashAttribute("lastCustomOfficer", customOfficerName);
            }
        }

        return "redirect:/mobile/waiting_dashboard";
    }

    @PostMapping("/api/waiting/finalize")
    @Transactional
    public String finalizeRecords(@RequestParam Map<String, String> allParams, HttpSession session, RedirectAttributes redirectAttributes) {
        String username = (String) session.getAttribute("username");
        Admin admin = (username != null) ? adminRepo.findByUsername(username) : null;
        if (admin == null || !permissionService.hasPermission(admin, "WEIGHING_FINALIZE")) {
            redirectAttributes.addFlashAttribute("errorMessage", "You don't have permission to finalize records.");
            return "redirect:/mobile/waiting_dashboard";
        }
        try {
            // ✅ FIX #1: Only fetch PENDING records (not already finalized)
            List<WaitingPoint> pendingRecords = waitingRepo.findByStatusOrderByDateDesc("PENDING");

            if (pendingRecords.isEmpty()) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "❌ No Records to Finalize: All records have already been finalized or processed.");
                return "redirect:/mobile/waiting_dashboard";
            }

            // Validate officer selection
            String finalOfficer = allParams.get("officerSelect");
            if ("Other".equals(finalOfficer)) {
                String customName = allParams.get("customOfficerName");
                finalOfficer = (customName != null && !customName.trim().isEmpty())
                        ? customName.trim()
                        : null;
            }

            if (finalOfficer == null || finalOfficer.trim().isEmpty()) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "❌ Officer Required: Please select or identify the Responsible Officer before finalizing records.");
                return "redirect:/mobile/waiting_dashboard";
            }

            // Fetch batch parameters
            String amigosBatch = allParams.get("amigosBatch");
            String amigosLot   = allParams.get("amigosLot");
            String estateBatch = allParams.get("estateBatch");
            String estateLot   = allParams.get("estateLot");
            String lotfaBatch  = allParams.get("lotfaBatch");
            String lotfaLot    = allParams.get("lotfaLot");

            // ✅ FIX #1: Validate batch IDs only for PENDING records
            boolean hasAmigos = pendingRecords.stream().anyMatch(r -> "Amigos".equalsIgnoreCase(r.getSection())
                    && (r.getBatchId() == null || r.getBatchId().trim().isEmpty()));
            boolean hasEstate = pendingRecords.stream().anyMatch(r -> "Estate".equalsIgnoreCase(r.getSection())
                    && (r.getBatchId() == null || r.getBatchId().trim().isEmpty()));
            boolean hasLotfa  = pendingRecords.stream().anyMatch(r -> "Farmer Group".equalsIgnoreCase(r.getSection())
                    && (r.getBatchId() == null || r.getBatchId().trim().isEmpty()));

            if (hasAmigos && (amigosBatch == null || amigosBatch.trim().isEmpty())) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "❌ Batch ID Required: Please enter a Batch ID for Amigos before finalizing.");
                return "redirect:/mobile/waiting_dashboard";
            }
            if (hasEstate && (estateBatch == null || estateBatch.trim().isEmpty())) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "❌ Batch ID Required: Please enter a Batch ID for Estate before finalizing.");
                return "redirect:/mobile/waiting_dashboard";
            }
            if (hasLotfa && (lotfaBatch == null || lotfaBatch.trim().isEmpty())) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "❌ Batch ID Required: Please enter a Batch ID for LOTFA before finalizing.");
                return "redirect:/mobile/waiting_dashboard";
            }

            // ✅ FIX: Lot Number was previously never validated server-side — enforce it
            // the same way Batch ID is enforced, for each section that has pending records.
            if (hasAmigos && (amigosLot == null || amigosLot.trim().isEmpty())) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "❌ Lot Number Required: Please enter a Lot Number for Amigos before finalizing.");
                return "redirect:/mobile/waiting_dashboard";
            }
            if (hasEstate && (estateLot == null || estateLot.trim().isEmpty())) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "❌ Lot Number Required: Please enter a Lot Number for Estate before finalizing.");
                return "redirect:/mobile/waiting_dashboard";
            }
            if (hasLotfa && (lotfaLot == null || lotfaLot.trim().isEmpty())) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "❌ Lot Number Required: Please enter a Lot Number for LOTFA before finalizing.");
                return "redirect:/mobile/waiting_dashboard";
            }

            // ✅ FIX #1: Update PENDING records and mark as FINALIZED
            int updatedCount = 0;
            LocalDateTime now = LocalDateTime.now();

            for (WaitingPoint wp : pendingRecords) {
                if (wp.getBatchId() == null || wp.getBatchId().trim().isEmpty()) {
                    String section = wp.getSection() != null ? wp.getSection() : "";
                    boolean hasBatchDetails = false;

                    if (section.equalsIgnoreCase("Amigos")) {
                        wp.setBatchId(amigosBatch.trim() + " ( Amigos )");
                        wp.setLotNumber(amigosLot != null ? amigosLot.trim() + " ( Amigos )" : "");
                        hasBatchDetails = true;
                    } else if (section.equalsIgnoreCase("Estate")) {
                        wp.setBatchId(estateBatch.trim() + " ( Estate )");
                        wp.setLotNumber(estateLot != null ? estateLot.trim() + " ( Estate )" : "");
                        hasBatchDetails = true;
                    } else if (section.equalsIgnoreCase("Farmer Group")) {
                        wp.setBatchId(lotfaBatch.trim() + " ( Farmer Group )");
                        wp.setLotNumber(lotfaLot != null ? lotfaLot.trim() + " ( Farmer Group )" : "");
                        hasBatchDetails = true;
                    }

                    if (hasBatchDetails) {
                        wp.setOfficerName(finalOfficer.trim());

                        // ✅ FIX #1: Mark as FINALIZED and set timestamp
                        wp.setStatus("FINALIZED");
                        wp.setFinalizedAt(now);

                        waitingRepo.save(wp);
                        updatedCount++;
                    }
                }
            }

            redirectAttributes.addFlashAttribute("successMessage",
                    "✅ Success: " + updatedCount + " records finalized with Officer: " + finalOfficer);

            if (updatedCount > 0) {
                NotificationEvent event = new NotificationEvent();
                event.setEventType("WEIGHING");
                event.setMessage(finalOfficer.trim() + " finalized " + updatedCount +
                        " weighing record" + (updatedCount == 1 ? "" : "s") + " at Weighing Point");
                notificationRepo.save(event);
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "❌ Error: " + e.getMessage());
        }
        return "redirect:/mobile/waiting_dashboard";
    }
}