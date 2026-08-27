package com.lumileaf.lumi.controller;

import com.lumileaf.lumi.model.Blending;
import com.lumileaf.lumi.model.BlendBalance;
import com.lumileaf.lumi.model.ProductionBatch;
import com.lumileaf.lumi.model.StockProduction;
import com.lumileaf.lumi.repository.BlendBalanceRepository;
import com.lumileaf.lumi.repository.BlendingRepository;
import com.lumileaf.lumi.repository.ProductionBatchRepository;
import com.lumileaf.lumi.repository.StockProductionRepository;
import com.lumileaf.qrcode.QRGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpSession;
import com.lumileaf.lumi.service.ContributionService;
import org.springframework.http.ResponseEntity;
import com.lumileaf.lumi.model.FarmerContribution;
import org.springframework.beans.factory.annotation.Value;
import com.lumileaf.lumi.model.Buyer;
import com.lumileaf.lumi.repository.BuyerRepository;


@Controller
public class BlendingController {
    @Value("${app.base-url}")
    private String baseUrl;

    @Autowired private BlendingRepository blendingRepo;
    @Autowired private ProductionBatchRepository productionRepo;
    @Autowired private BlendBalanceRepository blendBalanceRepo;
    @Autowired private ContributionService contributionService;
    @Autowired private BuyerRepository buyerRepo;
    private Map<String, List<Blending>> buildGroupedBlends() {
        return blendingRepo.findAll().stream()
                .collect(Collectors.groupingBy(Blending::getInvoiceNumber));
    }
    // ── FIX (BlendingController #6): Was missing entirely. Without this, Blending had
    // no way to see grades that had moved into Stock Production — the moment a batch
    // was consolidated (status → CONSOLIDATED), its tea became permanently invisible
    // to Blending, contradicting the requirement that Blending must be able to trace
    // through Stock Production lots.
    @Autowired private StockProductionRepository stockProductionRepo;

    @GetMapping("/blending")
    public String viewBlendingManagement(Model model, HttpSession session) {
        if (session.getAttribute("role") == null) return "redirect:/login";

        List<Blending> allBlends = blendingRepo.findAll();

        // Group by Invoice Number
        Map<String, List<Blending>> groupedBlends = allBlends.stream()
                .collect(Collectors.groupingBy(Blending::getInvoiceNumber));

        model.addAttribute("groupedBlends", groupedBlends);
        model.addAttribute("newBlend", new Blending());
        model.addAttribute("buyers", buyerRepo.findAll());

        return "blending";
    }


    // --- NEW: RECIPE PRE-FLIGHT STOCK CHECKER ---
    @GetMapping("/blending/api/stock-summary")
    @ResponseBody
    public Map<String, Double> getStockSummary() {
        String[] allGrades = {"OP1", "OPA", "BOP1", "PEKOE", "BOP", "BOPF", "EB", "FFSP", "FFEXS", "DUST", "BM", "BP", "REFUSE"};
        Map<String, Double> summary = new HashMap<>();
        List<Blending> allBlends = blendingRepo.findAll();

        for (String g : allGrades) {
            double prodStock = productionRepo.findAll().stream()
                    .filter(b -> "APPROVED".equals(b.getStatus()))
                    .mapToDouble(b -> getStockForGrade(b, g)).sum();

            // ── FIX (BlendingController #6): include Stock Production lots in the total.
            // Only APPROVED (lab-approved) lots count, matching the same rule already
            // applied to ProductionBatch above.
            double stockProdStock = stockProductionRepo.findAll().stream()
                    .filter(sp -> "APPROVED".equals(sp.getStatus()))
                    .mapToDouble(sp -> getStockForGrade(sp, g)).sum();

            double remnantStock = blendBalanceRepo.findAll().stream()
                    .mapToDouble(b -> getGradeValueFromBalance(b, g)).sum();

            double usedQty = allBlends.stream()
                    .filter(b -> g.equalsIgnoreCase(b.getGrade()))
                    .filter(b -> "APPROVED".equals(b.getStatus()))
                    .mapToDouble(Blending::getQuantity).sum();

            double available = Math.round((prodStock + stockProdStock + remnantStock - usedQty) * 100.0) / 100.0;
            summary.put(g, Math.max(0.0, available));
        }
        return summary;
    }
    @GetMapping("/api/blending/{id}/contributions")
    @ResponseBody
    public ResponseEntity<?> getBlendLineContributions(@PathVariable Long id) {
        Blending line = blendingRepo.findById(id).orElse(null);
        if (line == null) {
            return ResponseEntity.notFound().build();
        }

        if ("FROM-REMNANTS".equals(line.getBatchNumber())) {
            return ResponseEntity.ok(Map.of(
                    "applicable", false,
                    "reason", "Sourced from remnants — original farmer contribution is not tracked past this point.",
                    "contributions", List.of()
            ));
        }

        List<ProductionBatch> sourceBatches = contributionService.resolveLotNumberToBatches(line.getBatchNumber());
        List<FarmerContribution> lotContributions = contributionService.getLotContributionsByActualMadeTea(sourceBatches);

        double lineQty = line.getQuantity() != null ? line.getQuantity() : 0.0;
        List<Map<String, Object>> scaled = new ArrayList<>();
        for (FarmerContribution fc : lotContributions) {
            double allocatedKg = Math.round((fc.getPercent() / 100.0) * lineQty * 100.0) / 100.0;
            scaled.add(Map.of(
                    "supplierId", fc.getSupplierId(),
                    "supplierName", fc.getSupplierName(),
                    "percent", fc.getPercent(),
                    "allocatedKg", allocatedKg
            ));
        }

        return ResponseEntity.ok(Map.of(
                "applicable", true,
                "sourceLot", line.getBatchNumber(),
                "lineQuantity", lineQty,
                "contributions", scaled
        ));
    }
    @GetMapping("/blending/addGrade/{invoiceNumber}")
    public String addGradeToInvoice(@PathVariable String invoiceNumber, Model model) {
        Map<String, List<Blending>> groupedBlends = buildGroupedBlends();

        List<Blending> invoiceLines = groupedBlends.get(invoiceNumber);
        if (invoiceLines == null || invoiceLines.isEmpty()) {
            return "redirect:/blending";
        }

        Blending template = invoiceLines.get(0);
        Blending newLine = new Blending();
        newLine.setInvoiceNumber(template.getInvoiceNumber());
        newLine.setBuyerInfo(template.getBuyerInfo());
        newLine.setProductName(template.getProductName());
        newLine.setTargetTotalWeight(template.getTargetTotalWeight());
        newLine.setFinishedGoodNumber(template.getFinishedGoodNumber());
        newLine.setStatus("PENDING");

        model.addAttribute("groupedBlends", groupedBlends);
        model.addAttribute("newBlend", newLine);
        model.addAttribute("lockInvoice", true);
        model.addAttribute("buyers", buyerRepo.findAll());   // ADD THIS LINE
        return "blending";
    }

