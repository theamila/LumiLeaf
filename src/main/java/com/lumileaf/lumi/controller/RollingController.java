package com.lumileaf.lumi.controller;

import com.lumileaf.lumi.model.ProductionBatch;
import com.lumileaf.lumi.model.RollingPoint;
import com.lumileaf.lumi.model.WitheringPoint;
import com.lumileaf.lumi.repository.ProductionBatchRepository;
import com.lumileaf.lumi.repository.RollingPointRepository;
import com.lumileaf.lumi.repository.WitheringPointRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import com.lumileaf.lumi.model.NotificationEvent;
import com.lumileaf.lumi.repository.NotificationEventRepository;
import com.lumileaf.lumi.util.BatchIdUtils;

@Controller
public class RollingController {

    @Autowired private RollingPointRepository rollingRepo;
    @Autowired private WitheringPointRepository witheringRepo;
    @Autowired private ProductionBatchRepository productionRepo;
    @Autowired
    private NotificationEventRepository notificationRepo;

    @GetMapping(value = "/mobile/rolling_dashboard")
    public String showRollingDashboard(@RequestParam(value = "date", required = false) String dateStr,
                                       HttpSession session, Model model) {
        String username = (String) session.getAttribute("username");
        if (username == null) return "redirect:/login";

        LocalDate selectedDate = (dateStr != null && !dateStr.isBlank()) ? LocalDate.parse(dateStr) : LocalDate.now();
        model.addAttribute("selectedDate", selectedDate.toString());

        List<String> officers = Arrays.asList("Vipula");
        model.addAttribute("officersList", officers);

        // COMBINED FIX: Break down any combined batchId strings to ensure sub-batches are marked processed
        Set<String> processedBatches = rollingRepo.findAll().stream()
                .map(RollingPoint::getBatchId)
                .filter(Objects::nonNull)
                .flatMap(id -> BatchIdUtils.splitSubBatchIds(id).stream())
                .collect(Collectors.toSet());

        // Gather all eligible unrolled records
        List<WitheringPoint> unrolledRecords = witheringRepo.findAll().stream()
                .filter(w -> w.getBatchId() != null && !w.getBatchId().trim().isEmpty())
                .filter(w -> !processedBatches.contains(w.getBatchId().trim()))
                .collect(Collectors.toList());

        // Group records by shared Production trackers
        Map<String, List<WitheringPoint>> groupedBatches = new LinkedHashMap<>();
        for (WitheringPoint w : unrolledRecords) {
            String pBatch = w.getProductionBatchNo() != null ? w.getProductionBatchNo().trim() : "";
            String pLot = w.getProductionLotNo() != null ? w.getProductionLotNo().trim() : "";

            // Group together if both tracking fields exist; otherwise, isolate as a single batch
            String groupKey = (!pBatch.isEmpty() && !pLot.isEmpty())
                    ? pBatch + "||" + pLot
                    : "SINGLE_" + w.getBatchId().trim();

            groupedBatches.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(w);
        }

        // Map grouped results directly into the expected frontend dictionary structure
        List<Map<String, Object>> availableBatches = new ArrayList<>();
        for (List<WitheringPoint> batchGroup : groupedBatches.values()) {
            Map<String, Object> map = new HashMap<>();

            // Join identifiers together (e.g., "LOTFA2606-2 + AMG2606B-2")
            String combinedBatchId = batchGroup.stream()
                    .map(WitheringPoint::getBatchId)
                    .map(String::trim)
                    .collect(Collectors.joining(" + "));

            // Aggregate the physical weights
            double totalWitheredWeight = batchGroup.stream()
                    .mapToDouble(w -> w.getWitheredWeight() != null ? w.getWitheredWeight() : 0.0)
                    .sum();

            WitheringPoint reference = batchGroup.get(0);

            map.put("batchId", combinedBatchId);
            map.put("witheredWeight", totalWitheredWeight);
            map.put("officerName", reference.getOfficerName() != null ? reference.getOfficerName() : "Unknown");
            map.put("productionBatchNo", reference.getProductionBatchNo() != null ? reference.getProductionBatchNo() : "");
            map.put("productionLotNo", reference.getProductionLotNo() != null ? reference.getProductionLotNo() : "");

            availableBatches.add(map);
        }

        model.addAttribute("availableBatches", availableBatches);

        List<RollingPoint> dryingRecords = rollingRepo.findByRollingDate(selectedDate).stream()
                .filter(r -> {
                    String lotNumber = r.getLotNumber() != null ? r.getLotNumber().trim() : null;
                    ProductionBatch pb = null;
                    if (lotNumber != null && !lotNumber.isEmpty()) {
                        pb = productionRepo.findTopByLotNumberAndRollingDateOrderByIdDesc(lotNumber, selectedDate).orElse(null);
                    }
                    if (pb == null) return true;
                    return !"APPROVED".equals(pb.getStatus()) && !"CONSOLIDATED".equals(pb.getStatus());
                })
                .collect(Collectors.toList());
        model.addAttribute("dryingRecords", dryingRecords);

        return "RollingMobile";
    }

