package com.mengying.fqnovel.utils;

import com.mengying.fqnovel.config.FQApiProperties;
import com.mengying.fqnovel.dto.FQDirectoryRequest;
import com.mengying.fqnovel.dto.FQSearchRequest;
import com.mengying.fqnovel.dto.FqVariable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * FQ API 通用工具类
 * 用于构建API请求参数和请求头
 */
@Component
public class FQApiUtils {

    private static final String HEADER_AUTHORIZATION = "authorization";
    private static final String HEADER_X_READING_REQUEST = "x-reading-request";
    private static final String AUTHORIZATION_BEARER = "Bearer";

    private final FQApiProperties fqApiProperties;

    public FQApiUtils(FQApiProperties fqApiProperties) {
        this.fqApiProperties = fqApiProperties;
    }

    /**
     * 构建通用API请求参数
     * 从FqVariable提取通用参数并构建Map
     *
     * @param var FQ变量配置
     * @return 参数映射
     */
    public Map<String, String> buildCommonApiParams(FqVariable var) {
        Map<String, String> params = new HashMap<>(32);

        // 基础设备参数
        params.put("iid", var.getInstallId());
        params.put("device_id", var.getDeviceId());
        params.put("ac", var.getAc());
        params.put("channel", var.getChannel());
        params.put("aid", var.getAid());
        params.put("app_name", var.getAppName());
        params.put("version_code", var.getVersionCode());
        params.put("version_name", var.getVersionName());
        params.put("device_platform", var.getDevicePlatform());
        params.put("os", var.getOs());
        params.put("ssmix", var.getSsmix());
        params.put("device_type", var.getDeviceType());
        params.put("device_brand", var.getDeviceBrand());
        params.put("language", var.getLanguage());
        params.put("os_api", var.getOsApi());
        params.put("os_version", var.getOsVersion());
        params.put("manifest_version_code", var.getManifestVersionCode());
        params.put("resolution", var.getResolution());
        params.put("dpi", var.getDpi());
        params.put("update_version_code", var.getUpdateVersionCode());
        params.put("_rticket", String.valueOf(System.currentTimeMillis())); // 使用当前时间戳作为_rticket
        params.put("host_abi", var.getHostAbi());
        params.put("dragon_device_type", var.getDragonDeviceType());
        params.put("pv_player", var.getPvPlayer());
        params.put("compliance_status", var.getComplianceStatus());
        params.put("need_personal_recommend", var.getNeedPersonalRecommend());
        params.put("player_so_load", var.getPlayerSoLoad());
        params.put("is_android_pad_screen", var.getIsAndroidPadScreen());
        params.put("rom_version", var.getRomVersion());
        params.put("cdid", var.getCdid());

        return params;
    }

    /**
     * 构建batchFull特定的API参数
     * 在通用参数基础上添加batchFull特定参数
     *
     * @param var FQ变量配置
     * @param itemIds 章节ID列表
     * @param bookId 书籍ID
     * @param download 是否下载模式
     * @return 参数映射
     */
    public Map<String, String> buildBatchFullParams(FqVariable var, String itemIds, String bookId, boolean download) {
        Map<String, String> params = buildCommonApiParams(var);

        // batchFull特定参数
        params.put("item_ids", itemIds);
        params.put("key_register_ts", var.getKeyRegisterTs());
        // bookId 是必填参数；若上层未传入则保持为空，避免误用固定 bookId 导致请求错书
        params.put("book_id", bookId);
        params.put("req_type", download ? "0" : "1");

        return params;
    }

    /**
     * 构建通用请求头
     * 创建包含认证信息和标准头部的请求头映射
     *
     * @return 请求头映射
     */
    public Map<String, String> buildCommonHeaders() {
        return buildCommonHeaders(System.currentTimeMillis());
    }

    /**
     * 构建通用请求头（指定时间戳）
     * 创建包含认证信息和标准头部的请求头映射
     *
     * @param currentTime 当前时间戳
     * @return 请求头映射
     */
    public Map<String, String> buildCommonHeaders(long currentTime) {
        // 注意：签名算法对 header key 的大小写/顺序可能敏感；这里尽量对齐抓包/测试样例（小写 + 固定顺序）
        Map<String, String> headers = new LinkedHashMap<>(16);
        FQApiProperties.RuntimeProfile runtimeProfile = fqApiProperties.getRuntimeProfile();
        FQApiProperties.Device device = runtimeProfile == null ? null : runtimeProfile.getDeviceUnsafe();
        String installId = safeGet(device, FQApiProperties.Device::getInstallId);

        // 标准请求头（顺序参考抓包 header block）
        headers.put("accept", "application/json; charset=utf-8,application/x-protobuf");
        String cookie = CookieUtils.normalizeInstallId(safeGet(runtimeProfile, FQApiProperties.RuntimeProfile::getCookie), installId);
        putIfNotNull(headers, "cookie", cookie);
        String userAgent = safeGet(runtimeProfile, FQApiProperties.RuntimeProfile::getUserAgent);
        putIfNotNull(headers, "user-agent", userAgent);
        headers.put("accept-encoding", "gzip");
        headers.put("x-xs-from-web", "0");
        headers.put("x-vc-bdturing-sdk-version", "3.7.2.cn");
        headers.put("x-reading-request", currentTime + "-" + ThreadLocalRandom.current().nextInt(2_000_000_000));
        headers.put("sdk-version", "2");
        headers.put("x-tt-store-region-src", "did");
        headers.put("x-tt-store-region", "cn-zj");
        headers.put("lc", "101");
        headers.put("x-ss-req-ticket", String.valueOf(currentTime));
        headers.put("passport-sdk-version", "50564");
        String aid = safeGet(device, FQApiProperties.Device::getAid);
        putIfNotNull(headers, "x-ss-dp", aid);

        return headers;
    }

