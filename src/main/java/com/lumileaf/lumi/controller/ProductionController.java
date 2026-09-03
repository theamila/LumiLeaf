package com.lumileaf.lumi.controller;

import com.lumileaf.lumi.model.*;
import com.lumileaf.lumi.repository.*;
import com.lumileaf.lumi.repository.StockProductionRepository; // ✅ FIX: Explicitly imported the repository
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;

import java.time.LocalDate;
import java.util.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.stream.Collectors;
import java.util.Objects;
import com.lumileaf.lumi.util.BatchIdUtils;
import com.lumileaf.lumi.service.ContributionService;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.net.InetAddress;
import java.net.UnknownHostException;
import com.lumileaf.qrcode.QRGenerator;
import org.springframework.beans.factory.annotation.Value;


@Controller
public class ProductionController {

    @Autowired private ProductionBatchRepository productionRepo;
    @Autowired private RollingPointRepository rollingPointRepo;
    @Autowired private BlendingRepository blendingRepo;
    @Autowired private WitheringPointRepository witheringPointRepo;
    @Autowired private WaitingPointRepository waitingPointRepo;
    @Autowired private BlendBalanceRepository blendBalanceRepo;
    @Autowired private StockProductionRepository stockProductionRepo;
    @Autowired private ContributionService contributionService;
    @Value("${app.base-url}")
    private String baseUrl;

    @Autowired private TemplateEngine templateEngine;

    private double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double nz(Double v) { return v != null ? v : 0.0; }

    // Finds any StockProduction lot(s) this batch's lot number belongs to — handles both
    // today's 1:1 consolidation and future " + "-joined multi-batch lots — and recalculates
    // that lot's grade totals from scratch off the CURRENT state of its source batch(es).
    // REJECTED batches are excluded from the recalculation, which is what makes rejecting
    // an already-consolidated batch immediately deduct its tea from the stock lot.
    private void resyncStockProductionLot(String batchLotNumber) {
        if (batchLotNumber == null) return;
        for (StockProduction lot : stockProductionRepo.findAll()) {
            String[] sourceLots = lot.getLotNumber().split("\\s*\\+\\s*");
            boolean matches = Arrays.stream(sourceLots).anyMatch(s -> s.trim().equals(batchLotNumber));
            if (matches) recalculateStockLotTotals(lot, sourceLots);
        }
    }

    private void recalculateStockLotTotals(StockProduction lot, String[] sourceLotNumbers) {
        double op1=0, opa=0, bop1=0, pekoe=0, bop=0, bopf=0, eb=0, ffsp=0, ffexs=0, dust=0, bm=0, bp=0, refuse=0, total=0;
        for (String sourceLot : sourceLotNumbers) {
            List<ProductionBatch> batchesForSource = productionRepo.findAllByLotNumber(sourceLot.trim());
            if (batchesForSource == null || batchesForSource.isEmpty()) continue;
            for (ProductionBatch b : batchesForSource) {
                if (b == null || "REJECTED".equals(b.getStatus())) continue;
                op1 += nz(b.getOp1()); opa += nz(b.getOpa()); bop1 += nz(b.getBop1());
                pekoe += nz(b.getPekoe()); bop += nz(b.getBop()); bopf += nz(b.getBopf());
                eb += nz(b.getEb()); ffsp += nz(b.getFfsp()); ffexs += nz(b.getFfexs());
                dust += nz(b.getDust()); bm += nz(b.getBm()); bp += nz(b.getBp());
                refuse += nz(b.getRefusedTea()); total += nz(b.getActualMadeTea());
            }
        }
        lot.setOp1(roundToTwoDecimals(op1)); lot.setOpa(roundToTwoDecimals(opa));
        lot.setBop1(roundToTwoDecimals(bop1)); lot.setPekoe(roundToTwoDecimals(pekoe));
        lot.setBop(roundToTwoDecimals(bop)); lot.setBopf(roundToTwoDecimals(bopf));
        lot.setEb(roundToTwoDecimals(eb)); lot.setFfsp(roundToTwoDecimals(ffsp));
        lot.setFfexs(roundToTwoDecimals(ffexs)); lot.setDust(roundToTwoDecimals(dust));
        lot.setBm(roundToTwoDecimals(bm)); lot.setBp(roundToTwoDecimals(bp));
        lot.setRefusedTea(roundToTwoDecimals(refuse)); lot.setTotal(roundToTwoDecimals(total));
        stockProductionRepo.save(lot);
    }

