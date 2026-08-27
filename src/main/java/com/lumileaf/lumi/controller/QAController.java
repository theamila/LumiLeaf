package com.lumileaf.lumi.controller;

import com.lumileaf.lumi.model.*;
import com.lumileaf.lumi.repository.*;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;
import com.lumileaf.lumi.model.NotificationEvent;
import com.lumileaf.lumi.model.NotificationSeenMarker;
import com.lumileaf.lumi.repository.NotificationEventRepository;
import com.lumileaf.lumi.repository.NotificationSeenMarkerRepository;
import com.lumileaf.lumi.service.ContributionService;
// add this import near your other java.time imports
import java.time.LocalDateTime;
// Adjust the package if you saved it elsewhere

@Controller
public class QAController {

    private static final Logger logger = LoggerFactory.getLogger(QAController.class);
    @Autowired private ProductionBatchRepository productionRepo;
    @Autowired private NotificationEventRepository notificationRepo;
    @Autowired private NotificationSeenMarkerRepository notificationSeenRepo;
    @Autowired private BlendingRepository blendingRepo;
    @Autowired private RollingPointRepository rollingRepo;
    @Autowired private WitheringPointRepository witheringRepo;
    @Autowired private WaitingPointRepository waitingRepo;
    @Autowired private MassProductionRepository massProductionRepo;
    @Autowired private BlendBalanceRepository blendBalanceRepo;
    @Autowired private GradeTransactionRepository txRepo;
    @Autowired private StockProductionRepository stockProductionRepo;
    @Autowired private ContributionService contributionService;
    @Autowired private SupplierRepository supplierRepo;
    // Standardized global grade array for cross-endpoint key consistency
    private static final String[] ALL_GRADES = {
            "OP1", "OPA", "BOP1", "PEKOE", "BOP", "BOPF", "EB", "FFSP", "FFEXS", "DUST", "BM", "BP", "REFUSE"
    };
    // --------------------------------------------------
    // 1. QA DASHBOARD (UPDATED TO SHOW ALL GRADES)
    // --------------------------------------------------
    @GetMapping("/qa_dashboard")
    public String qaDashboard(HttpSession session, Model model) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";
        // Only consider batches that are APPROVED for production-derived stock
        List<ProductionBatch> approvedBatches = productionRepo.findAll().stream()
                .filter(b -> "APPROVED".equals(b.getStatus()))
                .collect(Collectors.toList());
        List<BlendBalance> remnants = blendBalanceRepo.findAll();
        List<GradeTransaction> transactions = txRepo.findAll();

        // Include StockProduction data
        List<StockProduction> stockProductions = stockProductionRepo.findAll();
        List<Map<String, Object>> gradesList = new ArrayList<>();