    @GetMapping("/blending/data/{grade}")
    @ResponseBody
    public Map<String, Object> getGradeData(@PathVariable String grade) {
        Map<String, Object> data = new HashMap<>();
        String upperGrade = grade.toUpperCase();

        List<ProductionBatch> relevantBatches = productionRepo.findAll().stream()
                .filter(b -> "APPROVED".equals(b.getStatus()))
                .filter(b -> getStockForGrade(b, upperGrade) > 0)
                .collect(Collectors.toList());

        Map<String, Double> batchStock = new HashMap<>();
        for (ProductionBatch batch : relevantBatches) {
            String lot = batch.getLotNumber();
            double originalQty = getStockForGrade(batch, upperGrade);
            double usedQty = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(lot, upperGrade)).orElse(0.0);
            double remaining = originalQty - usedQty;

            if (remaining > 0) {
                batchStock.put(lot, round(remaining));
            }
        }

        // ── FIX (BlendingController #6): merge Stock Production lots into the same
        // batchStock map the "Source Batch" dropdown already reads from — no template
        // change required, since it's the same map/field the UI already binds to.
        // merge() with Double::sum is a safety net in case a lot number were ever to
        // exist in both maps at once; in steady state this never triggers because a
        // batch's status is exclusively either APPROVED (in Production) or CONSOLIDATED
        // (moved to Stock Production), never both simultaneously.
        List<StockProduction> relevantStockLots = stockProductionRepo.findAll().stream()
                .filter(sp -> "APPROVED".equals(sp.getStatus()))
                .filter(sp -> getStockForGrade(sp, upperGrade) > 0)
                .collect(Collectors.toList());

        for (StockProduction sp : relevantStockLots) {
            String lot = sp.getLotNumber();
            double originalQty = getStockForGrade(sp, upperGrade);
            double usedQty = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(lot, upperGrade)).orElse(0.0);
            double remaining = originalQty - usedQty;

