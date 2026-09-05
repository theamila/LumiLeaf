package com.lumileaf.lumi.controller;

import com.lumileaf.lumi.model.Supplier;
import com.lumileaf.lumi.repository.SupplierRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Controller
public class SupplierPageController {

    private final SupplierRepository supplierRepository;
    private final String UPLOAD_DIR = "./uploads/";

    @org.springframework.beans.factory.annotation.Value("${azure.storage.connection-string}")
    private String blobConnectionString;

    @org.springframework.beans.factory.annotation.Value("${azure.storage.container-name}")
    private String blobContainerName;

    public SupplierPageController(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    @GetMapping("/supplier")
    public String supplierPage(Model model) {
        List<Supplier> allSuppliers = supplierRepository.findAll();

        List<Supplier> activeSuppliers = allSuppliers.stream()
                .filter(s -> s.getStatus() == null || !s.getStatus().equals("DELETED"))
                .collect(Collectors.toList());

        List<Supplier> deletedSuppliers = allSuppliers.stream()
                .filter(s -> "DELETED".equals(s.getStatus()))
                .collect(Collectors.toList());

        model.addAttribute("activeSuppliers", activeSuppliers);
        model.addAttribute("deletedSuppliers", deletedSuppliers);
        return "supplier";
    }


    @GetMapping("/api/supplier/{supplierId}/detail")
    @ResponseBody
    public ResponseEntity<?> getSupplierDetail(@PathVariable String supplierId) {
        Optional<Supplier> supplierOpt = supplierRepository.findBySupplierId(supplierId);
        if (supplierOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Supplier s = supplierOpt.get();
        Map<String, Object> detail = new HashMap<>();
        detail.put("supplierId", s.getSupplierId());
        detail.put("name", s.getName());
        detail.put("section", s.getSection());
        detail.put("photoUrl", s.getPhotoUrl());
        detail.put("landPhotoUrl", s.getLandPhotoUrl());
        detail.put("latitude", s.getLatitude());
        detail.put("longitude", s.getLongitude());
        return ResponseEntity.ok(detail);
    }
    @PostMapping("/api/supplier/{supplierId}/upload-photo")
    @ResponseBody
    public ResponseEntity<?> uploadFarmerPhoto(@PathVariable String supplierId, @RequestParam("file") MultipartFile file) {
        return handlePhotoUpload(supplierId, file, false);
    }

    @PostMapping("/api/supplier/{supplierId}/upload-land-photo")
    @ResponseBody
    public ResponseEntity<?> uploadLandPhoto(@PathVariable String supplierId, @RequestParam("file") MultipartFile file) {
        return handlePhotoUpload(supplierId, file, true);
    }

    private ResponseEntity<?> handlePhotoUpload(String supplierId, MultipartFile file, boolean isLandPhoto) {
        Optional<Supplier> supplierOpt = supplierRepository.findBySupplierId(supplierId);
        if (supplierOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "No file selected"));
        }

        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals("image/png") && !contentType.equals("image/jpeg"))) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Only PNG or JPG images are allowed"));
        }

        if (blobConnectionString == null || blobConnectionString.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("success", false, "message", "Photo storage is not configured yet."));
        }

        try {
            String extension = contentType.equals("image/png") ? ".png" : ".jpg";
            String fileName = "supplier_" + supplierId + "_" + (isLandPhoto ? "land_" : "photo_")
                    + System.currentTimeMillis() + extension;

            com.azure.storage.blob.BlobServiceClient blobServiceClient = new com.azure.storage.blob.BlobServiceClientBuilder()
                    .connectionString(blobConnectionString)
                    .buildClient();
            com.azure.storage.blob.BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(blobContainerName);
            com.azure.storage.blob.BlobClient blobClient = containerClient.getBlobClient(fileName);

            blobClient.upload(file.getInputStream(), file.getSize(), true);

            Supplier s = supplierOpt.get();
            String publicPath = blobClient.getBlobUrl();
            if (isLandPhoto) {
                s.setLandPhotoUrl(publicPath);
            } else {
                s.setPhotoUrl(publicPath);
            }
            supplierRepository.save(s);

            return ResponseEntity.ok(Map.of("success", true, "url", publicPath));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "Upload failed: " + e.getMessage()));
        }
    }
    @PostMapping("/supplier/save")
    public String saveSupplier(@ModelAttribute Supplier supplier) {
        Optional<Supplier> existingSupplier = supplierRepository.findBySupplierId(supplier.getSupplierId());

        if (existingSupplier.isPresent()) {
            if (supplier.getId() == null || !existingSupplier.get().getId().equals(supplier.getId())) {
                return "redirect:/supplier?error=duplicate_id";
            }
        }

        if (supplier.getId() != null) {
            Supplier current = supplierRepository.findById(supplier.getId()).orElse(null);
            if (current != null) {
                supplier.setStatus(current.getStatus());
                supplier.setPhotoUrl(current.getPhotoUrl());
                supplier.setLandPhotoUrl(current.getLandPhotoUrl());
            } else {
                supplier.setStatus("ACTIVE");
            }
        } else {
            supplier.setStatus("ACTIVE");
        }

        supplierRepository.save(supplier);
        return "redirect:/supplier?success=true";
    }

    // --- NEW FEATURE: CSV FILE UPLOAD LOGIC ---
    @PostMapping("/supplier/upload")
    public String uploadCSV(@RequestParam("file") MultipartFile file, Model model) {
        if (file.isEmpty()) {
            return "redirect:/supplier?error=empty_file";
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean isHeader = true;

            while ((line = br.readLine()) != null) {
                // CSV එකේ line එක comma වලින් වෙන් කර ගන්නවා
                String[] data = line.split(",");

                // Header line එක අතහැරලා දාන්න
                if (isHeader) {
                    isHeader = false;
                    continue;
                }

                // Data row එකේ අවම වශයෙන් Famer ID (index 1) සහ Route (index 2) තියෙන්න ඕනේ
                if (data.length >= 3) {
                    String farmerId = data[1].trim();
                    String routeName = data[2].trim();

                    if (!farmerId.isEmpty()) {
                        // Database එකේ දැනටමත් මේ Farmer ID එක තියෙනවද බලන්න
                        Optional<Supplier> existing = supplierRepository.findBySupplierId(farmerId);

                        if (existing.isEmpty()) {
                            Supplier newSupplier = new Supplier();
                            newSupplier.setSupplierId(farmerId);
                            newSupplier.setSection(routeName);


                            newSupplier.setName("Farmer - " + farmerId);
                            newSupplier.setContact("");
                            newSupplier.setStatus("ACTIVE");

                            supplierRepository.save(newSupplier);
                        } else {

                            Supplier existingSupplier = existing.get();
                            if(existingSupplier.getSection() == null || !existingSupplier.getSection().equals(routeName)){
                                existingSupplier.setSection(routeName);
                                supplierRepository.save(existingSupplier);
                            }
                        }
                    }
                }
            }
            return "redirect:/supplier?upload_success=true";

        } catch (Exception e) {
            e.printStackTrace();
            return "redirect:/supplier?error=upload_failed";
        }
    }

    @GetMapping("/supplier/delete/{id}")
    public String deleteSupplier(@PathVariable Long id) {
        Supplier supplier = supplierRepository.findById(id).orElse(null);
        if (supplier != null) {
            supplier.setStatus("DELETED");
            supplierRepository.save(supplier);
        }
        return "redirect:/supplier?deleted=true";
    }

    @GetMapping("/suppliers/by-section/{section}")
    @ResponseBody
    public List<Supplier> getSuppliersBySection(@PathVariable String section) {
        return supplierRepository.findBySection(section).stream()
                .filter(s -> s.getStatus() == null || !s.getStatus().equals("DELETED"))
                .collect(Collectors.toList());
    }
}