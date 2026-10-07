package com.lumileaf.lumi.controller;

import com.lumileaf.lumi.model.*;
import com.lumileaf.lumi.repository.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Arrays;
import com.lumileaf.lumi.service.PermissionService;

@Controller
public class RecordController {

    @Autowired private RollingPointRepository rollingRepo;
    @Autowired private WitheringPointRepository witheringRepo;
    @Autowired private AdminRepository adminRepo;
    @Autowired private ProductionBatchRepository productionRepo;
    @Autowired private PermissionService permissionService;
    @Autowired private WaitingPointRepository waitingPointRepo;

    // --- MATHEMATICAL ROUNDING HELPER ---
    private double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // Fixed path conflict by adding 'record_' prefix
    @GetMapping("/mobile/record_rolling_dashboard")
    public String showRollingDashboard(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpSession session, Model model) {

        String username = (String) session.getAttribute("username");
        if (username == null) return "redirect:/login";

        LocalDate selectedDate = (date != null) ? date : LocalDate.now();

        List<Object[]> witheringResults = witheringRepo.findSummedWitheringByDate(selectedDate);
        List<Map<String, Object>> readyToRoll = new ArrayList<>();

        List<String> finishedRollingIds = rollingRepo.findByEntryDate(selectedDate)
                .stream().map(RollingPoint::getBatchId).collect(Collectors.toList());

        for (Object[] row : witheringResults) {
            String bid = row[0].toString();
            if (!finishedRollingIds.contains(bid)) {
                Map<String, Object> batch = new HashMap<>();
                batch.put("batchId", bid);
                double rawWeight = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
                batch.put("witheredWeight", roundToTwoDecimals(rawWeight));
                readyToRoll.add(batch);
            }
        }
        model.addAttribute("availableBatches", readyToRoll);
        model.addAttribute("selectedDate", selectedDate);

        // ADD THIS LINE - Set officers list for the template
        model.addAttribute("officersList", Arrays.asList("Jagath", "Vipula", "Kamal Perera"));

        // FIX: Filter drying records by rollingDate (persisted), not productionDate (today only)
        List<ProductionBatch> activeDrying = productionRepo.findAll().stream()
                .filter(pb -> pb.getRollingDate() != null && pb.getRollingDate().equals(selectedDate))
                .filter(pb -> pb.getTemperature() == null || pb.getTemperature() == 0.0)
                .collect(Collectors.toList());
        model.addAttribute("dryingRecords", activeDrying);

        model.addAttribute("officers", adminRepo.findAll());
        model.addAttribute("records", rollingRepo.findByRollingOfficer(username));
        model.addAttribute("rollingPoint", new RollingPoint());

        return "RollingMobile";
    }

