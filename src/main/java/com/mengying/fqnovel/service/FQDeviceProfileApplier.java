package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.FQApiProperties;
import com.mengying.fqnovel.config.FQApiDeviceProfiles;
import com.mengying.fqnovel.config.FQApiRuntimeProfileManager;
import com.mengying.fqnovel.utils.CookieUtils;
import com.mengying.fqnovel.utils.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 将设备池 profile 原子应用到运行时快照。
 */
@Service
public class FQDeviceProfileApplier {

    private static final Logger log = LoggerFactory.getLogger(FQDeviceProfileApplier.class);

    private final FQApiRuntimeProfileManager runtimeProfileManager;

    public FQDeviceProfileApplier(FQApiRuntimeProfileManager runtimeProfileManager) {
        this.runtimeProfileManager = runtimeProfileManager;
    }

    public boolean apply(FQApiProperties.DeviceProfile profile) {
        if (profile == null) {
            return false;
        }
        if (profile.getDevice() == null) {
            log.warn("设备配置的 device 字段为空，跳过设备信息应用: profileName={}", profileName(profile));
            return false;
        }

        FQApiProperties.Device newDevice = FQApiDeviceProfiles.copyDevice(profile.getDevice());
        String userAgent = Texts.trimToNull(profile.getUserAgent());
        String cookie = Texts.trimToNull(profile.getCookie());
        if (userAgent == null || cookie == null) {
            log.warn(
                "设备配置缺少完整请求指纹，跳过设备切换: profileName={}, userAgentPresent={}, cookiePresent={}",
                profileName(profile),
                userAgent != null,
                cookie != null
            );
            return false;
        }

        String normalizedCookie = CookieUtils.normalizeInstallId(cookie, newDevice.getInstallId());
        runtimeProfileManager.applyRuntimeProfile(userAgent, normalizedCookie, newDevice);
        return true;
    }
    private static String profileName(FQApiProperties.DeviceProfile profile) {
        return profile == null ? null : profile.getName();
    }
}
