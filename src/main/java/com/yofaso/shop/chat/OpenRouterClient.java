package com.yofaso.shop.chat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Multi-provider LLM router (Groq / Google Gemini AI Studio / NVIDIA NIM / OpenRouter).
 * - Tuyen Chatbot (text): LLM_PROVIDER_ORDER (mac dinh: groq,gemini,nvidia,openrouter).
 * - Tuyen Vision / AI Stylist VTON: LLM_VISION_PROVIDER_ORDER (mac dinh: gemini,nvidia,openrouter),
 *   tu dong bo qua model text-only (nhu Groq text va nvidia/nemotron-3.5-lightning-30b-a3b) khi co anh.
 * - Chu dong chuyen model/provider truoc khi can quota (header Groq, RPM/RPD Gemini & NVIDIA NIM,
 *   /api/v1/key OpenRouter) de khach hang khong bao gio bi ngat cau tra loi.
 */
@Service
public class OpenRouterClient {
  private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);

  public static final String PROVIDER_GROQ = "groq";
  public static final String PROVIDER_GEMINI = "gemini";
  public static final String PROVIDER_NVIDIA = "nvidia";
  public static final String PROVIDER_OPENROUTER = "openrouter";

  public static final String DEFAULT_PROVIDER_ORDER = "groq,gemini,nvidia,openrouter";
  public static final String DEFAULT_VISION_PROVIDER_ORDER = "gemini,nvidia,openrouter";

  public static final String DEFAULT_GROQ_BASE_URL = "https://api.groq.com/openai/v1/chat/completions";
  public static final String DEFAULT_GROQ_MODELS =
      "openai/gpt-oss-120b,qwen/qwen3.8-27b,openai/gpt-oss-20b,allam-2-7b";

  public static final String DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models";
  public static final String DEFAULT_GEMINI_MODELS =
      "gemini-3.5-flash-lite,gemini-3.1-flash-lite-preview,gemini-3.8-flash,gemini-3.6-flash,"
          + "gemini-3-flash-preview,gemini-2.5-flash,gemini-2.5-flash-lite,gemini-4-hydrogen,"
          + "gemma-4-31b-it,gemma-4-26b-a4b-it";

  public static final String DEFAULT_NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1/chat/completions";
  public static final String DEFAULT_NVIDIA_MODELS =
      "nvidia/nemotron-3.5-lightning-30b-a3b,z-ai/glm-5.3-flash,minimaxai/minimax-m3.1-flash,"
          + "mistralai/mistral-large-4-1t-a52b,stepfun-ai/step-5-preview";

  public static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1/chat/completions";
  public static final String DEFAULT_OPENROUTER_MODELS =
      "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free,nvidia/nemotron-3.5-super-vl:free,"
          + "nvidia/nemotron-3.5-lightning:free,nvidia/nemotron-3-ultra-550b-a55b:free,"
          + "google/gemma-4-31b-it:free,google/gemma-4-26b-a4b-it:free";

  private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|h|m|s)");
  private static final Pattern DATA_URL_PATTERN = Pattern.compile("^data:([^;]+);base64,(.+)$", Pattern.DOTALL);

  public record CompletionResult(String content, String modelUsed, boolean usedRemote) {}

  private record ProviderConfig(
      String name,
      String apiKey,
      String baseUrl,
      List<String> models,
      boolean nativeGemini) {
    boolean configured() {
      return apiKey != null && !apiKey.isBlank()
          && !apiKey.startsWith("CHANGE") && !apiKey.equalsIgnoreCase("hehe")
          && !models.isEmpty();
    }
  }

  private record ModelLimits(int rpm, int rpd, int minRemainingRpd) {}

  private static final class LocalQuotaBucket {
    final Deque<Long> minuteTimestamps = new ArrayDeque<>();
    LocalDate dayUtc = LocalDate.now(ZoneOffset.UTC);
    int dayCount = 0;

    synchronized boolean isNearLimit(ModelLimits limits, long nowMs) {
      rollDay(nowMs);
      pruneMinute(nowMs);
      if (limits.rpm() > 0 && minuteTimestamps.size() >= Math.max(1, limits.rpm() - 1)) {
        return true;
      }
      if (limits.rpd() > 0 && (limits.rpd() - dayCount) <= limits.minRemainingRpd()) {
        return true;
      }
      return false;
    }

    synchronized void recordRequest(long nowMs) {
      rollDay(nowMs);
      pruneMinute(nowMs);
      minuteTimestamps.addLast(nowMs);
      dayCount++;
    }

    private void rollDay(long nowMs) {
      LocalDate today = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate();
      if (!today.equals(dayUtc)) {
        dayUtc = today;
        dayCount = 0;
      }
    }

    private void pruneMinute(long nowMs) {
      long cutoff = nowMs - 60_000L;
      while (!minuteTimestamps.isEmpty() && minuteTimestamps.peekFirst() < cutoff) {
        minuteTimestamps.removeFirst();
      }
    }
  }

  private final List<ProviderConfig> providers;
  private final List<ProviderConfig> visionProviders;
  private final double temperature;
  private final int maxTokens;
  private final Duration timeout;
  private final String siteUrl;
  private final String siteName;
  private final int groqMinRemainingRpd;
  private final int geminiMinRemainingRpd;
  private final int nvidiaMaxRpm;
  private final int openRouterMinRemainingRpd;
  private final JsonMapper json;
  private final HttpClient http;

  /** Cooldown theo tung model ("provider:model") de tranh goi lai model sap can/bi 429/404. */
  private final ConcurrentHashMap<String, Long> modelCooldownUntilMs = new ConcurrentHashMap<>();
  /** Cooldown toan provider (VD: OpenRouter het free_model_daily_requests). */
  private final ConcurrentHashMap<String, Long> providerCooldownUntilMs = new ConcurrentHashMap<>();
  /** Bo dem RPM/RPD cuc bo cho Gemini & NVIDIA NIM. */
  private final ConcurrentHashMap<String, LocalQuotaBucket> localQuotas = new ConcurrentHashMap<>();
  /** Cache ket qua /api/v1/key cua OpenRouter. */
  private volatile long openRouterQuotaCheckedAtMs = 0L;
  private volatile int openRouterRemainingDaily = Integer.MAX_VALUE;

  @Autowired
  public OpenRouterClient(
      @Value("${app.llm.provider-order:" + DEFAULT_PROVIDER_ORDER + "}") String providerOrderCsv,
      @Value("${app.llm.vision-provider-order:" + DEFAULT_VISION_PROVIDER_ORDER + "}") String visionOrderCsv,
      @Value("${app.groq.api-key:}") String groqApiKey,
      @Value("${app.groq.base-url:" + DEFAULT_GROQ_BASE_URL + "}") String groqBaseUrl,
      @Value("${app.groq.models:" + DEFAULT_GROQ_MODELS + "}") String groqModelsCsv,
      @Value("${app.groq.min-remaining-rpd:5}") int groqMinRemainingRpd,
      @Value("${app.gemini.api-key:}") String geminiApiKey,
      @Value("${app.gemini.base-url:" + DEFAULT_GEMINI_BASE_URL + "}") String geminiBaseUrl,
      @Value("${app.gemini.models:" + DEFAULT_GEMINI_MODELS + "}") String geminiModelsCsv,
      @Value("${app.gemini.min-remaining-rpd:2}") int geminiMinRemainingRpd,
      @Value("${app.nvidia.api-key:}") String nvidiaApiKey,
      @Value("${app.nvidia.base-url:" + DEFAULT_NVIDIA_BASE_URL + "}") String nvidiaBaseUrl,
      @Value("${app.nvidia.models:" + DEFAULT_NVIDIA_MODELS + "}") String nvidiaModelsCsv,
      @Value("${app.nvidia.max-rpm:40}") int nvidiaMaxRpm,
      @Value("${app.openrouter.api-key:}") String openRouterApiKey,
      @Value("${app.openrouter.base-url:" + DEFAULT_OPENROUTER_BASE_URL + "}") String openRouterBaseUrl,
      @Value("${app.openrouter.models:" + DEFAULT_OPENROUTER_MODELS + "}") String openRouterModelsCsv,
      @Value("${app.openrouter.min-remaining-rpd:2}") int openRouterMinRemainingRpd,
      @Value("${app.openrouter.temperature:0.2}") double temperature,
      @Value("${app.openrouter.max-tokens:1500}") int maxTokens,
      @Value("${app.openrouter.timeout-seconds:25}") int timeoutSeconds,
      @Value("${app.openrouter.site-url:http://localhost:3000}") String siteUrl,
      @Value("${app.openrouter.site-name:YoFaSo Shop}") String siteName,
      JsonMapper json) {
    this.temperature = Math.max(0.0, Math.min(1.0, temperature));
    this.maxTokens = Math.max(120, Math.min(4096, maxTokens));
    this.timeout = Duration.ofSeconds(Math.max(5, Math.min(60, timeoutSeconds)));
    this.siteUrl = siteUrl == null ? "" : siteUrl.trim();
    this.siteName = siteName == null ? "YoFaSo Shop" : siteName.trim();
    this.groqMinRemainingRpd = Math.max(1, groqMinRemainingRpd);
    this.geminiMinRemainingRpd = Math.max(1, geminiMinRemainingRpd);
    this.nvidiaMaxRpm = Math.max(5, nvidiaMaxRpm);
    this.openRouterMinRemainingRpd = Math.max(1, openRouterMinRemainingRpd);
    this.json = json;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    var byName = new LinkedHashMap<String, ProviderConfig>();
    byName.put(PROVIDER_GROQ, new ProviderConfig(
        PROVIDER_GROQ,
        cleanStr(groqApiKey),
        normalizeChatUrl(groqBaseUrl, DEFAULT_GROQ_BASE_URL),
        parseCsv(groqModelsCsv),
        false));
    byName.put(PROVIDER_GEMINI, new ProviderConfig(
        PROVIDER_GEMINI,
        cleanStr(geminiApiKey),
        blankDefault(geminiBaseUrl, DEFAULT_GEMINI_BASE_URL),
        parseCsv(geminiModelsCsv),
        true));
    byName.put(PROVIDER_NVIDIA, new ProviderConfig(
        PROVIDER_NVIDIA,
        cleanStr(nvidiaApiKey),
        normalizeChatUrl(nvidiaBaseUrl, DEFAULT_NVIDIA_BASE_URL),
        parseCsv(nvidiaModelsCsv),
        false));
    byName.put(PROVIDER_OPENROUTER, new ProviderConfig(
        PROVIDER_OPENROUTER,
        cleanStr(openRouterApiKey),
        normalizeChatUrl(openRouterBaseUrl, DEFAULT_OPENROUTER_BASE_URL),
        parseCsv(openRouterModelsCsv),
        false));

    this.providers = orderProviders(providerOrderCsv, byName, true);
    this.visionProviders = orderProviders(visionOrderCsv, byName, false);
  }

  /** Constructor tuong thich nguoc cho test 3 provider (Groq + Gemini + OpenRouter). */
  public OpenRouterClient(
      String providerOrderCsv,
      String groqApiKey, String groqBaseUrl, String groqModelsCsv, int groqMinRemainingRpd,
      String geminiApiKey, String geminiBaseUrl, String geminiModelsCsv, int geminiMinRemainingRpd,
      String openRouterApiKey, String openRouterBaseUrl, String openRouterModelsCsv, int openRouterMinRemainingRpd,
      double temperature, int maxTokens, int timeoutSeconds,
      String siteUrl, String siteName, JsonMapper json) {
    this(
        providerOrderCsv, DEFAULT_VISION_PROVIDER_ORDER,
        groqApiKey, groqBaseUrl, groqModelsCsv, groqMinRemainingRpd,
        geminiApiKey, geminiBaseUrl, geminiModelsCsv, geminiMinRemainingRpd,
        "", DEFAULT_NVIDIA_BASE_URL, "", 40,
        openRouterApiKey, openRouterBaseUrl, openRouterModelsCsv, openRouterMinRemainingRpd,
        temperature, maxTokens, timeoutSeconds, siteUrl, siteName, json);
  }

  /** Constructor tuong thich nguoc cho test chi dung OpenRouter. */
  public OpenRouterClient(
      String openRouterApiKey,
      String openRouterBaseUrl,
      String openRouterModelsCsv,
      double temperature,
      int maxTokens,
      int timeoutSeconds,
      String siteUrl,
      String siteName,
      JsonMapper json) {
    this(
        PROVIDER_OPENROUTER, DEFAULT_VISION_PROVIDER_ORDER,
        "", DEFAULT_GROQ_BASE_URL, "", 5,
        "", DEFAULT_GEMINI_BASE_URL, "", 2,
        "", DEFAULT_NVIDIA_BASE_URL, "", 40,
        openRouterApiKey, openRouterBaseUrl, openRouterModelsCsv, 2,
        temperature, maxTokens, timeoutSeconds, siteUrl, siteName, json);
  }

  private static List<ProviderConfig> orderProviders(
      String orderCsv, Map<String, ProviderConfig> byName, boolean appendRemaining) {
    var ordered = new ArrayList<ProviderConfig>();
    for (String pName : parseCsv(orderCsv)) {
      ProviderConfig cfg = byName.get(pName.toLowerCase(Locale.ROOT));
      if (cfg != null && !ordered.contains(cfg)) {
        ordered.add(cfg);
      }
    }
    if (appendRemaining) {
      for (ProviderConfig cfg : byName.values()) {
        if (!ordered.contains(cfg)) {
          ordered.add(cfg);
        }
      }
    }
    return List.copyOf(ordered);
  }

  public boolean isConfigured() {
    return providers.stream().anyMatch(ProviderConfig::configured);
  }

  public List<String> configuredProviders() {
    return providers.stream()
        .filter(ProviderConfig::configured)
        .map(ProviderConfig::name)
        .toList();
  }

  public List<String> configuredVisionProviders() {
    return visionProviders.stream()
        .filter(ProviderConfig::configured)
        .filter(p -> p.models().stream().anyMatch(m -> isVisionCapable(p.name(), m)))
        .map(ProviderConfig::name)
        .toList();
  }

  public List<String> configuredModels() {
    var all = new ArrayList<String>();
    for (ProviderConfig p : providers) {
      if (!p.configured()) continue;
      for (String m : p.models()) {
        all.add(p.name() + ":" + m);
      }
    }
    return List.copyOf(all);
  }

  /**
   * - Khi KHONG co anh: di theo LLM_PROVIDER_ORDER (mac dinh groq -> gemini -> nvidia -> openrouter).
   * - Khi CO anh (chat kem anh / AI Stylist VTON): uu tien tuyen Vision LLM_VISION_PROVIDER_ORDER
   *   (mac dinh gemini -> nvidia [4 model vision] -> openrouter [model VL/Omni]),
   *   neu tat ca model Vision deu ban/chua co key se fallback muot ve tuyen text-only.
   */
  public Optional<CompletionResult> complete(List<Map<String, Object>> messages, String imageDataUrl) {
    if (!isConfigured()) {
      return Optional.empty();
    }
    long nowMs = System.currentTimeMillis();
    boolean hasImage = imageDataUrl != null && !imageDataUrl.isBlank();

    if (hasImage) {
      Optional<CompletionResult> vision = tryProviders(visionProviders, messages, imageDataUrl, nowMs, false, true);
      if (vision.isPresent()) {
        return vision;
      }
      log.info("No vision model available for image request -> falling back smoothly to text-only route.");
    }

    Optional<CompletionResult> primary = tryProviders(providers, messages, null, nowMs, false, false);
    if (primary.isPresent()) {
      return primary;
    }

    boolean anySkippedBySoftCooldown = providers.stream()
        .filter(ProviderConfig::configured)
        .anyMatch(p -> isProviderCoolingDown(p.name(), nowMs)
            || p.models().stream().anyMatch(m -> isModelCoolingDown(p.name(), m, nowMs)));
    if (anySkippedBySoftCooldown) {
      log.warn("All LLM providers/models in soft cooldown -> attempting final fallback pass before local reply.");
      if (hasImage) {
        Optional<CompletionResult> emergencyVision = tryProviders(visionProviders, messages, imageDataUrl, nowMs, true, true);
        if (emergencyVision.isPresent()) return emergencyVision;
      }
      Optional<CompletionResult> emergency = tryProviders(providers, messages, null, nowMs, true, false);
      if (emergency.isPresent()) {
        return emergency;
      }
    }

    log.warn("All configured LLM providers ({}) unavailable; falling back to local reply.", configuredProviders());
    return Optional.empty();
  }

  private Optional<CompletionResult> tryProviders(
      List<ProviderConfig> route,
      List<Map<String, Object>> messages,
      String imageDataUrl,
      long nowMs,
      boolean ignoreSoftQuota,
      boolean visionOnly) {
    boolean hasImage = imageDataUrl != null && !imageDataUrl.isBlank();
    List<Map<String, Object>> openAiWithImage = hasImage ? attachImageToLastUserMessage(messages, imageDataUrl) : messages;

    for (ProviderConfig provider : route) {
      if (!provider.configured()) continue;
      if (!ignoreSoftQuota && isProviderCoolingDown(provider.name(), nowMs)) {
        continue;
      }
      if (!ignoreSoftQuota && PROVIDER_NVIDIA.equals(provider.name())
          && isNvidiaNearRpmLimit(nowMs)) {
        log.info("NVIDIA NIM near local {} RPM safety limit -> pre-switching provider.", nvidiaMaxRpm);
        continue;
      }
      if (!ignoreSoftQuota && PROVIDER_OPENROUTER.equals(provider.name())
          && isOpenRouterNearDailyLimit(provider, nowMs)) {
        continue;
      }

      for (String model : provider.models()) {
        if (visionOnly && !isVisionCapable(provider.name(), model)) {
          continue;
        }
        if (!ignoreSoftQuota && isModelCoolingDown(provider.name(), model, nowMs)) {
          continue;
        }
        if (!ignoreSoftQuota && PROVIDER_GEMINI.equals(provider.name())
            && isGeminiModelNearLocalLimit(model, nowMs)) {
          log.info("Gemini model {} near local RPM/RPD safety threshold -> pre-switching to next model.", model);
          continue;
        }

        Optional<String> reply;
        if (provider.nativeGemini()) {
          reply = callGeminiNative(provider, model, messages, hasImage ? imageDataUrl : null);
          if (reply.isEmpty() && hasImage && !visionOnly) {
            reply = callGeminiNative(provider, model, messages, null);
          }
        } else {
          List<Map<String, Object>> payload = (hasImage && isVisionCapable(provider.name(), model))
              ? openAiWithImage
              : messages;
          reply = callOpenAiCompatible(provider, model, payload);
          if (reply.isEmpty() && hasImage && !visionOnly && payload == openAiWithImage) {
            reply = callOpenAiCompatible(provider, model, messages);
          }
        }

        if (reply.isPresent()) {
          String tag = provider.name() + "/" + model;
          return Optional.of(new CompletionResult(reply.get(), tag, true));
        }
      }
    }
    return Optional.empty();
  }

  /** Xac dinh model co ho tro doc anh (Vision/Multimodal) hay chi text-only. */
  public static boolean isVisionCapable(String providerName, String model) {
    if (providerName == null || model == null) return false;
    String p = providerName.toLowerCase(Locale.ROOT);
    String m = model.toLowerCase(Locale.ROOT);
    return switch (p) {
      case PROVIDER_GEMINI -> true;
      case PROVIDER_NVIDIA -> !m.contains("nemotron-3.5-lightning") && !m.contains("embed") && !m.contains("rerank");
      case PROVIDER_OPENROUTER -> m.contains("omni") || m.contains("-vl") || m.contains("gemma-4")
          || m.contains("vision") || m.contains("gemini");
      case PROVIDER_GROQ -> m.contains("vision") || m.contains("llama-4-scout") || m.contains("llama-4-maverick");
      default -> false;
    };
  }

  @SuppressWarnings("unchecked")
  private Optional<String> callOpenAiCompatible(
      ProviderConfig provider, String model, List<Map<String, Object>> messages) {
    try {
      var body = new LinkedHashMap<String, Object>();
      body.put("model", model);
      body.put("messages", messages);
      body.put("temperature", temperature);
      body.put("max_tokens", maxTokens);

      var builder = HttpRequest.newBuilder(URI.create(provider.baseUrl()))
          .timeout(timeout)
          .header("Authorization", "Bearer " + provider.apiKey())
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .header("User-Agent", "YoFaSoShop/1.0")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      if (PROVIDER_OPENROUTER.equals(provider.name())) {
        builder.header("X-Title", siteName);
        if (!siteUrl.isBlank()) {
          builder.header("HTTP-Referer", siteUrl);
        }
      }

      HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();

      if (PROVIDER_GROQ.equals(provider.name())) {
        updateGroqQuotaFromHeaders(model, response.headers(), status);
      } else if (PROVIDER_NVIDIA.equals(provider.name()) && status >= 200 && status < 300) {
        localQuotas.computeIfAbsent(PROVIDER_NVIDIA, k -> new LocalQuotaBucket())
            .recordRequest(System.currentTimeMillis());
      } else if (PROVIDER_OPENROUTER.equals(provider.name()) && status >= 200 && status < 300) {
        if (openRouterRemainingDaily != Integer.MAX_VALUE && openRouterRemainingDaily > 0) {
          openRouterRemainingDaily--;
          if (openRouterRemainingDaily <= openRouterMinRemainingRpd) {
            markProviderCooldown(PROVIDER_OPENROUTER, Duration.ofMinutes(15).toMillis());
          }
        }
      }

      if (status < 200 || status >= 300) {
        handleHttpFailureCooldown(provider.name(), model, status, response.headers(), response.body());
        log.warn("{} model {} returned HTTP {} -> fallback to next model.", provider.name(), model, status);
        return Optional.empty();
      }

      Map<String, Object> parsed = json.readValue(response.body(), Map.class);
      Object choicesObj = parsed.get("choices");
      if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
        log.warn("{} model {} returned empty choices -> fallback.", provider.name(), model);
        return Optional.empty();
      }
      Object first = choices.getFirst();
      if (!(first instanceof Map<?, ?> firstMap)) return Optional.empty();
      Object messageObj = firstMap.get("message");
      if (!(messageObj instanceof Map<?, ?> msgMap)) return Optional.empty();
      String rawContent = extractTextContent(msgMap.get("content"));
      String cleaned = sanitizeReply(rawContent);
      if (cleaned.isBlank()) {
        log.warn("{} model {} returned blank content -> fallback.", provider.name(), model);
        return Optional.empty();
      }
      log.info("{} model {} succeeded ({} chars).", provider.name(), model, cleaned.length());
      return Optional.of(cleaned);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      log.warn("{} model {} connection/parse error ({}) -> fallback.", provider.name(), model, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Goi Google AI Studio REST API (/v1beta/models/{model}:generateContent).
   * Ho tro ca Gemini (native systemInstruction + multimodal inlineData) va Gemma 4 (fold system prompt).
   */
  @SuppressWarnings("unchecked")
  private Optional<String> callGeminiNative(
      ProviderConfig provider,
      String model,
      List<Map<String, Object>> messages,
      String imageDataUrl) {
    try {
      String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8);
      String base = provider.baseUrl().endsWith("/")
          ? provider.baseUrl().substring(0, provider.baseUrl().length() - 1)
          : provider.baseUrl();
      String endpoint = base + "/" + encodedModel + ":generateContent";

      Map<String, Object> body = buildGeminiPayload(model, messages, imageDataUrl);
      var req = HttpRequest.newBuilder(URI.create(endpoint))
          .timeout(timeout)
          .header("x-goog-api-key", provider.apiKey())
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
          .build();

      HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();

      if (status >= 200 && status < 300) {
        recordGeminiUsage(model);
      }

      if (status < 200 || status >= 300) {
        handleHttpFailureCooldown(provider.name(), model, status, response.headers(), response.body());
        log.warn("gemini model {} returned HTTP {} -> fallback to next model.", model, status);
        return Optional.empty();
      }

      Map<String, Object> parsed = json.readValue(response.body(), Map.class);
      Object candidatesObj = parsed.get("candidates");
      if (!(candidatesObj instanceof List<?> candidates) || candidates.isEmpty()) {
        log.warn("gemini model {} returned empty candidates -> fallback.", model);
        return Optional.empty();
      }
      Object firstCand = candidates.getFirst();
      if (!(firstCand instanceof Map<?, ?> candMap)) return Optional.empty();
      Object contentObj = candMap.get("content");
      if (!(contentObj instanceof Map<?, ?> contentMap)) return Optional.empty();
      Object partsObj = contentMap.get("parts");
      if (!(partsObj instanceof List<?> parts) || parts.isEmpty()) return Optional.empty();

      var sb = new StringBuilder();
      for (Object partObj : parts) {
        if (!(partObj instanceof Map<?, ?> partMap)) continue;
        if (Boolean.TRUE.equals(partMap.get("thought"))) continue;
        Object textObj = partMap.get("text");
        if (textObj instanceof String s) {
          sb.append(s);
        }
      }
      String cleaned = sanitizeReply(sb.toString());
      if (cleaned.isBlank()) {
        log.warn("gemini model {} returned blank content -> fallback.", model);
        return Optional.empty();
      }
      log.info("gemini model {} succeeded ({} chars).", model, cleaned.length());
      return Optional.of(cleaned);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      log.warn("gemini model {} connection/parse error ({}) -> fallback.", model, e.getMessage());
      return Optional.empty();
    }
  }

  Map<String, Object> buildGeminiPayload(
      String model, List<Map<String, Object>> messages, String imageDataUrl) {
    boolean isGemma = model.toLowerCase(Locale.ROOT).startsWith("gemma-");
    var systemParts = new StringBuilder();
    var contents = new ArrayList<Map<String, Object>>();

    for (Map<String, Object> msg : messages) {
      String role = Objects.toString(msg.get("role"), "user");
      String text = extractTextContent(msg.get("content"));
      if ("system".equals(role)) {
        if (!text.isBlank()) {
          if (!systemParts.isEmpty()) systemParts.append("\n\n");
          systemParts.append(text);
        }
        continue;
      }
      String geminiRole = "assistant".equals(role) ? "model" : "user";
      if (!contents.isEmpty() && geminiRole.equals(contents.getLast().get("role"))) {
        @SuppressWarnings("unchecked")
        var prevParts = (List<Map<String, Object>>) contents.getLast().get("parts");
        prevParts.add(new LinkedHashMap<>(Map.of("text", text)));
      } else {
        var parts = new ArrayList<Map<String, Object>>();
        parts.add(new LinkedHashMap<>(Map.of("text", text)));
        var turn = new LinkedHashMap<String, Object>();
        turn.put("role", geminiRole);
        turn.put("parts", parts);
        contents.add(turn);
      }
    }

    if (contents.isEmpty()) {
      var parts = new ArrayList<Map<String, Object>>();
      parts.add(new LinkedHashMap<>(Map.of("text", "Xin chào")));
      contents.add(new LinkedHashMap<>(Map.of("role", "user", "parts", parts)));
    }

    var body = new LinkedHashMap<String, Object>();
    String systemText = systemParts.toString().trim();
    if (!systemText.isEmpty()) {
      if (isGemma) {
        for (Map<String, Object> turn : contents) {
          if ("user".equals(turn.get("role"))) {
            @SuppressWarnings("unchecked")
            var parts = (List<Map<String, Object>>) turn.get("parts");
            String firstText = Objects.toString(parts.getFirst().get("text"), "");
            parts.getFirst().put("text", "[HƯỚNG DẪN HỆ THỐNG]\n" + systemText + "\n\n[TIN NHẮN KHÁCH HÀNG]\n" + firstText);
            break;
          }
        }
      } else {
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemText))));
      }
    }

    if (imageDataUrl != null && !imageDataUrl.isBlank()) {
      Matcher matcher = DATA_URL_PATTERN.matcher(imageDataUrl.trim());
      if (matcher.matches()) {
        String mimeType = matcher.group(1);
        String b64 = matcher.group(2);
        for (int i = contents.size() - 1; i >= 0; i--) {
          if ("user".equals(contents.get(i).get("role"))) {
            @SuppressWarnings("unchecked")
            var parts = (List<Map<String, Object>>) contents.get(i).get("parts");
            parts.add(Map.of("inlineData", Map.of("mimeType", mimeType, "data", b64)));
            break;
          }
        }
      }
    }

    body.put("contents", contents);
    var genConfig = new LinkedHashMap<String, Object>();
    genConfig.put("temperature", temperature);
    genConfig.put("maxOutputTokens", maxTokens);
    body.put("generationConfig", genConfig);
    return body;
  }

  void updateGroqQuotaFromHeaders(String model, HttpHeaders headers, int status) {
    if (headers == null) return;
    OptionalInt remReq = parseHeaderInt(headers, "x-ratelimit-remaining-requests");
    OptionalInt remTok = parseHeaderInt(headers, "x-ratelimit-remaining-tokens");
    long resetReqMs = parseResetDurationMs(headers.firstValue("x-ratelimit-reset-requests").orElse(null),
        Duration.ofMinutes(30).toMillis());
    long resetTokMs = parseResetDurationMs(headers.firstValue("x-ratelimit-reset-tokens").orElse(null),
        Duration.ofSeconds(60).toMillis());

    if (remReq.isPresent() && remReq.getAsInt() <= groqMinRemainingRpd) {
      long waitMs = Math.max(Duration.ofSeconds(30).toMillis(), Math.min(Duration.ofHours(24).toMillis(), resetReqMs));
      markModelCooldown(PROVIDER_GROQ, model, waitMs);
      log.info("Groq model {} remaining RPD={} (<= {}) -> pre-switching away for {}s.",
          model, remReq.getAsInt(), groqMinRemainingRpd, waitMs / 1000);
      return;
    }

    int tokenSafetyFloor = Math.max(800, maxTokens);
    if (remTok.isPresent() && remTok.getAsInt() < tokenSafetyFloor) {
      long waitMs = Math.max(Duration.ofSeconds(5).toMillis(), Math.min(Duration.ofMinutes(5).toMillis(), resetTokMs));
      markModelCooldown(PROVIDER_GROQ, model, waitMs);
      log.info("Groq model {} remaining TPM={} (< {}) -> temporary cooldown {}s.",
          model, remTok.getAsInt(), tokenSafetyFloor, waitMs / 1000);
    }
  }

  private void handleHttpFailureCooldown(
      String providerName, String model, int status, HttpHeaders headers, String errorBody) {
    if (status == 404) {
      markModelCooldown(providerName, model, Duration.ofHours(6).toMillis());
      return;
    }
    if (status == 401 || status == 403) {
      markProviderCooldown(providerName, Duration.ofMinutes(15).toMillis());
      return;
    }
    if (status == 402) {
      markProviderCooldown(providerName, Duration.ofHours(1).toMillis());
      return;
    }
    if (status == 429) {
      long retryMs = parseResetDurationMs(headers == null ? null : headers.firstValue("retry-after").orElse(null),
          Duration.ofSeconds(65).toMillis());
      String lower = errorBody == null ? "" : errorBody.toLowerCase(Locale.ROOT);
      if (lower.contains("per-day") || lower.contains("per day") || lower.contains("daily")) {
        retryMs = Math.max(retryMs, Duration.ofMinutes(30).toMillis());
      }
      markModelCooldown(providerName, model, Math.max(10_000L, Math.min(Duration.ofHours(6).toMillis(), retryMs)));
      return;
    }
    if (status >= 500) {
      markModelCooldown(providerName, model, Duration.ofSeconds(30).toMillis());
    }
  }

  @SuppressWarnings("unchecked")
  private boolean isOpenRouterNearDailyLimit(ProviderConfig provider, long nowMs) {
    if (!provider.baseUrl().contains("openrouter.ai")) {
      return false;
    }
    if (nowMs - openRouterQuotaCheckedAtMs > Duration.ofMinutes(5).toMillis()) {
      openRouterQuotaCheckedAtMs = nowMs;
      try {
        var req = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/key"))
            .timeout(Duration.ofSeconds(4))
            .header("Authorization", "Bearer " + provider.apiKey())
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
          Map<String, Object> root = json.readValue(resp.body(), Map.class);
          if (root.get("data") instanceof Map<?, ?> data
              && data.get("free_model_daily_requests") instanceof Map<?, ?> daily
              && daily.get("remaining") instanceof Number rem) {
            openRouterRemainingDaily = rem.intValue();
          }
        }
      } catch (Exception ignored) {
      }
    }
    if (openRouterRemainingDaily <= openRouterMinRemainingRpd) {
      log.info("OpenRouter remaining daily free requests={} (<= {}) -> pre-switching provider.",
          openRouterRemainingDaily, openRouterMinRemainingRpd);
      return true;
    }
    return false;
  }

  private boolean isNvidiaNearRpmLimit(long nowMs) {
    LocalQuotaBucket bucket = localQuotas.get(PROVIDER_NVIDIA);
    return bucket != null && bucket.isNearLimit(new ModelLimits(nvidiaMaxRpm, 0, 0), nowMs);
  }

  private boolean isGeminiModelNearLocalLimit(String model, long nowMs) {
    ModelLimits limits = geminiModelLimits(model);
    LocalQuotaBucket bucket = localQuotas.get(modelKey(PROVIDER_GEMINI, model));
    return bucket != null && bucket.isNearLimit(limits, nowMs);
  }

  private void recordGeminiUsage(String model) {
    localQuotas.computeIfAbsent(modelKey(PROVIDER_GEMINI, model), k -> new LocalQuotaBucket())
        .recordRequest(System.currentTimeMillis());
  }

  private ModelLimits geminiModelLimits(String model) {
    String m = model.toLowerCase(Locale.ROOT);
    if (m.startsWith("gemma-4")) {
      return new ModelLimits(30, 14_400, Math.max(5, geminiMinRemainingRpd));
    }
    if (m.contains("3.5-flash-lite") || m.contains("3.1-flash-lite")) {
      return new ModelLimits(15, 500, Math.max(5, geminiMinRemainingRpd));
    }
    if (m.contains("2.5-flash-lite")) {
      return new ModelLimits(10, 20, geminiMinRemainingRpd);
    }
    return new ModelLimits(5, 20, geminiMinRemainingRpd);
  }

  private void markModelCooldown(String provider, String model, long durationMs) {
    modelCooldownUntilMs.put(modelKey(provider, model), System.currentTimeMillis() + durationMs);
  }

  private void markProviderCooldown(String provider, long durationMs) {
    providerCooldownUntilMs.put(provider, System.currentTimeMillis() + durationMs);
  }

  private boolean isModelCoolingDown(String provider, String model, long nowMs) {
    Long until = modelCooldownUntilMs.get(modelKey(provider, model));
    return until != null && until > nowMs;
  }

  private boolean isProviderCoolingDown(String provider, long nowMs) {
    Long until = providerCooldownUntilMs.get(provider);
    return until != null && until > nowMs;
  }

  private static String modelKey(String provider, String model) {
    return provider + ":" + model;
  }

  private static OptionalInt parseHeaderInt(HttpHeaders headers, String name) {
    Optional<String> raw = headers.firstValue(name);
    if (raw.isEmpty()) return OptionalInt.empty();
    try {
      return OptionalInt.of(Integer.parseInt(raw.get().trim()));
    } catch (NumberFormatException e) {
      return OptionalInt.empty();
    }
  }

  /** Parse chuoi reset cua Groq/HTTP nhu "2m59.56s", "1h10m", "7.66s", "450ms" hoac so giay "60". */
  public static long parseResetDurationMs(String raw, long defaultMs) {
    if (raw == null || raw.isBlank()) return defaultMs;
    String s = raw.trim().toLowerCase(Locale.ROOT);
    try {
      double seconds = Double.parseDouble(s);
      return Math.max(0L, (long) (seconds * 1000.0));
    } catch (NumberFormatException ignored) {
    }
    Matcher m = DURATION_PART.matcher(s);
    double totalMs = 0.0;
    boolean matched = false;
    while (m.find()) {
      matched = true;
      double val = Double.parseDouble(m.group(1));
      switch (m.group(2)) {
        case "h" -> totalMs += val * 3_600_000.0;
        case "m" -> totalMs += val * 60_000.0;
        case "s" -> totalMs += val * 1_000.0;
        case "ms" -> totalMs += val;
        default -> {}
      }
    }
    return matched ? Math.max(0L, (long) totalMs) : defaultMs;
  }

  private List<Map<String, Object>> attachImageToLastUserMessage(
      List<Map<String, Object>> messages, String imageDataUrl) {
    if (imageDataUrl == null || imageDataUrl.isBlank() || messages.isEmpty()) {
      return messages;
    }
    var copy = new ArrayList<Map<String, Object>>(messages.size());
    for (int i = 0; i < messages.size(); i++) {
      var m = new LinkedHashMap<String, Object>(messages.get(i));
      if (i == messages.size() - 1 && "user".equals(m.get("role"))) {
        String text = Objects.toString(m.get("content"), "");
        m.put("content", List.of(
            Map.of("type", "text", "text", text),
            Map.of("type", "image_url", "image_url", Map.of("url", imageDataUrl))));
      }
      copy.add(m);
    }
    return copy;
  }

  private static String extractTextContent(Object content) {
    if (content == null) return "";
    if (content instanceof String s) return s;
    if (content instanceof List<?> parts) {
      var sb = new StringBuilder();
      for (Object part : parts) {
        if (part instanceof Map<?, ?> map && map.get("text") != null) {
          sb.append(map.get("text"));
        }
      }
      return sb.toString();
    }
    return Objects.toString(content, "");
  }

  static String sanitizeReply(String raw) {
    if (raw == null) return "";
    String cleaned = raw.replaceAll("(?s)<think>.*?</think>", "").trim();
    if (cleaned.length() > 3500) {
      cleaned = cleaned.substring(0, 3500).trim();
    }
    return cleaned;
  }

  static List<String> parseCsv(String csv) {
    if (csv == null || csv.isBlank()) return List.of();
    var list = new ArrayList<String>();
    for (String part : csv.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty() && !list.contains(trimmed)) {
        list.add(trimmed);
      }
    }
    return List.copyOf(list);
  }

  /** Cho phep dien ca ".../v1" lan ".../v1/chat/completions" trong .env ma khong bi 404. */
  static String normalizeChatUrl(String raw, String fallback) {
    String url = blankDefault(raw, fallback);
    String noSlash = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    if (noSlash.endsWith("/v1")) {
      return noSlash + "/chat/completions";
    }
    return noSlash;
  }

  private static String cleanStr(String s) {
    return s == null ? "" : s.trim();
  }

  private static String blankDefault(String s, String fallback) {
    return (s == null || s.isBlank()) ? fallback : s.trim();
  }
}