        for (String grade : ALL_GRADES) {
            double freshStock = 0.0;
            double remnantStock = 0.0;
            double txAdded = 0.0;
            double txSubtracted = 0.0;
            double stockProdAmount = 0.0;
            // 1. Calculate Production (only APPROVED batches)
            for (ProductionBatch batch : approvedBatches) {
                Double originalQtyNullable = getRawProdValue(batch, grade);
                double originalQty = (originalQtyNullable != null) ? originalQtyNullable : 0.0;

                double usedQty = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(batch.getLotNumber(), grade)).orElse(0.0);
                // Alternate naming for refused tea in older records
                if (grade.equals("REFUSE")) {
                    usedQty += Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(batch.getLotNumber(), "REFUSEDTEA")).orElse(0.0);
                }
                double remaining = originalQty - usedQty;
                if (remaining > 0) freshStock += remaining;
            }

            // 2. Calculate Remnants (BlendBalance)
            for (BlendBalance rem : remnants) {
                Double originalQtyNullable = getRawBalanceValue(rem, grade);
                double originalQty = (originalQtyNullable != null) ? originalQtyNullable : 0.0;

                double usedQty = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(rem.getBlendId(), grade)).orElse(0.0);
                if (grade.equals("REFUSE")) {
                    usedQty += Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(rem.getBlendId(), "REFUSEDTEA")).orElse(0.0);
                }
                double remaining = originalQty - usedQty;
                if (remaining > 0) remnantStock += remaining;
            }

            // 3. Calculate Transactions
            for (GradeTransaction tx : transactions) {
                if (tx.getSourceGrade() != null && (grade.equalsIgnoreCase(tx.getSourceGrade()) ||
                        (grade.equals("REFUSE") && "REFUSEDTEA".equalsIgnoreCase(tx.getSourceGrade())))) {

                    txSubtracted += tx.getSourceQty() != null ? tx.getSourceQty() : 0.0;
                }
                Double txValueNullable = getRawTxValue(tx, grade);
                txAdded += (txValueNullable != null) ? txValueNullable : 0.0;
            }

            // 4. Stock Production — deduct blending usage per lot, mirroring step 1,
            // and exclude REJECTED lots entirely so rejected tea no longer counts as stock.
            for (StockProduction sp : stockProductions) {
                if ("REJECTED".equals(sp.getStatus())) continue;

                Double spValNullable = getRawStockProdValue(sp, grade);
                double originalQty = (spValNullable != null) ? spValNullable : 0.0;

                double usedQty = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(sp.getLotNumber(), grade)).orElse(0.0);
                if (grade.equals("REFUSE")) {
                    usedQty += Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(sp.getLotNumber(), "REFUSEDTEA")).orElse(0.0);
                }
                double remaining = originalQty - usedQty;
                if (remaining > 0) stockProdAmount += remaining;
            }

            // Final production-derived freshStock calculation
            freshStock = freshStock + txAdded - txSubtracted + stockProdAmount;
            // Protection against negative stock metrics
            if (freshStock < 0) {
                freshStock = 0.0;
            }

            // Unconditionally add every grade to the dashboard
            Map<String, Object> gradeData = new HashMap<>();
            gradeData.put("name", grade);
            gradeData.put("freshStock", round(freshStock));
            gradeData.put("remnantStock", round(remnantStock));
            gradeData.put("totalStock", round(freshStock + remnantStock));
            gradesList.add(gradeData);
        }

        model.addAttribute("gradesList", gradesList);
        return "qa_dashboard";
    }
    @GetMapping("/notifications")
    public String viewNotifications(HttpSession session, Model model) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";

        List<NotificationEvent> events = notificationRepo.findAllByOrderByIdDesc();
        model.addAttribute("events", events);

        // Mark everything as seen the moment this page is loaded
        Long maxId = events.isEmpty() ? 0L : events.get(0).getId();
        NotificationSeenMarker marker = notificationSeenRepo.findById(1L).orElse(new NotificationSeenMarker());
        marker.setId(1L);
        marker.setLastSeenId(maxId);
        notificationSeenRepo.save(marker);

        return "notifications";
    }
    @GetMapping("/api/qa/notifications/unread-count")
    @ResponseBody
    public Map<String, Long> getUnreadNotificationCount(HttpSession session) {
        Map<String, Long> result = new HashMap<>();
        if (session.getAttribute("role") == null) {
            result.put("unread", 0L);
            return result;
        }
        NotificationSeenMarker marker = notificationSeenRepo.findById(1L).orElse(new NotificationSeenMarker());
        long unread = notificationRepo.countByIdGreaterThan(marker.getLastSeenId());
        result.put("unread", unread);
        return result;
    }

    @GetMapping("/api/qa/grade-stock")
    @ResponseBody
    public Map<String, Double> getGradeStock() {
        Map<String, Double> stockMap = new HashMap<>();
        List<ProductionBatch> approvedBatches = productionRepo.findAll().stream()
                .filter(b -> "APPROVED".equals(b.getStatus()))
                .collect(Collectors.toList());
        List<BlendBalance> remnants = blendBalanceRepo.findAll();
        List<GradeTransaction> transactions = txRepo.findAll();
        List<StockProduction> stockProductions = stockProductionRepo.findAll();
        for (String grade : ALL_GRADES) {
            double total = 0.0;
            // 1. From Production
            for (ProductionBatch batch : approvedBatches) {
                Double originalQtyNullable = getRawProdValue(batch, grade);
                double originalQty = (originalQtyNullable != null) ? originalQtyNullable : 0.0;

                double usedQty = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(batch.getLotNumber(), grade)).orElse(0.0);
                if (grade.equals("REFUSE")) {
                    usedQty += Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(batch.getLotNumber(), "REFUSEDTEA")).orElse(0.0);
                }
                total += Math.max(0, originalQty - usedQty);
            }

            // 2. From Remnants
            for (BlendBalance rem : remnants) {
                Double originalQtyNullable = getRawBalanceValue(rem, grade);
                double originalQty = (originalQtyNullable != null) ? originalQtyNullable : 0.0;

                double usedQty = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(rem.getBlendId(), grade)).orElse(0.0);
                if (grade.equals("REFUSE")) {
                    usedQty += Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(rem.getBlendId(), "REFUSEDTEA")).orElse(0.0);
                }
                total += Math.max(0, originalQty - usedQty);
            }

            // 3. From Transactions
            for (GradeTransaction tx : transactions) {
                if (tx.getSourceGrade() != null && (grade.equalsIgnoreCase(tx.getSourceGrade()) ||
                        (grade.equals("REFUSE") && "REFUSEDTEA".equalsIgnoreCase(tx.getSourceGrade())))) {

                    total -= tx.getSourceQty() != null ? tx.getSourceQty() : 0.0;
                }
                Double txValueNullable = getRawTxValue(tx, grade);
                total += (txValueNullable != null) ? txValueNullable : 0.0;
            }

            // 4. From Stock Production — deduct blending usage per lot,
            // and exclude REJECTED lots entirely so rejected tea no longer counts as stock.
            for (StockProduction sp : stockProductions) {
                if ("REJECTED".equals(sp.getStatus())) continue;

                Double spValNullable = getRawStockProdValue(sp, grade);
                double originalQty = (spValNullable != null) ? spValNullable : 0.0;

                double usedQty = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(sp.getLotNumber(), grade)).orElse(0.0);
                if (grade.equals("REFUSE")) {
                    usedQty += Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(sp.getLotNumber(), "REFUSEDTEA")).orElse(0.0);
                }
                total += Math.max(0, originalQty - usedQty);
            }

            // Unconditionally add the grade to the map
            stockMap.put(grade, round(Math.max(0.0, total)));
        }
        return stockMap;
    }

    // Helper that returns the raw (nullable) value from a ProductionBatch for presence checks.
    private Double getRawProdValue(ProductionBatch batch, String grade) {
        if (batch == null || grade == null) return null;
        return switch (grade.toUpperCase()) {
            case "OP1" -> batch.getOp1();
            case "OPA" -> batch.getOpa();
            case "BOP1" -> batch.getBop1();
            case "PEKOE" -> batch.getPekoe();
            case "BOP" -> batch.getBop();
            case "BOPF" -> batch.getBopf();
            case "EB" -> batch.getEb();
            case "FFSP" -> batch.getFfsp();
            case "FFEXS" -> batch.getFfexs();
            case "DUST" -> batch.getDust();
            case "BM" -> batch.getBm();
            case "BP" -> batch.getBp();
            case "REFUSE", "REFUSEDTEA" -> batch.getRefusedTea();
            default -> null;
        };
    }

    private Double getRawBalanceValue(BlendBalance balance, String grade) {
        if (balance == null || grade == null) return null;
        return switch (grade.toUpperCase()) {
            case "OP1" -> balance.getOp1();
            case "OPA" -> balance.getOpa();
            case "BOP1" -> balance.getBop1();
            case "PEKOE" -> balance.getPekoe();
            case "BOP" -> balance.getBop();
            case "BOPF" -> balance.getBopf();
            case "EB" -> balance.getEb();
            case "FFSP" -> balance.getFfsp();
            case "FFEXS" -> balance.getFfexs();
            case "DUST" -> balance.getDust();
            case "BM" -> balance.getBm();
            case "BP" -> balance.getBp();
            case "REFUSE", "REFUSEDTEA" -> balance.getRefusedTea();
            default -> null;
        };
    }

    private Double getRawTxValue(GradeTransaction tx, String grade) {
        if (tx == null || grade == null) return null;
        return switch (grade.toUpperCase()) {
            case "OP1"    -> tx.getOp1();
            case "OPA"    -> tx.getOpa();
            case "BOP1"   -> tx.getBop1();
            case "PEKOE"  -> tx.getPekoe();
            case "BOP"    -> tx.getBop();
            case "BOPF"   -> tx.getBopf();
            case "EB"     -> tx.getEb();
            case "FFSP"   -> tx.getFfsp();
            case "FFEXS"  -> tx.getFfexs();
            case "DUST"   -> tx.getDust();
            case "BM"     -> tx.getBm();
            case "BP"     -> tx.getBp();
            case "REFUSE", "REFUSEDTEA" -> tx.getRefusedTea();
            default -> null;
        };
    }

    private Double getRawStockProdValue(StockProduction sp, String grade) {
        if (sp == null || grade == null) return null;
        return switch (grade.toUpperCase()) {
            case "OP1"    -> sp.getOp1();
            case "OPA"    -> sp.getOpa();
            case "BOP1"   -> sp.getBop1();
            case "PEKOE"  -> sp.getPekoe();
            case "BOP"    -> sp.getBop();
            case "BOPF"   -> sp.getBopf();
            case "EB"     -> sp.getEb();
            case "FFSP"   -> sp.getFfsp();
            case "FFEXS"  -> sp.getFfexs();
            case "DUST"   -> sp.getDust();
            case "BM"     -> sp.getBm();
            case "BP"     -> sp.getBp();
            case "REFUSE", "REFUSEDTEA" -> sp.getRefusedTea();
            default -> null;
        };
    }

    private double getGradeValueFromTx(GradeTransaction tx, String grade) {
        Double v = getRawTxValue(tx, grade);
        return (v != null) ? v : 0.0;
    }

    private double extractProdStock(ProductionBatch batch, String grade) {
        Double v = getRawProdValue(batch, grade);
        return (v != null) ? v : 0.0;
    }

    private double extractRemnantStock(BlendBalance balance, String grade) {
        Double v = getRawBalanceValue(balance, grade);
        return (v != null) ? v : 0.0;
    }

    // --------------------------------------------------
    // 2. DRYING RECORDS QA CONTROLS
    // --------------------------------------------------
    @GetMapping("/qa-drying-records")
    public String viewDryingRecords(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            HttpSession session, Model model){

        if(!"QA".equals(session.getAttribute("role"))) return "redirect:/login";

        // ✅ FIX #5: Show PENDING, DRYING, APPROVED, and CONSOLIDATED batches in QA drying tab
        // APPROVED batches show with a badge for reference but cannot be re-approved
        List<ProductionBatch> dryingRecords = productionRepo.findAll().stream()
                .filter(b -> "DRYING".equals(b.getStatus()) || "PENDING".equals(b.getStatus()) || "APPROVED".equals(b.getStatus()) || "CONSOLIDATED".equals(b.getStatus()))
                .collect(Collectors.toList());

        // ✅ FIX: Apply Date Range Filter by dryingDate (with fallback to productionDate)
        // This allows drying data from different dates to show up in QA
        if (startDate != null && !startDate.isEmpty()) {
            LocalDate start = LocalDate.parse(startDate);
            dryingRecords = dryingRecords.stream()
                    .filter(r -> {
                        LocalDate dateToCheck = r.getDryingDate() != null ? r.getDryingDate() : r.getProductionDate();
                        return dateToCheck != null && !dateToCheck.isBefore(start);
                    })
                    .collect(Collectors.toList());
        }
        if (endDate != null && !endDate.isEmpty()) {
            LocalDate end = LocalDate.parse(endDate);
            dryingRecords = dryingRecords.stream()
                    .filter(r -> {
                        LocalDate dateToCheck = r.getDryingDate() != null ? r.getDryingDate() : r.getProductionDate();
                        return dateToCheck != null && !dateToCheck.isAfter(end);
                    })
                    .collect(Collectors.toList());
        }

        Collections.reverse(dryingRecords); // Most recent first

        model.addAttribute("dryingRecords", dryingRecords);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        System.out.println("DEBUG: Found " + dryingRecords.size() + " drying records");
        for (ProductionBatch b : dryingRecords) {
            System.out.println("  - " + b.getLotNumber() + " | Status: " + b.getStatus() + " | DryingDate: " + b.getDryingDate());
        }
        return "qa_drying_records";
    }


    // Asynchronous API endpoint for QA Updates with server-side validation
    @PostMapping("/api/qa/drying/update-status")
    @ResponseBody
    public ResponseEntity<?> updateDryingStatusAjax(
            @RequestParam Long id,
            @RequestParam String status,
            @RequestParam(required = false) Double humidity,
            HttpSession session) {

        String username = (String) session.getAttribute("username");
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized access."));
        }

        try {
            ProductionBatch batch = productionRepo.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Batch ID not found: " + id));
            if ("APPROVED".equalsIgnoreCase(status)) {
                if (humidity == null || humidity <= 0) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Validation Failed: Valid environmental humidity required for approval."));
                }
                batch.setHumidity(humidity);
            }

            batch.setStatus(status.toUpperCase());
            batch.setProcessedByQA(true);
            // FIX (Issue 4): dryingOfficer must stay as the officer who actually ran
            // Rolling/Drying (set by RollingController). QA approving/consolidating
            // a batch is a separate action and must never overwrite that value with
            // whichever QA user happens to be logged in.

