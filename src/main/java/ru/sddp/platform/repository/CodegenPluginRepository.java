package ru.sddp.platform.repository;
import ru.sddp.platform.domain.CodegenPlugin;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CodegenPluginRepository extends JpaRepository<CodegenPlugin, UUID> { List<CodegenPlugin> findByActiveTrue(); }