            if (remaining > 0) {
                batchStock.merge(lot, round(remaining), Double::sum);
            }
        }

        List<BlendBalance> allBalances = blendBalanceRepo.findAll();
        Map<String, Double> remnantBalances = new HashMap<>();

        for (BlendBalance b : allBalances) {
            double original = getGradeValueFromBalance(b, upperGrade);
            double used = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(b.getBlendId(), upperGrade)).orElse(0.0);
            double remaining = round(original - used);

            if (remaining > 0) {
                remnantBalances.put(b.getBlendId(), remaining);
            }
        }

        data.put("batchStock", batchStock);
        data.put("remnantBalances", remnantBalances);
        return data;
    }

    @PostMapping("/blending/save")
    @Transactional
    public String saveBlending(@ModelAttribute("newBlend") Blending blending, RedirectAttributes ra) {
        if (blending.getGrade() != null) blending.setGrade(blending.getGrade().trim().toUpperCase());

        // ✅ FIX Issue 2: Check for duplicate FG number across all invoices
        List<Blending> matchingFG = blendingRepo.findAllByFinishedGoodNumber(blending.getFinishedGoodNumber());
        boolean conflictsWithOtherInvoice = matchingFG.stream()
                .anyMatch(b -> !b.getInvoiceNumber().equals(blending.getInvoiceNumber()));
        if (conflictsWithOtherInvoice) {
            ra.addFlashAttribute("error", "Error: Finished Good Number '" + blending.getFinishedGoodNumber()
                    + "' is already used by another invoice. FG numbers must be unique across invoices.");
            return "redirect:/blending";
        }

        boolean isRemnantSource = "FROM-REMNANTS".equals(blending.getBatchNumber()) ||
                (blending.getBlendingNumber() != null && !blending.getBlendingNumber().isEmpty());

        if (isRemnantSource) {
            String targetRef = blending.getBlendingNumber();

            Optional<BlendBalance> balanceOpt = blendBalanceRepo.findByBlendId(targetRef);
            if (balanceOpt.isEmpty()) {
                ra.addFlashAttribute("error", "Error: Remnant Reference '" + targetRef + "' not found.");
                return "redirect:/blending";
            }

            BlendBalance originalEntry = balanceOpt.get();
            double originalQty = getGradeValueFromBalance(originalEntry, blending.getGrade());
            double usedAlready = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(targetRef, blending.getGrade())).orElse(0.0);
            double availableNow = originalQty - usedAlready;

            if (blending.getQuantity() > availableNow) {
                ra.addFlashAttribute("error", "Insufficient Remnant Stock! " + targetRef +
                        " only has " + round(availableNow) + "kg of " + blending.getGrade() + " left.");
                return "redirect:/blending";
            }

            blending.setBatchNumber(targetRef);
        } else {
            // ── FIX (BlendingController #6): Look up ALL APPROVED production batches for this lot and sum their grade stock
            // This prevents double-counting against batches already CONSOLIDATED into StockProduction.
            List<ProductionBatch> approvedBatches = productionRepo.findAllByLotNumber(blending.getBatchNumber())
                    .stream()
                    .filter(b -> "APPROVED".equals(b.getStatus()))
                    .collect(Collectors.toList());

            if (!approvedBatches.isEmpty()) {
                double originalQty = approvedBatches.stream()
                        .mapToDouble(b -> getStockForGrade(b, blending.getGrade()))
                        .sum();
                double usedQty = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(blending.getBatchNumber(), blending.getGrade())).orElse(0.0);
                double available = originalQty - usedQty;

                if (blending.getQuantity() > available) {
                    ra.addFlashAttribute("error", "Insufficient fresh stock! Only " + round(available) + "kg remaining.");
                    return "redirect:/blending";
                }
            } else {
                // ── FIX (BlendingController #6): fall back to Stock Production if the
                // lot number doesn't match an active (APPROVED) ProductionBatch — it may
                // already have been consolidated into Stock Production under the same
                // lot number.
                Optional<StockProduction> stockOpt = stockProductionRepo.findByLotNumber(blending.getBatchNumber())
                        .filter(sp -> "APPROVED".equals(sp.getStatus()));

                if (stockOpt.isPresent()) {
                    double originalQty = getStockForGrade(stockOpt.get(), blending.getGrade());
                    double usedQty = Optional.ofNullable(blendingRepo.sumQuantityByBatchAndGrade(blending.getBatchNumber(), blending.getGrade())).orElse(0.0);
                    double available = originalQty - usedQty;

                    if (blending.getQuantity() > available) {
                        ra.addFlashAttribute("error", "Insufficient stock in stock-production lot! Only " + round(available) + "kg remaining.");
                        return "redirect:/blending";
                    }
                }
            }
        }

        // FIX (Issue 2): status now comes from which button was clicked (set by blending.html