// ✅ FIX #3: When approved, ensure timestamp is set for production workflow

// ✅ FIX #3: When approved, ensure timestamp is set for production workflow
            if ("APPROVED".equalsIgnoreCase(status)) {
                batch.setProcessedByQA(true);
                logger.info("Batch {} approved by QA officer {}. Ready for production workflow.", batch.getLotNumber(), username);
            }

            productionRepo.save(batch);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "QA Status synced successfully.",
                    "newStatus", batch.getStatus()
            ));
        } catch (Exception e) {
            logger.error("Error approving drying batch id={}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // approve helper retained (keeps original behavior)
    private String approveDryingInternal(Long id, Double humidity, String username) {
        ProductionBatch batch = productionRepo.findById(id).orElseThrow();
        logger.info("Approving drying batch id={} before: temp={}, moisture={}, status={}",
                id, batch.getTemperature(), batch.getMoistureContent(), batch.getStatus());
        batch.setHumidity(humidity != null ? humidity : batch.getHumidity() != null ? batch.getHumidity() : 0.0);
        batch.setStatus("APPROVED");
        batch.setProcessedByQA(true);
        // FIX (Issue 4): dryingOfficer must stay as the officer who actually ran
        // Rolling/Drying (set by RollingController). QA approving a batch here is a
        // separate action and must never overwrite that value.

        // Keep grade distribution performed here if you want auto calculation on POST approve
        // calculateAndDistributeGrades(batch);
        productionRepo.save(batch);

        logger.info("Approved drying batch id={} after: humidity={}, status={}, dryingOfficer={}",
                id, batch.getHumidity(), batch.getStatus(), batch.getDryingOfficer());
        return "redirect:/qa-drying-records?success";
    }

    @PostMapping("/qa/drying/approve")
    public String approveDrying(@RequestParam Long id,
                                @RequestParam(required = false) Double humidity,
                                HttpSession session) {
        String username = (String) session.getAttribute("username");
        return approveDryingInternal(id, humidity, username);
    }

    @GetMapping("/qa/drying/approve")
    public String approveDryingGet(@RequestParam Long id,
                                   @RequestParam(required = false) Double humidity,
                                   HttpSession session) {

        logger.warn("Fallback GET /qa/drying/approve invoked for id={} (temporary)", id);
        String username = (String) session.getAttribute("username");
        return approveDryingInternal(id, humidity, username);
    }

    // --------------------------------------------------
    // 3. WAITING, ROLLING, WITHERING, MASS PRODUCTION, ETC.
    // These methods preserved as original. (Kept unchanged.)
    // --------------------------------------------------
    @GetMapping("/waiting")
    public String viewWaitingRecords(HttpSession session, Model model) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";

        List<WaitingPoint> records = waitingRepo.findAll().stream()
                .sorted(Comparator.comparing(WaitingPoint::getId).reversed())
                .collect(Collectors.toList());

        for (WaitingPoint record : records) {
            if (record.getSupplierName() == null || record.getSupplierName().isEmpty()) {
                supplierRepo.findBySupplierId(record.getSupplierId())
                        .ifPresent(s -> record.setSupplierName(s.getName()));
            }
        }

        Map<String, Double> batchTotals = records.stream()
                .filter(r -> r.getBatchId() != null && !r.getBatchId().isBlank())
                .collect(Collectors.groupingBy(
                        WaitingPoint::getBatchId,
                        Collectors.summingDouble(r -> r.getWeight() != null ? r.getWeight() : 0.0)
                ));

        List<WaitingRecordRow> rows = records.stream()
                .map(r -> {
                    if (r.getBatchId() == null || r.getBatchId().isBlank()) {
                        return new WaitingRecordRow(r, null);
                    }
                    double total = batchTotals.getOrDefault(r.getBatchId(), 0.0);
                    double weight = r.getWeight() != null ? r.getWeight() : 0.0;
                    double percent = total > 0 ? Math.round((weight / total) * 10000.0) / 100.0 : 0.0;
                    return new WaitingRecordRow(r, percent);
                })
                .collect(Collectors.toList());

        // REPLACE WITH:
        model.addAttribute("records", rows);

        List<String> finalizedBatchIds = waitingRepo.findByStatusOrderByDateDesc("FINALIZED").stream()
                .map(WaitingPoint::getBatchId)
                .filter(b -> b != null && !b.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        model.addAttribute("finalizedBatchIds", finalizedBatchIds);

        return "waiting_records_view";
    }


    @GetMapping("/waiting/delete/{id}")
    public String deleteWaitingRecord(@PathVariable Long id, HttpSession session) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";
        waitingRepo.deleteById(id);
        return "redirect:/waiting?deleted=true";
    }
    @GetMapping("/waiting/edit/{id}")
    public String editWaitingRecordForm(@PathVariable Long id, HttpSession session, Model model) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";
        WaitingPoint record = waitingRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Waiting record not found: " + id));
        model.addAttribute("record", record);
        return "edit_waiting_record";
    }

    @PostMapping("/waiting/update")
    public String updateWaitingRecord(@ModelAttribute("record") WaitingPoint submitted,
                                      HttpSession session,
                                      RedirectAttributes ra) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";

        WaitingPoint existing = waitingRepo.findById(submitted.getId())
                .orElseThrow(() -> new IllegalArgumentException("Waiting record not found: " + submitted.getId()));

        // Only weight is editable — batchId/section stay locked, matching the
        // read-only fields already enforced client-side in edit_waiting_record.html
        if (submitted.getWeight() == null || submitted.getWeight() <= 0) {
            ra.addFlashAttribute("error", "Weight must be a positive number.");
            return "redirect:/waiting/edit/" + submitted.getId();
        }

        double oldWeight = existing.getWeight() != null ? existing.getWeight() : 0.0;
        existing.setWeight(submitted.getWeight());
        waitingRepo.save(existing);

        NotificationEvent event = new NotificationEvent();
        event.setEventType("WEIGHING_CORRECTION");
        event.setMessage("Weight corrected for " + existing.getSupplierName() + " (batch " + existing.getBatchId()
                + "): " + oldWeight + "kg -> " + submitted.getWeight() + "kg by "
                + session.getAttribute("username"));
        notificationRepo.save(event);

        ra.addFlashAttribute("successMessage", "Record updated successfully.");
        return "redirect:/waiting";
    }
    @GetMapping("/waiting/add-to-batch")
    public String addToBatchForm(HttpSession session, Model model) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";

        List<String> finalizedBatchIds = waitingRepo.findByStatusOrderByDateDesc("FINALIZED").stream()
                .map(WaitingPoint::getBatchId)
                .filter(b -> b != null && !b.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        model.addAttribute("finalizedBatchIds", finalizedBatchIds);
        return "add_to_existing_batch";
    }

    @PostMapping("/waiting/add-to-batch")
    public String addToBatchSave(@RequestParam("batchId") String batchId,
                                 @RequestParam("supplierId") String supplierId,
                                 @RequestParam("weight") Double weight,
                                 @RequestParam(value = "grossWeight", required = false) Double grossWeight,
                                 @RequestParam(value = "bags", required = false) Integer bags,
                                 HttpSession session,
                                 RedirectAttributes ra) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";

        if (batchId == null || batchId.isBlank()) {
            ra.addFlashAttribute("error", "Please select an existing batch.");
            return "redirect:/waiting";
        }
        if (supplierId == null || supplierId.isBlank()) {
            ra.addFlashAttribute("error", "Supplier ID is required.");
            return "redirect:/waiting";
        }
        if (weight == null || weight <= 0) {
            ra.addFlashAttribute("error", "Weight must be a positive number.");
            return "redirect:/waiting";
        }

        // Copy date/section/lotNumber/route from an existing record in this exact
        // batch — this is what eliminates the typo risk entirely: the officer picks
        // the batch from a dropdown, never retypes the batchId string by hand.
        List<WaitingPoint> siblingRecords = waitingRepo.findByBatchId(batchId.trim());
        if (siblingRecords == null || siblingRecords.isEmpty()) {
            ra.addFlashAttribute("error", "Selected batch has no existing records.");
            return "redirect:/waiting";
        }
        WaitingPoint template = siblingRecords.get(0);

        WaitingPoint wp = new WaitingPoint();
        wp.setBatchId(template.getBatchId());
        wp.setDate(template.getDate());
        wp.setSection(template.getSection());
        wp.setLotNumber(template.getLotNumber());
        wp.setRoute(template.getRoute());

        wp.setSupplierId(supplierId.trim());
        supplierRepo.findBySupplierId(supplierId.trim())
                .ifPresentOrElse(s -> wp.setSupplierName(s.getName()), () -> wp.setSupplierName(""));

        wp.setWeight(weight);
        wp.setGrossWeight(grossWeight);
        wp.setBags(bags);

        wp.setOfficerName("BACKFILL:" + session.getAttribute("username"));
        wp.setStatus("FINALIZED");
        wp.setFinalizedAt(LocalDateTime.now());

        waitingRepo.save(wp);

        NotificationEvent event = new NotificationEvent();
        event.setEventType("WEIGHING_BACKFILL");
        event.setMessage(session.getAttribute("username") + " added a missed record for supplier "
                + wp.getSupplierId() + " to finalized batch " + wp.getBatchId() + " (" + weight + "kg)");
        notificationRepo.save(event);

        ra.addFlashAttribute("successMessage", "Missing record added to batch " + wp.getBatchId() + ".");
        return "redirect:/waiting";
    }

    @GetMapping("/qa-rolling-records")
    public String viewRollingRecords(HttpSession session, Model model){
        if(!"QA".equals(session.getAttribute("role"))) return "redirect:/login";
        List<RollingPoint> allRecords = rollingRepo.findAll();
        Map<String, RollingPoint> groupedMap = new LinkedHashMap<>();
        for(RollingPoint r : allRecords){
            String bid = r.getBatchId();
            if(bid == null) continue;

            double weightIn = r.getWeightIn() != null ? r.getWeightIn() : 0;
            double d1 = r.getDhool1() != null ? r.getDhool1() : 0;
            double d2 = r.getDhool2() != null ? r.getDhool2() : 0;
            double d3 = r.getDhool3() != null ? r.getDhool3() : 0;
            double bulk = r.getBigBulk() != null ? r.getBigBulk() : 0;
            if(groupedMap.containsKey(bid)){
                RollingPoint e = groupedMap.get(bid);
                double newWeight = (e.getWeightIn() != null ? e.getWeightIn() : 0) + weightIn;
                double nd1 = (e.getDhool1() != null ? e.getDhool1() : 0) + d1;
                double nd2 = (e.getDhool2() != null ? e.getDhool2() : 0) + d2;
                double nd3 = (e.getDhool3() != null ? e.getDhool3() : 0) + d3;
                double nb = (e.getBigBulk() != null ? e.getBigBulk() : 0) + bulk;

                e.setWeightIn(round(newWeight));
                e.setDhool1(round(nd1));
                e.setDhool2(round(nd2));
                e.setDhool3(round(nd3));
                e.setBigBulk(round(nb));
                double totalOut = nd1 + nd2 + nd3 + nb;
                e.setProcessLoss(round(newWeight - totalOut));
                // ✅ FIX #1: Preserve rainfall and weather condition when updating existing batch
                e.setRainfall(r.getRainfall());
                e.setWeatherCondition(r.getWeatherCondition());
            } else {
                RollingPoint display = new RollingPoint();
                display.setBatchId(bid);

                display.setRollingDate(r.getEntryDate());
                display.setWeightIn(weightIn);
                display.setDhool1(d1);
                display.setDhool2(d2);
                display.setDhool3(d3);
                display.setBigBulk(bulk);
                display.setProcessLoss(r.getProcessLoss() != null ? r.getProcessLoss() : 0);
                String actualName = (r.getOfficerName() != null && !r.getOfficerName().isBlank()) ?
                        r.getOfficerName() :
                        (r.getRollingOfficer() != null ? r.getRollingOfficer() : "Unknown Officer");
                display.setOfficerName(actualName);
                display.setRollingOfficer(actualName);
                // ✅ FIX #1: Include rainfall and weather condition in display
                display.setRainfall(r.getRainfall());
                display.setWeatherCondition(r.getWeatherCondition());

                groupedMap.put(bid, display);
            }
        }

        List<RollingPoint> finalHistory = new ArrayList<>(groupedMap.values());
        finalHistory.sort(Comparator.comparing(RollingPoint::getEntryDate, Comparator.nullsLast(Comparator.reverseOrder())));

        double totalInput = 0.0;
        double totalOutput = 0.0;
        double totalLoss = 0.0;
        for (RollingPoint r : finalHistory) {
            double weightIn = r.getWeightIn() != null ?
                    r.getWeightIn() : 0;
            double d1 = r.getDhool1() != null ? r.getDhool1() : 0;
            double d2 = r.getDhool2() != null ?
                    r.getDhool2() : 0;
            double d3 = r.getDhool3() != null ? r.getDhool3() : 0;
            double bulk = r.getBigBulk() != null ?
                    r.getBigBulk() : 0;

            double output = d1 + d2 + d3 + bulk;
            double loss = weightIn - output;
            totalInput += weightIn;
            totalOutput += output;
            totalLoss += loss;
        }

        double avgLoss = !finalHistory.isEmpty() ?
                totalLoss / finalHistory.size() : 0;

        model.addAttribute("totalInput", round(totalInput));
        model.addAttribute("totalOutput", round(totalOutput));
        model.addAttribute("avgLoss", round(avgLoss));
        model.addAttribute("rollingRecords", finalHistory);
        return "qa_rolling_records";
    }

    @GetMapping("/qa-withering-records")
    public String viewWitheringRecords(HttpSession session, Model model) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";
        List<WitheringPoint> allRecords = witheringRepo.findAll();
        Map<String, WitheringPoint> map = new LinkedHashMap<>();
        for (WitheringPoint r : allRecords) {
            String bid = r.getBatchId();
            if (bid == null) continue;

            double before = r.getIntakeWeight() != null ? r.getIntakeWeight() : 0;
            double after = r.getWitheredWeight() != null ? r.getWitheredWeight() : 0;
            if (map.containsKey(bid)) {
                WitheringPoint existing = map.get(bid);
                existing.setIntakeWeight((existing.getIntakeWeight() != null ? existing.getIntakeWeight() : 0) + before);
                existing.setWitheredWeight((existing.getWitheredWeight() != null ? existing.getWitheredWeight() : 0) + after);
                if ((existing.getEndTime() == null || "--:--".equals(existing.getEndTime())) && r.getEndTime() != null) {
                    existing.setEndTime(r.getEndTime());
                }
            } else {
                WitheringPoint d = new WitheringPoint();
                d.setBatchId(bid);
                d.setIntakeWeight(before);
                d.setWitheredWeight(after);
                d.setDate(r.getDate());

                d.setOfficerName(r.getOfficerName() != null ? r.getOfficerName() : "Unknown");
                d.setLabors(r.getLabors() != null ? r.getLabors() : "Not Assigned");
                d.setTimeTaken(r.getTimeTaken() != null ? r.getTimeTaken() : "0h 00m");

                d.setStartTime(r.getStartTime() != null ? r.getStartTime() : "--:--");
                d.setEndTime(r.getEndTime() != null ? r.getEndTime() : "--:--");

                map.put(bid, d);
            }
        }

        List<WitheringPoint> list = new ArrayList<>(map.values());
        list.sort(Comparator.comparing(WitheringPoint::getDate, Comparator.nullsLast(Comparator.reverseOrder())));

        model.addAttribute("witheringRecords", list);
        return "qa_withering_records";
    }

    @GetMapping("/mass-production")
    public String viewMassProduction(@RequestParam(required=false) String startDate, @RequestParam(required=false) String endDate, HttpSession session, Model model){
        if(!"QA".equals(session.getAttribute("role"))) return "redirect:/login";

        // ✅ FIXED: Changed variable type from List<Object[]> to List<MassProductionDTO>
        List<MassProductionDTO> raw = waitingRepo.aggregateWeightByDate();
        List<MassProductionDTO> list = new ArrayList<>();

        LocalDate start = (startDate != null && !startDate.isBlank()) ? LocalDate.parse(startDate) : LocalDate.of(1970, 1, 1);
        LocalDate end = (endDate != null && !endDate.isBlank()) ? LocalDate.parse(endDate) : LocalDate.of(2099, 12, 31);

        // ✅ FIXED: Updated iterator and reading syntax to use DTO getter methods instead of index casting
        for(MassProductionDTO r : raw){
            if(r.getDate() == null) continue;
            LocalDate d = r.getDate();
            if(!d.isBefore(start) && !d.isAfter(end)){
                double weight = r.getTotalWeight();
                // ✅ FIX #2: Fetch estimatedAmount from database
                MassProduction mp = massProductionRepo.findByDate(d).orElse(null);
                double actual = mp != null && mp.getActualMadeTea() != null ? mp.getActualMadeTea() : 0.0;
                double estimated = mp != null && mp.getEstimatedAmount() != null ? mp.getEstimatedAmount() : 0.0;

                MassProductionDTO dto = new MassProductionDTO(d, weight, actual);
                // ✅ FIX #2: Set the custom estimated amount
                dto.setEstimatedAmount(estimated);
                list.add(dto);
            }
        }
        model.addAttribute("productionList", list);

        // ✅ To-date summary cards (Net Received & Actual Made, cumulative up to today)
        LocalDate today = LocalDate.now();

        double toDateNetReceived = raw.stream()
                .filter(r -> r.getDate() != null && !r.getDate().isAfter(today))
                .mapToDouble(MassProductionDTO::getTotalWeight)
                .sum();

        double toDateActualMade = massProductionRepo.findAll().stream()
                .filter(r -> r.getDate() != null && !r.getDate().isAfter(today))
                .mapToDouble(r -> r.getActualMadeTea() != null ? r.getActualMadeTea() : 0.0)
                .sum();

        LocalDate lastNetDate = raw.stream()
                .map(MassProductionDTO::getDate)
                .filter(d -> d != null && !d.isAfter(today))
                .max(LocalDate::compareTo)
                .orElse(null);

        LocalDate lastActualDate = massProductionRepo.findAll().stream()
                .filter(r -> r.getActualMadeTea() != null && r.getActualMadeTea() > 0)
                .map(MassProduction::getDate)
                .filter(d -> d != null && !d.isAfter(today))
                .max(LocalDate::compareTo)
                .orElse(null);

        model.addAttribute("toDateNetReceived", round(toDateNetReceived));
        model.addAttribute("toDateActualMade", round(toDateActualMade));
        model.addAttribute("todayDate", today);
        model.addAttribute("lastNetDate", lastNetDate);
        model.addAttribute("lastActualDate", lastActualDate);

        return "mass_production";
    }

    // ✅ FIX #2: New endpoint to update estimated amount
    @PostMapping("/update-estimated-amount")
    public String updateEstimatedAmount(@RequestParam String date, @RequestParam Double estimatedAmount){
        LocalDate d = LocalDate.parse(date);
        MassProduction r = massProductionRepo.findByDate(d).orElse(new MassProduction());
        r.setDate(d);
        r.setEstimatedAmount(estimatedAmount != null ? estimatedAmount : 0);
        massProductionRepo.save(r);
        return "redirect:/mass-production?success";
    }

    @PostMapping("/update-actual-tea")
    public String updateActualTea(@RequestParam String date, @RequestParam Double actual){
        LocalDate d = LocalDate.parse(date);
        MassProduction r = massProductionRepo.findByDate(d).orElse(new MassProduction());
        r.setDate(d);
        r.setActualMadeTea(actual != null ? actual : 0);
        massProductionRepo.save(r);
        return "redirect:/mass-production?success";
    }

    @GetMapping("/qa/blend-balance")
    public String viewBlendBalance(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            HttpSession session, Model model) {

        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";
        List<BlendBalance> allBalances = blendBalanceRepo.findAll();

        if (startDate != null && !startDate.isEmpty() && endDate != null && !endDate.isEmpty()) {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            allBalances = allBalances.stream()
                    .filter(b -> {
                        LocalDate d = (b.getEntryDate() != null) ? b.getEntryDate() : LocalDate.now();
                        return !d.isBefore(start) && !d.isAfter(end);

                    })
                    .collect(Collectors.toList());
        }

        List<Map<String, Object>> liveHistory = new ArrayList<>();
        for (BlendBalance b : allBalances) {
            Map<String, Object> row = new HashMap<>();
            row.put("blendId", b.getBlendId());

            Map<String, Double> currentWeights = new HashMap<>();
            double totalRowCurrent = 0.0;
            for (String grade : ALL_GRADES) {
                double original = getGradeValueFromBalance(b, grade);
                double used = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(b.getBlendId(), grade)).orElse(0.0);
                if (grade.equals("REFUSE")) {
                    used += Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(b.getBlendId(), "REFUSEDTEA")).orElse(0.0);
                }
                double current = Math.max(0, original - used);
                currentWeights.put(grade, round(current));
                row.put(grade + "_orig", original);
                totalRowCurrent += current;
            }
            row.put("weights", currentWeights);
            row.put("totalCurrent", round(totalRowCurrent));

            // Fix 2: build compact "GRADE: value (Orig: x)" comma-separated summary,
            // omitting any grade whose current value is 0
            StringBuilder summary = new StringBuilder();
            for (String grade : ALL_GRADES) {
                double current = currentWeights.get(grade);
                if (current > 0) {
                    Object origObj = row.get(grade + "_orig");
                    double orig = (origObj instanceof Number) ? ((Number) origObj).doubleValue() : 0.0;
                    if (summary.length() > 0) summary.append(", ");
                    summary.append(grade).append(": ").append(current)
                            .append(" (Orig: ").append(round(orig)).append(")");
                }
            }
            row.put("gradesSummary", summary.length() > 0 ? summary.toString() : "Fully depleted");

            liveHistory.add(row);
        }

        model.addAttribute("history", liveHistory);
        model.addAttribute("allBlends", blendingRepo.findAll());

        return "blend_balance_entry";
    }

    @PostMapping("/qa/save-blend-balance")
    public String saveManualBlend(@RequestParam Map<String, String> params, RedirectAttributes redirectAttributes){
        String blendId = params.get("blendId");
        if(blendId == null || blendId.trim().isEmpty()){
            redirectAttributes.addFlashAttribute("error", "Blend ID is required.");
            return "redirect:/qa/blend-balance";
        }

        BlendBalance e = new BlendBalance();
        e.setBlendId(blendId);
        e.setOp1(safeParse(params.get("op1")));
        e.setOpa(safeParse(params.get("opa")));
        e.setBop1(safeParse(params.get("bop1")));
        e.setPekoe(safeParse(params.get("pekoe")));
        e.setBop(safeParse(params.get("bop")));
        e.setBopf(safeParse(params.get("bopf")));
        e.setEb(safeParse(params.get("eb")));
        e.setFfsp(safeParse(params.get("ffsp")));
        e.setFfexs(safeParse(params.get("ffexs")));
        e.setDust(safeParse(params.get("dust")));
        e.setBm(safeParse(params.get("bm")));
        e.setBp(safeParse(params.get("bp")));
        e.setRefusedTea(safeParse(params.get("refusedTea")));

        blendBalanceRepo.save(e);
        return "redirect:/qa/blend-balance?success";
    }

    @PostMapping("/qa/reset-inventory")
    public String resetInventory(@RequestParam(required=false) String confirm, HttpSession session){
        if(!"QA".equals(session.getAttribute("role"))) return "redirect:/login";
        if(!"YES".equals(confirm)) return "redirect:/qa_dashboard?reset_cancelled";

        blendBalanceRepo.deleteAll();
        productionRepo.deleteAll();
        blendingRepo.deleteAll();
        return "redirect:/qa_dashboard?reset_success";
    }

    @GetMapping("/transactions")
    public String showTransactionsPage(HttpSession session, Model model) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";
        List<GradeTransaction> history = txRepo.findAll();
        history.sort(Comparator.comparing(GradeTransaction::getId).reversed());

        model.addAttribute("transactions", history);
        return "transactions";
    }

    @PostMapping("/qa/save-transaction")
    public String saveTransaction(@RequestParam Map<String, String> params, HttpSession session, RedirectAttributes ra) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";
        String sourceGrade = params.get("sourceGrade");
        double sourceQty = safeParse(params.get("sourceQty"));

        Map<String, Double> currentStock = getGradeStock();
        String stockKey = "REFUSEDTEA".equalsIgnoreCase(sourceGrade) ?
                "REFUSE" : sourceGrade.toUpperCase();
        double availableAmount = currentStock.getOrDefault(stockKey, 0.0);

        if (sourceQty > availableAmount) {
            ra.addFlashAttribute("error", "Failed: Insufficient stock in " + sourceGrade +
                    ". Available: " + availableAmount + "kg, Requested: " + sourceQty + "kg");
            return "redirect:/transactions";
        }

        GradeTransaction tx = new GradeTransaction();
        tx.setDate(LocalDate.now());
        tx.setTime(LocalTime.now());
        String officerName = params.get("officer");
        tx.setOfficer((officerName != null && !officerName.trim().isEmpty())
                ? officerName.trim()
                : (String) session.getAttribute("username"));

        tx.setSourceGrade(sourceGrade);
        tx.setSourceQty(sourceQty);

        tx.setOp1(safeParse(params.get("op1")));
        tx.setOpa(safeParse(params.get("opa")));
        tx.setBop1(safeParse(params.get("bop1")));
        tx.setPekoe(safeParse(params.get("pekoe")));
        tx.setBop(safeParse(params.get("bop")));
        tx.setBopf(safeParse(params.get("bopf")));
        tx.setEb(safeParse(params.get("eb")));
        tx.setFfsp(safeParse(params.get("ffsp")));
        tx.setFfexs(safeParse(params.get("ffexs")));
        tx.setDust(safeParse(params.get("dust")));
        tx.setBm(safeParse(params.get("bm")));
        tx.setBp(safeParse(params.get("bp")));
        tx.setRefusedTea(safeParse(params.get("refusedTea")));

        txRepo.save(tx);
        return "redirect:/transactions?success";
    }

    @GetMapping("/api/qa/record-counts")
    @ResponseBody
    public Map<String, Long> getRecordCounts(HttpSession session) {
        Map<String, Long> activity = new HashMap<>();
        if (session.getAttribute("role") != null) {
            LocalDate threeDaysAgo = LocalDate.now().minusDays(3);
            long weighing = waitingRepo.findAll().stream()
                    .filter(r -> r.getDate() != null && !r.getDate().isBefore(threeDaysAgo))
                    .count();
            long withering = witheringRepo.findAll().stream()
                    .filter(r -> r.getDate() != null && !r.getDate().isBefore(threeDaysAgo))
                    .count();
            long rolling = rollingRepo.findAll().stream()
                    .filter(r -> r.getEntryDate() != null && !r.getEntryDate().isBefore(threeDaysAgo))
                    .count();
            long drying = productionRepo.findAll().stream()
                    .filter(r -> r.getProductionDate() != null && !r.getProductionDate().isBefore(threeDaysAgo))
                    .filter(r -> "DRYING".equals(r.getStatus()) || "PENDING".equals(r.getStatus()) || "APPROVED".equals(r.getStatus()) || "CONSOLIDATED".equals(r.getStatus()))
                    .count();
            activity.put("weighing", weighing);
            activity.put("withering", withering);
            activity.put("rolling", rolling);
            activity.put("drying", drying);
        }

        return activity;
    }

    // PRIVATE HELPERS
    private double getGradeValueFromBalance(BlendBalance balance, String grade) {
        if (balance == null || grade == null) return 0.0;
        Double value = switch (grade.toUpperCase()) {
            case "OP1"    -> balance.getOp1();
            case "OPA"    -> balance.getOpa();
            case "BOP1"   -> balance.getBop1();
            case "PEKOE"  -> balance.getPekoe();
            case "BOP"    -> balance.getBop();
            case "BOPF"   -> balance.getBopf();
            case "EB"     -> balance.getEb();
            case "FFSP"   -> balance.getFfsp();
            case "FFEXS"  -> balance.getFfexs();
            case "DUST"   -> balance.getDust();
            case "BM"     -> balance.getBm();
            case "BP"     -> balance.getBp();
            case "REFUSE", "REFUSEDTEA" -> balance.getRefusedTea();
            default -> 0.0;
        };
        return (value != null) ? value : 0.0;
    }

    private double getGradeValueFromStockProduction(StockProduction sp, String grade) {
        if (sp == null || grade == null) return 0.0;
        Double value = switch (grade.toUpperCase()) {
            case "OP1"    -> sp.getOp1();
            case "OPA"    -> sp.getOpa();
            case "BOP1"   -> sp.getBop1();
            case "PEKOE"  -> sp.getPekoe();
            case "BOP"    -> sp.getBop();
            case "BOPF"   -> sp.getBopf();
            case "EB"     -> sp.getEb();
            case "FFSP"   -> sp.getFfsp();
            case "FFEXS"  -> sp.getFfexs();
            case "DUST"   -> sp.getDust();
            case "BM"     -> sp.getBm();
            case "BP"     -> sp.getBp();
            case "REFUSE", "REFUSEDTEA" -> sp.getRefusedTea();
            default -> 0.0;
        };
        return (value != null) ? value : 0.0;
    }

    private double safeParse(String value) {
        try {
            if (value == null || value.trim().isEmpty()) return 0.0;
            return Double.parseDouble(value);
        } catch (Exception e) { return 0.0; }
    }

    private double round(double v){
        return Math.round(v * 100.0) / 100.0;
    }
}