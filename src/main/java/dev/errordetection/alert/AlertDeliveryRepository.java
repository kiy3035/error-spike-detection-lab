package dev.errordetection.alert;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AlertDeliveryRepository
        extends JpaRepository<AlertDelivery, UUID>, JpaSpecificationExecutor<AlertDelivery> {
}
