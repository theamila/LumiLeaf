package com.lumileaf.lumi.controller;

import com.lumileaf.lumi.model.WitheringPoint;
import com.lumileaf.lumi.model.WaitingPoint;
import com.lumileaf.lumi.repository.WitheringPointRepository;
import com.lumileaf.lumi.repository.WaitingPointRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import com.lumileaf.lumi.model.NotificationEvent;
import com.lumileaf.lumi.repository.NotificationEventRepository;
import com.lumileaf.lumi.util.BatchIdUtils;

@Controller
public class WitheringController {

    @Autowired private WitheringPointRepository witheringRepo;
    @Autowired private WaitingPointRepository waitingRepo;
    @Autowired
    private NotificationEventRepository notificationRepo;

    @GetMapping("/mobile/withering_dashboard")
    public String showWitheringForm(HttpSession session, Model model) {
        String username = (String) session.getAttribute("username");
        if (username == null) return "redirect:/login";

        // Consider a batch "processed" if there exists any WitheringPoint for that batch,
// regardless of when it was processed. Prevents previously-combined batches
// from reappearing the next day.
        Set<String> processedBatchIds = witheringRepo.findAll().stream()
                .map(WitheringPoint::getBatchId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        List<WaitingPoint> finalizedRecords = waitingRepo.findAll().stream()
                .filter(r -> r.getBatchId() != null && !r.getBatchId().trim().isEmpty())
                .collect(Collectors.toList());

        // ── FIX 1: Group by FULL batch ID (e.g. "113 ( ESTATE )", "113 ( LOTFA )")
        // Previously grouped by base key ("113"), which merged LOTFA + ESTATE weights
        // into one combined total. Now each type gets its own correct weight.
        Map<String, Double> fullBatchWeightMap = finalizedRecords.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getBatchId().trim(),
                        Collectors.summingDouble(r -> r.getWeight() != null ? r.getWeight() : 0.0)
                ));

        Set<String> uniqueFullBatchIds = finalizedRecords.stream()
                .map(WaitingPoint::getBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Map<String, Object>> arrivedBatches = new ArrayList<>();
        for (String fullBatchId : uniqueFullBatchIds) {
            // ── FIX 1 (continued): Look up weight by full ID, not stripped base key
            Double specificWeight = fullBatchWeightMap.getOrDefault(fullBatchId.trim(), 0.0);

            Map<String, Object> batchInfo = new HashMap<>();
            batchInfo.put("batchId", fullBatchId);
            batchInfo.put("totalWeight", specificWeight);
            batchInfo.put("isProcessed", processedBatchIds.contains(fullBatchId));
            arrivedBatches.add(batchInfo);
        }

        model.addAttribute("batches", arrivedBatches);

        // Standard predefined dropdown options passed to the UI template layer
        List<String> officerNames = Arrays.asList("Jagath");
        model.addAttribute("officers", officerNames);

        model.addAttribute("witheringHistory", buildGroupedWitheringHistory());
        model.addAttribute("witheringPoint", new WitheringPoint());

        return "WitheringMobile";
    }
    @PostMapping("/qa/withering/recompute-intake")
    public String recomputeIntakeWeight(@RequestParam("batchId") String batchId,
                                        HttpSession session,
                                        RedirectAttributes ra) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";

        List<WitheringPoint> records = witheringRepo.findAll().stream()
                .filter(w -> batchId != null && batchId.trim().equals(w.getBatchId()))
                .collect(Collectors.toList());

        if (records.isEmpty()) {
            ra.addFlashAttribute("error", "No withering record found for batch " + batchId);
            return "redirect:/qa-withering-records";
        }

        double correctIntake = roundWeight(computeIntakeWeightForBatch(batchId));

        for (WitheringPoint w : records) {
            double oldIntake = w.getIntakeWeight() != null ? w.getIntakeWeight() : 0.0;
            w.setIntakeWeight(correctIntake);
            w.setIntakeAdjustedAt(java.time.LocalDateTime.now());
            w.setIntakeAdjustedBy((String) session.getAttribute("username"));
            witheringRepo.save(w);

            NotificationEvent event = new NotificationEvent();
            event.setEventType("WITHERING_CORRECTION");
            event.setMessage(session.getAttribute("username") + " recomputed intake weight for batch "
                    + batchId + ": " + oldIntake + "kg -> " + correctIntake
                    + "kg (witheredWeight was NOT changed — remeasure physically if needed)");
            notificationRepo.save(event);
        }

