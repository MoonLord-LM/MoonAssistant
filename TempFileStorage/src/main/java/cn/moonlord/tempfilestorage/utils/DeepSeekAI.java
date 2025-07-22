package cn.moonlord.tempfilestorage.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * <p>模型：deepseek-chat、deepseek-reasoner<p/>
 * <p>上下文长度限制：输入 64K，输出 8K（deepseek-chat）、64K（deepseek-reasoner）<br/><p/>
 * <p><a href="https://api-docs.deepseek.com/zh-cn/quick_start/pricing">模型和价格说明</a><p/>
 * <p><a href="https://api-docs.deepseek.com/zh-cn/api/create-chat-completion">对话补全接口说明</a><p/>
 */
@Slf4j
public class DeepSeekAI {

    public static void main(String[] args) {
        log.info("DeepSeekAI.main ：\r\n\r\n" + DeepSeekAI.chat("你好，帮我写一篇小说，大概100字"));
    }

    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String API_KEY = "WXpKemRFMXFhM3BhVjFWNVRsZFJOVTlYVlRGT1JGRXdXVEpGZVZwcVZYaE9SRlpwVFZkYWFrOVhVVEJOUkZVOQ==";
    private static final String MODEL_NAME = "deepseek-chat";

    private static final Function<String, String> API_KEY_PROTECT = source -> new String(Base64.getDecoder().decode(source), StandardCharsets.UTF_8);

    /**
     * 对话历史记录
     */
    public static final List<Map<String, String>> messages = new ArrayList<>();

    @SneakyThrows
    public static String chat(String input) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + API_KEY_PROTECT.apply(API_KEY_PROTECT.apply(API_KEY_PROTECT.apply(API_KEY))));
        headers.set("Content-Type", "application/json");
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", input);
        messages.add(userMessage);
        Map<String, Object> data = new HashMap<>();
        data.put("model", MODEL_NAME);
        data.put("messages", messages);
        data.put("temperature", 0.3);
        data.put("max_tokens", 1000);
        data.put("stream", false);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(data, headers);
        ResponseEntity<String> response = null;
        try {
            response = (new RestTemplate()).exchange(API_URL, HttpMethod.POST, entity, String.class);
        } catch (HttpClientErrorException e) {
            log.error("chat error: {}", e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("chat error", e);
        }
        if (response != null) {
            log.info("请求响应: {}", response.getBody());
            Map body = (new ObjectMapper()).readValue(response.getBody(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            Map<String, Object> message;
            if (choices != null && !choices.isEmpty()) {
                message = (Map<String, Object>) choices.get(0).get("message");
            } else {
                message = (Map<String, Object>) body.get("message");
            }
            String assistantContent = null;
            if (message != null && !message.isEmpty()) {
                assistantContent = (String) message.get("content");
            }
            if (StringUtils.hasText(assistantContent)) {
                int index = assistantContent.indexOf("</think>");
                assistantContent = (index != -1) ? assistantContent.substring(index + "</think>".length()) : assistantContent;
                assistantContent = assistantContent.trim();
            }
            if (StringUtils.hasText(assistantContent)) {
                Map<String, String> assistantMessage = new HashMap<>();
                assistantMessage.put("role", "assistant");
                assistantMessage.put("content", assistantContent);
                messages.add(assistantMessage);
                return assistantContent;
            }
        }
        log.error("chat exception");
        return "未收到有效的响应";
    }

}
