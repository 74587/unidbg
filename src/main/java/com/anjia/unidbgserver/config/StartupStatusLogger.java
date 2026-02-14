package com.anjia.unidbgserver.config;

import com.anjia.unidbgserver.service.FQDeviceRotationService;
import com.anjia.unidbgserver.service.FQRegisterKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupStatusLogger {

    private final FQApiProperties fqApiProperties;
    private final FQDeviceRotationService deviceRotationService;
    private final FQRegisterKeyService registerKeyService;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String poolName = deviceRotationService.getCurrentProfileName();
        FQApiProperties.RuntimeProfile runtimeProfile = fqApiProperties.getRuntimeProfile();
        FQApiProperties.Device runtimeDevice = runtimeProfile != null ? runtimeProfile.getDevice() : null;
        String deviceId = runtimeDevice != null ? runtimeDevice.getDeviceId() : null;
        int poolSize = fqApiProperties.getDevicePool() != null ? fqApiProperties.getDevicePool().size() : 0;

        log.info("设备配置：池大小={}, 当前={}, ID={}", poolSize, poolName, deviceId);

        // 预热：启动时预先获取 registerkey
        try {
            log.info("预热 registerkey...");
            registerKeyService.refreshRegisterKey();
            Map<String, Object> cache = registerKeyService.getCacheStatus();
            Long keyver = (Long) cache.get("currentKeyver");
            log.info("预热完成：keyver={}", keyver);
        } catch (Exception e) {
            log.warn("预热失败（将在首次请求时重试）: {}", e.getMessage());
        }
    }
}