        ra.addFlashAttribute("successMessage",
                "Intake weight recomputed for batch " + batchId
                        + ". Note: witheredWeight/processLoss were not touched — those need a physical remeasurement, not just a data fix.");
        return "redirect:/qa-withering-records";
    }

    @PostMapping("/mobile/withering/save")
    public String saveWitheringRecord(@RequestParam(value = "batchIds", required = false) List<String> batchIds,
                                      @RequestParam Map<String, String> allParams,
                                      HttpSession session,
                                      RedirectAttributes ra) {

        String username = (String) session.getAttribute("username");
        if (username == null) return "redirect:/login";

        // Fallback handle to support single-item form submission profiles safely
        List<String> finalBatchIds = batchIds;
        if (finalBatchIds == null || finalBatchIds.isEmpty()) {
            String singleBatchId = allParams.get("batchId");
            if (singleBatchId != null && !singleBatchId.isBlank()) {
                finalBatchIds = Collections.singletonList(singleBatchId);
            } else {
                ra.addFlashAttribute("error", "Please select at least one batch to process!");
                return "redirect:/mobile/withering_dashboard";
            }
        }

        for (String batchId : finalBatchIds) {
            boolean alreadyProcessed = witheringRepo.findAll().stream()
                    .anyMatch(w -> batchId != null && batchId.trim().equals(w.getBatchId()));
            if (alreadyProcessed) {
                ra.addFlashAttribute("error", "This batch has already been processed and cannot be submitted again!");
                return "redirect:/mobile/withering_dashboard";
            }
        }

        // Pre-calculate cumulative server intake pool for proportional allocation
        double totalServerIntakeWeight = 0.0;
        for (String bId : finalBatchIds) {
            totalServerIntakeWeight += roundWeight(computeIntakeWeightForBatch(bId));
        }

        double totalWitheredWeight = 0.0;
        try {
            String witheredStr = allParams.get("witheredWeight");
            if (witheredStr != null && !witheredStr.isBlank()) {
                totalWitheredWeight = roundWeight(Double.parseDouble(witheredStr.replace(",", ".").trim()));
            } else {
                System.err.println("[Withering] witheredWeight was blank/missing — batch=" + finalBatchIds);
                ra.addFlashAttribute("error", "Withered weight is required. Please enter a value and resubmit.");
                return "redirect:/mobile/withering_dashboard";
            }
        } catch (NumberFormatException e) {
            System.err.println("[Withering] Could not parse witheredWeight: '"
                    + allParams.get("witheredWeight") + "' — batch=" + finalBatchIds);
            ra.addFlashAttribute("error", "Invalid withered weight value. Please enter a valid number.");
            return "redirect:/mobile/withering_dashboard";
        }

        // ── FIX (WitheringController #1): productionBatchNo / productionLotNo are the
        // fields the entire downstream traceability chain (Rolling auto-fill, Production,
        // Stock Production, Blending) depends on. Previously these were read with
        // allParams.get(...) and saved with NO validation — a missing/blank form field
        // would silently save a null value and break the chain with no error at the source.
        // Now validated with the same strictness already used for witheredWeight above.
        String productionBatchNo = allParams.get("productionBatchNo");
        String productionLotNo = allParams.get("productionLotNo");

        if (productionBatchNo == null || productionBatchNo.isBlank()
                || productionLotNo == null || productionLotNo.isBlank()) {
            ra.addFlashAttribute("error", "Production batch number and lot number are required.");
            return "redirect:/mobile/withering_dashboard";
        }

        double distributedWitheredSum = 0.0;

        // Loop through each selected batch and generate independent tracking records
        for (int i = 0; i < finalBatchIds.size(); i++) {
            String batchId = finalBatchIds.get(i);
            WitheringPoint record = new WitheringPoint();

            // ── DYNAMIC OFFICER HANDLER ──
            // Reads primary drop-down field selection
            String finalOfficer = allParams.get("officerName");

            // Overrides selection value if text-field manual entry is triggered via "Other"
            if ("Other".equals(finalOfficer)) {
                finalOfficer = allParams.get("customOfficerName");
            }

            record.setDate(LocalDate.now());
            record.setWitheringOfficer(username);
            record.setOfficerName(finalOfficer != null ? finalOfficer.trim() : "Unknown");
            record.setBatchId(batchId);
            record.setLabors(allParams.get("labors"));
            record.setStartTime(allParams.get("startTime"));
            record.setEndTime(allParams.get("endTime"));

            record.setProductionBatchNo(BatchIdUtils.normalizeLotNumber(productionBatchNo));
            record.setProductionLotNo(BatchIdUtils.normalizeLotNumber(productionLotNo));

            if (record.getStartTime() != null && record.getEndTime() != null) {
                record.setTimeTaken(calculateDuration(record.getStartTime(), record.getEndTime()));
            }

            // ── FIX 2: computeIntakeWeightForBatch() now matches on full batch ID,
            // so "113 ( ESTATE )" only sums ESTATE records, not LOTFA+ESTATE combined.
            // This ensures the correct intakeWeight is saved to the DB, which is what
            // the QA page reads — fixing the 0.0 kg display in qa_withering_records.
            //
            // ── FIX 3 (floating point): Raw double summation of WaitingPoint weights
            // produces values like 99.97000000000001 due to IEEE 754 representation.
            // roundWeight() clamps the result to 2 decimal places before saving so
            // the DB always stores clean values (100.0, not 99.97).
            double serverIntakeWeight = roundWeight(computeIntakeWeightForBatch(batchId));
            if (serverIntakeWeight > 0) {
                record.setIntakeWeight(serverIntakeWeight);
            } else {
                try {
                    String intakeStr = allParams.get("intakeWeight");
                    if (intakeStr != null && !intakeStr.isBlank()) {
                        double parsed = roundWeight(Double.parseDouble(intakeStr.replace(",", ".").trim()));
                        if (parsed > 0) record.setIntakeWeight(roundWeight(parsed / finalBatchIds.size()));
                    }
                } catch (NumberFormatException e) {
                    System.err.println("[Withering] Could not parse intakeWeight from client: '"
                            + allParams.get("intakeWeight") + "' — batch=" + batchId);
                }
            }

            // Proportional distribution logic to split cumulative output weights accurately
            double individualWithered = 0.0;
            if (i == finalBatchIds.size() - 1) {
                // Last batch captures the remaining float value to prevent math precision loss
                individualWithered = roundWeight(totalWitheredWeight - distributedWitheredSum);
            } else {
                if (totalServerIntakeWeight > 0) {
                    individualWithered = roundWeight(totalWitheredWeight * (serverIntakeWeight / totalServerIntakeWeight));
                } else {
                    individualWithered = roundWeight(totalWitheredWeight / finalBatchIds.size());
                }
                distributedWitheredSum += individualWithered;
            }
            record.setWitheredWeight(individualWithered);

            witheringRepo.save(record);
        }

        String noteOfficer = allParams.get("officerName");
        if ("Other".equals(noteOfficer)) {
            noteOfficer = allParams.get("customOfficerName");
        }
        if (noteOfficer == null || noteOfficer.isBlank()) noteOfficer = "Unknown";

        NotificationEvent event = new NotificationEvent();
        event.setEventType("WITHERING");
        event.setMessage(noteOfficer.trim() + " completed withering for " +
                finalBatchIds.size() + " batch" + (finalBatchIds.size() == 1 ? "" : "es") +
                " (" + String.join(", ", finalBatchIds) + ")");
        notificationRepo.save(event);

        return "redirect:/mobile/withering_dashboard?success";
    }

    /**
     * FIX 2: Matches WaitingPoint records by FULL batch ID (e.g. "113 ( ESTATE )").
     *
     * Previously this method stripped the type suffix and summed all sub-batches
     * sharing the same number — so both "113 ( ESTATE )" and "113 ( LOTFA )" would
     * return the combined total. Now each full ID maps to its own weight only.
     */
    private double computeIntakeWeightForBatch(String fullBatchId) {
        if (fullBatchId == null || fullBatchId.isBlank()) return 0.0;
        String trimmedTarget = fullBatchId.trim();

        return waitingRepo.findAll().stream()
                .filter(r -> r.getBatchId() != null
                        && r.getBatchId().trim().equals(trimmedTarget))
                .mapToDouble(r -> r.getWeight() != null ? r.getWeight() : 0.0)
                .sum();
    }

    /**
     * FIX (Issue 3): Groups raw WitheringPoint rows the same way RollingController
     * groups them for its dashboard — by shared productionBatchNo + productionLotNo
     * (falling back to an isolated "SINGLE_" group when either is missing). Each
     * group is collapsed into one display record with batch IDs joined by " + ",
     * matching the exact convention Rolling/Drying/QA already use, so a multi-batch
     * Withering submission shows as ONE combined card instead of N separate ones.
     */
    private List<Map<String, Object>> buildGroupedWitheringHistory() {
        List<WitheringPoint> rawRecords = witheringRepo.findAll().stream()
                .filter(w -> w.getBatchId() != null && !w.getBatchId().trim().isEmpty())
                .collect(Collectors.toList());

        Map<String, List<WitheringPoint>> grouped = new LinkedHashMap<>();
        for (WitheringPoint w : rawRecords) {
            String pBatch = w.getProductionBatchNo() != null ? w.getProductionBatchNo().trim() : "";
            String pLot = w.getProductionLotNo() != null ? w.getProductionLotNo().trim() : "";

            String groupKey = (!pBatch.isEmpty() && !pLot.isEmpty())
                    ? pBatch + "||" + pLot
                    : "SINGLE_" + w.getBatchId().trim();

            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(w);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (List<WitheringPoint> group : grouped.values()) {
            WitheringPoint reference = group.get(0);

            String combinedBatchId = group.stream()
                    .map(WitheringPoint::getBatchId)
                    .map(String::trim)
                    .collect(Collectors.joining(" + "));

            double totalIntake = group.stream()
                    .mapToDouble(w -> w.getIntakeWeight() != null ? w.getIntakeWeight() : 0.0)
                    .sum();
            double totalWithered = group.stream()
                    .mapToDouble(w -> w.getWitheredWeight() != null ? w.getWitheredWeight() : 0.0)
                    .sum();

            Map<String, Object> map = new HashMap<>();
            map.put("batchId", combinedBatchId);
            map.put("date", reference.getDate());
            map.put("intakeWeight", roundWeight(totalIntake));
            map.put("witheredWeight", roundWeight(totalWithered));
            map.put("productionBatchNo", reference.getProductionBatchNo());
            map.put("productionLotNo", reference.getProductionLotNo());
            map.put("startTime", reference.getStartTime());
            map.put("endTime", reference.getEndTime());
            map.put("officerName", reference.getOfficerName());
            map.put("labors", reference.getLabors());
            map.put("timeTaken", reference.getTimeTaken());

            result.add(map);
        }

        result.sort((a, b) -> {
            LocalDate da = (LocalDate) a.get("date");
            LocalDate db = (LocalDate) b.get("date");
            if (da == null) return 1;
            if (db == null) return -1;
            return db.compareTo(da); // newest first
        });

        return result;
    }

    @GetMapping("/qa/withering-records")
    public String showQaWitheringRecords(HttpSession session, Model model) {
        if (session.getAttribute("username") == null) return "redirect:/login";

        model.addAttribute("witheringRecords", buildGroupedWitheringHistory());
        return "qa_withering_records";
    }

    @GetMapping("/mobile/withering-summary")
    public String showWitheringSummary(HttpSession session, Model model) {
        if (session.getAttribute("username") == null) return "redirect:/login";

        model.addAttribute("witheringHistory", buildGroupedWitheringHistory());
        return "withering_history_summary";
    }

    @GetMapping("/production/withering-history")
    public String showProductionWitheringHistory(HttpSession session, Model model) {
        if (session.getAttribute("username") == null) return "redirect:/login";

        model.addAttribute("witheringHistory", buildGroupedWitheringHistory());
        return "withering_history_summary";
    }

    /**
     * Rounds a weight value to 2 decimal places to eliminate IEEE 754 floating
     * point drift (e.g. 99.97000000000001 → 100.0 after summing doubles).
     * Always apply this before saving any weight to the DB.
     */
    private double roundWeight(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String calculateDuration(String start, String end) {
        try {
            LocalTime startTime = LocalTime.parse(start);
            LocalTime endTime = LocalTime.parse(end);
            long minutes = ChronoUnit.MINUTES.between(startTime, endTime);
            if (minutes < 0) minutes = (24 * 60) + minutes;
            return String.format("%dh %02dm", minutes / 60, minutes % 60);
        } catch (Exception e) { return "N/A"; }
    }
}