    @GetMapping("/api/stock/view-pdf/{id}")
    @ResponseBody
    public ResponseEntity<Resource> viewPdf(@PathVariable Long id) {
        StockProduction lot = stockProductionRepo.findById(id).orElseThrow();

        if (lot.getPdfReportPath() == null || lot.getPdfReportPath().isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Path path = Paths.get(lot.getPdfReportPath());
        Resource resource = new FileSystemResource(path.toFile());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + path.getFileName().toString() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(resource);
    }


    @GetMapping("/production")
    public String showProductionPage(
            @RequestParam(value = "date", required = false) String dateParam,
            @RequestParam(value = "success", required = false) String success,
            @RequestParam(value = "syncSuccess", required = false) String syncSuccess,
            @RequestParam(value = "deleted", required = false) String deleted,
            @RequestParam(value = "rejected", required = false) String rejected,
            Model model) {

        // ── FIX (ProductionController #3): Previously filtered out CONSOLIDATED batches,
        // which hid them from /production entirely the moment they were sent to Stock
        // Production. Per requirements, a batch must remain visible in Production even
        // after being consolidated — so ALL statuses are now shown, no filter applied.
        List<ProductionBatch> allBatches = productionRepo.findAll();
        List<ProductionBatch> batchesToUpdate = new ArrayList<>();

        for (ProductionBatch batch : allBatches) {
            if (batch.getLotNumber() != null) {

                // 1. Fetch Rolling Date dynamically
                Optional<RollingPoint> rpOpt = rollingPointRepo.findFirstByBatchId(batch.getLotNumber());
                if (rpOpt.isPresent()) {
                    if (rpOpt.get().getRollingDate() != null) {
                        batch.setRollingDate(rpOpt.get().getRollingDate());
                    } else {
                        batch.setRollingDate(rpOpt.get().getEntryDate());
                    }
                }

                boolean needsUpdate = false;

                // ── FIX (ProductionController #4, replaces old FIX #14): The old code derived
                // a "normalizedBatchId" from batch.getLotNumber() (the PRODUCTION lot number,
                // e.g. "LOTFA2606-2") by regex-stripping section suffixes, then looked up
                // WaitingPoint by that value. But WaitingPoint.batchId lives in a completely
                // different ID space — the raw weighing/gate batch ID (e.g. "113 ( ESTATE )").
                // These never matched, which is why green leaf date/weight silently failed to
                // populate. Now we use batch.getProductionId() directly — the raw weighing
                // batch ID stored by RollingController.saveRollingRecord() — with no
                // transformation needed, since it's already in the correct ID space.
                // ── FIX (Issue 5): productionId may be a single gate batch ID, or a
                // "+"-joined combined ID (e.g. "113 ( ESTATE ) + 114 ( LOTFA )") when
                // multiple withering batches were processed together. WaitingPoint rows
                // are always stored as individual, un-joined batch IDs, so an exact-match
                // lookup on the full joined string never returns anything. Split on "+"
                // and query + aggregate every sub-batch, matching the pattern already
                // used in RollingController/ProductionController's trace lookups.
                List<WaitingPoint> gateRecords = new ArrayList<>();
                if (batch.getProductionId() != null && !batch.getProductionId().isBlank()) {
                    for (String trimmedSubId : BatchIdUtils.splitSubBatchIds(batch.getProductionId())) {
                        List<WaitingPoint> subRecords = waitingPointRepo.findByBatchId(trimmedSubId);
                        if (subRecords != null) {
                            gateRecords.addAll(subRecords);
                        }
                    }
                }

                if (gateRecords.isEmpty()) {
                    System.out.println("⚠️  WARNING: No waiting point records found for weighing batch: "
                            + batch.getProductionId() + " (production lot: " + batch.getLotNumber() + ")");
                }

                // ✅ FIX #7 & #14: ALWAYS populate dates and weights from CSV, regardless of status
                // This ensures green leaf data is available immediately on every page load
                if (!gateRecords.isEmpty()) {
                    Optional<LocalDate> earliestWeighingDate = gateRecords.stream()
                            .map(WaitingPoint::getDate)
                            .filter(Objects::nonNull)
                            .min(LocalDate::compareTo);

                    if (earliestWeighingDate.isPresent()) {
                        LocalDate csvDate = earliestWeighingDate.get();
                        batch.setTransactionDate(csvDate);
                        // ✅ ONLY set productionDate if null (never overwrite existing)
                        if (batch.getProductionDate() == null) {
                            batch.setProductionDate(csvDate);
                        }
                        needsUpdate = true;
                        System.out.println("✅ Green Leaf Date set to: " + csvDate + " for batch: " + batch.getLotNumber());
                    }

                    // ✅ FIX #14: Sum ALL weights from waiting point (green leaf amount)
                    double totalGateWeight = gateRecords.stream()
                            .filter(w -> w.getWeight() != null)
                            .mapToDouble(WaitingPoint::getWeight)
                            .sum();

                    totalGateWeight = roundToTwoDecimals(totalGateWeight);

                    if (totalGateWeight > 0) {
                        batch.setGreenLeavesQty(totalGateWeight);
                        batch.setEstimatedMadeTea(roundToTwoDecimals(totalGateWeight * 0.215));
                        needsUpdate = true;
                        System.out.println("✅ Green Leaves set to: " + totalGateWeight + " kg for batch: " + batch.getLotNumber());
                    }
                }

                if (needsUpdate) {
                    batchesToUpdate.add(batch);
                }
            }
        }

        // Perform batch save
        if (!batchesToUpdate.isEmpty()) {
            productionRepo.saveAll(batchesToUpdate);
        }

        model.addAttribute("batches", allBatches);
        model.addAttribute("newBatch", new ProductionBatch());
        return "production";
    }

    private final String UPLOAD_DIR = "./uploads/";

    @PostMapping("/api/stock/upload-pdf/{id}")
    @ResponseBody
    public ResponseEntity<?> uploadPdf(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        try {
            StockProduction lot = stockProductionRepo.findById(id).orElseThrow();

            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);

            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Path filePath = uploadPath.resolve(fileName);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            lot.setPdfReportPath(filePath.toString());
            lot.setStatus("TESTED");
            if (lot.getTestedQty() == null || lot.getTestedQty() == 0.0) {
                lot.setTestedQty(lot.getTotal() != null ? lot.getTotal() : 0.0);
            }
            stockProductionRepo.save(lot);

            // ✅ FIX #1: Return JSON response for SweetAlert trigger
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Lab Report uploaded successfully",
                    "lotId", lot.getId(),
                    "status", lot.getStatus()
            ));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Error saving file: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/production/save")
    public String saveBatch(@ModelAttribute("newBatch") ProductionBatch batch, HttpSession session) {
        String role = (String) session.getAttribute("role");
        String username = (String) session.getAttribute("username");

        ProductionBatch batchToSave = (batch.getId() != null)
                ? productionRepo.findById(batch.getId()).orElse(new ProductionBatch())
                : productionRepo.findByLotNumberAndRollingDate(batch.getLotNumber(), batch.getProductionDate()).orElse(new ProductionBatch());

        batchToSave.setLotNumber(batch.getLotNumber());

        // ✅ FIX #3: ONLY update production date if batch is new
        // If batch already has a production date, NEVER overwrite it
        if (batchToSave.getProductionDate() == null && batch.getProductionDate() != null) {
            batchToSave.setProductionDate(batch.getProductionDate());
        }

        updateGradesInternal(batchToSave, batch);

        // ✅ FIX SET 1: Actual Made Tea is now a manually entered ceiling — no longer
        // derived from the sum of grades. Only overwrite it when the form actually
        // submitted a value.
        if (batch.getActualMadeTea() != null) {
            batchToSave.setActualMadeTea(roundToTwoDecimals(batch.getActualMadeTea()));
        }

        if ("QA".equals(role)) {
            // FIX (Issue 4): production.html's form has no humidity input field, so
            // `batch.getHumidity()` is always 0.0 (the entity's default) on every
            // submit from this page — never a real value. Only overwrite the stored
            // humidity when a genuine positive reading was actually submitted;
            // otherwise leave whatever QA Drying already recorded untouched.
            if (batch.getHumidity() != null && batch.getHumidity() > 0) {
                batchToSave.setHumidity(batch.getHumidity());
            }
            batchToSave.setStatus(batch.getStatus());
        } else {
            batchToSave.setDryingOfficer(username);
            batchToSave.setTemperature(batch.getTemperature());
            batchToSave.setMoistureContent(batch.getMoistureContent());
            if (batch.getGreenLeavesQty() != null) {
                double roundedGreenLeaves = roundToTwoDecimals(batch.getGreenLeavesQty());
                batchToSave.setGreenLeavesQty(roundedGreenLeaves);
                batchToSave.setEstimatedMadeTea(roundToTwoDecimals(roundedGreenLeaves * 0.215));
            }
            if (batchToSave.getStatus() == null || batchToSave.getStatus().isEmpty()) {
                batchToSave.setStatus("PENDING");
            }
        }
        // ✅ NEW: If status is changing to DRYING, set the drying date
        if ("DRYING".equalsIgnoreCase(batch.getStatus()) && batchToSave.getDryingDate() == null) {
            batchToSave.setDryingDate(LocalDate.now());
        }

        productionRepo.save(batchToSave);
        // FIX (Issue 3 / Option A): once a batch is CONSOLIDATED into Stock Production,
        // Stock Production's grade breakdown becomes independently owned (editable only
        // via "Edit Production" on the Stock Production tab). Production-tab edits after
        // consolidation must no longer resync/overwrite it — that resync call is removed
        // here and in updateBatchCellInlineCell(). It's intentionally kept in
        // rejectBatch() below, since deducting a rejected batch's tea from an already-
        // consolidated stock lot is a separate, existing, intentional feature.
        return "redirect:/production?success";
    }