    @PostMapping("/rolling/save")
    public String saveRollingRecord(@RequestParam Map<String, String> allParams,
                                    HttpSession session,
                                    RedirectAttributes ra) {

        String username = (String) session.getAttribute("username");
        if (username == null) return "redirect:/login";

        String batchId = allParams.get("batchId");
        String dateStr = allParams.get("selectedDate");
        LocalDate selectedDate = (dateStr != null && !dateStr.isBlank()) ? LocalDate.parse(dateStr) : LocalDate.now();

        RollingPoint record = rollingRepo.findByBatchId(batchId).orElse(new RollingPoint());

        record.setEntryDate(LocalDate.now());
        record.setRollingDate(selectedDate);
        record.setBatchId(batchId);
        record.setRollingOfficer(username);

        String finalOfficer = allParams.get("rollingOfficer");
        if ("Other".equals(finalOfficer)) {
            finalOfficer = allParams.get("customOfficerName");
        }
        record.setOfficerName(finalOfficer != null ? finalOfficer.trim() : "Unknown");

        double weightIn = parseOrZero(allParams.get("beforeWeight"));
        double d1 = parseOrZero(allParams.get("dhool1"));
        double d2 = parseOrZero(allParams.get("dhool2"));
        double d3 = parseOrZero(allParams.get("dhool3"));
        double bb = parseOrZero(allParams.get("bigBulk"));
        // ADD THESE 2 LINES after line 132 (after setting processLoss):
        record.setRainfall(parseOrZero(allParams.get("rainfall")));
        record.setWeatherCondition(allParams.get("weatherCondition") != null ? allParams.get("weatherCondition").trim() : "N/A");

        record.setWeightIn(round(weightIn));
        record.setDhool1(round(d1));
        record.setDhool2(round(d2));
        record.setDhool3(round(d3));
        record.setBigBulk(round(bb));

        double totalOut = d1 + d2 + d3 + bb;
        record.setWeightOut(round(totalOut));
        record.setProcessLoss(round(weightIn - totalOut));

        if (batchId != null && !batchId.isBlank()) {
            // COMBINED FIX: Use the first sub-batch token to pull down the correct metadata
            String firstSubBatchId = batchId.split("\\+")[0].trim();
            witheringRepo.findAll().stream()
                    .filter(w -> w.getBatchId() != null && firstSubBatchId.equalsIgnoreCase(w.getBatchId().trim()))
                    .findFirst()
                    .ifPresent(w -> {
                        record.setProductionBatchNo(w.getProductionBatchNo());
                        record.setProductionLotNo(w.getProductionLotNo());
                        record.setLotNumber(w.getProductionLotNo() != null ? w.getProductionLotNo() : batchId);
                    });
        }

        if (record.getLotNumber() == null) {
            record.setLotNumber(batchId);
        }

        rollingRepo.save(record);

        if (record.getLotNumber() != null) {
            // Prefer lookup by the raw weighing/gate batch id (productionId) which preserves
            // the unique day/section suffix (e.g., "112/3/EST" vs "112/4/EST").
            // Fall back to any existing productionRepo.findByProductionId(...) before creating new.
            String trimmedBatchId = batchId != null ? batchId.trim() : null;
            ProductionBatch pb = null;

            if (trimmedBatchId != null && !trimmedBatchId.isEmpty()) {
                pb = productionRepo.findByProductionIdAndRollingDate(trimmedBatchId, selectedDate)
                        .orElseGet(() -> productionRepo.findByProductionId(trimmedBatchId).orElse(null));
            }

            if (pb == null) {
                // No existing ProductionBatch found by productionId -> create new
                pb = new ProductionBatch();
            }

            // Wire up productionId (raw gate ID) and the production lot number separately
            pb.setProductionId(trimmedBatchId);
            pb.setLotNumber(record.getLotNumber());
            pb.setProductionDate(LocalDate.now());
            pb.setRollingDate(selectedDate);
            pb.setDryingOfficer(record.getOfficerName());
            pb.setDhool1(record.getDhool1());
            pb.setDhool2(record.getDhool2());
            pb.setDhool3(record.getDhool3());
            pb.setBigBulk(record.getBigBulk());
            pb.setStatus("PENDING");
            productionRepo.save(pb);
        }

        NotificationEvent rollEvent = new NotificationEvent();
        rollEvent.setEventType("ROLLING");
        rollEvent.setMessage(record.getOfficerName() + " completed rolling for batch " + batchId);
        notificationRepo.save(rollEvent);

        return "redirect:/mobile/rolling_dashboard?date=" + selectedDate + "&success";
    }

