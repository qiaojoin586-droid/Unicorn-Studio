package com.unicorn.studio.repository;

import com.unicorn.studio.model.StatsDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StatsDailyRepository extends JpaRepository<StatsDaily, Long> {

    Optional<StatsDaily> findByStatDate(LocalDate date);

    List<StatsDaily> findByStatDateBetweenOrderByStatDateAsc(LocalDate from, LocalDate to);
}
