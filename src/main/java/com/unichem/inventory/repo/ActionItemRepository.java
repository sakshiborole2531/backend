package com.unichem.inventory.repo;

import com.unichem.inventory.domain.ActionItem;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ActionItemRepository extends JpaRepository<ActionItem, String> {
    @Query(value = "select nextval('action_seq')", nativeQuery = true)
    long nextSeq();
    List<ActionItem> findByItemKeyAndStageNot(String itemKey, String stage);
    List<ActionItem> findByItemKey(String itemKey);
}
