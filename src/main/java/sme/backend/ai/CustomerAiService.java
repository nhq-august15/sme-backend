package sme.backend.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CustomerAiService {

    private final ChatClient chatClient;

    public CustomerAiService(@Qualifier("customerChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String chat(String userMessage) {
        try {
            return chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Customer AI chat error: {}", e.getMessage(), e);
            return "Xin lỗi, hiện tại tôi đang gặp chút sự cố kỹ thuật. Bạn vui lòng thử lại sau nhé!";
        }
    }
}