    @PostMapping("/qa/approve-drying")
    public String approveDryingRecord(@RequestParam("id") Long id,
                                      @RequestParam("humidity") Double humidity,
                                      @RequestParam("status") String status) {
        ProductionBatch batch = productionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid batch ID: " + id));

        batch.setHumidity(humidity);
        batch.setStatus(status);
        productionRepo.save(batch);

        return "redirect:/production?syncSuccess";
    }

    @PostMapping("/api/production/update-cell")
    @ResponseBody
    public ResponseEntity<?> updateBatchCellInlineCell(@RequestParam("id") Long id,
                                                       @RequestParam("field") String field,
                                                       @RequestParam("value") Double value) {
        try {
            ProductionBatch batch = productionRepo.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Target Batch row not found"));

            switch (field.toUpperCase()) {
                case "OP1":   batch.setOp1(value); break;
                case "OPA":   batch.setOpa(value); break;
                case "BOP1":  batch.setBop1(value); break;
                case "PEKOE": batch.setPekoe(value); break;
                case "BOP":   batch.setBop(value); break;
                case "BOPF":  batch.setBopf(value); break;
                case "EB":    batch.setEb(value); break;
                case "FFSP":  batch.setFfsp(value); break;
                case "FFEXS": batch.setFfexs(value); break;
                case "DUST":  batch.setDust(value); break;
                case "BM":    batch.setBm(value); break;
                case "BP":    batch.setBp(value); break;
                case "REFUSE": batch.setRefusedTea(value); break;
                default: return ResponseEntity.badRequest().body("Unknown grade column mapping");
            }

            // ✅ FIX SET 1: Actual Made Tea is a manual ceiling — inline grade edits no
            // longer recalculate it. Instead validate the new grade sum doesn't exceed it.
            double gradeSum = calculateTotal(batch);
            double ceiling = batch.getActualMadeTea() != null ? batch.getActualMadeTea() : 0.0;

            if (ceiling > 0 && roundToTwoDecimals(gradeSum) > roundToTwoDecimals(ceiling)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Sum of grades (" + roundToTwoDecimals(gradeSum) + " kg) cannot exceed Actual Made (" + roundToTwoDecimals(ceiling) + " kg)."
                ));
            }

            productionRepo.save(batch);
            // FIX (Issue 3 / Option A): see saveBatch() above — inline grade edits on the
            // Production tab must no longer overwrite an already-consolidated Stock
            // Production lot's grade breakdown.

