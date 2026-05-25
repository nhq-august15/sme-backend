package sme.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import sme.backend.entity.Order;
import sme.backend.utils.HmacUtil;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayosService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${payos.client-id}")
    private String clientId;

    @Value("${payos.api-key}")
    private String apiKey;

    @Value("${payos.checksum-key}")
    private String checksumKey;

    public Map<String, Object> createPaymentLink(String orderCodeStr, Long amount, String description, String returnUrl, String cancelUrl) {
        return createPaymentLinkInternal(orderCodeStr, amount, description, returnUrl, cancelUrl, 0);
    }

    private Map<String, Object> createPaymentLinkInternal(String orderCodeStr, Long amount, String description, String returnUrl, String cancelUrl, int retryCount) {
        try {
            Long orderCode = Long.parseLong(orderCodeStr.replace("ORD-", ""));
            
            if (retryCount > 0) {
                 int randomSuffix = 10 + new java.util.Random().nextInt(90);
                 orderCode = Long.parseLong(orderCode.toString() + randomSuffix);
            }
            
            // Signature data
            Map<String, String> sigParams = new HashMap<>();
            sigParams.put("amount", amount.toString());
            sigParams.put("cancelUrl", cancelUrl);
            sigParams.put("description", description);
            sigParams.put("orderCode", orderCode.toString());
            sigParams.put("returnUrl", returnUrl);

            String signatureData = HmacUtil.buildSignatureString(sigParams);
            String signature = HmacUtil.hmacSha256(checksumKey, signatureData);

            // Body
            Map<String, Object> body = new HashMap<>();
            body.put("orderCode", orderCode);
            body.put("amount", amount);
            body.put("description", description);
            body.put("returnUrl", returnUrl);
            body.put("cancelUrl", cancelUrl);
            body.put("signature", signature);

            HttpHeaders headers = new HttpHeaders();
            headers.set("x-client-id", clientId);
            headers.set("x-api-key", apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            log.info("Gọi PayOS API tạo link thanh toán cho đơn: {} (lần thử: {})", orderCode, retryCount);
            ResponseEntity<Map> res = restTemplate.postForEntity(
                    "https://api-merchant.payos.vn/v2/payment-requests",
                    entity,
                    Map.class
            );

            Map<String, Object> responseBody = res.getBody();
            if (responseBody != null && responseBody.containsKey("code")) {
                String code = String.valueOf(responseBody.get("code"));
                if (!"00".equals(code)) {
                    String desc = String.valueOf(responseBody.get("desc"));
                    if (desc.contains("tồn tại") && retryCount == 0) {
                        log.info("Đơn đã tồn tại trên PayOS (HTTP 200), thử lại với mã mới...");
                        return createPaymentLinkInternal(orderCodeStr, amount, description, returnUrl, cancelUrl, 1);
                    }
                    throw new sme.backend.exception.BusinessException("PAYOS_ERROR", "Lỗi từ PayOS: " + desc);
                }
            }

            return responseBody;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            String errorResponse = e.getResponseBodyAsString();
            log.error("Lỗi HTTP từ PayOS: {}", errorResponse);
            try {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(errorResponse);
                if (node.has("desc")) {
                    String desc = node.get("desc").asText();
                    if (desc.contains("tồn tại") && retryCount == 0) {
                        log.info("Đơn đã tồn tại trên PayOS, thử lại với mã mới...");
                        return createPaymentLinkInternal(orderCodeStr, amount, description, returnUrl, cancelUrl, 1);
                    }
                    throw new sme.backend.exception.BusinessException("PAYOS_ERROR", "Lỗi từ PayOS: " + desc);
                }
            } catch (sme.backend.exception.BusinessException ex) {
                throw ex; // Re-throw our exception
            } catch (Exception ex) {
                // Ignore parsing error
            }
            throw new sme.backend.exception.BusinessException("PAYOS_ERROR", "Lỗi tạo link PayOS: " + errorResponse);
        } catch (Exception e) {
            log.error("Lỗi khi tạo payment link PayOS: ", e);
            throw new sme.backend.exception.BusinessException("PAYOS_ERROR", "Không thể tạo link thanh toán PayOS: " + e.getMessage());
        }
    }

    public boolean verifyWebhook(Map<String, Object> webhookBody) {
        try {
            Map<String, Object> data = (Map<String, Object>) webhookBody.get("data");
            String signature = (String) webhookBody.get("signature");

            if (data == null || signature == null) {
                return false;
            }

            // PayOS yêu cầu sort các key trong object data theo alphabet và nối lại
            Map<String, String> sigParams = new HashMap<>();
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (entry.getValue() != null) {
                    sigParams.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }

            String signatureData = HmacUtil.buildSignatureString(sigParams);
            String expectedSignature = HmacUtil.hmacSha256(checksumKey, signatureData);

            return expectedSignature.equals(signature);
        } catch (Exception e) {
            log.error("Lỗi xác thực webhook PayOS: ", e);
            return false;
        }
    }

    public Map<String, Object> getPaymentStatus(Long orderCode) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-client-id", clientId);
            headers.set("x-api-key", apiKey);

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> res = restTemplate.exchange(
                    "https://api-merchant.payos.vn/v2/payment-requests/" + orderCode,
                    org.springframework.http.HttpMethod.GET,
                    entity,
                    Map.class
            );
            return res.getBody();
        } catch (Exception e) {
            log.error("Lỗi khi kiểm tra trạng thái PayOS: ", e);
            return null;
        }
    }
}
