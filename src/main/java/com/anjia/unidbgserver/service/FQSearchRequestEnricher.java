package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQApiProperties;
import com.anjia.unidbgserver.dto.FQSearchRequest;
import com.anjia.unidbgserver.utils.Texts;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class FQSearchRequestEnricher {

    private static final int DEFAULT_OFFSET = 0;
    private static final int DEFAULT_COUNT = 20;
    private static final int DEFAULT_TAB_TYPE = 1;
    private static final int DEFAULT_BOOKSHELF_SEARCH_PLAN = 4;
    private static final int DEFAULT_USER_IS_LOGIN = 0;
    private static final int DEFAULT_BOOKSTORE_TAB = 2;
    private static final int DEFAULT_SEARCH_SOURCE = 1;
    private static final String DEFAULT_CLIENT_AB_INFO = "{}";
    private static final String DEFAULT_CLICKED_CONTENT = "search_history";
    private static final String DEFAULT_SEARCH_SOURCE_ID = "his###";
    private static final String DEFAULT_TAB_NAME = "store";

    private static final int DEFAULT_LAST_SEARCH_PAGE_INTERVAL = 0;
    private static final int DEFAULT_LINE_WORDS_NUM = 0;
    private static final int DEFAULT_LAST_CONSUME_INTERVAL = 0;
    private static final int DEFAULT_PAD_COLUMN_COVER = 0;
    private static final String DEFAULT_KLINK_EGDI = "";

    private static final int DEFAULT_CHARGING = 1;
    private static final int DEFAULT_SCREEN_BRIGHTNESS = 72;
    private static final int DEFAULT_BATTERY_PCT = 78;
    private static final int DEFAULT_DOWN_SPEED = 89121;
    private static final int DEFAULT_SYS_DARK_MODE = 0;
    private static final int DEFAULT_APP_DARK_MODE = 0;
    private static final int DEFAULT_FONT_SCALE = 100;
    private static final int DEFAULT_IS_ANDROID_PAD_SCREEN = 0;
    private static final int DEFAULT_NETWORK_TYPE = 4;
    private static final int DEFAULT_CURRENT_VOLUME = 75;
    private static final int DEFAULT_NEED_PERSONAL_RECOMMEND = 0;
    private static final int DEFAULT_PLAYER_SO_LOAD = 1;
    private static final int DEFAULT_GENDER = 1;
    private static final int DEFAULT_COMPLIANCE_STATUS = 0;
    private static final int DEFAULT_HAR_STATUS = 0;

    private final FQApiProperties fqApiProperties;

    public FQSearchRequestEnricher(FQApiProperties fqApiProperties) {
        this.fqApiProperties = fqApiProperties;
    }

    public void enrich(FQSearchRequest request) {
        if (request == null) {
            return;
        }

        applyQueryDefaults(request);
        applySearchBehaviorDefaults(request);
        applySessionDefaults(request);
        applyRuntimeDefaults(request);
        applyDeviceProfileDefaults(request);
    }

    private void applyQueryDefaults(FQSearchRequest request) {
        setIfNull(request::getOffset, request::setOffset, DEFAULT_OFFSET);
        setIfNull(request::getCount, request::setCount, DEFAULT_COUNT);
        setIfNull(request::getTabType, request::setTabType, DEFAULT_TAB_TYPE);
        setIfNull(request::getBookshelfSearchPlan, request::setBookshelfSearchPlan, DEFAULT_BOOKSHELF_SEARCH_PLAN);
        setIfNull(request::getFromRs, request::setFromRs, false);
        setIfNull(request::getUserIsLogin, request::setUserIsLogin, DEFAULT_USER_IS_LOGIN);
        setIfNull(request::getBookstoreTab, request::setBookstoreTab, DEFAULT_BOOKSTORE_TAB);
        setIfNull(request::getSearchSource, request::setSearchSource, DEFAULT_SEARCH_SOURCE);
        setIfNull(request::getUseLynx, request::setUseLynx, false);
        setIfNull(request::getUseCorrect, request::setUseCorrect, true);
        setIfNull(request::getIsFirstEnterSearch, request::setIsFirstEnterSearch, true);
        setIfNull(request::getClientAbInfo, request::setClientAbInfo, DEFAULT_CLIENT_AB_INFO);
        setIfNull(request::getClickedContent, request::setClickedContent, DEFAULT_CLICKED_CONTENT);
        setIfNull(request::getSearchSourceId, request::setSearchSourceId, DEFAULT_SEARCH_SOURCE_ID);
        setIfNull(request::getTabName, request::setTabName, DEFAULT_TAB_NAME);
    }

    private static void applySearchBehaviorDefaults(FQSearchRequest request) {
        setIfNull(request::getLastSearchPageInterval, request::setLastSearchPageInterval, DEFAULT_LAST_SEARCH_PAGE_INTERVAL);
        setIfNull(request::getLineWordsNum, request::setLineWordsNum, DEFAULT_LINE_WORDS_NUM);
        setIfNull(request::getLastConsumeInterval, request::setLastConsumeInterval, DEFAULT_LAST_CONSUME_INTERVAL);
        setIfNull(request::getPadColumnCover, request::setPadColumnCover, DEFAULT_PAD_COLUMN_COVER);
        setIfNull(request::getKlinkEgdi, request::setKlinkEgdi, DEFAULT_KLINK_EGDI);
    }

    private static void applySessionDefaults(FQSearchRequest request) {
        ensureSessionId(request::getNormalSessionId, request::setNormalSessionId);
        ensureSessionId(request::getColdStartSessionId, request::setColdStartSessionId);
    }

    private static void applyRuntimeDefaults(FQSearchRequest request) {
        setIfNull(request::getCharging, request::setCharging, DEFAULT_CHARGING);
        setIfNull(request::getScreenBrightness, request::setScreenBrightness, DEFAULT_SCREEN_BRIGHTNESS);
        setIfNull(request::getBatteryPct, request::setBatteryPct, DEFAULT_BATTERY_PCT);
        setIfNull(request::getDownSpeed, request::setDownSpeed, DEFAULT_DOWN_SPEED);
        setIfNull(request::getSysDarkMode, request::setSysDarkMode, DEFAULT_SYS_DARK_MODE);
        setIfNull(request::getAppDarkMode, request::setAppDarkMode, DEFAULT_APP_DARK_MODE);
        setIfNull(request::getFontScale, request::setFontScale, DEFAULT_FONT_SCALE);
        setIfNull(request::getIsAndroidPadScreen, request::setIsAndroidPadScreen, DEFAULT_IS_ANDROID_PAD_SCREEN);
        setIfNull(request::getNetworkType, request::setNetworkType, DEFAULT_NETWORK_TYPE);
        setIfNull(request::getCurrentVolume, request::setCurrentVolume, DEFAULT_CURRENT_VOLUME);
        setIfNull(request::getNeedPersonalRecommend, request::setNeedPersonalRecommend, DEFAULT_NEED_PERSONAL_RECOMMEND);
        setIfNull(request::getPlayerSoLoad, request::setPlayerSoLoad, DEFAULT_PLAYER_SO_LOAD);
        setIfNull(request::getGender, request::setGender, DEFAULT_GENDER);
        setIfNull(request::getComplianceStatus, request::setComplianceStatus, DEFAULT_COMPLIANCE_STATUS);
        setIfNull(request::getHarStatus, request::setHarStatus, DEFAULT_HAR_STATUS);
    }

    private void applyDeviceProfileDefaults(FQSearchRequest request) {
        FQApiProperties.Device device = runtimeDevice();
        setIfNull(request::getRomVersion, request::setRomVersion, deviceFieldOrEmpty(device, FQApiProperties.Device::getRomVersion));
        setIfNull(request::getCdid, request::setCdid, deviceFieldOrEmpty(device, FQApiProperties.Device::getCdid));
    }

    private static <T> void setIfNull(Supplier<T> getter, Consumer<T> setter, T defaultValue) {
        if (getter.get() == null) {
            setter.accept(defaultValue);
        }
    }

    private static void ensureSessionId(Supplier<String> getter, Consumer<String> setter) {
        if (!Texts.hasText(getter.get())) {
            setter.accept(UUID.randomUUID().toString());
        }
    }

    private FQApiProperties.Device runtimeDevice() {
        FQApiProperties.RuntimeProfile runtimeProfile = fqApiProperties == null ? null : fqApiProperties.getRuntimeProfile();
        return runtimeProfile == null ? null : runtimeProfile.getDevice();
    }

    private static String deviceFieldOrEmpty(FQApiProperties.Device device, Function<FQApiProperties.Device, String> getter) {
        if (device == null || getter == null) {
            return "";
        }
        return Texts.nullToEmpty(getter.apply(device));
    }
}
