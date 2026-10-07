package com.lumileaf.lumi.repository;

import com.lumileaf.lumi.model.AdminPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminPermissionRepository extends JpaRepository<AdminPermission, Long> {
    List<AdminPermission> findByAdminId(Integer adminId);
    boolean existsByAdminIdAndPermissionKey(Integer adminId, String permissionKey);
    void deleteByAdminIdAndPermissionKey(Integer adminId, String permissionKey);
}