    // Fixed path conflict by adding 'record_' prefix
    @PostMapping("/record_rolling/save")
    public String saveRolling(@ModelAttribute("rollingPoint") RollingPoint rolling,
                              @RequestParam("rollingOfficer") String selectedOfficer,
                              @RequestParam(value = "customOfficerName", required = false) String customOfficerName, // NEW PARAMETER
                              @RequestParam("batchId") String masterBatchId,
                              @RequestParam("beforeWeight") Double weightIn,
                              @RequestParam("dhool1") Double h1,
                              @RequestParam("dhool2") Double h2,
                              @RequestParam("dhool3") Double h3,
                              @RequestParam("bigBulk") Double bBulk,
                              @RequestParam(value = "selectedDate", required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate selectedDate,
                              HttpSession session) {

        String username = (String) session.getAttribute("username");
        if (username == null) return "redirect:/login";

        Admin admin = adminRepo.findByUsername(username);
        if (admin == null || !permissionService.hasPermission(admin, "ROLLING_SAVE")) {
            return "redirect:/mobile/record_rolling_dashboard?error=no_permission";
        }

        // Use selectedDate from form (the date picker value), fallback to today
        LocalDate activeRollingDate = (selectedDate != null) ? selectedDate : LocalDate.now();

        double cleanWeightIn = roundToTwoDecimals(weightIn != null ? weightIn : 0.0);

        // Server-side output weight guard — redirect back to the correct date on failure
        double submittedTotal = roundToTwoDecimals(
                (h1 != null ? h1 : 0.0) +
                        (h2 != null ? h2 : 0.0) +
                        (h3 != null ? h3 : 0.0) +
                        (bBulk != null ? bBulk : 0.0)
        );
        if (submittedTotal > cleanWeightIn) {
            return "redirect:/mobile/record_rolling_dashboard?date=" + activeRollingDate + "&error=output_exceeds_input";
        }

        // ── DYNAMIC OFFICER LOGIC FIX ──
        String finalOfficer = selectedOfficer;
        if ("Other".equals(finalOfficer) && customOfficerName != null && !customOfficerName.isBlank()) {
            finalOfficer = customOfficerName.trim();
        }

        rolling.setEntryDate(LocalDate.now());
        rolling.setRollingDate(activeRollingDate);
        rolling.setRollingOfficer(finalOfficer);
        rolling.setOfficerName(finalOfficer);
        rolling.setBatchId(masterBatchId);
        rolling.setWeightIn(cleanWeightIn);

        double cleanWeightOut = roundToTwoDecimals(
                (h1 != null ? h1 : 0) + (h2 != null ? h2 : 0) +
                        (h3 != null ? h3 : 0) + (bBulk != null ? bBulk : 0));

        rolling.setWeightOut(cleanWeightOut);
        rolling.setProcessLoss(roundToTwoDecimals(cleanWeightIn - cleanWeightOut));
        rollingRepo.save(rolling);

        // Create the ProductionBatch for Drying
        ProductionBatch pb = new ProductionBatch();
        pb.setLotNumber(masterBatchId);
        // ✅ FIX #3: Use CSV import date (Green Leaf Arrived Date) from WaitingPoint, not today
        // ✅ FIX #14: Normalize batch ID for lookup
        String normalizedBatchId = masterBatchId;
        if (normalizedBatchId != null) {
            normalizedBatchId = normalizedBatchId.replaceAll("\\s*\\(\\s*(Estate|Amigos|LOTFA)\\s*\\).*", "").trim();
        }

        List<WaitingPoint> gateRecords = waitingPointRepo.findByBatchId(normalizedBatchId);
        Optional<LocalDate> csvImportDate = (gateRecords == null || gateRecords.isEmpty())
                ? Optional.empty()
                : gateRecords.stream()
                .map(WaitingPoint::getDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo);

        pb.setProductionDate(csvImportDate.orElse(LocalDate.now()));
        pb.setRollingDate(activeRollingDate);
        pb.setDryingOfficer(finalOfficer); // Persists to live QA pipeline cleanly
        pb.setDhool1(roundToTwoDecimals(h1 != null ? h1 : 0.0));
        pb.setDhool2(roundToTwoDecimals(h2 != null ? h2 : 0.0));
        pb.setDhool3(roundToTwoDecimals(h3 != null ? h3 : 0.0));
        pb.setBigBulk(roundToTwoDecimals(bBulk != null ? bBulk : 0.0));
        pb.setStatus("PENDING");
        productionRepo.save(pb);

        return "redirect:/mobile/record_rolling_dashboard?date=" + activeRollingDate + "&success";
    }

    // Fixed path conflict by adding 'record_' prefix
    @PostMapping("/mobile/record_update-drying")
    public String updateDryingMobile(@RequestParam("id") Long id,
                                     @RequestParam("temp") Double temp,
                                     @RequestParam("moisture") Double moisture,
                                     @RequestParam(value = "d1", required = false) Double d1,
                                     @RequestParam(value = "d2", required = false) Double d2,
                                     @RequestParam(value = "d3", required = false) Double d3,
                                     @RequestParam(value = "bb", required = false) Double bb,
                                     @RequestParam(value = "redirectDate", required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate redirectDate,
                                     HttpSession session) {

        if (session.getAttribute("username") == null) return "redirect:/login";

        ProductionBatch batch = productionRepo.findById(id).orElseThrow();
        batch.setTemperature(temp);
        batch.setMoistureContent(moisture);

        batch.setDryDhool1(roundToTwoDecimals(d1 != null ? d1 : 0.0));
        batch.setDryDhool2(roundToTwoDecimals(d2 != null ? d2 : 0.0));
        batch.setDryDhool3(roundToTwoDecimals(d3 != null ? d3 : 0.0));
        batch.setDryBigBulk(roundToTwoDecimals(bb != null ? bb : 0.0));

        double beforeDrying = roundToTwoDecimals(
                (batch.getDhool1() != null ? batch.getDhool1() : 0.0) +
                        (batch.getDhool2() != null ? batch.getDhool2() : 0.0) +
                        (batch.getDhool3() != null ? batch.getDhool3() : 0.0) +
                        (batch.getBigBulk() != null ? batch.getBigBulk() : 0.0));

        double afterDrying = roundToTwoDecimals(
                batch.getDryDhool1() + batch.getDryDhool2() +
                        batch.getDryDhool3() + batch.getDryBigBulk());

        batch.setDryingLoss(roundToTwoDecimals(beforeDrying - afterDrying));
        productionRepo.save(batch);

        String dateParam = (redirectDate != null) ? redirectDate.toString() : LocalDate.now().toString();
        return "redirect:/mobile/record_rolling_dashboard?date=" + dateParam + "&success_drying";
    }
}