// JS before submit) rather than always being forced to APPROVED. "Save & Next Grade"
// submits PENDING (no stock deducted yet); "Finish & Close Invoice" submits APPROVED
// for this line AND flips every other PENDING line on the same invoice to APPROVED,
// so the whole invoice's stock deducts together at close time.
        if (blending.getStatus() == null || blending.getStatus().isBlank()) {
            blending.setStatus("PENDING");
        }
        blendingRepo.save(blending);

        if ("APPROVED".equals(blending.getStatus())) {
            List<Blending> sameInvoiceLines = blendingRepo.findByInvoiceNumber(blending.getInvoiceNumber());
            for (Blending line : sameInvoiceLines) {
                if ("PENDING".equals(line.getStatus())) {
                    line.setStatus("APPROVED");
                    blendingRepo.save(line);
                }
            }
        }

        ra.addFlashAttribute("success", "Blending recorded. Inventory updated.");
        return "redirect:/blending";
    }
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

    private double getStockForGrade(ProductionBatch batch, String grade) {
        if (batch == null || grade == null) return 0.0;
        Double value = switch (grade.toUpperCase()) {
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
            default -> 0.0;
        };
        return (value != null) ? value : 0.0;
    }

    // ── FIX (BlendingController #6): new overload mirroring getStockForGrade(ProductionBatch, String)
    // above, but for StockProduction lots — same switch structure, same grade key set.
    private double getStockForGrade(StockProduction sp, String grade) {
        if (sp == null || grade == null) return 0.0;
        Double value = switch (grade.toUpperCase()) {
            case "OP1" -> sp.getOp1();
            case "OPA" -> sp.getOpa();
            case "BOP1" -> sp.getBop1();
            case "PEKOE" -> sp.getPekoe();
            case "BOP" -> sp.getBop();
            case "BOPF" -> sp.getBopf();
            case "EB" -> sp.getEb();
            case "FFSP" -> sp.getFfsp();
            case "FFEXS" -> sp.getFfexs();
            case "DUST" -> sp.getDust();
            case "BM" -> sp.getBm();
            case "BP" -> sp.getBp();
            case "REFUSE", "REFUSEDTEA" -> sp.getRefusedTea();
            default -> 0.0;
        };
        return (value != null) ? value : 0.0;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @PostMapping("/blending/updateStatus")
    public String updateStatus(@RequestParam Long id, @RequestParam String status) {
        Blending blend = blendingRepo.findById(id).orElseThrow();
        blend.setStatus(status);
        blendingRepo.save(blend);
        return "redirect:/blending";
    }

    // AFTER
    @GetMapping("/blending/edit/{id}")
    public String editBlend(@PathVariable Long id, Model model) {
        Blending blendToEdit = blendingRepo.findById(id).orElseThrow();

        // FIX (Issue A / Bug 1): the template's card list (and the JS invoiceData
        // extraction it feeds) is built from groupedBlends, not "blends". Without
        // this, the edit page renders zero .blend-card elements, so invoiceData
        // is empty and the Invoice Number dropdown has no options to auto-select.
        List<Blending> allBlends = blendingRepo.findAll();
        Map<String, List<Blending>> groupedBlends = allBlends.stream()
                .collect(Collectors.groupingBy(Blending::getInvoiceNumber));

        model.addAttribute("groupedBlends", groupedBlends);
        model.addAttribute("newBlend", blendToEdit);
        model.addAttribute("buyers", buyerRepo.findAll());
        return "blending";
    }
    @PostMapping("/blending/buyers/add")
    public String addBuyer(@RequestParam String name, RedirectAttributes ra) {
        if (name != null && !name.trim().isEmpty()) {
            Buyer buyer = new Buyer();
            buyer.setName(name.trim());
            buyerRepo.save(buyer);
        }
        return "redirect:/blending";
    }
    @GetMapping("/blending/dispatch-note/{id}")
    public String showDispatchNote(@PathVariable Long id, Model model) {
        Blending blend = blendingRepo.findById(id).orElseThrow();

        // Default 10kg-increment package split — user can adjust in the template before printing.
        double totalQty = blend.getQuantity() != null ? blend.getQuantity() : 0.0;
        List<Double> defaultPackages = new ArrayList<>();
        double remaining = totalQty;
        while (remaining > 10.0) {
            defaultPackages.add(10.0);
            remaining = Math.round((remaining - 10.0) * 100.0) / 100.0;
        }
        if (remaining > 0) {
            defaultPackages.add(remaining);
        }

        String traceUrl = baseUrl + "/trace/" + blend.getFinishedGoodNumber();
        String qrCode = QRGenerator.generateQRBase64(traceUrl, 300, 300);

        model.addAttribute("blend", blend);
        model.addAttribute("defaultPackages", defaultPackages);
        model.addAttribute("tareWeight", 1.700);
        model.addAttribute("qrCode", qrCode);
        return "dispatch_note_label";
    }

    @GetMapping("/blending/label/{id}")
    public String showQrLabel(@PathVariable Long id, Model model) {
        Blending blend = blendingRepo.findById(id).orElseThrow();
        String ipAddress;
        try { ipAddress = InetAddress.getLocalHost().getHostAddress(); }
        catch (UnknownHostException e) { ipAddress = "localhost"; }

        String traceUrl = baseUrl + "/trace/" + blend.getFinishedGoodNumber();
        String qrBase64 = QRGenerator.generateQRBase64(traceUrl, 300, 300);

        model.addAttribute("blend", blend);
        model.addAttribute("qrCode", qrBase64);
        return "blend_label";
    }
}