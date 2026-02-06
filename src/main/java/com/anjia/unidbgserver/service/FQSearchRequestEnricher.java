package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQApiProperties;
import com.anjia.unidbgserver.dto.FQSearchRequest;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.UUID;

@Service
public class FQSearchRequestEnricher {

    @Resource
    private FQApiProperties fqApiProperties;

    public void enrich(FQSearchRequest request) {
        if (request == null) {
            return;
        }

        if (request.getOffset() == null) request.setOffset(0);
        if (request.getCount() == null) request.setCount(20);
        if (request.getTabType() == null) request.setTabType(1);
        if (request.getBookshelfSearchPlan() == null) request.setBookshelfSearchPlan(4);
        if (request.getFromRs() == null) request.setFromRs(false);
        if (request.getUserIsLogin() == null) request.setUserIsLogin(0);
        if (request.getBookstoreTab() == null) request.setBookstoreTab(2);
        if (request.getSearchSource() == null) request.setSearchSource(1);
        if (request.getUseLynx() == null) request.setUseLynx(false);
        if (request.getUseCorrect() == null) request.setUseCorrect(true);
        if (request.getIsFirstEnterSearch() == null) request.setIsFirstEnterSearch(true);
        if (request.getClientAbInfo() == null) request.setClientAbInfo("{}");
        if (request.getClickedContent() == null) request.setClickedContent("search_history");
        if (request.getSearchSourceId() == null) request.setSearchSourceId("his###");
        if (request.getTabName() == null) request.setTabName("store");

        if (request.getLastSearchPageInterval() == null) request.setLastSearchPageInterval(0);
        if (request.getLineWordsNum() == null) request.setLineWordsNum(0);
        if (request.getLastConsumeInterval() == null) request.setLastConsumeInterval(0);
        if (request.getPadColumnCover() == null) request.setPadColumnCover(0);
        if (request.getKlinkEgdi() == null) request.setKlinkEgdi("");

        if (request.getNormalSessionId() == null || request.getNormalSessionId().trim().isEmpty()) {
            request.setNormalSessionId(UUID.randomUUID().toString());
        }
        if (request.getColdStartSessionId() == null || request.getColdStartSessionId().trim().isEmpty()) {
            request.setColdStartSessionId(UUID.randomUUID().toString());
        }

        if (request.getCharging() == null) request.setCharging(1);
        if (request.getScreenBrightness() == null) request.setScreenBrightness(72);
        if (request.getBatteryPct() == null) request.setBatteryPct(78);
        if (request.getDownSpeed() == null) request.setDownSpeed(89121);
        if (request.getSysDarkMode() == null) request.setSysDarkMode(0);
        if (request.getAppDarkMode() == null) request.setAppDarkMode(0);
        if (request.getFontScale() == null) request.setFontScale(100);
        if (request.getIsAndroidPadScreen() == null) request.setIsAndroidPadScreen(0);
        if (request.getNetworkType() == null) request.setNetworkType(4);
        if (request.getCurrentVolume() == null) request.setCurrentVolume(75);
        if (request.getNeedPersonalRecommend() == null) request.setNeedPersonalRecommend(0);
        if (request.getPlayerSoLoad() == null) request.setPlayerSoLoad(1);
        if (request.getGender() == null) request.setGender(1);
        if (request.getComplianceStatus() == null) request.setComplianceStatus(0);
        if (request.getHarStatus() == null) request.setHarStatus(0);

        FQApiProperties.Device device = fqApiProperties != null ? fqApiProperties.getDevice() : null;
        if (request.getRomVersion() == null) {
            request.setRomVersion(device != null ? device.getRomVersion() : "");
        }
        if (request.getCdid() == null) {
            request.setCdid(device != null ? device.getCdid() : "");
        }
    }
}