    /**
     * 构建搜索接口请求头。
     * 某些搜索请求需要携带 authorization: Bearer，并尽量保持 header 顺序稳定。
     */
    public Map<String, String> buildSearchHeaders() {
        Map<String, String> base = buildCommonHeaders();
        if (base.containsKey(HEADER_AUTHORIZATION)) {
            return base;
        }

        Map<String, String> ordered = new LinkedHashMap<>(base.size() + 1);
        for (Map.Entry<String, String> entry : base.entrySet()) {
            ordered.put(entry.getKey(), entry.getValue());
            if (HEADER_X_READING_REQUEST.equalsIgnoreCase(entry.getKey())) {
                ordered.put(HEADER_AUTHORIZATION, AUTHORIZATION_BEARER);
            }
        }
        if (!ordered.containsKey(HEADER_AUTHORIZATION)) {
            ordered.put(HEADER_AUTHORIZATION, AUTHORIZATION_BEARER);
        }
        return ordered;
    }

    /**
     * 构建RegisterKey请求头
     * 在通用请求头基础上添加RegisterKey特定头部
     *
     * @return 请求头映射
     */
    public Map<String, String> buildRegisterKeyHeaders() {
        return buildRegisterKeyHeaders(System.currentTimeMillis());
    }

    /**
     * 构建RegisterKey请求头（指定时间戳）
     * 在通用请求头基础上添加RegisterKey特定头部
     *
     * @param currentTime 当前时间戳
     * @return 请求头映射
     */
    public Map<String, String> buildRegisterKeyHeaders(long currentTime) {
        Map<String, String> headers = buildCommonHeaders(currentTime);

        // RegisterKey特定头部
        headers.put("content-type", "application/json");

        return headers;
    }

    /**
     * 构建带参数的URL
     * 只对白名单参数进行编码，其他参数直接拼接，避免二次编码问题
     *
     * @param baseUrl 基础URL
     * @param params 参数映射
     * @return 完整URL
     */
    private static final Set<String> ENCODE_WHITELIST = Set.of(
        "query", "client_ab_info", "search_source_id", "search_id", "device_type", "resolution", "rom_version"
    );

