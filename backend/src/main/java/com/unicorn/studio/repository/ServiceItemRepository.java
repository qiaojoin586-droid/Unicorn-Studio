package com.unicorn.studio.repository;

import com.unicorn.studio.model.ServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceItemRepository extends JpaRepository<ServiceItem, Long> {

    List<ServiceItem> findByStatusOrderBySortOrderAscIdAsc(String status);
}
