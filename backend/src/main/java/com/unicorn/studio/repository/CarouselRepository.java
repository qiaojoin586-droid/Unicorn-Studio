package com.unicorn.studio.repository;

import com.unicorn.studio.model.Carousel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface CarouselRepository extends JpaRepository<Carousel, Long>, JpaSpecificationExecutor<Carousel> {

    List<Carousel> findByStatusOrderBySortOrderAscIdAsc(Integer status);
}
