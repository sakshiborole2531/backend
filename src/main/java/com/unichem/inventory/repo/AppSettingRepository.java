package com.unichem.inventory.repo;

import com.unichem.inventory.domain.AppSetting;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {

}