    @PostMapping("/mobile/update-drying")
    public String updateDryingRecord(@RequestParam Map<String, String> allParams,
                                     HttpSession session,
                                     RedirectAttributes ra) {
        String username = (String) session.getAttribute("username");
        if (username == null) return "redirect:/login";

        String idStr = allParams.get("id");
        String redirectDate = allParams.get("redirectDate");

        if (idStr != null) {
            Long id = Long.parseLong(idStr);
            Optional<RollingPoint> opt = rollingRepo.findById(id);
            if (opt.isPresent()) {
                RollingPoint record = opt.get();
                record.setTemperature(parseOrZero(allParams.get("temp")));

                double m1 = parseOrZero(allParams.get("m1"));
                double m2 = parseOrZero(allParams.get("m2"));
                double m3 = parseOrZero(allParams.get("m3"));
                double mbb = parseOrZero(allParams.get("mbb"));

                record.setMoistureD1(round(m1));
                record.setMoistureD2(round(m2));
                record.setMoistureD3(round(m3));
                record.setMoistureBB(round(mbb));
                record.setMoistureContent(round((m1 + m2 + m3 + mbb) / 4.0));

                double d1 = parseOrZero(allParams.get("d1"));
                double d2 = parseOrZero(allParams.get("d2"));
                double d3 = parseOrZero(allParams.get("d3"));
                double bb = parseOrZero(allParams.get("bb"));

                record.setDryD1(round(d1));
                record.setDryD2(round(d2));
                record.setDryD3(round(d3));
                record.setDryBigBulk(round(bb));

                double rollingTotal = (record.getDhool1() != null ? record.getDhool1() : 0) +
                        (record.getDhool2() != null ? record.getDhool2() : 0) +
                        (record.getDhool3() != null ? record.getDhool3() : 0) +
                        (record.getBigBulk() != null ? record.getBigBulk() : 0);

                record.setDryLoss(round(rollingTotal - (d1 + d2 + d3 + bb)));
                record.setDryingCompleted(true);

                rollingRepo.save(record);

                NotificationEvent dryEvent = new NotificationEvent();
                dryEvent.setEventType("DRYING");
                dryEvent.setMessage("Drying completed for batch " + record.getBatchId() +
                        (record.getLotNumber() != null ? " (Lot " + record.getLotNumber() + ")" : ""));
                notificationRepo.save(dryEvent);

                // --- Replacement block (Option B) ---
                // Prefer productionId (batch id) when available, otherwise fall back to latest lotNumber+rollingDate
                String lotNumber = record.getLotNumber() != null ? record.getLotNumber().trim() : null;
                String prodId = record.getBatchId() != null ? record.getBatchId().trim() : null;
                LocalDate rollingDate = record.getRollingDate();

                ProductionBatch pb = null;

                // 1) Prefer exact productionId + rollingDate
                if (prodId != null && !prodId.isEmpty()) {
                    pb = productionRepo.findByProductionIdAndRollingDate(prodId, rollingDate).orElse(null);
                }

                // 2) Fall back to the most recent record for lotNumber + rollingDate
                if (pb == null && lotNumber != null && !lotNumber.isEmpty()) {
                    pb = productionRepo.findTopByLotNumberAndRollingDateOrderByIdDesc(lotNumber, rollingDate).orElse(null);
                }

                // 3) If still not found, create a new ProductionBatch
                if (pb == null) {
                    pb = new ProductionBatch();
                    pb.setProductionId(prodId);
                    pb.setLotNumber(lotNumber);
                    pb.setProductionDate(LocalDate.now());
                    pb.setRollingDate(rollingDate);
                }

                // Update fields and save
                pb.setTemperature(record.getTemperature());
                pb.setMoistureContent(record.getMoistureContent());
                pb.setMoistureD1(record.getMoistureD1());
                pb.setMoistureD2(record.getMoistureD2());
                pb.setMoistureD3(record.getMoistureD3());
                pb.setMoistureBB(record.getMoistureBB());
                pb.setDryDhool1(record.getDryD1());
                pb.setDryDhool2(record.getDryD2());
                pb.setDryDhool3(record.getDryD3());
                pb.setDryBigBulk(record.getDryBigBulk());
                pb.setDryingLoss(record.getDryLoss());
                pb.setStatus("DRYING");

                productionRepo.save(pb);
                // --- end replacement block ---
            }
        }

        return "redirect:/mobile/rolling_dashboard?date=" + redirectDate + "&success_drying";
    }
    private double parseOrZero(String s) {
        try {
            return (s == null || s.isBlank()) ? 0.0 : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}