            return ResponseEntity.ok(Map.of("success", true, "updatedTotal", ceiling));
        } catch(Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @PostMapping("/api/production/consolidate")
    @ResponseBody
    public ResponseEntity<?> consolidate(@RequestBody List<Long> ids) {
        try {
            List<ProductionBatch> batches = productionRepo.findAllById(ids);
            if (batches.isEmpty()) return ResponseEntity.badRequest().body("No batches found");

            // ✅ FIX SET 2: Block consolidation if any selected batch is missing its
            // Actual Made Tea amount — Stock Production totals are keyed off it.
            List<String> missingActual = batches.stream()
                    .filter(b -> b.getActualMadeTea() == null || b.getActualMadeTea() <= 0)
                    .map(ProductionBatch::getLotNumber)
                    .collect(Collectors.toList());

            if (!missingActual.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        "Cannot send to Stock Production — missing Actual Made Tea amount for batch(es): "
                                + String.join(", ", missingActual));
            }

            StockProduction lot = new StockProduction();
            lot.setCreatedDate(LocalDate.now());

            // ── FIX (ProductionController #2): Stock lot number must EQUAL the production
            // lot number(s) it was consolidated from — this is the hard traceability
            // requirement (Stock Production lot → Production lot must match exactly).
            // The old code discarded this StringBuilder and instead generated a synthetic
            // "STOCK-<date>-<digits>" string, which never matched any ProductionBatch.lotNumber.
            // Now: 1 batch (today's default use case) → lot.lotNumber is IDENTICAL to that
            // batch's lot number. Multiple batches (future use case, once enabled) → joined
            // with " + ", the same convention RollingController already uses for combined
            // batch IDs, so traceability parsing stays consistent across the app.
            LinkedHashSet<String> distinctLotNumbers = new LinkedHashSet<>();
            for (ProductionBatch b : batches) {
                distinctLotNumbers.add(b.getLotNumber());
            }
            lot.setLotNumber(String.join(" + ", distinctLotNumbers));

            double runningTotalActual = 0;

            lot.setOp1(0.0); lot.setOpa(0.0); lot.setBop1(0.0); lot.setPekoe(0.0);
            lot.setBop(0.0); lot.setBopf(0.0); lot.setEb(0.0); lot.setFfsp(0.0);
            lot.setFfexs(0.0); lot.setDust(0.0); lot.setBm(0.0); lot.setBp(0.0);
            lot.setRefusedTea(0.0);

            for (ProductionBatch b : batches) {
                lot.setOp1(lot.getOp1() + (b.getOp1() != null ? b.getOp1() : 0));
                lot.setOpa(lot.getOpa() + (b.getOpa() != null ? b.getOpa() : 0));
                lot.setBop1(lot.getBop1() + (b.getBop1() != null ? b.getBop1() : 0));
                lot.setPekoe(lot.getPekoe() + (b.getPekoe() != null ? b.getPekoe() : 0));
                lot.setBop(lot.getBop() + (b.getBop() != null ? b.getBop() : 0));
                lot.setBopf(lot.getBopf() + (b.getBopf() != null ? b.getBopf() : 0));
                lot.setEb(lot.getEb() + (b.getEb() != null ? b.getEb() : 0));
                lot.setFfsp(lot.getFfsp() + (b.getFfsp() != null ? b.getFfsp() : 0));
                lot.setFfexs(lot.getFfexs() + (b.getFfexs() != null ? b.getFfexs() : 0));
                lot.setDust(lot.getDust() + (b.getDust() != null ? b.getDust() : 0));
                lot.setBm(lot.getBm() + (b.getBm() != null ? b.getBm() : 0));
                lot.setBp(lot.getBp() + (b.getBp() != null ? b.getBp() : 0));
                lot.setRefusedTea(lot.getRefusedTea() + (b.getRefusedTea() != null ? b.getRefusedTea() : 0));

                runningTotalActual += (b.getActualMadeTea() != null ? b.getActualMadeTea() : 0);

                b.setStatus("CONSOLIDATED");
            }

            lot.setTotal(roundToTwoDecimals(runningTotalActual));

            lot.setOp1(roundToTwoDecimals(lot.getOp1()));
            lot.setOpa(roundToTwoDecimals(lot.getOpa()));
            lot.setBop1(roundToTwoDecimals(lot.getBop1()));
            lot.setPekoe(roundToTwoDecimals(lot.getPekoe()));
            lot.setBop(roundToTwoDecimals(lot.getBop()));
            lot.setBopf(roundToTwoDecimals(lot.getBopf()));
            lot.setEb(roundToTwoDecimals(lot.getEb()));
            lot.setFfsp(roundToTwoDecimals(lot.getFfsp()));
            lot.setFfexs(roundToTwoDecimals(lot.getFfexs()));
            lot.setDust(roundToTwoDecimals(lot.getDust()));
            lot.setBm(roundToTwoDecimals(lot.getBm()));
            lot.setBp(roundToTwoDecimals(lot.getBp()));
            lot.setRefusedTea(roundToTwoDecimals(lot.getRefusedTea()));

            stockProductionRepo.save(lot);
            productionRepo.saveAll(batches);

            return ResponseEntity.ok("Success");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    private double calculateTotal(ProductionBatch b) {
        return (b.getOp1()!=null?b.getOp1():0) + (b.getOpa()!=null?b.getOpa():0) +
                (b.getBop1()!=null?b.getBop1():0) + (b.getPekoe()!=null?b.getPekoe():0) +
                (b.getBop()!=null?b.getBop():0) + (b.getBopf()!=null?b.getBopf():0) +
                (b.getEb()!=null?b.getEb():0) + (b.getFfsp()!=null?b.getFfsp():0) +
                (b.getFfexs()!=null?b.getFfexs():0) + (b.getDust()!=null?b.getDust():0) +
                (b.getBm()!=null?b.getBm():0) + (b.getBp()!=null?b.getBp():0) +
                (b.getRefusedTea()!=null?b.getRefusedTea():0);
    }

    private void updateGradesInternal(ProductionBatch target, ProductionBatch source) {
        if (source.getOp1() != null) target.setOp1(source.getOp1());
        if (source.getOpa() != null) target.setOpa(source.getOpa());
        if (source.getBop1() != null) target.setBop1(source.getBop1());
        if (source.getPekoe() != null) target.setPekoe(source.getPekoe());
        if (source.getBop() != null) target.setBop(source.getBop());
        if (source.getBopf() != null) target.setBopf(source.getBopf());
        if (source.getEb() != null) target.setEb(source.getEb());
        if (source.getFfsp() != null) target.setFfsp(source.getFfsp());
        if (source.getFfexs() != null) target.setFfexs(source.getFfexs());
        if (source.getDust() != null) target.setDust(source.getDust());
        if (source.getBm() != null) target.setBm(source.getBm());
        if (source.getBp() != null) target.setBp(source.getBp());
        if (source.getRefusedTea() != null) target.setRefusedTea(source.getRefusedTea());
    }

    @GetMapping("/production/delete/{id}")
    public String deleteBatch(@PathVariable Long id) {
        productionRepo.deleteById(id);
        return "redirect:/production?deleted";
    }

    @PostMapping("/production/reject")
    public String rejectBatch(@RequestParam Long id, @RequestParam String note) {
        ProductionBatch batch = productionRepo.findById(id).orElseThrow();
        batch.setStatus("REJECTED");
        batch.setRejectNote(note);
        productionRepo.save(batch);
        resyncStockProductionLot(batch.getLotNumber());   // ADD THIS LINE
        return "redirect:/production?rejected";
    }

    @GetMapping("/stock-production")
    public String showStockProductionPage(HttpSession session, Model model) {
        if (!"QA".equals(session.getAttribute("role"))) return "redirect:/login";

        List<StockProduction> allLots = stockProductionRepo.findAll();
        Collections.reverse(allLots);

        model.addAttribute("consolidatedLots", allLots);
        return "stock_production";
    }

    @GetMapping("/stock-production/report/view/{id}")
    @ResponseBody
    public ResponseEntity<byte[]> streamPdfReport(@PathVariable("id") Long id) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            StockProduction lot = stockProductionRepo.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid lot ID: " + id));

            Context context = new Context();
            context.setVariable("lot", lot);
            context.setVariable("title", "ORGANICS STOCK BALANCE REPORT");

            String htmlContent = templateEngine.process("pdf_stock_report", context);

            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(htmlContent, "/");
            builder.toStream(baos);
            builder.run();

            byte[] pdfBytes = baos.toByteArray();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.add("Content-Disposition", "inline; filename=Stock_Report_" + lot.getLotNumber() + ".pdf");

            return ResponseEntity.ok().headers(headers).body(pdfBytes);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }


    @GetMapping("/trace/invoice/{invoiceNumber}")
    public String traceMasterInvoice(@PathVariable String invoiceNumber, Model model) {
        List<Blending> blendItems = blendingRepo.findByInvoiceNumber(invoiceNumber);

        if (blendItems.isEmpty()) {
            return "redirect:/blending?error=Invoice Not Found";
        }

        String traceUrl = baseUrl + "/trace/invoice/" + invoiceNumber;
        String qrCode = QRGenerator.generateQRBase64(traceUrl, 400, 400);
        model.addAttribute("qrCode", qrCode);

        model.addAttribute("invoiceNumber", invoiceNumber);
        model.addAttribute("buyer", blendItems.get(0).getBuyerInfo());
        model.addAttribute("productName", blendItems.get(0).getProductName());

        List<Map<String, Object>> traceTree = new ArrayList<>();

        for (Blending item : blendItems) {
            Map<String, Object> node = new HashMap<>();
            node.put("grade", item.getGrade());
            node.put("quantity", item.getQuantity());
            node.put("batchNumber", item.getBatchNumber());
            node.put("blendingRef", item.getBlendingNumber());
            node.put("status", item.getStatus());
            node.put("stockLot", null);
            node.put("stockBatches", null);

            if (!"FROM-REMNANTS".equals(item.getBatchNumber())) {
                String batchId = item.getBatchNumber();

                // If this batchId refers to a StockProduction lot, expose the stock lot and its source batches
                Optional<StockProduction> stockOpt = stockProductionRepo.findByLotNumber(batchId);
                if (stockOpt.isPresent()) {
                    StockProduction stockLot = stockOpt.get();
                    node.put("stockLot", stockLot);

                    // Display lot normalized to a single code: the first sub-token (e.g. "112EST")
                    String displayLot = Arrays.stream(stockLot.getLotNumber().split("\\s*\\+\\s*"))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .findFirst()
                            .orElse(stockLot.getLotNumber());
                    node.put("displayLotNumber", displayLot);

                    // Build a lightweight list of constituent production batches with richer metadata
                    List<Map<String, Object>> stockBatchesInfo = new ArrayList<>();
                    for (String sourceLot : stockLot.getLotNumber().split("\\s*\\+\\s*")) {
                        List<ProductionBatch> subs = productionRepo.findAllByLotNumber(sourceLot.trim());
                        if (subs != null) {
                            for (ProductionBatch b : subs) {
                                if (b == null) continue;

                                // Keep both a normalized single-token label for display and the full lot string
                                String lotSingle = Arrays.stream(b.getLotNumber().split("\\s*\\+\\s*"))
                                        .map(String::trim)
                                        .filter(s -> !s.isEmpty())
                                        .findFirst()
                                        .orElse(b.getLotNumber());

                                Map<String, Object> sbInfo = new HashMap<>();
                                sbInfo.put("id", b.getId());                         // DB id (useful for fetching contributions)
                                sbInfo.put("lotNumber", lotSingle);                   // short display token (e.g. "112EST")
                                sbInfo.put("lotNumberFull", b.getLotNumber());       // full stored lot string (may contain "+")
                                if (b.getProductionId() != null && !b.getProductionId().isBlank()) {
                                    sbInfo.put("productionId", b.getProductionId()); // gate/weighing batch id if available
                                }
                                String s = b.getStatus();
                                // Do not expose the literal "CONSOLIDATED" to the UI; only show meaningful statuses
                                if (s != null && !"CONSOLIDATED".equalsIgnoreCase(s)) {
                                    sbInfo.put("status", s);
                                }
                                stockBatchesInfo.add(sbInfo);
                            }
                        }
                    }
                    node.put("stockBatches", stockBatchesInfo.isEmpty() ? null : stockBatchesInfo);

                    // Farmer contributions based on the resolved source batches (Actual Made Tea if present)
                    // We need the full ProductionBatch list for calculating contributions
                    List<ProductionBatch> resolvedSourceBatches = new ArrayList<>();
                    for (String sourceLot : stockLot.getLotNumber().split("\\s*\\+\\s*")) {
                        List<ProductionBatch> subs = productionRepo.findAllByLotNumber(sourceLot.trim());
                        if (subs != null && !subs.isEmpty()) resolvedSourceBatches.addAll(subs);
                    }
                    List<FarmerContribution> lotContributions = contributionService.getLotContributionsByActualMadeTea(resolvedSourceBatches);
                    double lineQty = item.getQuantity() != null ? item.getQuantity() : 0.0;
                    List<Map<String, Object>> scaledContributions = new ArrayList<>();
                    for (FarmerContribution fc : lotContributions) {
                        double allocatedKg = Math.round((fc.getPercent() / 100.0) * lineQty * 100.0) / 100.0;
                        scaledContributions.add(Map.of(
                                "supplierId", fc.getSupplierId(),
                                "supplierName", fc.getSupplierName(),
                                "percent", fc.getPercent(),
                                "allocatedKg", allocatedKg
                        ));
                    }
                    node.put("farmerContributions", scaledContributions);

                    // Aggregate rolling/withering/waiting across all resolved production batches
                    List<WitheringPoint> witheringRecords = new ArrayList<>();
                    List<WaitingPoint> waitingRecords = new ArrayList<>();

                    // Representative rolling (first source batch with a productionId)
                    ProductionBatch representative = resolvedSourceBatches.stream()
                            .filter(b -> b != null && b.getProductionId() != null && !b.getProductionId().isBlank())
                            .findFirst()
                            .orElse(null);

                    node.put("production", representative); // keep template behavior: representative if present

                    if (representative != null && representative.getProductionId() != null && !representative.getProductionId().isBlank()) {
                        List<String> repSubIds = BatchIdUtils.splitSubBatchIds(representative.getProductionId());
                        if (!repSubIds.isEmpty()) {
                            node.put("rolling", rollingPointRepo.findFirstByBatchId(repSubIds.get(0)).orElse(null));
                        } else {
                            node.put("rolling", null);
                        }
                    } else {
                        node.put("rolling", null);
                    }

                    for (ProductionBatch pb : resolvedSourceBatches) {
                        if (pb == null) continue;
                        String gateId = pb.getProductionId();
                        if (gateId == null || gateId.isBlank()) continue;
                        for (String subId : BatchIdUtils.splitSubBatchIds(gateId)) {
                            witheringPointRepo.findFirstByBatchId(subId).ifPresent(witheringRecords::add);
                            waitingPointRepo.findFirstByBatchId(subId).ifPresent(waitingRecords::add);
                        }
                    }

                    node.put("withering", witheringRecords.isEmpty() ? null : witheringRecords);
                    node.put("waiting", waitingRecords.isEmpty() ? null : waitingRecords);

                } else {
                    // Normal ProductionBatch lot handling (existing behavior), but add displayLotNumber too
                    List<ProductionBatch> productionList = productionRepo.findAllByLotNumber(batchId);

                    ProductionBatch representative = productionList.stream()
                            .filter(Objects::nonNull)
                            .max(Comparator.comparing(p -> p.getId() != null ? p.getId() : 0L))
                            .orElse(null);

                    node.put("production", representative);

                    // Provide a normalized display lot number (first token) for UI
                    String displayLot = representative != null && representative.getLotNumber() != null
                            ? Arrays.stream(representative.getLotNumber().split("\\s*\\+\\s*"))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .findFirst()
                            .orElse(representative != null ? representative.getLotNumber() : batchId)
                            : batchId;
                    node.put("displayLotNumber", displayLot);

                    // Farmer contributions based on all resolved batches (Actual Made Tea if present)
                    List<FarmerContribution> lotContributions = contributionService.getLotContributionsByActualMadeTea(productionList);
                    double lineQty = item.getQuantity() != null ? item.getQuantity() : 0.0;
                    List<Map<String, Object>> scaledContributions = new ArrayList<>();
                    for (FarmerContribution fc : lotContributions) {
                        double allocatedKg = Math.round((fc.getPercent() / 100.0) * lineQty * 100.0) / 100.0;
                        scaledContributions.add(Map.of(
                                "supplierId", fc.getSupplierId(),
                                "supplierName", fc.getSupplierName(),
                                "percent", fc.getPercent(),
                                "allocatedKg", allocatedKg
                        ));
                    }
                    node.put("farmerContributions", scaledContributions);

                    // Aggregate rolling/withering/waiting across all resolved production batches
                    List<WitheringPoint> witheringRecords = new ArrayList<>();
                    List<WaitingPoint> waitingRecords = new ArrayList<>();

                    if (representative != null && representative.getProductionId() != null && !representative.getProductionId().isBlank()) {
                        List<String> subBatchIds = BatchIdUtils.splitSubBatchIds(representative.getProductionId());
                        if (!subBatchIds.isEmpty()) {
                            node.put("rolling", rollingPointRepo.findFirstByBatchId(subBatchIds.get(0)).orElse(null));
                        } else {
                            node.put("rolling", null);
                        }
                    } else {
                        node.put("rolling", null);
                    }

                    for (ProductionBatch pb : productionList) {
                        if (pb == null) continue;
                        String gateId = pb.getProductionId();
                        if (gateId == null || gateId.isBlank()) continue;
                        for (String subId : BatchIdUtils.splitSubBatchIds(gateId)) {
                            witheringPointRepo.findFirstByBatchId(subId).ifPresent(witheringRecords::add);
                            waitingPointRepo.findFirstByBatchId(subId).ifPresent(waitingRecords::add);
                        }
                    }

                    node.put("withering", witheringRecords.isEmpty() ? null : witheringRecords);
                    node.put("waiting", waitingRecords.isEmpty() ? null : waitingRecords);
                }
            } else {
                // FROM-REMNANTS case: keep the same shapes the template expects
                node.put("production", null);
                node.put("withering", null);
                node.put("waiting", null);
                node.put("farmerContributions", List.of());
                node.put("displayLotNumber", item.getBatchNumber());
            }

            traceTree.add(node);
        }

        model.addAttribute("traceTree", traceTree);
        return "master_trace_page";
    }

    @GetMapping("/api/stock/{id}/contributions")
    @ResponseBody
    public ResponseEntity<?> getStockLotFarmerContributions(@PathVariable Long id) {
        StockProduction lot = stockProductionRepo.findById(id).orElse(null);
        if (lot == null) {
            return ResponseEntity.notFound().build();
        }
        List<ProductionBatch> sourceBatches = new ArrayList<>();
        for (String sourceLot : lot.getLotNumber().split("\\s*\\+\\s*")) {
            List<ProductionBatch> list = productionRepo.findAllByLotNumber(sourceLot.trim());
            if (list != null && !list.isEmpty()) sourceBatches.addAll(list);
        }
        return ResponseEntity.ok(contributionService.getLotContributionsByActualMadeTea(sourceBatches));
    }

    @GetMapping("/api/production/{id}/contributions")
    @ResponseBody
    public ResponseEntity<?> getProductionBatchContributions(@PathVariable Long id) {
        // Try to find the production batch by id
        Optional<ProductionBatch> pbOpt = productionRepo.findById(id);
        if (pbOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ProductionBatch pb = pbOpt.get();

        // Use the single production batch only so contributions are scaled to this batch's Actual Made Tea
        List<ProductionBatch> batches = List.of(pb);

        return ResponseEntity.ok(contributionService.getLotContributionsByActualMadeTea(batches));
    }
    @GetMapping("/mobile/drying_dashboard")
    public String showMobileDryingForm(HttpSession session, Model model) {
        if (session.getAttribute("username") == null) return "redirect:/login";

        List<ProductionBatch> activeDryingRecords = productionRepo.findAll();
        model.addAttribute("dryingRecords", activeDryingRecords);

        List<WitheringPoint> completedWithering = witheringPointRepo.findAll();
        model.addAttribute("witheredBatches", completedWithering);

        model.addAttribute("officers", Arrays.asList("Vipula"));
        return "Drying";
    }
    @PostMapping("/api/stock/update-status")
    @ResponseBody
    public ResponseEntity<?> updateStockStatus(@RequestParam Long id,
                                               @RequestParam String status,
                                               @RequestParam(required = false) String remark) {
        try {
            StockProduction lot = stockProductionRepo.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid lot ID: " + id));

            // ✅ FIX #4: Validate that sum of grades does NOT exceed total
            double gradeSum = (lot.getOp1() != null ? lot.getOp1() : 0) +
                    (lot.getOpa() != null ? lot.getOpa() : 0) +
                    (lot.getBop1() != null ? lot.getBop1() : 0) +
                    (lot.getPekoe() != null ? lot.getPekoe() : 0) +
                    (lot.getBop() != null ? lot.getBop() : 0) +
                    (lot.getBopf() != null ? lot.getBopf() : 0) +
                    (lot.getEb() != null ? lot.getEb() : 0) +
                    (lot.getFfsp() != null ? lot.getFfsp() : 0) +
                    (lot.getFfexs() != null ? lot.getFfexs() : 0) +
                    (lot.getDust() != null ? lot.getDust() : 0) +
                    (lot.getBm() != null ? lot.getBm() : 0) +
                    (lot.getBp() != null ? lot.getBp() : 0) +
                    (lot.getRefusedTea() != null ? lot.getRefusedTea() : 0);

            double total = lot.getTotal() != null ? lot.getTotal() : 0;

            if (gradeSum > total) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Validation Error: Sum of tea grades (" + roundToTwoDecimals(gradeSum) + " kg) cannot exceed total (" + roundToTwoDecimals(total) + " kg)"
                ));
            }

            // If status is APPROVED, validate that pdfReportPath is not null
            if ("APPROVED".equals(status) && (lot.getPdfReportPath() == null || lot.getPdfReportPath().isEmpty())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Lab Report PDF must be uploaded before approval"
                ));
            }

            lot.setStatus(status);
            if ("REJECTED".equals(status) && remark != null) {
                lot.setRejectNote(remark);
            }
            stockProductionRepo.save(lot);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Stock lot status updated to " + status,
                    "lotId", lot.getId(),
                    "status", lot.getStatus()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Error: " + e.getMessage()
            ));
        }
    }
    @PostMapping("/stock-production/edit")
    public String editStockProduction(@ModelAttribute StockProduction editedStock, RedirectAttributes ra) {
        StockProduction existing = stockProductionRepo.findById(editedStock.getId())
                .orElseThrow(() -> new IllegalArgumentException("Invalid stock ID: " + editedStock.getId()));

        // FIX (Issue 3 follow-up): existing.getTotal() is the lot's fixed ceiling —
        // set once at consolidation from the source batch(es)' Actual Made Tea, and
        // never recalculated here. Grade edits are additive (see below), but the
        // cumulative grade sum can never exceed this ceiling, otherwise Edit
        // Production could be submitted unlimited times to inflate stock beyond what
        // was actually produced (matches the same ceiling rule already enforced on
        // the Production tab's inline grade edits via Actual Made Tea).
        double ceiling = nz(existing.getTotal());

        double newOp1 = roundToTwoDecimals(nz(existing.getOp1()) + nz(editedStock.getOp1()));
        double newOpa = roundToTwoDecimals(nz(existing.getOpa()) + nz(editedStock.getOpa()));
        double newBop1 = roundToTwoDecimals(nz(existing.getBop1()) + nz(editedStock.getBop1()));
        double newPekoe = roundToTwoDecimals(nz(existing.getPekoe()) + nz(editedStock.getPekoe()));
        double newBop = roundToTwoDecimals(nz(existing.getBop()) + nz(editedStock.getBop()));
        double newBopf = roundToTwoDecimals(nz(existing.getBopf()) + nz(editedStock.getBopf()));
        double newEb = roundToTwoDecimals(nz(existing.getEb()) + nz(editedStock.getEb()));
        double newFfsp = roundToTwoDecimals(nz(existing.getFfsp()) + nz(editedStock.getFfsp()));
        double newFfexs = roundToTwoDecimals(nz(existing.getFfexs()) + nz(editedStock.getFfexs()));
        double newDust = roundToTwoDecimals(nz(existing.getDust()) + nz(editedStock.getDust()));
        double newBm = roundToTwoDecimals(nz(existing.getBm()) + nz(editedStock.getBm()));
        double newBp = roundToTwoDecimals(nz(existing.getBp()) + nz(editedStock.getBp()));
        double newRefused = roundToTwoDecimals(nz(existing.getRefusedTea()) + nz(editedStock.getRefusedTea()));

        double newGradeSum = roundToTwoDecimals(newOp1 + newOpa + newBop1 + newPekoe + newBop + newBopf +
                newEb + newFfsp + newFfexs + newDust + newBm + newBp + newRefused);

        if (ceiling > 0 && newGradeSum > ceiling) {
            ra.addFlashAttribute("error", "Sum of tea grades (" + newGradeSum + " kg) cannot exceed this lot's total capacity (" + ceiling + " kg).");
            return "redirect:/stock-production";
        }

        existing.setOp1(newOp1);
        existing.setOpa(newOpa);
        existing.setBop1(newBop1);
        existing.setPekoe(newPekoe);
        existing.setBop(newBop);
        existing.setBopf(newBopf);
        existing.setEb(newEb);
        existing.setFfsp(newFfsp);
        existing.setFfexs(newFfexs);
        existing.setDust(newDust);
        existing.setBm(newBm);
        existing.setBp(newBp);
        existing.setRefusedTea(newRefused);

        // Not Tested / Tested quantities stay as direct overwrites (unchanged) — these
        // reflect current lab-testing state, not cumulative grade stock.
        existing.setNotTestedQty(editedStock.getNotTestedQty());
        existing.setTestedQty(editedStock.getTestedQty());

        // total is NOT recalculated here — it's the fixed ceiling set at consolidation
        // (or by resyncStockProductionLot() if the batch is later rejected). Grade
        // breakdown edits only redistribute within that ceiling, never change it.
        stockProductionRepo.save(existing);

        return "redirect:/stock-production?success=edited";
    }
    @PostMapping("/mobile/drying/save")
    public String saveMobileDryingRecord(@RequestParam Map<String, String> allParams, HttpSession session) {
        if (session.getAttribute("username") == null) return "redirect:/login";

        String batchId = allParams.get("batchId");
        ProductionBatch batchToSave = productionRepo.findByLotNumber(batchId).orElse(new ProductionBatch());

        batchToSave.setLotNumber(batchId);

        // ✅ FIX #3: ONLY set production date on first save
        // Never overwrite existing production date
        if (batchToSave.getProductionDate() == null) {
            // FIX (Issue 5): batchId here may be a "+"-joined combined ID from a
            // multi-batch Withering submission. WaitingPoint rows are always stored
            // individually, so an exact-match lookup on the joined string returns
            // nothing. Split on "+" and query each sub-batch, same pattern used in
            // showProductionPage().
            List<WaitingPoint> gateRecords = new ArrayList<>();
            if (batchId != null && !batchId.isBlank()) {
                for (String trimmedSubId : BatchIdUtils.splitSubBatchIds(batchId)) {
                    List<WaitingPoint> subRecords = waitingPointRepo.findByBatchId(trimmedSubId);
                    if (subRecords != null) {
                        gateRecords.addAll(subRecords);
                    }
                }
            }

            Optional<LocalDate> earliestWeighingDate = gateRecords.stream()
                    .map(WaitingPoint::getDate)
                    .filter(Objects::nonNull)
                    .min(LocalDate::compareTo);

            batchToSave.setProductionDate(earliestWeighingDate.orElse(LocalDate.now()));
        }

        String finalOfficer = allParams.get("officerName");
        if ("Other".equals(finalOfficer)) {
            finalOfficer = allParams.get("customOfficerName");
        }
        batchToSave.setDryingOfficer(finalOfficer != null ? finalOfficer.trim() : "Unknown");

        if (allParams.containsKey("temperature") && !allParams.get("temperature").isEmpty()) {
            batchToSave.setTemperature(Double.parseDouble(allParams.get("temperature")));
        }
        if (allParams.containsKey("moistureContent") && !allParams.get("moistureContent").isEmpty()) {
            batchToSave.setMoistureContent(Double.parseDouble(allParams.get("moistureContent")));
        }

        batchToSave.setStatus("DRYING");
        // ✅ NEW: Set drying date to today when drying record is created/updated
        if (batchToSave.getDryingDate() == null) {
            batchToSave.setDryingDate(LocalDate.now());
        }

        productionRepo.save(batchToSave);
        return "redirect:/mobile/drying_dashboard?success";
    }
}