package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQApiProperties;
import com.anjia.unidbgserver.dto.DeviceInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 设备信息风控（ILLEGAL_ACCESS）时的自愈：自动更换设备信息并刷新 registerkey。
 * 不写入配置文件、不重启进程，仅在内存中生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FQDeviceRotationService {

    private final FQApiProperties fqApiProperties;
    private final FQRegisterKeyService registerKeyService;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile long lastRotateAtMs = 0L;
    private final AtomicInteger poolIndex = new AtomicInteger(0);

    @PostConstruct
    public void initDevicePoolOnStartup() {
        List<FQApiProperties.DeviceProfile> pool = fqApiProperties.getDevicePool();
        if (pool == null || pool.isEmpty()) {
            return;
        }

        int limit = Math.max(1, fqApiProperties.getDevicePoolSize());
        if (pool.size() > limit) {
            pool = new ArrayList<>(pool.subList(0, limit));
            fqApiProperties.setDevicePool(pool);
        }

        if (fqApiProperties.isDevicePoolShuffleOnStartup() && pool.size() > 1) {
            Collections.shuffle(pool, ThreadLocalRandom.current());
        }

        // 启动时随机选择一个作为当前设备（经过 shuffle 后取第一个即可）
        FQApiProperties.DeviceProfile selected = pool.get(0);
        applyDeviceProfile(selected);
        poolIndex.set(1);

        String name = selected.getName();
        String deviceId = selected.getDevice() != null ? selected.getDevice().getDeviceId() : null;
        String installId = selected.getDevice() != null ? selected.getDevice().getInstallId() : null;
        log.info("启动已选择设备池设备：name={}, deviceId={}, installId={}", name, deviceId, installId);
    }

    /**
     * 尝试旋转设备（带冷却时间，避免并发风暴）。
     *
     * @return 旋转成功返回新设备信息，否则返回 null
     */
    public DeviceInfo rotateIfNeeded(String reason) {
        return rotateInternal(reason, false);
    }

    /**
     * 强制旋转设备（忽略冷却时间），用于单次请求内的自愈流程。
     * 仍然会加锁，避免并发下“乱序切换”。
     */
    public DeviceInfo forceRotate(String reason) {
        return rotateInternal(reason, true);
    }

    private DeviceInfo rotateInternal(String reason, boolean ignoreCooldown) {
        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0L, fqApiProperties.getDeviceRotateCooldownMs());
        if (!ignoreCooldown && now - lastRotateAtMs < cooldownMs) {
            return null;
        }

        lock.lock();
        try {
            now = System.currentTimeMillis();
            cooldownMs = Math.max(0L, fqApiProperties.getDeviceRotateCooldownMs());
            if (!ignoreCooldown && now - lastRotateAtMs < cooldownMs) {
                return null;
            }

            DeviceInfo deviceInfo = rotateFromConfiguredPool(reason);
            if (deviceInfo == null) {
                log.warn("检测到风控/异常，但未配置 fq.api.device-pool，无法自动切换设备：reason={}", reason);
                return null;
            }

            lastRotateAtMs = now;
            try {
                registerKeyService.clearCache();
                registerKeyService.refreshRegisterKey();
            } catch (Exception e) {
                log.warn("设备旋转后刷新 registerkey 失败（可忽略，下次请求会再刷新）", e);
            }

            return deviceInfo;
        } finally {
            lock.unlock();
        }
    }

    private DeviceInfo rotateFromConfiguredPool(String reason) {
        List<FQApiProperties.DeviceProfile> pool = fqApiProperties.getDevicePool();
        if (pool == null || pool.isEmpty()) {
            return null;
        }

        String currentDeviceId = fqApiProperties.getDevice() != null ? fqApiProperties.getDevice().getDeviceId() : null;
        String currentInstallId = fqApiProperties.getDevice() != null ? fqApiProperties.getDevice().getInstallId() : null;

        FQApiProperties.DeviceProfile profile = null;
        int idx = -1;
        for (int i = 0; i < pool.size(); i++) {
            int candidateIdx = Math.floorMod(poolIndex.getAndIncrement(), pool.size());
            FQApiProperties.DeviceProfile candidate = pool.get(candidateIdx);
            if (candidate == null) {
                continue;
            }

            String candidateDeviceId = candidate.getDevice() != null ? candidate.getDevice().getDeviceId() : null;
            String candidateInstallId = candidate.getDevice() != null ? candidate.getDevice().getInstallId() : null;

            boolean sameDeviceId = candidateDeviceId != null && candidateDeviceId.equals(currentDeviceId);
            boolean sameInstallId = candidateInstallId != null && candidateInstallId.equals(currentInstallId);
            if (sameDeviceId && sameInstallId) {
                continue;
            }

            profile = candidate;
            idx = candidateIdx;
            break;
        }

        if (profile == null) {
            return null;
        }

        applyDeviceProfile(profile);

        String name = profile.getName();
        String deviceId = profile.getDevice() != null ? profile.getDevice().getDeviceId() : null;
        String installId = profile.getDevice() != null ? profile.getDevice().getInstallId() : null;
        log.warn("检测到风控/异常，已从配置池切换设备：index={}, name={}, deviceId={}, installId={}, reason={}",
            idx, name, deviceId, installId, reason);

        return toDeviceInfo(profile);
    }

    private DeviceInfo toDeviceInfo(FQApiProperties.DeviceProfile profile) {
        if (profile == null) {
            return null;
        }
        FQApiProperties.Device device = profile.getDevice();
        return DeviceInfo.builder()
            .userAgent(profile.getUserAgent())
            .cookie(profile.getCookie())
            .aid(device != null ? device.getAid() : null)
            .cdid(device != null ? device.getCdid() : null)
            .deviceBrand(device != null ? device.getDeviceBrand() : null)
            .deviceId(device != null ? device.getDeviceId() : null)
            .deviceType(device != null ? device.getDeviceType() : null)
            .dpi(device != null ? device.getDpi() : null)
            .hostAbi(device != null ? device.getHostAbi() : null)
            .installId(device != null ? device.getInstallId() : null)
            .resolution(device != null ? device.getResolution() : null)
            .romVersion(device != null ? device.getRomVersion() : null)
            .updateVersionCode(device != null ? device.getUpdateVersionCode() : null)
            .versionCode(device != null ? device.getVersionCode() : null)
            .versionName(device != null ? device.getVersionName() : null)
            .osVersion(device != null ? device.getOsVersion() : null)
            .osApi(device != null ? parseIntOrNull(device.getOsApi()) : null)
            .build();
    }

    private Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            String v = value.trim();
            if (v.isEmpty()) {
                return null;
            }
            return Integer.valueOf(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void applyDeviceProfile(FQApiProperties.DeviceProfile profile) {
        if (profile == null) {
            return;
        }
        if (profile.getUserAgent() != null) {
            fqApiProperties.setUserAgent(profile.getUserAgent());
        }
        if (profile.getCookie() != null) {
            fqApiProperties.setCookie(profile.getCookie());
        }

        if (fqApiProperties.getDevice() == null) {
            fqApiProperties.setDevice(new FQApiProperties.Device());
        }
        if (profile.getDevice() == null) {
            return;
        }

        FQApiProperties.Device src = profile.getDevice();
        FQApiProperties.Device dst = fqApiProperties.getDevice();
        copyIfPresent(src.getAid(), dst::setAid);
        copyIfPresent(src.getCdid(), dst::setCdid);
        copyIfPresent(src.getDeviceBrand(), dst::setDeviceBrand);
        copyIfPresent(src.getDeviceId(), dst::setDeviceId);
        copyIfPresent(src.getDeviceType(), dst::setDeviceType);
        copyIfPresent(src.getDpi(), dst::setDpi);
        copyIfPresent(src.getHostAbi(), dst::setHostAbi);
        copyIfPresent(src.getInstallId(), dst::setInstallId);
        copyIfPresent(src.getResolution(), dst::setResolution);
        copyIfPresent(src.getRomVersion(), dst::setRomVersion);
        copyIfPresent(src.getUpdateVersionCode(), dst::setUpdateVersionCode);
        copyIfPresent(src.getVersionCode(), dst::setVersionCode);
        copyIfPresent(src.getVersionName(), dst::setVersionName);
        copyIfPresent(src.getOsVersion(), dst::setOsVersion);
        copyIfPresent(src.getOsApi(), dst::setOsApi);
    }

    private void copyIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value == null) {
            return;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return;
        }
        setter.accept(v);
    }
}
