package ru.sddp.platform.domain;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(name="codegen_plugins")
public class CodegenPlugin {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable=false) private String language;
    @Column(name="delivery_type", nullable=false) private String deliveryType;
    @Column(name="executable_target", nullable=false) private String executableTarget;
    @Column(name="is_active", nullable=false) private boolean active=true;
    protected CodegenPlugin() { }
    public CodegenPlugin(String language, String deliveryType, String executableTarget) { this.language=language; this.deliveryType=deliveryType; this.executableTarget=executableTarget; }
    public UUID getId(){return id;} public String getLanguage(){return language;} public String getDeliveryType(){return deliveryType;} public String getExecutableTarget(){return executableTarget;} public boolean isActive(){return active;}
}