    public String buildUrlWithParams(String baseUrl, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return baseUrl;
        }

        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        urlBuilder.append("?");

        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                urlBuilder.append("&");
            }
            String key = entry.getKey();
            String value = entry.getValue();
            urlBuilder.append(key).append("=");

            if (ENCODE_WHITELIST.contains(key)) {
                urlBuilder.append(encodeIfNeeded(value));
            } else {
                urlBuilder.append(Texts.nullToEmpty(value));
            }
            first = false;
        }

        return urlBuilder.toString();
    }

    /**
     * 对参数值进行编码（已编码过的不再编码）
     */
    private String encodeIfNeeded(String value) {
        if (value == null) {
            return "";
        }
        try {
            // 先尝试解码，如果解码后与原值不同，说明已编码
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (!decoded.equals(value)) {
                return value; // 已编码，直接返回
            }
        } catch (IllegalArgumentException ignored) {
            // 解码失败，按未编码处理并继续尝试编码
        }
        try {
            // 未编码，进行编码
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return value;
        }
    }

    public Map<String, String> buildSearchParams(FqVariable var, FQSearchRequest searchRequest) {
        Map<String, String> params = buildCommonApiParams(var);

        // 搜索特定参数，全部补齐，部分可动态生成
        params.put("bookshelf_search_plan", String.valueOf(searchRequest.getBookshelfSearchPlan()));
        params.put("offset", String.valueOf(searchRequest.getOffset()));
        params.put("from_rs", bool01(searchRequest.getFromRs()));
        params.put("user_is_login", String.valueOf(searchRequest.getUserIsLogin()));
        params.put("bookstore_tab", String.valueOf(searchRequest.getBookstoreTab()));
        params.put("query", searchRequest.getQuery()); // 中文需编码，建议提前处理
        params.put("count", String.valueOf(searchRequest.getCount()));
        params.put("search_source", String.valueOf(searchRequest.getSearchSource()));
        params.put("clicked_content", searchRequest.getClickedContent());
        params.put("search_source_id", searchRequest.getSearchSourceId());
        params.put("use_lynx", bool01(searchRequest.getUseLynx()));
        params.put("use_correct", bool01(searchRequest.getUseCorrect()));
        params.put("last_search_page_interval", String.valueOf(searchRequest.getLastSearchPageInterval()));
        params.put("line_words_num", String.valueOf(searchRequest.getLineWordsNum()));
        params.put("tab_name", searchRequest.getTabName());
        params.put("last_consume_interval", String.valueOf(searchRequest.getLastConsumeInterval()));
        params.put("pad_column_cover", String.valueOf(searchRequest.getPadColumnCover()));
        params.put("is_first_enter_search", bool01(searchRequest.getIsFirstEnterSearch()));

        // 添加search_id参数（如果存在）
        putTrimmedIfHasText(params, "search_id", searchRequest.getSearchId());

        // 添加passback参数（与offset相同）
        Integer passback = Objects.requireNonNullElse(searchRequest.getPassback(), searchRequest.getOffset());
        params.put("passback", String.valueOf(passback));

        // 添加tab_type参数
        putIfNotNull(params, "tab_type", searchRequest.getTabType());

        // 只在is_first_enter_search为true时添加client_ab_info
        if (Boolean.TRUE.equals(searchRequest.getIsFirstEnterSearch())) {
            params.put("client_ab_info", searchRequest.getClientAbInfo()); // JSON需编码
        }

        params.put("normal_session_id", searchRequest.getNormalSessionId());
        params.put("cold_start_session_id", searchRequest.getColdStartSessionId());
        params.put("charging", String.valueOf(searchRequest.getCharging()));
        params.put("screen_brightness", String.valueOf(searchRequest.getScreenBrightness()));
        params.put("battery_pct", String.valueOf(searchRequest.getBatteryPct()));
        params.put("down_speed", String.valueOf(searchRequest.getDownSpeed()));
        params.put("sys_dark_mode", String.valueOf(searchRequest.getSysDarkMode()));
        params.put("app_dark_mode", String.valueOf(searchRequest.getAppDarkMode()));
        params.put("font_scale", String.valueOf(searchRequest.getFontScale()));
        params.put("is_android_pad_screen", String.valueOf(searchRequest.getIsAndroidPadScreen()));
        params.put("network_type", String.valueOf(searchRequest.getNetworkType()));
        params.put("current_volume", String.valueOf(searchRequest.getCurrentVolume()));
        return params;
    }

    private static String bool01(Boolean value) {
        return Boolean.TRUE.equals(value) ? "1" : "0";
    }

    /**
     * 构建目录API参数
     * 在通用参数基础上添加目录特定参数
     *
     * @param var FQ变量配置
     * @param directoryRequest 目录请求参数
     * @return 参数映射
     */
    public Map<String, String> buildDirectoryParams(FqVariable var, FQDirectoryRequest directoryRequest) {
        Map<String, String> params = buildCommonApiParams(var);

        // 目录特定参数
        if (directoryRequest == null) {
            params.put("book_type", "0");
            params.put("book_id", "");
            params.put("need_version", String.valueOf(Boolean.TRUE));
            return params;
        }

        Integer bookType = directoryRequest.getBookType();
        Boolean needVersion = directoryRequest.getNeedVersion();
        boolean minimalResponse = Boolean.TRUE.equals(directoryRequest.getMinimalResponse());
        String bookId = directoryRequest.getBookId();
        boolean finalNeedVersion = minimalResponse
            ? false
            : Objects.requireNonNullElse(needVersion, Boolean.TRUE);

        params.put("book_type", String.valueOf(intOrDefault(bookType, 0)));
        params.put("book_id", Texts.nullToEmpty(bookId));
        params.put("need_version", String.valueOf(finalNeedVersion));

        // 可选MD5参数
        putTrimmedIfHasText(params, "item_data_list_md5", directoryRequest.getItemDataListMd5());
        putTrimmedIfHasText(params, "catalog_data_md5", directoryRequest.getCatalogDataMd5());
        putTrimmedIfHasText(params, "book_info_md5", directoryRequest.getBookInfoMd5());

        return params;
    }

    /**
     * 获取API基础URL
     *
     * @return API基础URL
     */
    public String getBaseUrl() {
        return fqApiProperties.getBaseUrl();
    }

    /**
     * 搜索/目录相关接口要求使用 c 域名，统一在此处转换，避免业务层散落 replace 逻辑。
     */
    public String getSearchApiBaseUrl() {
        return normalizeSearchApiBaseUrl(fqApiProperties.getBaseUrl());
    }

    private static String normalizeSearchApiBaseUrl(String baseUrl) {
        if (!Texts.hasText(baseUrl)) {
            return "";
        }
        return baseUrl.replace("api5-normal-sinfonlineb", "api5-normal-sinfonlinec");
    }

    private static int intOrDefault(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }

    private static void putTrimmedIfHasText(Map<String, String> params, String key, String value) {
        String trimmed = Texts.trimToNull(value);
        if (trimmed != null) {
            params.put(key, trimmed);
        }
    }

    private static void putIfNotNull(Map<String, String> params, String key, Object value) {
        if (value != null) {
            params.put(key, String.valueOf(value));
        }
    }

    private static <T, R> R safeGet(T source, Function<T, R> getter) {
        return source == null ? null : getter.apply(source);
    }
}
