package com.sentrix.core.datasource;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ObservationDataSourceRepository extends JpaRepository<ObservationDataSource, Long> {
    List<ObservationDataSource> findByEnvironmentIdOrderByTypeAsc(Long environmentId);
    Optional<ObservationDataSource> findByEnvironmentIdAndType(Long environmentId, DataSourceType type);
}
